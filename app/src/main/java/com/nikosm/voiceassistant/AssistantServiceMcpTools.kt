package com.nikosm.voiceassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * M9 on the service: which servers a persona offers, the confirmation a call may need, and the
 * audit record.
 *
 * The parts that could be pure are elsewhere: the protocol in `McpProtocol.kt`, the connections in
 * `McpRegistry.kt`, the declarations in `McpTools.kt`. This file is only what the live service owns
 * — a question waiting for an answer, and the settings it reads.
 *
 * Ported from the Linux build's `AssistantServiceMcpTools.kt` with three substitutions, each forced
 * by the platform rather than chosen:
 *
 *  - **The audit file is written with `java.io.File`, not `java.nio.file.Path`.** Android has
 *    `java.nio.file` from API 26, but the app's own storage is a `File` in `filesDir` and every
 *    other Android file path in this tree is a `File`.
 *  - **The timeout notification is a `NotificationManager` post**, not the Linux tree's `Notifier`.
 *  - **There is no `appEnv`**: the Android service reaches its own `filesDir` directly.
 */

/** What the user's answer (or its absence) means for one call. */
internal enum class McpApprovalDecision { ALLOW, TRUST, DENIED, TIMEOUT }

/** The question the UI renders: which server, which tool, and the arguments the model sent. */
internal data class McpApprovalRequest(
    val serverId: String,
    val serverName: String,
    /** The declared name (`mcp_<server>_<tool>`), which is what the model asked for. */
    val toolName: String,
    /** The arguments as JSON, truncated for display. Never written to the audit. */
    val arguments: String,
    val personaName: String,
)

/**
 * The confirmation state machine, the filesystem write gate's twin.
 *
 * The rules are deliberately the same ones the write gate learned the hard way: **dismissal denies,
 * an unanswered question times out to deny, and a newer turn denies whatever was pending** — so a
 * stale dialog can never approve a call, and a hands-free utterance arriving while the prompt is up
 * fails safe. Trust is per server and persisted, because a question raised on a backgrounded window
 * that times out unseen reads as a bug (the reason the filesystem grant persists too).
 */
internal class McpApprovalGate {

    private val _pending = MutableStateFlow<McpApprovalRequest?>(null)

    /** The question the UI is showing, or null when there is nothing to answer. */
    val pending = _pending.asStateFlow()

    /** Written on the service coroutine, completed from the UI: the volatile is the hand-off. */
    @Volatile
    private var deferred: CompletableDeferred<McpApprovalDecision>? = null

    /** The user's answer. Ignored when no question is pending, like every dialog dismissal. */
    fun answer(allow: Boolean, trustServer: Boolean = false) {
        val waiting = deferred ?: return
        waiting.complete(
            when {
                !allow -> McpApprovalDecision.DENIED
                trustServer -> McpApprovalDecision.TRUST
                else -> McpApprovalDecision.ALLOW
            },
        )
    }

    /** A newer turn started, or the app is closing: deny, so a stale dialog can never approve. */
    fun denyPending() {
        deferred?.complete(McpApprovalDecision.DENIED)
    }

    /** Ask and wait. Denied on timeout, because nobody may be there to read it. */
    suspend fun request(
        request: McpApprovalRequest,
        timeoutMs: Long = MCP_APPROVAL_TIMEOUT_MS,
    ): McpApprovalDecision {
        val waiting = CompletableDeferred<McpApprovalDecision>()
        deferred = waiting
        _pending.value = request
        return try {
            withTimeoutOrNull(timeoutMs) { waiting.await() } ?: McpApprovalDecision.TIMEOUT
        } finally {
            if (deferred === waiting) {
                _pending.value = null
                deferred = null
            }
        }
    }
}

internal const val MCP_APPROVAL_TIMEOUT_MS = 120_000L

/** One card, reused: there is at most one question pending, and it is answered or withdrawn. */
internal const val MCP_APPROVAL_NOTIFICATION_ID = 4

/** How much of an arguments object the dialog shows. The audit shows none of it. */
private const val MCP_ARGUMENTS_PREVIEW = 1_000

// ---------------------------------------------------------------------------- what a persona has

/** The enabled servers this persona offers, in settings order. */
internal fun AssistantService.enabledMcpServersFor(persona: Persona): List<McpServerConfig> {
    val ids = settingsManager.getMcpServersForPersona(persona.name).toSet()
    if (ids.isEmpty()) return emptyList()
    return settingsManager.getMcpServers().filter { it.enabled && it.id in ids }
}

/** Whether this persona has any MCP server at all — the gateway-route refusal asks this. */
internal fun AssistantService.mcpServersEnabledFor(persona: Persona): Boolean =
    enabledMcpServersFor(persona).isNotEmpty()

/**
 * The tools this turn may offer, resolved lazily through the registry. A server that is down
 * contributes nothing and one logged sentence; it never fails the turn.
 */
internal suspend fun AssistantService.resolveMcpTools(persona: Persona): List<McpTool> =
    enabledMcpServersFor(persona).flatMap { server -> mcpRegistry.toolsFor(server) }
// ---------------------------------------------------------------------------- one call

/**
 * Ask if this server has not been trusted, and remember the answer when it is "always".
 *
 * A trusted server skips the question entirely and returns [McpApprovalDecision.TRUST], which is
 * what the audit records — the same word an already-trusted filesystem root gets, because from the
 * user's side those are the same decision: stop asking about this server.
 */
