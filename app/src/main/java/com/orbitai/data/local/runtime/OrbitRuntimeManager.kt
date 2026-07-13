package com.orbitai.data.local.runtime

import android.content.Context
import com.orbitai.core.logging.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "OrbitAiRuntime"
private const val BUSYBOX_ASSET = "busybox-arm64"
private const val BUSYBOX_BINARY = "busybox"
private const val BUSYBOX_NATIVE_LIB = "libbusybox.so"

/**
 * Resolve the absolute path to a system tool (sh, chmod, cp, mv) under
 * `/system/bin`. We honor `ANDROID_ROOT` so custom ROMs that mount the system
 * tree elsewhere (e.g. emulators) work transparently.
 */
private fun systemBin(tool: String): String {
    val root = android.system.Os.getenv("ANDROID_ROOT") ?: "/system"
    return "$root/bin/$tool"
}

class OrbitAiRuntimeManager(val context: Context) {
    val runtimeDir = File(context.filesDir, "orbit_runtime")
    val binDir = File(runtimeDir, "bin")
    val tmpDir = File(runtimeDir, "tmp")
    val packagesDir = File(runtimeDir, "packages")
    val downloadsDir = File(runtimeDir, "downloads")
    val agentsDir = File(runtimeDir, "agents")
    val logsDir = File(runtimeDir, "logs")
    val environmentsDir = File(runtimeDir, "environments")

