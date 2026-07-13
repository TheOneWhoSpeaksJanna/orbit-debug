package com.orbitai.core.logging

import android.content.Context
import android.os.Build
import android.util.Log
import com.orbitai.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Structured file logger with session IDs, durations, and consistent log levels.
 *
 * LOG FORMAT:
 *   [timestamp] | [level] | [thread] | [tag] | [session=ID] | [event] | [details]
 *
 * Example:
 *   2026-07-03 19:32:27.458 | I | worker-1 | PackageInstaller | session=2026-07-03_19-32-07_8421 | installPackage start | package=nodejs
 *   2026-07-03 19:32:41.901 | I | worker-1 | PackageInstaller | session=2026-07-03_19-32-07_8421 | download success | bytes=10268488 time=33.4s
 *   2026-07-03 19:34:04.486 | E | worker-3 | PackageInstaller | session=2026-07-03_19-32-07_8421 | install failed | reason=tar extraction exit=126
 *
 * LOG LEVELS:
 *   I = normal flow, milestones, successful steps
 *   D = detailed debug info (paths, env vars, internal state)
 *   W = recoverable problems, fallback paths, missing optional items
 *   E = actual failures that break a step
 *
 * USAGE:
 *   FileLogger.i("Tag", "installPackage start", "package=$pkgId")
 *   FileLogger.i("Tag", "download success", "bytes=$size time=${ms}ms")
 *   FileLogger.e("Tag", "install failed", "reason=$reason")
 *
 * For durations, use the timed() helper:
 *   val result = FileLogger.timed("Tag", "download") { ... }
 */
object FileLogger {

    private const val LOG_DIR = "omniclaw_logs"
    private const val MAX_LOG_FILES = 7
    private const val MAX_CRASH_FILES = 10
    private const val TAG = "Orbit AI"
    private const val FILE_LOGGER_TAG = "FileLogger"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val sessionFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val executor = Executors.newSingleThreadExecutor()

    private var appContext: Context? = null
    private var logDir: File? = null
    private var isInitialized = false
    private var writeFailureLogged = false
    private var originalExceptionHandler: Thread.UncaughtExceptionHandler? = null

    /** Unique session ID for this app launch. Included in every log line. */
    val sessionId: String by lazy {
        sessionFormat.format(Date()) + "_" + (1000..9999).random()
    }

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        val appCtx = appContext!!

        logDir = resolvePublicLogDir(appCtx)
        if (logDir == null || (logDir!!.exists() && !logDir!!.canWrite())) {
            logDir = appCtx.getExternalFilesDir(LOG_DIR)
        }
        if (logDir == null) {
            logDir = File(appCtx.cacheDir, LOG_DIR)
        }
        logDir?.let { dir ->
            if (!dir.exists()) dir.mkdirs()
            cleanOldLogs(dir)
        }
        isInitialized = true
        installCrashHandler()

