package com.nikosm.voiceassistant

import org.json.JSONObject
import java.io.File

/**
 * Every MCP tool call, written down — the M9 sibling of the Linux tree's audit file.
 *
 * **Argument values and results are never written here.** They routinely contain the very thing the
 * call was about — a door code, a calendar entry, a message body — and an audit file that quotes
 * them is a second copy of the user's data in a place they may not think to shred. The confirmation
 * dialog is the only place arguments appear, and it disappears with the turn. What remains is the
 * receipt: who asked, which server, which tool, whether it ran, how much came back, and whether the
 * user approved it.
 *
 * The file is `java.io.File` rather than the Linux tree's `java.nio.file.Path`: this app's storage
 * is a `File` in `filesDir`, and every other file this tree writes is one.
 */
internal data class McpToolAuditEntry(
    val timestamp: Long,
    /** The persona whose turn made the call. */
    val persona: String,
    val server: String,
    val serverId: String,
    /** The declared, namespaced name — never the model's own spelling, which does not exist. */
    val operation: String,
    val bytes: Long,
    /** `ok`, `error`, `denied` or `timeout`. */
    val result: String,
    /** `user`, `trusted`, `denied` or `timeout`, the same words the filesystem audit uses. */
    val confirmed: String,
    val detail: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("ts", timestamp)
        .put("persona", persona)
        .put("server", server)
        .put("server_id", serverId)
        .put("op", operation)
        .put("bytes", bytes)
        .put("result", result)
        .put("confirmed", confirmed)
        .put("detail", detail ?: JSONObject.NULL)

    internal companion object {
        internal fun fromJson(json: JSONObject): McpToolAuditEntry? = runCatching {
            McpToolAuditEntry(
                timestamp = json.getLong("ts"),
                persona = json.optString("persona"),
                server = json.optString("server"),
                serverId = json.optString("server_id"),
                operation = json.getString("op"),
                bytes = json.optLong("bytes"),
                result = json.optString("result"),
                confirmed = json.optString("confirmed"),
                detail = json.opt("detail")?.takeIf { it != JSONObject.NULL }?.toString(),
            )
        }.getOrNull()
    }
}

internal const val MCP_AUDIT_FILE = "mcp-tool-audit.jsonl"

private const val MCP_AUDIT_MAX_BYTES = 1_048_576L
private const val MCP_AUDIT_KEEP_LINES = 2000

/** Append one record; never throws and never fails a turn, exactly as the filesystem audit does. */
internal fun appendMcpToolAudit(file: File, entry: McpToolAuditEntry, logger: AppLogger) {
    runCatching {
        file.parentFile?.mkdirs()
        if (file.exists() && file.length() > MCP_AUDIT_MAX_BYTES) {
            // Trimmed by LINES rather than by bytes: the cap is about how much history the
            // settings screen shows, and a partial line would be an unreadable record.
            val kept = file.readLines().takeLast(MCP_AUDIT_KEEP_LINES)
            file.writeText(kept.joinToString("\n") + "\n")
        }
        file.appendText(entry.toJson().toString() + "\n")
    }.onFailure { logger.w("McpToolAudit", "could not write the MCP audit record: ${it.message}") }
}

/** The newest [limit] records, newest first. Unreadable lines are skipped, not fatal. */
internal fun readMcpToolAudit(file: File, limit: Int): List<McpToolAuditEntry> {
    if (!file.exists() || !file.canRead()) return emptyList()
    return runCatching {
        file.readLines()
            .asReversed()
            .mapNotNull { line -> runCatching { McpToolAuditEntry.fromJson(JSONObject(line)) }.getOrNull() }
            .take(limit)
    }.getOrDefault(emptyList())
}
