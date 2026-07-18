package com.orbitai.data.local.runtime

import android.content.Context
import com.orbitai.core.logging.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "TermuxRuntime"

/**
 * Termux-based runtime that runs the Termux bootstrap rootfs under PRoot.
 *
 * WHY PRoot IS REQUIRED:
 *   Android 10+ (API 29+) with targetSdk >= 29 enforces W^X via SELinux:
 *   files under /data/data/<pkg>/files/ get the `app_data_file` label,
 *   which does NOT allow execve(). Trying to exec any binary extracted
 *   from the Termux bootstrap zip fails with "Permission denied" (EACCES).
 *
 *   The ONLY writable+executable location available to a targetSdk>=29
 *   app is /data/app/<pkg>/lib/<abi>/ (label `apk_data_file`), which is
 *   populated at install time from jniLibs. We bundle libproot.so there.
 *   PRoot uses ptrace to intercept every execve syscall made by its
 *   children, so binaries inside the rootfs are mapped into memory by
 *   proot itself (only needing read access, which app_data_file allows)
 *   and never exec'd by the kernel.
 *
 * ROOTFS LAYOUT (matches proot-distro's termux-type layout):
 *   rootfsDir/                              <- PRoot -r (the chroot root)
 *     data/data/com.termux/files/usr/       <- Termux PREFIX (bin, lib, etc)
 *     data/data/com.termux/files/home/      <- HOME directory
 *   runtimeDir/agents/                      <- Agent code (bind-mounted to /orbit)
 *   runtimeDir/workspace/                   <- User workspace (bind-mounted to /workspace)
 *
 *   This layout avoids self-referential bind mounts. The Termux hardcoded
 *   path /data/data/com.termux/files/usr maps directly to the extracted
 *   files without any bind mount tricks.
 *
 * PRoot FLAGS (matching proot-distro):
 *   --kill-on-exit   — kill children if proot dies
 *   -L               — fix_symlink_size extension. CRITICAL: Termux's dpkg
 *                      treats wrong symlink st_size as a FATAL error. Without
 *                      -L, dpkg aborts during package extraction with
 *                      "symbolic link size has changed" → apt exit 100.
 *   -r rootfsDir     — rootfs root
 *   -b /dev /proc... — bind Android system dirs
 *   -b runtimeDir:/orbit — make agent code visible inside rootfs
 *
 *   NOT used (intentionally):
 *   --link2symlink   — Termux's dpkg already uses rename() instead of link().
 *                      link2symlink creates broken host-path symlinks.
 *   -0               — Termux apt refuses to run as root.
 *   -b prefix:prefix — self-referential bind creates recursive paths that
 *                      break postinst scripts doing directory traversal.
 */
class TermuxRuntime(private val context: Context) {

    /** App context, exposed so callers can load bundled assets (e.g. the provider catalog). */
    val appContext: Context get() = context

    val runtimeDir = File(context.filesDir, "orbit_runtime")

    /** PRoot root (the chroot root). Contains data/data/com.termux/files/usr/ */
    val rootfsDir = File(runtimeDir, "termux-rootfs")

    /** Termux PREFIX = /data/data/com.termux/files/usr inside the rootfs. */
    val prefixDir = File(rootfsDir, "data/data/com.termux/files/usr")

    /** bin directory inside the Termux prefix. */
    val binDir = File(prefixDir, "bin")

    /** Agent code lives here (outside rootfs, bind-mounted to /orbit). */
    val agentsDir = File(runtimeDir, "agents")
    val workspaceDir = File(runtimeDir, "workspace")
    val tmpDir = File(runtimeDir, "tmp")

    /** Termux HOME directory (host path), used for storage symlinks. */
    val homeDir = File(rootfsDir, "data/data/com.termux/files/home")

    /**
     * Directory where Android extracts jniLibs at install time.
     * SELinux label `apk_data_file` allows execve() here.
     */
    private val nativeLibDir: String by lazy {
        context.applicationInfo.nativeLibraryDir ?: ""
    }

