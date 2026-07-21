package com.orbitai.data.local.runtime

import android.content.Context
import com.orbitai.core.logging.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

private const val TAG = "HermesRuntime"

/**
 * HermesRuntime — runs the REAL Nous hermes-agent (Python) on-device inside a
 * glibc Debian aarch64 PRoot rootfs. This is NOT an OpenRouter chat shim: the
 * agent process (LLM reasoning + tool calls + shell execution) runs locally;
 * OpenRouter is only the LLM backend (passed via OPENROUTER_API_KEY).
 *
 * Why a separate rootfs from TermuxRuntime: hermes-agent requires Python
 * 3.11–3.13 + glibc manylinux wheels. The Termux bootstrap is bionic + Python
 * 3.14, which cannot run it. So Hermes ships its own Debian aarch64 rootfs
 * (bundled as src/hermes/assets/hermes-rootfs.tar.gz, pre-built with the agent
 * already pip-installed at /opt/hermes-venv).
 *
 * PRoot invocation mirrors TermuxRuntime but uses normal FHS paths and the
 * app's bundled libproot.so (same SELinux exec-allowed lib dir).
 */
class HermesRuntime(private val context: Context) {

    val runtimeDir = File(context.filesDir, "orbit_runtime")
    val rootfsDir = File(runtimeDir, "hermes_rootfs")
    val resolvFile = File(rootfsDir, "etc/resolv.conf")
    val hermesBin = File(rootfsDir, "opt/hermes-venv/bin/hermes")

    val isInstalled: Boolean get() = hermesBin.exists()

    private val nativeLibDir: String by lazy {
        context.applicationInfo.nativeLibraryDir ?: ""
    }
    private val prootPath: String get() = "$nativeLibDir/libproot.so"
    private val prootLoaderPath: String get() = "$nativeLibDir/libproot_loader.so"

    init {
        runtimeDir.mkdirs()
    }

    /**
     * Extract the bundled Debian rootfs from APK assets (gzip tarball) into
     * runtimeDir/hermes_rootfs. Idempotent: skips if already extracted.
     * Seeds /etc/resolv.conf (ships empty in the image -> DNS would fail).
     */
    suspend fun install(onProgress: (Float, String) -> Unit = { _, _ -> }): Boolean =
        withContext(Dispatchers.IO) {
            if (isInstalled) {
                FileLogger.i(TAG, "Hermes rootfs already installed")
                return@withContext true
            }
            try {
                onProgress(0.05f, "Copying Hermes runtime...")
                // NOTE: AAPT2 decompresses .gz assets at build time and stores
                // them under the inner name (hermes-rootfs.tar), so the asset
                // in the APK is a plain (already-decompressed) tarball.
                val assetName = "hermes-rootfs.tar"
                val assetStream = context.assets.open(assetName)
                val tempTarGz = File(runtimeDir, "hermes-rootfs.tar")
                tempTarGz.parentFile?.mkdirs()
                assetStream.use { it.copyTo(tempTarGz.outputStream()) }
                FileLogger.i(TAG, "rootfs tarball copied", "bytes=${tempTarGz.length()}")

                onProgress(0.15f, "Extracting Linux environment (this takes a minute)...")
                val extractStart = System.currentTimeMillis()
                rootfsDir.mkdirs()
                // Use the device's tar (gzip-compressed tarball, NOT a zip).
                // Java's ZipInputStream cannot parse tar; tar handles it natively
                // and far faster. Use the absolute path — the app process PATH
                // may not include /system/bin.
                val tarBin = "/system/bin/tar"
                val extractCmd = arrayOf(
                    tarBin, "-xf", tempTarGz.absolutePath,
                    "-C", rootfsDir.absolutePath
                )
                val proc = Runtime.getRuntime().exec(extractCmd)
                val tOut = proc.inputStream.bufferedReader().readText()
                val tErr = proc.errorStream.bufferedReader().readText()
                val rc = proc.waitFor()
                if (rc != 0) {
                    FileLogger.e(TAG, "tar extract failed", "rc=$rc err=$tErr out=$tOut")
                    return@withContext false
                }
                val nFiles = rootfsDir.walkTopDown().count()
                FileLogger.i(TAG, "Extraction done", "files=$nFiles time=${System.currentTimeMillis() - extractStart}ms")

                // Seed resolv.conf (DNS inside proot)
                resolvFile.parentFile?.mkdirs()
                resolvFile.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                FileLogger.i(TAG, "resolv.conf seeded")

                // Clean up the tarball to reclaim space
                tempTarGz.delete()
                onProgress(0.95f, "Finalizing...")
                val ok = isInstalled
                if (!ok) {
                    FileLogger.e(TAG, "Hermes binary missing after extraction", "path=${hermesBin.absolutePath}")
                    return@withContext false
                }
                onProgress(1f, "Hermes runtime ready")
                true
            } catch (e: Exception) {
                FileLogger.e(TAG, "Hermes rootfs install failed", e, "reason=${e.message}")
                false
            }
        }

