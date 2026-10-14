package com.nikosm.voiceassistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

// ---------------------------------------------------------------------------
// Reading an imported ComfyUI workflow file.
//
// Android-side helpers for the places a user brings a workflow INTO the app: the Image Gen entry's
// dialog (SettingsScreens.kt) and the persona editor's Image Character picker. Deliberately NOT in
// `ComfyWorkflow.kt`: that file is pure (no Android surface) so its validation and token filling
// stay unit-testable, and a ContentResolver has no place there.
// ---------------------------------------------------------------------------

/**
 * Reads a picked workflow as UTF-8 text.
 *
 * Capped at [COMFY_MAX_WORKFLOW_CHARS] bytes plus one — the count is in BYTES while the validation
 * cap is in characters, and for a JSON workflow the byte count is at least the character count, so
 * the read always stops early enough for the "too large" message rather than for a silently
 * truncated — and therefore invalid — template. Returns null only when the file cannot be read.
 */
internal fun readComfyWorkflowText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val limit = COMFY_MAX_WORKFLOW_CHARS
        val buffer = ByteArray(64 * 1024)
        val collected = java.io.ByteArrayOutputStream()
        var total = 0
        while (total <= limit) {
            val read = stream.read(buffer)
            if (read <= 0) break
            collected.write(buffer, 0, read)
            total += read
        }
        String(collected.toByteArray(), Charsets.UTF_8)
    }
} catch (_: Exception) {
    null
}

/**
 * A display name for a picked workflow: the file's own name without its extension, or "" when the
 * provider offers neither a name nor a usable path segment.
 *
 * Labels an imported Image Character in the library. A provider that answers neither query (some
 * cloud/SAF providers) legitimately yields "", and the caller substitutes its own default rather
 * than showing a blank row.
 */
internal fun imageWorkflowDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
    } catch (_: Exception) {
        // Fall through to the path segment below.
    }
    val resolved = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
    return resolved.orEmpty().substringAfterLast('/').substringBeforeLast('.')
}