    /** Path to the PRoot binary in the exec-allowed lib directory. */
    val prootPath: String by lazy { "$nativeLibDir/libproot.so" }
    val prootLoaderPath: String by lazy { "$nativeLibDir/libproot_loader.so" }

    /** Termux PREFIX path as seen INSIDE the rootfs (absolute, not host). */
    private val termuxPrefix = "/data/data/com.termux/files/usr"
    private val termuxHome = "/data/data/com.termux/files/home"

    val isInstalled: Boolean get() = File(binDir, "bash").exists()

    private var _installInProgress = false
    val installInProgress: Boolean get() = _installInProgress

    init {
        runtimeDir.mkdirs()
        agentsDir.mkdirs()
        workspaceDir.mkdirs()
        tmpDir.mkdirs()
    }

    /**
     * Install the Termux bootstrap rootfs by extracting the bundled zip
     * into the proot-distro layout: rootfsDir/data/data/com.termux/files/usr/
     * After extraction, packages (nodejs, git, python3) are installed via apt.
     */
    suspend fun install(
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled) {
            FileLogger.i(TAG, "Termux rootfs already installed")
            // Even if rootfs is extracted, the critical symlinks or tools might
            // be missing (e.g. a partial prior install). Ensure them first.
            ensureCriticalSymlinks()
            // Even if rootfs is extracted, tools might not be installed
            // (e.g. if apt failed on a previous run). Check and install.
            val toolsOk = ensureToolsInstalled(onProgress)
            return@withContext toolsOk
        }
        if (_installInProgress) {
            FileLogger.w(TAG, "Termux install already in progress, skipping duplicate call")
            return@withContext false
        }

        _installInProgress = true
        FileLogger.i(TAG, "Termux install start")
        val startTime = System.currentTimeMillis()

