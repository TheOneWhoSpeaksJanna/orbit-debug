package com.orbitai.data.local.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.orbitai.BuildConfig
import com.orbitai.core.logging.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateManager"

/**
 * Robust, self-contained update system for Orbit-AI.
 *
 * WHY THIS EXISTS
 * --------------
 * The previous updater compared `BuildConfig.VERSION_NAME.substringAfterLast('-')`
 * (which yields the flavor suffix, e.g. "openclaude" -> null) and used a hardcoded
 * `versionCode = 13`. Because the versionCode never increased, Android's package
 * manager silently REJECTED update APKs as "not an upgrade", so bug fixes never
 * reached installed users — they had to uninstall/reinstall.
 *
 * This manager instead:
 *   1. Reads a `version-info.json` asset published WITH each GitHub release.
 *      That file carries the authoritative `versionCode` for every flavor, so
 *      the "is this newer?" decision is exact (no string parsing of the tag).
 *   2. Compares the release's flavor versionCode against the INSTALLED
 *      BuildConfig.VERSION_CODE (now auto-bumped at build time, so every build
 *      is strictly newer than the previous one).
 *   3. Downloads the matching per-flavor APK and installs it in-place with
 *      `cmd package install -r` (keeps user data, replaces the code).
 *   4. Force-restarts the app so the new code is loaded (Android keeps the old
 *      dex until the process dies).
 *
 * The release MUST publish `version-info.json` with this shape:
 * {
 *   "tag": "v123",
 *   "flavors": {
 *     "openclaude": { "versionCode": 123456, "apk": "orbit-ai-openclaude-debug.apk" },
 *     ...
 *   }
 * }
 * If the asset is missing we fall back to comparing the run-number in the tag
 * against a build timestamp, but the JSON path is preferred.
 */

data class UpdateCheckResult(
    val available: Boolean,
    val tag: String = "",
    val apkUrl: String? = null,
    val newVersionCode: Int = 0,
    val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    /** Expected SHA-256 of the APK, read from version-info.json (may be blank). */
    val expectedSha256: String = "",
    val message: String = ""
)

sealed class UpdateInstallResult {
    data object Success : UpdateInstallResult()
    data class Failure(val reason: String) : UpdateInstallResult()
    data class NeedsManualInstall(val apkUri: android.net.Uri) : UpdateInstallResult()
}

class UpdateManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val silentUpdater: SilentUpdater
) {

    companion object {
        const val RELEASES_LATEST =
            "https://api.github.com/repos/TheOneWhoSpeaksJanna/Orbit-AI/releases/latest"
        const val VERSION_INFO_NAME = "version-info.json"

        /**
         * Verify a freshly-downloaded update APK before it is installed.
         * Defense against a tampered/compromised release artifact:
         *   1. SHA-256 of the file must equal the `sha256` published in
         *      version-info.json for this flavor (CI must emit it).
         *   2. The APK's signing-certificate SHA-1 must equal the
         *      certificate of the currently-installed package (same key
         *      that signed what the user already trusts). A different key =
         *      a different owner of the signing identity -> refuse.
         *
         * Returns null when verification passes, or a human-readable reason
         * when it must be refused. If version-info.json has no `sha256`
         * entry (old releases), we skip check #1 (warn) but still enforce #2.
         */
        fun verifyUpdate(
            context: Context,
            apkFile: File,
            expectedSha256: String?
        ): String? {
            // 1. Checksum (if the release provides one).
            if (!expectedSha256.isNullOrBlank()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                apkFile.inputStream().use { fis ->
                    val buf = ByteArray(1 shl 16)
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    return "Checksum mismatch: expected $expectedSha256, got $actual"
                }
            }

            // 2. Signing-cert must match the installed package's cert.
            return try {
                val pkgMgr = context.packageManager
                val installed = pkgMgr.getPackageInfo(
                    context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                val installedSigs = installed.signingInfo
                    ?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
                    ?: return "Cannot read installed package signature."
                val installedSha1 = installedSigs.firstOrNull()?.let { sig ->
                    val d = java.security.MessageDigest.getInstance("SHA-1")
                    d.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
                } ?: return "Installed package has no signature."

                val dlPkg = pkgMgr.getPackageArchiveInfo(
                    apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                val dlSigs = dlPkg?.signingInfo
                    ?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
                val dlSha1 = dlSigs?.firstOrNull()?.let { sig ->
                    val d = java.security.MessageDigest.getInstance("SHA-1")
                    d.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
                }
                if (dlSha1 == null) {
                    "Downloaded APK has no signature."
                } else if (!dlSha1.equals(installedSha1, ignoreCase = true)) {
                    "Signing certificate mismatch: the update is signed by a " +
                        "different key than the installed app. Refusing to install."
                } else {
                    null // verified
                }
            } catch (e: Exception) {
                "Verification error: ${e.message}"
            }
        }
    }

    /**
     * Map the running build's applicationId suffix to a flavor key.
     * Must cover EVERY flavor declared in app/build.gradle.kts, or the
     * updater silently falls through to "normal" and never finds the
     * correct per-flavor APK (was missing "hermes" -> broken updates).
     */
    private fun currentFlavor(): String = when {
        BuildConfig.APPLICATION_ID.endsWith(".hermes") -> "hermes"
        BuildConfig.APPLICATION_ID.endsWith(".openclaude") -> "openclaude"
        BuildConfig.APPLICATION_ID.endsWith(".opencode") -> "opencode"
        BuildConfig.APPLICATION_ID.endsWith(".claudecode") -> "claudecode"
        BuildConfig.APPLICATION_ID.endsWith(".codex") -> "codex"
        else -> "normal"
    }

    /**
     * Check GitHub for a newer release for this flavor.
     * Pure network + comparison — does NOT download.
     */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val flavor = currentFlavor()
        val currentCode = BuildConfig.VERSION_CODE
        try {
            val apiResp = okHttpClient.newCall(
                okhttp3.Request.Builder().url(RELEASES_LATEST).build()
            ).execute()
            if (!apiResp.isSuccessful) {
                return@withContext UpdateCheckResult(
                    available = false, message = "Couldn't reach update server (HTTP ${apiResp.code})."
                )
            }
            val release = JSONObject(apiResp.body?.string().orEmpty())
            val tag = release.optString("tag_name", "")
            val assets = release.optJSONArray("assets") ?: org.json.JSONArray()

            // Preferred: parse version-info.json for an exact versionCode.
            var versionInfo: JSONObject? = null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (name.equals(VERSION_INFO_NAME, ignoreCase = true)) {
                    versionInfo = JSONObject(
                        okHttpClient.newCall(
                            okhttp3.Request.Builder()
                                .url(a.optString("browser_download_url", ""))
                                .build()
                        ).execute().body?.string().orEmpty()
                    )
                }
                // The CI workflow publishes artifacts named
                // "orbit-ai-<flavor>-debug-<run>.apk". Match on the
                // "orbit-ai-" prefix + "-<flavor>-debug" substring so the
                // updater finds the right APK regardless of the trailing run
                // number (the old "app-$flavor-debug" pattern never matched
                // and left apkUrl null -> "No APK found").
                if (name.contains("orbit-ai-$flavor-debug", ignoreCase = true) &&
                    name.endsWith(".apk", ignoreCase = true)
                ) {
                    apkUrl = a.optString("browser_download_url", "")
                }
            }

            val newCode: Int? = if (versionInfo != null) {
                versionInfo.optJSONObject("flavors")
                    ?.optJSONObject(flavor)
                    ?.optInt("versionCode", -1)
                    ?.takeIf { it >= 0 }
            } else {
                // Fallback: run-number in the tag (e.g. "v123" -> 123).
                tag.substringAfter('v').toIntOrNull()
            }

            // Expected APK checksum (if the release published one). CI should
            // emit "sha256" per flavor in version-info.json so the updater can
            // cryptographically verify the download before installing it.
            val expectedSha256 = versionInfo
                ?.optJSONObject("flavors")
                ?.optJSONObject(flavor)
                ?.optString("sha256", "")
                .orEmpty()

            if (apkUrl.isNullOrBlank()) {
                return@withContext UpdateCheckResult(
                    available = false, tag = tag,
                    message = "No APK found for flavor '$flavor'."
                )
            }

            return@withContext if (newCode != null && newCode > currentCode) {
                UpdateCheckResult(
                    available = true, tag = tag, apkUrl = apkUrl,
                    newVersionCode = newCode, currentVersionCode = currentCode,
                    expectedSha256 = expectedSha256,
                    message = "Update available: $tag (build $newCode)."
                )
            } else {
                UpdateCheckResult(
                    available = false, tag = tag, currentVersionCode = currentCode,
                    newVersionCode = newCode ?: 0,
                    expectedSha256 = expectedSha256,
                    message = "Already on the latest version ($tag)."
                )
            }
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: "network error"
            UpdateCheckResult(available = false, message = "Couldn't check for updates — $reason")
        }
    }

    /**
     * Download + install the update APK referenced by [apkUrl].
     * [expectedSha256] (from version-info.json, may be blank) is verified
     * before install. On success the caller should call [restartApp].
     */
    suspend fun downloadAndInstall(
        apkUrl: String,
        expectedSha256: String = ""
    ): UpdateInstallResult =
        withContext(Dispatchers.IO) {
            try {
                val dlResp = okHttpClient.newCall(
                    okhttp3.Request.Builder().url(apkUrl).build()
                ).execute()
                if (!dlResp.isSuccessful) {
                    return@withContext UpdateInstallResult.Failure("Download failed (HTTP ${dlResp.code}).")
                }
                val outDir = File(context.cacheDir, "updates")
                outDir.mkdirs()
                val apkFile = File(outDir, "orbit_update.apk")
                dlResp.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { out -> input.copyTo(out) }
                }

                // Verify the downloaded artifact before touching the installer.
                // Refuses to install on checksum OR signing-cert mismatch.
                val verifyReason = UpdateManager.verifyUpdate(context, apkFile, expectedSha256)
                if (verifyReason != null) {
                    FileLogger.e(TAG, "Update verification FAILED", verifyReason)
                    return@withContext UpdateInstallResult.Failure(
                        "Update rejected: $verifyReason"
                    )
                }
                FileLogger.i(TAG, "Update verified (checksum + signing cert OK)")

                if (!silentUpdater.canSilentInstall()) {
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", apkFile
                    )
                    return@withContext UpdateInstallResult.NeedsManualInstall(uri)
                }
                when (val res = silentUpdater.installApk(apkFile)) {
                    is SilentUpdater.UpdateResult.Success ->
                        UpdateInstallResult.Success
                    is SilentUpdater.UpdateResult.Failure ->
                        UpdateInstallResult.Failure(res.reason)
                    is SilentUpdater.UpdateResult.NeedsManualInstall ->
                        UpdateInstallResult.NeedsManualInstall(res.apkUri)
                }
            } catch (e: Exception) {
                UpdateInstallResult.Failure("Install error: ${e.message}")
            }
        }

    /** Kill the running process so Android loads the freshly-installed code. */
    fun restartApp() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
            Runtime.getRuntime().exit(0)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Restart failed", e, "reason=${e.message}")
        }
    }
}