    // Caches whether busybox is actually executable.
    private var busyboxVerified: Boolean? = null
    // Caches the native library directory path.
    private val nativeLibDir: String? by lazy {
        try {
            context.applicationInfo.nativeLibraryDir?.also {
                FileLogger.i(TAG, "Native lib dir", "path=$it")
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Could not get nativeLibraryDir: ${e.message}")
            null
        }
    }

    init {
        listOf(runtimeDir, binDir, tmpDir, packagesDir, downloadsDir, agentsDir, logsDir, environmentsDir).forEach {
            it.mkdirs()
        }
    }

    fun getEnvVars(): Array<String> {
        val existingPath = System.getenv("PATH") ?: ""
        return arrayOf("PATH=${binDir.absolutePath}:$existingPath")
    }

    /**
     * Full path to the BusyBox binary, or null if not available.
     *
     * STRATEGY (in priority order):
     * 1. Native library dir (/data/app/<pkg>/lib/<abi>/libbusybox.so) —
     *    This is the PRIMARY method. Android extracts .so files from the APK
     *    to a directory with an SELinux label that ALLOWS exec. This works
     *    on ALL Android versions including 10+ with W^X enforcement.
     * 2. filesDir/bin/busybox — FALLBACK only. Copied from APK assets + chmod.
     *    This FAILS on Android 10+ with strict SELinux (e.g. Samsung, Xiaomi).
     *    We keep it as a fallback for older Android versions or custom ROMs
     *    that don't enforce W^X.
     */
    fun busyBoxPath(): String? {
        // Use cached result if available
        if (busyboxVerified != null) {
            return if (busyboxVerified!!) cachedBusyboxPath else null
        }

        // ── Strategy 1: Native library dir ──────────────────────────
        nativeLibDir?.let { libDir ->
            val nativeBusybox = File(libDir, BUSYBOX_NATIVE_LIB)
            if (nativeBusybox.exists()) {
                FileLogger.d(TAG, "Found libbusybox.so at ${nativeBusybox.absolutePath}")
                if (tryExecBusybox(nativeBusybox.absolutePath)) {
                    cachedBusyboxPath = nativeBusybox.absolutePath
                    busyboxVerified = true
                    FileLogger.i(TAG, "BusyBox verified at native lib: ${nativeBusybox.absolutePath}")
                    return nativeBusybox.absolutePath
                } else {
                    FileLogger.w(TAG, "libbusybox.so exists but exec failed: ${nativeBusybox.absolutePath}")
                }
            } else {
                FileLogger.w(TAG, "libbusybox.so not found in nativeLibDir", "path=${nativeBusybox.absolutePath}")
            }
        }

        // ── Strategy 2: filesDir/bin/busybox (fallback) ─────────────
        val fallbackBusybox = File(binDir, BUSYBOX_BINARY)
        if (fallbackBusybox.exists() && fallbackBusybox.canExecute()) {
            if (tryExecBusybox(fallbackBusybox.absolutePath)) {
                cachedBusyboxPath = fallbackBusybox.absolutePath
                busyboxVerified = true
                FileLogger.i(TAG, "BusyBox verified at fallback: ${fallbackBusybox.absolutePath}")
                return fallbackBusybox.absolutePath
            }
        }

        FileLogger.w(TAG, "BusyBox unavailable", "checked=native-lib,fallback")
        busyboxVerified = false
        return null
    }

    private var cachedBusyboxPath: String? = null

    /** Try to exec busybox with a trivial command to verify it actually works. */
    private fun tryExecBusybox(path: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(path, "true"))
            val exit = p.waitFor()
            FileLogger.d(TAG, "BusyBox exec test", "path=$path exit=$exit")
            exit == 0
        } catch (e: Exception) {
            FileLogger.w(TAG, "BusyBox exec test failed", "path=$path reason=${e.message}")
            false
        }
    }

    /**
     * Find the actual node binary inside the installed nodejs package.
     * Returns the absolute path if found, null otherwise.
     */
    fun findNodeBinary(): String? {
        val candidates = listOf(
            File(packagesDir, "nodejs/usr/bin/node"),
            File(packagesDir, "nodejs/bin/node"),
            File(packagesDir, "node/bin/node")
        )
        for (c in candidates) {
            if (c.exists()) {
                FileLogger.d(TAG, "Node binary found", "path=${c.absolutePath} canExecute=${c.canExecute()}")
                return c.absolutePath
            }
        }
        FileLogger.w(TAG, "Node binary not found", "searched=${packagesDir.absolutePath}")
        return null
    }

    /**
     * Find the node binary's lib directory (for LD_LIBRARY_PATH).
     */
    fun findNodeLibDir(): String? {
        val candidates = listOf(
            File(packagesDir, "nodejs/usr/lib"),
            File(packagesDir, "nodejs/lib"),
            File(packagesDir, "node/lib")
        )
        for (c in candidates) {
            if (c.isDirectory && c.canRead()) {
                return c.absolutePath
            }
        }
        return null
    }

    /**
     * Build a colon-separated LD_LIBRARY_PATH that includes all
     * packages/[star]/usr/lib directories + the native lib dir.
     */
    fun buildLdLibraryPath(): String {
        val libs = mutableListOf<String>()
        // Native lib dir first (for busybox's own deps if any)
        nativeLibDir?.let { libs.add(it) }
        packagesDir.listFiles()?.forEach { pkgDir ->
            if (pkgDir.isDirectory) {
                val usrLib = File(pkgDir, "usr/lib")
                if (usrLib.isDirectory && usrLib.canRead()) {
                    libs.add(usrLib.absolutePath)
                }
                val lib = File(pkgDir, "lib")
                if (lib.isDirectory && lib.canRead()) {
                    libs.add(lib.absolutePath)
                }
            }
        }
        return libs.joinToString(":")
    }

    /**
     * Build a PATH string that includes:
     *  1. Native lib dir (for libbusybox.so — we symlink it as 'busybox')
     *  2. orbit_runtime/bin/ (for wrapper scripts invoked via `sh <wrapper>`)
     *  3. Every packages/[star]/usr/bin/ (for actual binaries like node, git)
     *  4. The system PATH
     */
    fun buildPath(): String {
        val paths = mutableListOf<String>()
        nativeLibDir?.let { paths.add(it) }
        paths.add(binDir.absolutePath)
        packagesDir.listFiles()?.forEach { pkgDir ->
            if (pkgDir.isDirectory) {
                val usrBin = File(pkgDir, "usr/bin")
                if (usrBin.isDirectory && usrBin.canRead()) {
                    paths.add(usrBin.absolutePath)
                }
                val bin = File(pkgDir, "bin")
                if (bin.isDirectory && bin.canRead()) {
                    paths.add(bin.absolutePath)
                }
            }
        }
        val systemPath = System.getenv("PATH") ?: ""
        paths.add(systemPath)
        return paths.joinToString(":")
    }

    /**
     * Ensure BusyBox is available.
     *
     * On Android 10+, the primary method is the native library (libbusybox.so)
     * which is automatically extracted by the system to an exec-able directory.
     * We also copy it to filesDir/bin/busybox as a fallback for older devices.
     *
     * This is safe to call repeatedly — it skips if already installed.
     *
     * @return true if BusyBox is available after the call
     */
    suspend fun installBusyBox(): Boolean = withContext(Dispatchers.IO) {
        // Check if native lib busybox is already verified
        if (busyboxVerified == true) {
            return@withContext true
        }

        // Try native lib first (no extraction needed — system already did it)
        nativeLibDir?.let { libDir ->
            val nativeBusybox = File(libDir, BUSYBOX_NATIVE_LIB)
            if (nativeBusybox.exists()) {
                FileLogger.d(TAG, "libbusybox.so found", "path=${nativeBusybox.absolutePath}")
                if (tryExecBusybox(nativeBusybox.absolutePath)) {
                    cachedBusyboxPath = nativeBusybox.absolutePath
                    busyboxVerified = true
                    FileLogger.i(TAG, "BusyBox ready", "source=native-lib path=${nativeBusybox.absolutePath}")
                    return@withContext true
                } else {
                    FileLogger.w(TAG, "Native lib BusyBox exec failed, falling back to asset extraction")
                }
            } else {
                FileLogger.w(TAG, "libbusybox.so not found, falling back to asset extraction", "dir=$libDir")
            }
        }

        // Fallback: extract from APK assets to filesDir/bin/busybox
        // This may fail on Android 10+ with strict SELinux, but works on older devices.
        busyboxVerified = null
        val busyboxFile = File(binDir, BUSYBOX_BINARY)

        try {
            FileLogger.i(TAG, "Extracting BusyBox from APK assets (fallback)...")
            context.assets.open(BUSYBOX_ASSET).use { input ->
                busyboxFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val chmodResult = Runtime.getRuntime()
                .exec(arrayOf(systemBin("chmod"), "755", busyboxFile.absolutePath))
            val chmodExit = chmodResult.waitFor()
            FileLogger.d(TAG, "chmod result", "path=${busyboxFile.absolutePath} exit=$chmodExit")

            if (tryExecBusybox(busyboxFile.absolutePath)) {
                cachedBusyboxPath = busyboxFile.absolutePath
                busyboxVerified = true
                FileLogger.i(TAG, "BusyBox ready", "source=fallback path=${busyboxFile.absolutePath}")
                true
            } else {
                FileLogger.e(TAG, "BusyBox NOT executable from filesDir — SELinux W^X enforcement. " +
                    "The native library (libbusybox.so) should be used instead but was not found. " +
                    "Check that jniLibs/armeabi-v7a/libbusybox.so is in the APK.")
                busyboxVerified = false
                false
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "BusyBox install failed", e, "reason=${e.message}")
            false
        }
    }
}