        // Startup context (logged once at init)
        i(TAG, "App start", "version=${BuildConfig.VERSION_NAME} build=${BuildConfig.BUILD_TYPE} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL} abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"} session=$sessionId")
    }

    fun getLogDirPath(): String? = logDir?.absolutePath

    private fun resolvePublicLogDir(context: Context): File? {
        return try {
            // Prefer app-specific external storage — always writable WITHOUT any
            // dangerous permission. Writing to /sdcard root (Environment.getExternalStorageDirectory())
            // is NOT writable on modern Android / emulated storage and silently drops every log,
            // which is why the app ran but produced zero log files.
            val extDir = context.getExternalFilesDir(LOG_DIR)
            if (extDir != null) {
                if (!extDir.exists()) extDir.mkdirs()
                if (extDir.canWrite()) return extDir
            }
            // Fallback to internal app files dir (always writable, private to the app).
            val internalDir = File(context.filesDir, LOG_DIR)
            if (!internalDir.exists()) internalDir.mkdirs()
            if (internalDir.canWrite()) internalDir else null
        } catch (_: Exception) {
            null
        }
    }

    private fun installCrashHandler() {
        originalExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e(TAG, "CRASH", "${throwable.javaClass.name}: ${throwable.message}")
            logCrashSync(throwable, thread)
            originalExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    // ── Core log methods ──────────────────────────────────────────

    fun d(tag: String, event: String, details: String = "") = write("D", tag, event, details)
    fun i(tag: String, event: String, details: String = "") = write("I", tag, event, details)
    fun w(tag: String, event: String, details: String = "") = write("W", tag, event, details)
    fun e(tag: String, event: String, details: String = "") = write("E", tag, event, details)
    fun e(tag: String, event: String, throwable: Throwable?, details: String = "") {
        val detail = if (throwable != null) {
            val stack = throwable.stackTraceToString().lines().take(15).joinToString("\n")
            "$details\n$stack"
        } else details
        write("E", tag, event, detail)
    }

    /**
     * Execute a block and log its duration. Returns the block's result.
     *
     * Example:
     *   val data = FileLogger.timed("Download", "fetch nodejs.deb") {
     *       httpClient.newCall(request).execute()
     *   }
     *   // Logs: I | Download | session=... | fetch nodejs.deb success | time=33400ms
     */
    fun <T> timed(tag: String, event: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            val duration = System.currentTimeMillis() - start
            i(tag, "$event success", "time=${duration}ms")
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            e(tag, "$event failed", "time=${duration}ms reason=${e.message}")
            throw e
        }
    }

    private fun write(level: String, tag: String, event: String, details: String) {
        // Always write to logcat first
        try {
            val logcatMsg = if (details.isNotBlank()) "$event | $details" else event
            when (level) {
                "D" -> Log.d(tag, logcatMsg)
                "I" -> Log.i(tag, logcatMsg)
                "W" -> Log.w(tag, logcatMsg)
                "E" -> Log.e(tag, logcatMsg)
                else -> Log.i(tag, logcatMsg)
            }
        } catch (_: Throwable) { }

        if (!isInitialized) return
        val time = timeFormat.format(Date())
        val threadName = Thread.currentThread().name
        val detailPart = if (details.isNotBlank()) " | $details" else ""
        val line = "$time | $level | $threadName | $tag | session=$sessionId | $event$detailPart\n"

        executor.execute {
            val dir = logDir ?: return@execute
            if (!dir.exists()) dir.mkdirs()
            if (!dir.exists() || !dir.canWrite()) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true
                    try { Log.e(FILE_LOGGER_TAG, "Cannot write logs to ${dir.absolutePath}") } catch (_: Throwable) { }
                }
                return@execute
            }
            val file = File(dir, "app_${dateFormat.format(Date())}.log")
            try {
                FileWriter(file, true).use { it.append(line) }
                writeFailureLogged = false
            } catch (e: Exception) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true
                    try { Log.e(FILE_LOGGER_TAG, "FileWriter failed: ${e.message}") } catch (_: Throwable) { }
                }
            }
        }
    }

    private fun logCrashSync(throwable: Throwable, thread: Thread?) {
        if (!isInitialized) return
        val time = timeFormat.format(Date())
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val crashFileName = "crash_$ts.log"

        val header = buildString {
            append("$time | E | ${thread?.name ?: "unknown"} | CRASH | session=$sessionId | === UNCAUGHT CRASH ===\n")
            append("$time | E | ${thread?.name ?: "unknown"} | CRASH | session=$sessionId | ${throwable.javaClass.name}: ${throwable.message}\n")
            append(throwable.stackTraceToString().lines().joinToString("\n") { line ->
                "$time | E | ${thread?.name ?: "unknown"} | CRASH | session=$sessionId |   $line"
            })
            append("\n")
        }

        try { Log.e(TAG, "CRASH SUMMARY: ${throwable.javaClass.name}: ${throwable.message}") } catch (_: Throwable) { }

        logDir?.let { dir ->
            try {
                if (!dir.exists()) dir.mkdirs()
                FileWriter(File(dir, crashFileName), false).use { it.append(header) }
                FileWriter(File(dir, "app_${dateFormat.format(Date())}.log"), true).use { it.append(header) }
                cleanCrashLogs(dir)
            } catch (_: Exception) { }
        }
    }

    private fun cleanOldLogs(dir: File) {
        val files = dir.listFiles { f -> f.name.matches(Regex("app_\\d{4}-\\d{2}-\\d{2}\\.log")) }
            ?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_LOG_FILES) {
            files.drop(MAX_LOG_FILES).forEach { it.delete() }
        }
    }

    private fun cleanCrashLogs(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_CRASH_FILES) {
            files.drop(MAX_CRASH_FILES).forEach { it.delete() }
        }
    }
}