internal suspend fun AssistantService.approveMcpCall(
    tool: McpTool,
    arguments: JSONObject,
    personaName: String,
): McpApprovalDecision {
    val server = settingsManager.getMcpServers().firstOrNull { it.id == tool.serverId }
        ?: return McpApprovalDecision.DENIED
    if (server.trust == McpTrust.TRUSTED) return McpApprovalDecision.TRUST

    val decision = mcpApprovalGate.request(
        McpApprovalRequest(
            serverId = server.id,
            serverName = server.name,
            toolName = tool.declaredName,
            arguments = arguments.toString().take(MCP_ARGUMENTS_PREVIEW),
            personaName = personaName,
        ),
    )
    if (decision == McpApprovalDecision.TRUST) {
        // Written only when it changes something, so a trusted server does not rewrite the
        // settings file on every call.
        settingsManager.saveMcpTrust(server.id, McpTrust.TRUSTED)
    }
    if (decision == McpApprovalDecision.TIMEOUT) {
        // The notification is the one channel that works with the app in the background. Past
        // tense: this fires after the gate has withdrawn the question, so the call was denied.
        runCatching { postMcpApprovalTimeoutNotification(server.name) }
    }
    return decision
}

/**
 * One MCP call end to end: approve, run, audit. **Never throws for a tool-side failure** — the model
 * gets a sentence and the turn continues, which is the contract every other tool follows.
 */
internal suspend fun AssistantService.runMcpTool(
    tool: McpTool,
    arguments: JSONObject,
    personaName: String,
): McpCallOutcome {
    val decision = approveMcpCall(tool, arguments, personaName)
    val outcome = when (decision) {
        McpApprovalDecision.DENIED, McpApprovalDecision.TIMEOUT ->
            McpCallOutcome("The user did not approve this call, so it was not run.", ok = false)
        McpApprovalDecision.ALLOW, McpApprovalDecision.TRUST -> mcpRegistry.call(tool, arguments)
    }
    auditMcpToolCall(tool, outcome, decision, personaName)
    return outcome
}

/** One audit line: names, sizes and the decision — never an argument value or a result. */
internal fun AssistantService.auditMcpToolCall(
    tool: McpTool,
    outcome: McpCallOutcome,
    decision: McpApprovalDecision,
    personaName: String,
) {
    val confirmed = when (decision) {
        McpApprovalDecision.ALLOW -> "user"
        McpApprovalDecision.TRUST -> "trusted"
        McpApprovalDecision.DENIED -> "denied"
        McpApprovalDecision.TIMEOUT -> "timeout"
    }
    val result = if (outcome.ok) "ok" else "error"
    logger.i("McpTools", "${tool.declaredName} -> $result (${outcome.text.length} chars, $confirmed)")
    appendMcpToolAudit(
        mcpAuditFile(),
        McpToolAuditEntry(
            timestamp = System.currentTimeMillis(),
            persona = personaName,
            server = tool.serverName,
            serverId = tool.serverId,
            operation = tool.declaredName,
            bytes = outcome.text.toByteArray().size.toLong(),
            result = result,
            confirmed = confirmed,
        ),
        logger,
    )
}

/**
 * The audit file, in the app's own storage.
 *
 * `filesDir/mcp/` — a subdirectory of its own, because the audit is one file among several the app
 * may write and the settings screen names the path in the audit's own header text.
 */
internal fun AssistantService.mcpAuditFile(): File =
    File(File(filesDir, "mcp"), MCP_AUDIT_FILE)

/**
 * Post the "nobody answered" card.
 *
 * The service's own channel is the foreground notification, which is the assistant's status line and
 * is not what this is: the card has to survive the app being in the background, so it goes out on
 * the same channel the spontaneous messages use — a channel that already exists and already has
 * the user's permission expectations attached to it.
 */
private fun AssistantService.postMcpApprovalTimeoutNotification(serverName: String) {
    val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
    val channelId = SPONTANEOUS_CHANNEL_ID
    // The channel is created by the spontaneous-delivery path, which may never have run on this
    // install. Creating it here is idempotent and costs nothing when it already exists.
    val channel = android.app.NotificationChannel(
        channelId,
        "Persona messages",
        android.app.NotificationManager.IMPORTANCE_DEFAULT,
    )
    manager.createNotificationChannel(channel)
    manager.notify(
        MCP_APPROVAL_NOTIFICATION_ID,
        androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tool call not approved")
            .setContentText("A tool call to \"$serverName\" was not approved — no answer in time.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build(),
    )
}

// ---------------------------------------------------------------------------- settings seam

internal fun AssistantService.mcpServersSetting(): List<McpServerConfig> = settingsManager.getMcpServers()

internal fun AssistantService.saveMcpServersSetting(servers: List<McpServerConfig>) {
    settingsManager.saveMcpServers(servers)
}

internal fun AssistantService.mcpServersForPersonaSetting(personaName: String): List<String> =
    settingsManager.getMcpServersForPersona(personaName)

internal fun AssistantService.setMcpServersForPersonaSetting(personaName: String, serverIds: List<String>) {
    settingsManager.saveMcpServersForPersona(personaName, serverIds)
}

/** "Stop trusting this server" from the settings screen; the next call asks again. */
internal fun AssistantService.forgetMcpTrust(serverId: String) {
    settingsManager.saveMcpTrust(serverId, McpTrust.ASK)
    logger.i("McpTools", "trust revoked for MCP server '$serverId'")
}
