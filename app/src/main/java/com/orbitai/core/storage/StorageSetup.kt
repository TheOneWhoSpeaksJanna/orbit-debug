package com.orbitai.core.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.orbitai.core.logging.FileLogger
import java.io.File

/**
 * Replicates what `termux-setup-storage` does, headlessly:
 *  1. Ensures the app holds the Android storage permission so the
 *     `/storage/emulated/0` tree is visible from inside the Termux PRoot.
 *  2. Creates the `~/storage` symlink tree inside the Termux HOME so the
 *     agent can reach `/storage/emulated/0/Download` as `~/storage/downloads`.
 *
 * On Android 11+ (scoped storage) the app must hold the permission (or
 * MANAGE_EXTERNAL_STORAGE) before the emulated volume is mounted for it;
 * without it, `/storage/emulated/0` is simply absent from the process view
 * and the AI cannot write outside its home dir.
 */
object StorageSetup {
    private const val TAG = "StorageSetup"

    /** True if the app can see /storage/emulated/0 (legacy perm or all-files). */
    fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Create the `~/storage/{downloads,dcim,pictures,...}` symlinks inside the
     * Termux HOME directory. `homeDir` is the host path to
     * .../termux-rootfs/data/data/com.termux/files/home. These mirror what
     * `termux-setup-storage` builds, so the AI's files land on the real
     * shared storage instead of the sandboxed home Download.
     */
    fun createStorageSymlinks(homeDir: File) {
        if (!homeDir.exists()) {
            FileLogger.w(TAG, "Termux HOME missing, skipping storage symlinks", homeDir.absolutePath)
            return
        }
        val storageRoot = "/storage/emulated/0"
        val targets = mapOf(
            "downloads" to "$storageRoot/Download",
            "dcim" to "$storageRoot/DCIM",
            "pictures" to "$storageRoot/Pictures",
            "music" to "$storageRoot/Music",
            "movies" to "$storageRoot/Movies",
            "documents" to "$storageRoot/Documents",
            "external-1" to "/storage/emulated/0",
            "shared" to "$storageRoot"
        )
        val storageLink = File(homeDir, "storage")
        try {
            if (storageLink.exists() || storageLink.mkdirs()) {
                for ((name, target) in targets) {
                    val link = File(storageLink, name)
                    try {
                        if (link.exists()) link.delete()
                        Runtime.getRuntime().exec(
                            arrayOf("ln", "-s", target, link.absolutePath)
                        ).waitFor()
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "symlink failed: $name -> $target", e.message ?: "")
                    }
                }
                FileLogger.i(TAG, "storage symlinks created under ${storageLink.absolutePath}")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "createStorageSymlinks failed", e, e.message ?: "")
        }
    }
}
