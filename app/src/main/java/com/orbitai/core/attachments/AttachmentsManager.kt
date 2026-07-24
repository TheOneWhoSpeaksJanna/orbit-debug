package com.orbitai.core.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.orbitai.core.config.FlavorConfig
import com.orbitai.core.logging.FileLogger
import com.orbitai.data.local.runtime.HermesRuntime
import com.orbitai.data.local.runtime.TermuxRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Owns the queue of files attached to the *next* chat message and the logic to
 * copy them into the PRoot-visible directory the active agent reads from.
 *
 * Extracted out of [com.orbitai.ui.viewmodels.ChatViewModel] so the chat
 * ViewModel no longer mixes attachment I/O with agent execution + streaming.
 * Pure, behavior-preserving: same [AttachmentItem] model, same copy targets.
 */
class AttachmentsManager(
    private val termuxRuntime: TermuxRuntime
) {
    data class AttachmentItem(
        val uri: String,
        val displayName: String
    )

    private val _attachments = MutableStateFlow<List<AttachmentItem>>(emptyList())
    val attachments: StateFlow<List<AttachmentItem>> = _attachments.asStateFlow()

    fun attachFile(uri: Uri) {
        val ctx = termuxRuntime.appContext
        val name = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
            } ?: uri.lastPathSegment ?: "file"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "file"
        }
        _attachments.value = _attachments.value + AttachmentItem(uri = uri.toString(), displayName = name)
    }

    fun removeAttachment(item: AttachmentItem) {
        _attachments.value = _attachments.value.filter { it != item }
    }

    fun clear() {
        _attachments.value = emptyList()
    }

    /**
     * Copy the queued attachments into a PRoot-visible directory
     * (HOME/attachments) so the CLI agent can actually open and read them,
     * and return a prompt suffix that tells the agent where they are + their
     * type. This is the real multimodal ingestion path: the agent reads the
     * file bytes (images, text, PDFs) from disk itself.
     *
     * Returns "" when there are no attachments.
     */
    fun prepareForAgent(): String {
        val items = _attachments.value
        if (items.isEmpty()) return ""
        val ctx: Context = termuxRuntime.appContext
        val hostDir: File
        val insideBase: String
        if (FlavorConfig.isHermes) {
            val hr = HermesRuntime(ctx)
            hostDir = File(hr.rootfsDir, "home/attachments")
            insideBase = "/home/attachments"
        } else {
            hostDir = File(termuxRuntime.homeDir, "attachments")
            insideBase = "/data/data/com.termux/files/home/attachments"
        }
        hostDir.mkdirs()
        val lines = StringBuilder("\n\n--- Attached files (read them from disk) ---\n")
        var copiedCount = 0
        for ((idx, item) in items.withIndex()) {
            try {
                val uri = Uri.parse(item.uri)
                val safe = item.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val outName = "${idx}_$safe"
                val outFile = File(hostDir, outName)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("contentResolver returned null stream for ${item.uri}")
                outFile.setReadable(true, false)
                val kind = when {
                    safe.matches(Regex(".*\\.(png|jpe?g|webp|gif|bmp)$", RegexOption.IGNORE_CASE)) -> "image"
                    safe.matches(Regex(".*\\.(pdf)$", RegexOption.IGNORE_CASE)) -> "PDF"
                    else -> "file"
                }
                val guidance = when (kind) {
                    "image" -> "This is an IMAGE — use your vision/multimodal capability to look at it and answer about its contents. Do NOT describe it from the filename; read the actual pixels from disk."
                    "PDF" -> "This is a PDF document — read and analyze its text/contents from disk."
                    else -> "This is a file — read its contents from disk."
                }
                lines.append("- $kind: $insideBase/$outName\n  $guidance\n")
                copiedCount++
            } catch (e: Exception) {
                FileLogger.w(
                    "AttachmentsManager", "Attachment copy failed",
                    "name=${item.displayName} err=${e.message}"
                )
            }
        }
        lines.append("--- end attachments ---\n")
        if (copiedCount < items.size) {
            lines.append("(Note: ${items.size - copiedCount} attachment(s) could not be read from the picker.)\n")
        }
        return lines.toString()
    }
}