        try {
            // Step 1: Copy bootstrap zip from assets
            onProgress(0.05f, "Copying Termux bootstrap...")
            val assetStream = context.assets.open("termux-bootstrap.zip")
            val tempZip = File(tmpDir, "termux-bootstrap.zip")
            tempZip.parentFile?.mkdirs()

            val copyStart = System.currentTimeMillis()
            assetStream.use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output)
                }
            }
            FileLogger.i(TAG, "Bootstrap zip copied", "bytes=${tempZip.length()} time=${System.currentTimeMillis() - copyStart}ms")

            // Step 2: Extract bootstrap zip into prefixDir.
            // The bootstrap zip contains the CONTENTS of usr/ — so we extract
            // directly into prefixDir (which IS .../usr/).
            onProgress(0.1f, "Extracting rootfs (this takes a minute)...")
            FileLogger.i(TAG, "Extraction start", "target=${prefixDir.absolutePath}")
            val extractStart = System.currentTimeMillis()
            var fileCount = 0
            java.util.zip.ZipInputStream(tempZip.inputStream()).use { zis ->
                var entry: java.util.zip.ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val outFile = File(prefixDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        outFile.setExecutable(true, false)
                        fileCount++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val extractDuration = System.currentTimeMillis() - extractStart
            FileLogger.i(TAG, "Extraction success", "files=$fileCount time=${extractDuration}ms")

            tempZip.delete()

            // Step 3: Create symlinks from SYMLINKS.txt
            onProgress(0.4f, "Creating symlinks...")
            FileLogger.i(TAG, "Symlink creation start")
            val symlinksFile = File(prefixDir, "SYMLINKS.txt")
            if (symlinksFile.exists()) {
                val symlinkStart = System.currentTimeMillis()
                var symlinkCount = 0
                var symlinkFailed = 0
                symlinksFile.readLines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = line.split("\u2190")
                    if (parts.size == 2) {
                        val target = parts[0].trim().removePrefix("./")
                        val linkName = parts[1].trim().removePrefix("./")
                        val linkFile = File(prefixDir, linkName)
                        try {
                            linkFile.parentFile?.mkdirs()
                            if (linkFile.exists()) linkFile.delete()
                            Runtime.getRuntime().exec(arrayOf(
                                "ln", "-s", target, linkFile.absolutePath
                            )).waitFor()
                            symlinkCount++
                        } catch (_: Exception) {
                            symlinkFailed++
                        }
                    }
                }
                FileLogger.i(TAG, "Symlinks created", "count=$symlinkCount failed=$symlinkFailed time=${System.currentTimeMillis() - symlinkStart}ms")
            }

            val binSh = File(binDir, "sh")
            val binBash = File(binDir, "bash")
            FileLogger.i(TAG, "Binary check", "sh=${binSh.exists()} bash=${binBash.exists()} sh_canonical=${binSh.canonicalPath}")

            // Step 4: Create home directory and tmp dir inside the rootfs
            onProgress(0.5f, "Configuring environment...")
            File(rootfsDir, "data/data/com.termux/files/home").mkdirs()
            File(prefixDir, "tmp").mkdirs()

            // CRITICAL: create the symlinks (/bin/sh,/bin/bash -> bash and
            // /usr/bin/env,/bin/env -> env) that make bash builtins and node-CLI
            // shebangs work like real Termux. Done via ensureCriticalSymlinks()
            // (also called on the early-return path so a pre-existing rootfs
            // still gets correct symlinks).
            ensureCriticalSymlinks()

            // CRITICAL: apt's compiled-in cache path is
            // /data/data/com.termux/cache/apt/archives/partial
            // (NOT under files/usr/ — it's one level up, at files/../cache).
            // Without this directory, apt install fails with:
            //   E: Archives directory /data/data/com.termux/cache/apt/archives/partial is missing.
            File(rootfsDir, "data/data/com.termux/cache/apt/archives/partial").mkdirs()
            File(rootfsDir, "data/data/com.termux/cache/apt/archives/partial").setReadable(true, false)
            File(rootfsDir, "data/data/com.termux/cache/apt/archives/partial").setWritable(true, false)
            File(rootfsDir, "data/data/com.termux/cache/apt/archives/partial").setExecutable(true, false)

            // Set up apt sources.list (the bootstrap includes one, but we
            // overwrite to ensure it points to the right repo).
            File(prefixDir, "etc/apt/sources.list.d").mkdirs()
            File(prefixDir, "etc/apt/apt.conf.d").mkdirs()
            File(prefixDir, "etc/apt/preferences.d").mkdirs()
            val sourcesList = File(prefixDir, "etc/apt/sources.list")
            sourcesList.parentFile?.mkdirs()
            sourcesList.writeText("deb https://packages.termux.dev/apt/termux-main/ stable main\n")

            // DNS resolution inside the rootfs
            val resolvConf = File(rootfsDir, "etc/resolv.conf")
            resolvConf.parentFile?.mkdirs()
            if (!resolvConf.exists()) {
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            }
            FileLogger.i(TAG, "Environment configured")

            // Step 5: Install packages via PRoot + apt
            onProgress(0.6f, "Installing nodejs, git, python3 (downloads ~50MB)...")
            val toolsOk = installTools()
            if (!toolsOk) {
                FileLogger.e(TAG, "Tool installation failed")
                onProgress(0.0f, "Failed to install packages")
                return@withContext false
            }

            val totalDuration = System.currentTimeMillis() - startTime
            FileLogger.i(TAG, "Termux install success", "time=${totalDuration}ms")
            onProgress(1.0f, "Ready")
            true
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - startTime
            FileLogger.e(TAG, "Termux install failed", e, "time=${totalDuration}ms reason=${e.message}")
            onProgress(0.0f, "Failed: ${e.message}")
            false
        } finally {
            _installInProgress = false
        }
    }

    /**
     * Check if node/npm/git are installed. If not, run apt install.
     * This is called both during initial install and during retries.
     * Returns true if tools are available after this call.
     */
    /**
     * Create the critical symlinks that make the agent CLIs and the in-app
     * Terminal behave like real Termux, regardless of whether the rootfs was
     * just extracted or already present (install() may early-return when the
     * rootfs already exists, so this must run on every install() call).
     *   - /bin/sh and /bin/bash -> $PREFIX/bin/bash  (bash builtins work)
     *   - /usr/bin/env and /bin/env -> $PREFIX/bin/env  (node-CLI shebangs
     *     "#!/usr/bin/env node" resolve, so `openclaude`/`claude`/`codex`/
     *     `opencode` run directly instead of failing with "bad interpreter")
     */
    private fun ensureCriticalSymlinks() {
        try {
            val binDir = File(rootfsDir, "bin"); binDir.mkdirs()
            val usrBinDir = File(rootfsDir, "usr/bin"); usrBinDir.mkdirs()
            for (link in listOf(
                File(binDir, "sh"),
                File(binDir, "bash"),
                File(usrBinDir, "env"),
                File(binDir, "env")
            )) {
                if (!link.exists()) {
                    try {
                        Runtime.getRuntime().exec(
                            arrayOf("ln", "-sf", "$termuxPrefix/bin/bash", link.absolutePath)
                        ).waitFor()
                    } catch (_: Exception) { /* best-effort */ }
                }
            }
            // env must point at the env binary, not bash
            val envTargets = listOf(File(usrBinDir, "env"), File(binDir, "env"))
            for (envLink in envTargets) {
                if (envLink.exists()) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("rm", "-f", envLink.absolutePath)).waitFor()
                        Runtime.getRuntime().exec(
                            arrayOf("ln", "-sf", "$termuxPrefix/bin/env", envLink.absolutePath)
                        ).waitFor()
                    } catch (_: Exception) { /* best-effort */ }
                }
            }
        } catch (_: Exception) { /* best-effort */ }
    }

    suspend fun ensureToolsInstalled(
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val nodeExists = File(binDir, "node").exists()
        val npmExists = File(binDir, "npm").exists()
        val gitExists = File(binDir, "git").exists()
        FileLogger.i(TAG, "Tool check", "node=$nodeExists npm=$npmExists git=$gitExists")

        if (nodeExists && npmExists && gitExists) {
            return@withContext true
        }

        onProgress(0.6f, "Installing nodejs, git, python3 (downloads ~50MB)...")
        installTools()
    }

    /**
     * Run apt update + apt install nodejs git python3 inside the rootfs
     * under PRoot. Uses -o APT::Sandbox::User=root to disable apt's _apt
     * sandbox (which is broken under PRoot).
     */
    private suspend fun installTools(): Boolean = withContext(Dispatchers.IO) {
        FileLogger.i(TAG, "pkg install start", "packages=nodejs,git,python3")
        val pkgStart = System.currentTimeMillis()

        // Ensure apt's cache directory exists (in case it was deleted or
        // this is a retry after a partial install). apt's compiled-in path
        // is /data/data/com.termux/cache/apt/archives/partial.
        File(rootfsDir, "data/data/com.termux/cache/apt/archives/partial").mkdirs()

        // Fix any half-configured packages from previous failed installs
        val fixResult = executeInTermux("dpkg --configure -a 2>&1 || true", "")
        FileLogger.i(TAG, "dpkg configure result", "exit=${fixResult.exitCode} output=${fixResult.output.take(500)}")

        // ── Try offline installation first (pre-bundled .deb files) ──
        // If the assets/offline-debs/ directory was bundled in the APK,
        // copy the .deb files to the rootfs and install via dpkg -i.
        // This is ~10x faster than apt install (no network download).
        val offlineOk = tryInstallOfflineDebs()
        if (offlineOk) {
            val pkgDuration = System.currentTimeMillis() - pkgStart
            FileLogger.i(TAG, "Offline dpkg install success", "time=${pkgDuration}ms")
            val npmCheck = File(binDir, "npm")
            FileLogger.i(TAG, "Tool check", "node=${File(binDir, "node").exists()} npm=${npmCheck.exists()} git=${File(binDir, "git").exists()}")
            if (File(binDir, "node").exists() && npmCheck.exists()) {
                return@withContext true
            }
            FileLogger.w(TAG, "Offline install completed but tools not found, falling back to apt")
        }

        // ── Fall back to online apt install ──
        FileLogger.i(TAG, "Falling back to apt install (online)")

        // apt update
        val updateResult = executeInTermux(
            "apt update -o APT::Sandbox::User=root 2>&1",
            ""
        )
        FileLogger.i(TAG, "apt update result", "exit=${updateResult.exitCode} time=--ms output=${updateResult.output.take(2000)}")

        // apt install
        val installResult = executeInTermux(
            "apt install -y -o APT::Sandbox::User=root nodejs git python3 2>&1",
            ""
        )
        val pkgDuration = System.currentTimeMillis() - pkgStart
        FileLogger.i(TAG, "pkg install result", "exit=${installResult.exitCode} time=${pkgDuration}ms output=${installResult.output.take(3000)}")

        if (installResult.exitCode != 0) {
            val nodeCheck = File(binDir, "node")
            val npmCheck = File(binDir, "npm")
            val gitCheck = File(binDir, "git")
            if (!nodeCheck.exists() || !npmCheck.exists() || !gitCheck.exists()) {
                FileLogger.e(TAG, "apt install failed and tools still missing",
                    "exit=${installResult.exitCode} node=${nodeCheck.exists()} npm=${npmCheck.exists()} git=${gitCheck.exists()}")
                return@withContext false
            }
            // Tools are present (offline dpkg install succeeded even though the
            // online apt fallback failed — e.g. no Termux repo reachable). Not
            // a real failure, so don't emit a scary E line into the logs.
            FileLogger.w(TAG, "apt install failed but tools already present (offline install ok)",
                "exit=${installResult.exitCode}")
        }

        val npmCheck = File(binDir, "npm")
        FileLogger.i(TAG, "Tool check", "node=${File(binDir, "node").exists()} npm=${npmCheck.exists()} git=${File(binDir, "git").exists()}")
        File(binDir, "node").exists() && npmCheck.exists()
    }

    /**
     * Try to install packages from pre-bundled .deb files in assets/offline-debs/.
     * Returns true if the .deb files were found and dpkg -i succeeded.
     * Returns false if no .deb files are bundled (falls back to apt install).
     */
    private suspend fun tryInstallOfflineDebs(): Boolean = withContext(Dispatchers.IO) {
        val debsDir = File(runtimeDir, "offline-debs")
        debsDir.mkdirs()

        // Copy .deb files from assets to the rootfs (visible at /offline-debs
        // via the runtimeDir:/orbit bind mount... wait, we need them inside
        // the rootfs. Copy to rootfsDir/offline-debs so they're at /offline-debs
        // inside PRoot.)
        val rootfsDebsDir = File(rootfsDir, "offline-debs")
        rootfsDebsDir.mkdirs()

        var debCount = 0
        try {
            val assetFiles = context.assets.list("offline-debs") ?: emptyArray()
            if (assetFiles.isEmpty()) {
                FileLogger.i(TAG, "No offline-debs in assets, using online apt")
                return@withContext false
            }

            FileLogger.i(TAG, "Copying offline debs", "count=${assetFiles.size}")
            for (filename in assetFiles) {
                if (!filename.endsWith(".deb")) continue
                val outFile = File(rootfsDebsDir, filename)
                context.assets.open("offline-debs/$filename").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                debCount++
            }
            FileLogger.i(TAG, "Offline debs copied", "count=$debCount")
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to copy offline debs", "reason=${e.message}")
            return@withContext false
        }

        if (debCount == 0) {
            return@withContext false
        }

        // Install all .deb files via dpkg -i (offline, no network needed).
        // dpkg -i installs packages in dependency order if possible, but may
        // leave some half-configured. Run dpkg --configure -a after to finish.
        FileLogger.i(TAG, "dpkg -i start", "debs=$debCount")
        val dpkgStart = System.currentTimeMillis()

        val installResult = executeInTermux(
            "dpkg -i /offline-debs/*.deb 2>&1 || true",
            ""
        )
        FileLogger.i(TAG, "dpkg -i result", "exit=${installResult.exitCode} time=${System.currentTimeMillis() - dpkgStart}ms output=${installResult.output.take(2000)}")

        // Configure any half-configured packages
        val configResult = executeInTermux("dpkg --configure -a 2>&1 || true", "")
        FileLogger.i(TAG, "dpkg configure result", "exit=${configResult.exitCode} output=${configResult.output.take(1000)}")

        // Clean up .deb files to save space
        rootfsDebsDir.deleteRecursively()

        true
    }

    /**
     * Execute a command inside the Termux rootfs under PRoot.
     *
     * PRoot flags: see class docs for the full rationale.
     */
    suspend fun executeInTermux(
        command: String,
        stdin: String = ""
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!isInstalled) {
            return@withContext CommandResult("Termux rootfs not installed.", -1, command)
        }

        val execStart = System.currentTimeMillis()
        FileLogger.d(TAG, "Termux exec start", "cmd=${command.take(200)}")

        try {
            // Build PRoot argv — matches proot-distro's termux-type invocation
            val prootArgs = mutableListOf(
                prootPath,
                "--kill-on-exit",
                // NOTE: -L (fix_symlink_size) is not in this PRoot build.
                // It's only needed when --link2symlink creates fake symlinks.
                // Since we don't use --link2symlink, our symlinks are real
                // kernel-level symlinks and lstat returns correct sizes.
                "-r", rootfsDir.absolutePath,
                // Bind Android system directories so binaries can find libs, /dev, /proc etc.
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/system",
                "-b", "/vendor",
                "-b", "/apex",
                "-b", "/odm",
                "-b", "/linkerconfig",
                "-b", "/data/local/tmp:/tmp",
                // CRITICAL: expose the emulated shared storage inside the
                // rootfs so the agent can write files the USER can see
                // (e.g. ~/storage/downloads -> /storage/emulated/0/Download).
                // Without this bind, /storage doesn't exist inside the chroot
                // and every write to shared storage fails with
                // "Directory nonexistent" (the dangling symlink problem).
                "-b", "/storage",
                // Make our runtime dir visible inside the rootfs at /orbit
                "-b", "$runtimeDir:/orbit",
                // Set working directory to Termux HOME
                "-w", termuxHome,
                // Exec BASH inside the Termux prefix so bash builtins (help,
                // compgen, source, history) work — matches real Termux, where
                // the login shell is bash, not dash. dash made `help` etc.
                // fail with "sh: 1: help: not found".
                "$termuxPrefix/bin/bash", "-c", command
            )

            val pb = ProcessBuilder(prootArgs)
            pb.directory(runtimeDir)

            val env = pb.environment()
            // PRoot loader path (proot dlopens this for ptrace injection)
            env["PROOT_LOADER"] = prootLoaderPath
            env["PROOT_LOADER_32"] = prootLoaderPath
            env["PROOT_TMP_DIR"] = tmpDir.absolutePath
            // Termux environment — must match what Termux binaries expect
            env["PREFIX"] = termuxPrefix
            // IMPORTANT: only the Termux prefix bin is on PATH. The previous
            // code also prepended /system/bin:/system/xbin, but those binaries
            // live under the system SELinux label and CANNOT be execve()'d by
            // this app's domain — every lookup that fell through to them failed
            // with EACCES ("Permission denied", exit 127), making ordinary
            // terminal commands unusable. Termux ships its own coreutils
            // (ls, cat, cp, …) under its prefix, so they cover the toolset.
            env["PATH"] = "$termuxPrefix/bin"
            env["HOME"] = termuxHome
            env["TMPDIR"] = "$termuxPrefix/tmp"
            env["LANG"] = "en_US.UTF-8"
            env["TERM"] = "xterm-256color"
            // IMPORTANT: LD_LIBRARY_PATH is consumed by the HOST linker when it
            // loads libproot.so (and its deps like libtalloc) — this happens
            // BEFORE PRoot chroots, so it must point at the HOST-side paths,
            // NOT the in-rootfs absolute path ($termuxPrefix = /data/data/...).
            // Pointing it at the chroot path made PRoot unable to find its own
            // shared libs -> every terminal command failed with a loader error.
            // Inside the chroot, Termux binaries resolve their libs via their
            // own rpath, so the in-rootfs path is unnecessary.
            env["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib:/system/lib64"
            // OpenClaude's Bash tool needs SHELL to point at a POSIX shell inside
            // the rootfs. The termux rootfs ships usr/bin/{sh,bash} but has no
            // /bin/sh and no /etc/passwd entry, so without this the agent reports
            // "No suitable shell found" and every shell command fails.
            env["SHELL"] = "$termuxPrefix/bin/bash"

            val process = pb.start()

            if (stdin.isNotEmpty()) {
                process.outputStream.write(stdin.toByteArray())
                process.outputStream.flush()
            }
            process.outputStream.close()

            val stdoutText = StringBuilder()
            val stderrText = StringBuilder()

            val stdoutThread = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { stdoutText.appendLine(it) }
                }
            }
            val stderrThread = Thread {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { stderrText.appendLine(it) }
                }
            }
            stdoutThread.start()
            stderrThread.start()

            // HARD WATCHDOG: process.waitFor() + the stream-pump
            // threads are native-blocking and CANNOT be interrupted by
            // coroutine cancellation. A hanging command (e.g. `while true`,
            // a stuck build, `cat` on a FIFO) would otherwise dead-lock
            // this thread — and the caller's coroutine — forever.
            // Bound the whole exec with a watchdog that destroys the
            // process (and its PRoot children) if it overstays.
            val maxMillis = 5 * 60 * 1000L // 5 minutes
            val waited = process.waitFor(maxMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!waited) {
                FileLogger.w(TAG, "Termux exec watchdog", "cmd=${command.take(200)} killed after ${maxMillis}ms (hung)")
                try { process.destroyForcibly() } catch (_: Exception) {}
            }
            // Bound the stream pumps too (forEachLine blocks on EOF,
            // which a half-dead process may never send).
            stdoutThread.join(2000)
            if (stdoutThread.isAlive) stdoutThread.interrupt()
            stderrThread.join(2000)
            if (stderrThread.isAlive) stderrThread.interrupt()

            val exitCode = process.exitValue()
            val output = stdoutText.toString().trim()
            val stderr = stderrText.toString().trim()
            val execDuration = System.currentTimeMillis() - execStart

            if (exitCode != 0) {
                FileLogger.w(TAG, "Termux exec failed", "exit=$exitCode time=${execDuration}ms stderr=${stderr.take(500)}")
            } else {
                FileLogger.d(TAG, "Termux exec success", "exit=0 time=${execDuration}ms output=${output.length}chars")
            }

            // The Termux binaries run under PRoot cannot read the device's
            // generated linker configuration, emitting a harmless but very
            // noisy "WARNING: linker: Warning: failed to find generated linker
            // configuration from /linkerconfig/ld.config.txt" line on every
            // command. Strip it from the captured output so logs stay readable
            // (it has no effect on command behaviour — confirmed against the
            // live device).
            val combinedOutput = if (stderr.isNotBlank()) "$output\n$stderr" else output
            CommandResult(combinedOutput.lines()
                .filter { !it.trim().startsWith("WARNING: linker:") }
                .joinToString("\n").trimEnd(), exitCode, command)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Termux exec exception", e, "cmd=${command.take(100)} reason=${e.message}")
            CommandResult("Error: ${e.message}", -1, command)
        }
    }

    /**
     * Streaming variant of [executeInTermux].
     *
     * Runs the command inside the Termux rootfs under PRoot and invokes
     * [onLine] for every stdout/stderr line AS IT ARRIVES, instead of
     * blocking until the process exits. This is what powers the chat
     * "transcript" / "thinking" view so the user can see exactly what the
     * agent is doing (tool calls, reasoning, errors) in real time instead
     * of only discovering problems after the run finishes.
     *
     * Returns the same [CommandResult] as [executeInTermux] for callers that
     * still want the full captured output. The watchdog from executeInTermux
     * is preserved so a hung command cannot leak threads.
     */
    suspend fun executeInTermuxStreamed(
        command: String,
        stdin: String = "",
        onLine: (String) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!isInstalled) {
            onLine("Termux rootfs not installed.")
            return@withContext CommandResult("Termux rootfs not installed.", -1, command)
        }

        val execStart = System.currentTimeMillis()
        FileLogger.d(TAG, "Termux exec start (streamed)", "cmd=${command.take(200)}")

        try {
            val prootArgs = mutableListOf(
                prootPath,
                "--kill-on-exit",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/system",
                "-b", "/vendor",
                "-b", "/apex",
                "-b", "/odm",
                "-b", "/linkerconfig",
                "-b", "/data/local/tmp:/tmp",
                "-b", "/storage",
                "-b", "$runtimeDir:/orbit",
                "-w", termuxHome,
                // Exec BASH so bash builtins (help, compgen, …) work, matching
                // real Termux (login shell = bash, not dash).
                "$termuxPrefix/bin/bash", "-c", command
            )

            val pb = ProcessBuilder(prootArgs)
            pb.directory(runtimeDir)

            val env = pb.environment()
            env["PROOT_LOADER"] = prootLoaderPath
            env["PROOT_LOADER_32"] = prootLoaderPath
            env["PROOT_TMP_DIR"] = tmpDir.absolutePath
            env["PREFIX"] = termuxPrefix
            env["PATH"] = "$termuxPrefix/bin"
            env["HOME"] = termuxHome
            env["TMPDIR"] = "$termuxPrefix/tmp"
            env["LANG"] = "en_US.UTF-8"
            env["TERM"] = "xterm-256color"
            env["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib:/system/lib64"
            env["SHELL"] = "$termuxPrefix/bin/bash"

            val process = pb.start()

            if (stdin.isNotEmpty()) {
                process.outputStream.write(stdin.toByteArray())
                process.outputStream.flush()
            }
            process.outputStream.close()

            val stdoutText = StringBuilder()
            val stderrText = StringBuilder()

            val stdoutThread = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine {
                        if (!it.trim().startsWith("WARNING: linker:")) onLine(it)
                    }
                }
            }
            val stderrThread = Thread {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine {
                        stderrText.appendLine(it)
                        if (!it.trim().startsWith("WARNING: linker:")) onLine(it)
                    }
                }
            }
            stdoutThread.start()
            stderrThread.start()

            val maxMillis = 5 * 60 * 1000L
            val waited = process.waitFor(maxMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!waited) {
                FileLogger.w(TAG, "Termux exec watchdog", "cmd=${command.take(200)} killed after ${maxMillis}ms (hung)")
                try { process.destroyForcibly() } catch (_: Exception) {}
            }
            stdoutThread.join(2000)
            if (stdoutThread.isAlive) stdoutThread.interrupt()
            stderrThread.join(2000)
            if (stderrThread.isAlive) stderrThread.interrupt()

            val exitCode = process.exitValue()
            val output = stdoutText.toString().trim()
            val stderr = stderrText.toString().trim()
            val execDuration = System.currentTimeMillis() - execStart

            if (exitCode != 0) {
                FileLogger.w(TAG, "Termux exec failed (streamed)", "exit=$exitCode time=${execDuration}ms stderr=${stderr.take(500)}")
            }

            val combinedOutput = if (stderr.isNotBlank()) "$output\n$stderr" else output
            CommandResult(combinedOutput.lines()
                .filter { !it.trim().startsWith("WARNING: linker:") }
                .joinToString("\n").trimEnd(), exitCode, command)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Termux exec exception (streamed)", e, "cmd=${command.take(100)} reason=${e.message}")
            onLine("Error: ${e.message}")
            CommandResult("Error: ${e.message}", -1, command)
        }
    }

    fun isToolInstalled(tool: String): Boolean = File(binDir, tool).exists()

    /**
     * Returns the path to node INSIDE the rootfs (host path).
     * Callers that want to exec node must do so via executeInTermux().
     */
    fun getNodePath(): String? {
        val node = File(binDir, "node")
        return if (node.exists() && node.canExecute()) node.absolutePath else null
    }

    fun getPrefixPath(): String = prefixDir.absolutePath
    fun getRuntimePath(): String = runtimeDir.absolutePath
}

data class CommandResult(
    val output: String,
    val exitCode: Int,
    val command: String
)
