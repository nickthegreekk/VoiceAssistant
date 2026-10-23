package com.nikosm.voiceassistant

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Set of flag names that indicate credential values follow. */
internal val CREDENTIAL_FLAGS = setOf(
    "--token", "--api-key", "--apikey", "--key", "--password", "--secret", "--auth", "--bearer"
)

internal fun redactMcpArgs(args: List<String>): List<String> {
    val out = ArrayList<String>(args.size)
    var redactNext = false
    for (a in args) {
        if (redactNext) {
            redactNext = false
            if (!a.startsWith("-")) {
                out.add("<redacted>")
                continue
            }
        }
        val name = a.substringBefore('=').lowercase()
        when {
            '=' in a && name in CREDENTIAL_FLAGS -> out.add(a.substringBefore('=') + "=<redacted>")
            name in CREDENTIAL_FLAGS -> { out.add(a); redactNext = true }
            else -> out.add(a)
        }
    }
    return out
}

internal fun redactMcpUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    val parsed = url.toHttpUrlOrNull()
    return if (parsed != null) {
        parsed.newBuilder().username("").password("").query(null).fragment(null).build().toString()
    } else {
        url.substringBefore('?').substringBefore('#').replace(Regex("(?<=://)[^/@]*@"), "")
    }
}

/** Redacts all credential fields from a BackupData instance.
 *  Called when excludeSecrets is true in exportBackup().
 *  A non-excluded export must stay identical to today's, including the MCP trust stripping.
 */
internal fun redactBackupCredentials(backup: BackupData): BackupData {
    // Server, Ollama, and Image-gen: clear password and apiKey, redact URL
    val serverBasesSanitized = backup.serverBases.map { s ->
        s.copy(password = null, apiKey = null, url = redactMcpUrl(s.url) ?: s.url)
    }

    val ollamaBasesSanitized = backup.ollamaBases.map { o ->
        o.copy(password = null, apiKey = null, url = redactMcpUrl(o.url) ?: o.url)
    }

    val imageGenBasesSanitized = backup.imageGenBases.map { ig ->
        ig.copy(apiKey = null, password = null, url = redactMcpUrl(ig.url) ?: ig.url)
    }

    // Cloud and custom-cloud: clear apiKey
    val cloudApisSanitized = backup.cloudApis.map { ca ->
        ca.copy(apiKey = "")
    }

    val customCloudApisSanitized = backup.customCloudApis.map { cc ->
        cc.copy(apiKey = "")
    }

    // MCP servers: apply redaction, set trust = ASK, leave enabled alone
    val mcpServersSanitized = backup.mcpServers.map { mcp ->
        val filteredArgs = redactMcpArgs(mcp.args)
        mcp.copy(
            trust = McpTrust.ASK,
            env = emptyMap(),
            headers = emptyMap(),
            url = redactMcpUrl(mcp.url) ?: mcp.url,
            args = filteredArgs
            // enabled is left alone - import already restores servers disabled
        )
    }

    // copy() keeps every field NOT named here — including any field added to BackupData
    // later — identical to the backup. The previous constructor rebuild silently reset
    // personaMessages and personaImages to their defaults.
    return backup.copy(
        serverBases = serverBasesSanitized,
        ollamaBases = ollamaBasesSanitized,
        imageGenBases = imageGenBasesSanitized,
        cloudApis = cloudApisSanitized,
        customCloudApis = customCloudApisSanitized,
        mcpServers = mcpServersSanitized,
        searxngUrl = backup.searxngUrl?.let { redactMcpUrl(it) },
        ragServerUrl = backup.ragServerUrl?.let { redactMcpUrl(it) },
        ragUsername = null,
        ragPassword = null
    )
}