    /** Build the PRoot argv for an arbitrary command inside the glibc rootfs. */
    private fun prootArgs(command: String): MutableList<String> {
        return mutableListOf(
            prootPath,
            "--kill-on-exit",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/storage",
            "-b", "/data/local/tmp:/tmp",
            "-w", "/root",
            "/usr/bin/env",
            "PATH=/opt/hermes-venv/bin:/usr/bin:/bin",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PYTHONHOME=/usr",
            "PYTHONPATH=/opt/hermes-venv/lib/python3.11/site-packages",
            "sh", "-c", command
        )
    }

    /**
     * Run a shell command inside the Hermes rootfs and return combined output.
     * Used by the Terminal tab.
     */
    suspend fun execute(command: String, stdin: String = ""): CommandResult =
        withContext(Dispatchers.IO) {
            if (!isInstalled) {
                return@withContext CommandResult("Hermes runtime not installed.", -1, command)
            }
            try {
                val pb = ProcessBuilder(prootArgs(command))
                pb.directory(runtimeDir)
                val env = pb.environment()
                env["PROOT_LOADER"] = prootLoaderPath
                env["PROOT_LOADER_32"] = prootLoaderPath
                env["PROOT_TMP_DIR"] = runtimeDir.absolutePath
                val process = pb.start()
                if (stdin.isNotEmpty()) {
                    process.outputStream.write(stdin.toByteArray())
                    process.outputStream.flush()
                }
                process.outputStream.close()
                val out = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                val rc = if (process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) process.exitValue() else -1
                CommandResult(out + err, rc, command)
            } catch (e: Exception) {
                CommandResult("Hermes exec error: ${e.message}", -1, command)
            }
        }

    /**
     * Run the real hermes-agent on a one-shot prompt, streaming output lines.
     * Pass the OpenRouter key + model. Returns the combined output.
     */
    suspend fun runAgent(
        prompt: String,
        apiKey: String,
        model: String,
        streamLine: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        if (!isInstalled) return@withContext "Hermes runtime not installed."
        val escaped = prompt.replace("\\", "\\\\").replace("'", "\\'").replace("$", "\\$")
        val cmd = buildString {
            append("OPENROUTER_API_KEY='$apiKey' ")
            append("/opt/hermes-venv/bin/hermes -z '$escaped'")
            append(" --provider openrouter -m '$model' --yolo")
        }
        try {
            val pb = ProcessBuilder(prootArgs(cmd))
            pb.directory(runtimeDir)
            val env = pb.environment()
            env["PROOT_LOADER"] = prootLoaderPath
            env["PROOT_LOADER_32"] = prootLoaderPath
            env["PROOT_TMP_DIR"] = runtimeDir.absolutePath
            val process = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val t1 = Thread {
                process.inputStream.bufferedReader().use { r -> r.forEachLine { stdout.appendLine(it); streamLine(it) } }
            }
            val t2 = Thread {
                process.errorStream.bufferedReader().use { r -> r.forEachLine { stderr.appendLine(it); streamLine(it) } }
            }
            t1.start(); t2.start()
            val rc = if (process.waitFor(8, java.util.concurrent.TimeUnit.MINUTES)) process.exitValue() else -1
            t1.join(); t2.join()
            FileLogger.i(TAG, "hermes agent run", "exit=$rc")
            (stdout.toString() + stderr.toString()).trim()
        } catch (e: Exception) {
            FileLogger.e(TAG, "hermes agent run failed", e, "reason=${e.message}")
            "Hermes agent error: ${e.message}"
        }
    }
}
