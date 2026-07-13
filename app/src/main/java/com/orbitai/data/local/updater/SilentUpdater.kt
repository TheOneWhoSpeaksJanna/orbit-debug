package com.orbitai.data.local.updater

import android.content.Context
import com.orbitai.core.logging.FileLogger
import com.orbitai.data.local.runtime.OrbitAiRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

private const val TAG = "SilentUpdater"

/**
 * Installs / updates an APK silently (no system "install?" prompt) using
 * Shizuku.
 *
 * How the silence works:
 *   Shizuku runs commands with the `shell` (uid 2000) identity, which is a
 *   member of the `adb` group and therefore holds
 *   `android.permission.INSTALL_PACKAGES`. Invoking `pm install -r <apk>`
 *   through Shizuku's process bridge performs the install WITHOUT showing the
 *   normal confirmation dialog — exactly the "silent update" behaviour asked
 *   for. This reuses the same Shizuku newProcess bridge already used by
 *   LocalCommandRunner for privileged (SUDO) commands, so it is a proven path.
 *
 * Requires:
 *   - Shizuku running and permission granted (Shizuku.checkSelfPermission()).
 *   - The app holds `REQUEST_INSTALL_PACKAGES` (declared in the manifest).
 *
 * Returns an [UpdateResult] describing success or the failure reason.
 */
class SilentUpdater(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val runtimeManager: OrbitAiRuntimeManager
) {
    sealed class UpdateResult {
        data object Success : UpdateResult()
        data class Failure(val reason: String) : UpdateResult()
    }

    suspend fun installApk(apkFile: File): UpdateResult = withContext(Dispatchers.IO) {
        if (!Shizuku.pingBinder()) {
            return@withContext UpdateResult.Failure("Shizuku is not running.")
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return@withContext UpdateResult.Failure("Shizuku permission not granted.")
        }
        if (!apkFile.exists()) {
            return@withContext UpdateResult.Failure("APK not found: ${apkFile.absolutePath}")
        }

        try {
            // Make the APK world-readable so the shell process (a different
            // UID) can open it.
            apkFile.setReadable(true, false)

            val command = "pm install -r -t \"${apkFile.absolutePath}\""
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as? Process ?: return@withContext UpdateResult.Failure("Shizuku API mismatch.")

            val output = buildString {
                BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                    generateSequence { r.readLine() }.forEach { appendLine(it) }
                }
                BufferedReader(InputStreamReader(process.errorStream)).use { r ->
                    generateSequence { r.readLine() }.forEach { appendLine(it) }
                }
            }
            process.waitFor()
            val exit = process.exitValue()
            FileLogger.i(TAG, "pm install result", "exit=$exit output=${output.take(500)}")

            if (exit == 0 && output.contains("Success", ignoreCase = true)) {
                UpdateResult.Success
            } else if (exit == 0) {
                // Some pm versions print "Success" to stderr or nothing; treat
                // exit 0 as success unless the output explicitly says failure.
                if (output.contains("Failure", ignoreCase = true)) {
                    UpdateResult.Failure(output.trim().ifBlank { "pm exited 0 but reported failure" })
                } else {
                    UpdateResult.Success
                }
            } else {
                UpdateResult.Failure(output.trim().ifBlank { "pm install failed (exit $exit)" })
            }
        } catch (e: NoSuchMethodException) {
            FileLogger.e(TAG, "Shizuku API error", e, "reason=${e.message}")
            UpdateResult.Failure("Shizuku API changed — newProcess not found. Please update the app.")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Silent install exception", e, "reason=${e.message}")
            UpdateResult.Failure("Exception: ${e.message}")
        }
    }
}
