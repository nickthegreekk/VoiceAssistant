package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Settings → MCP (PLAN.md §10/M9): the servers a persona can call, their transport and trust, the
 * Test button, and the audit.
 *
 * **Off is the shipped state**, like the filesystem screen: no server exists until one is added,
 * and no persona offers one until it is switched on in the persona editor. The screen says both
 * rules in words rather than leaving them to the plan: tools run only in the main chat, and the
 * first call to an untrusted server asks before it runs.
 *
 * **No scroll of its own.** This page is rendered inside the settings shell's `LazyColumn` item
 * (`SettingsScreens.kt`), and a lazy item is measured with unbounded height — a `verticalScroll`
 * here is measured against infinity and Compose throws. M8's Files screen learned this live; the
 * editor dialog below *may* scroll because a dialog is its own window.
 */
@Composable
internal fun McpSettings(service: AssistantService, contentPadding: Int = 16) {
    var servers by remember { mutableStateOf(service.mcpServersSetting()) }
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var audit by remember { mutableStateOf(emptyList<McpToolAuditEntry>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(servers) {
        audit = readMcpToolAudit(service.mcpAuditFile(), MCP_AUDIT_ROWS)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(contentPadding.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("MCP tool servers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Servers that speak the Model Context Protocol become tools the assistant can call — a " +
                "home-automation sidecar on another machine, a knowledge server on your network. They " +
                "are offered in the main chat only, and only to the personas you enable them for. The " +
                "first call to a server asks before it runs; \"always allow\" remembers that server " +
                "until you revoke it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            "This app talks to servers over HTTP. A server that runs as a program on your computer " +
                "cannot be started from a phone, so only HTTP servers can be added here.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        if (servers.isEmpty()) {
            Text(
                "No servers configured. Nothing is spawned and no connection is opened until one is " +
                    "added and enabled for a persona.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        servers.forEach { server ->
            Surface(shape = RectangleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                mcpServerDetail(server),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        Switch(
                            checked = server.enabled,
                            onCheckedChange = { on ->
                                service.saveMcpServersSetting(
                                    servers.map { if (it.id == server.id) it.copy(enabled = on) else it },
                                )
                                servers = service.mcpServersSetting()
                            },
                        )
                    }
                    Text(
                        if (server.trust == McpTrust.TRUSTED) {
                            "Calls here run without asking. Revoke that below."
                        } else {
                            "Calls here ask first, with the server, the tool and its arguments."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editing = server }) { Text("Edit") }
                        TextButton(onClick = {
                            scope.launch { notice = probeMcpServer(service, server) }
                        }) { Text("Test") }
                        if (server.trust == McpTrust.TRUSTED) {
                            TextButton(onClick = {
                                service.forgetMcpTrust(server.id)
                                servers = service.mcpServersSetting()
                                notice = "Calls to \"${server.name}\" will ask again."
                            }) { Text("Stop trusting") }
                        }
                        TextButton(onClick = {
                            service.saveMcpServersSetting(servers.filterNot { it.id == server.id })
                            servers = service.mcpServersSetting()
                            notice = "\"${server.name}\" removed."
                        }) { Text("Remove") }
                    }
                }
            }
        }

        Button(onClick = { editing = McpServerConfig(id = "", name = "") }) { Text("Add server…") }

        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "The last $MCP_AUDIT_ROWS calls, newest first. The record is at " +
                "${service.mcpAuditFile().absolutePath} and never contains argument values " +
                "or results.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (audit.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            audit.forEach { entry -> McpAuditRow(entry) }
        }
    }

    editing?.let { draft ->
        McpServerEditor(
            draft = draft,
            onCancel = { editing = null },
            onSave = { saved ->
                val id = saved.id.ifBlank {
                    uniqueMcpServerId(saved.name, servers.map { it.id }.toSet())
                }
                service.saveMcpServersSetting(
                    servers.filterNot { it.id == id } + saved.copy(id = id),
                )
                servers = service.mcpServersSetting()
                editing = null
                notice = "\"${saved.name}\" saved."
            },
        )
    }
}

@Composable
private fun McpServerEditor(
    draft: McpServerConfig,
    onCancel: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    var name by remember(draft) { mutableStateOf(draft.name) }
    var url by remember(draft) { mutableStateOf(draft.url) }
    var headers by remember(draft) {
        mutableStateOf(draft.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    // **HTTP only on this platform.** The stored model still knows about the stdio transport —
    // the settings blob has to stay readable by the desktop build — but an Android app cannot
    // launch a program, so the editor offers no way to configure one. A server that arrives from
    // a backup or from the desktop build with a command keeps its command field, and the Test
    // button says plainly that this platform cannot run it.
    val valid = name.isNotBlank() && url.isNotBlank()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (draft.id.isBlank()) "Add an MCP server" else "Edit ${draft.name}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = true,
                        onClick = { },
                        shape = SegmentedButtonDefaults.itemShape(0, 1),
                    ) { Text("HTTP") }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (http or https)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headers,
                    onValueChange = { headers = it },
                    label = { Text("Headers (Name: value per line)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Headers are stored in the settings file, like provider keys. Editing the URL " +
                        "revokes this server's trust.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        draft.copy(
                            name = name.trim(),
                            // The transport is always HTTP here: the editor offers no other
                            // one, and a draft that arrived with a command keeps the command
                            // field for the desktop build to read — but the transport itself
                            // is this platform's, not the draft's.
                            transport = McpTransportKind.HTTP,
                            url = url.trim(),
                            headers = parseMcpKeyValues(headers, ':'),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * The persona editor's MCP block: which of the configured servers this persona may call.
 *
 * Default off for everyone, and the text says which two fences are already in place — main chat
 * only, and a confirmation before an untrusted server runs anything — because a switch that reads
 * "allow tools" without saying what still guards them invites the wrong mental model.
 */
@Composable
internal fun PersonaMcpSection(personaName: String, service: AssistantService) {
    val servers = remember(personaName) { service.mcpServersSetting() }
    var enabled by remember(personaName) { mutableStateOf(service.mcpServersForPersonaSetting(personaName)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (servers.isEmpty()) {
            Text(
                "No MCP servers are configured. Add them in Settings → MCP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            return@Column
        }
        Text(
            "Servers this persona may call, in the main chat only. An untrusted server asks before " +
                "each first call; a denied call is reported, not invented.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        servers.forEach { server ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (server.enabled) mcpServerDetail(server) else "Disabled in Settings → MCP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = server.id in enabled,
                    onCheckedChange = { on ->
                        enabled = if (on) (enabled + server.id).distinct() else enabled - server.id
                        service.setMcpServersForPersonaSetting(personaName, enabled)
                    },
                )
            }
        }
    }
}

/**
 * The MCP confirmation: what the model asked for, from which server, with which arguments.
 *
 * **Dismissal denies** — closing a dialog is not an approval — and the same is true of the gate's
 * timeout when nobody is there to answer. The checkbox is the "always allow" that persists; editing
 * the server later revokes it (`SettingsManager.saveMcpServers`).
 */
@Composable
internal fun McpApprovalDialog(
    request: McpApprovalRequest,
    onAnswer: (approve: Boolean, trustServer: Boolean) -> Unit,
) {
    var trust by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onAnswer(false, false) },
        title = { Text("Run a tool?") },
        text = {
            Column {
                Text("${request.personaName} wants to call:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    request.toolName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "from \"${request.serverName}\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(8.dp))
                Text("With these arguments:", style = MaterialTheme.typography.labelSmall)
                Text(
                    request.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trust, onCheckedChange = { trust = it })
                    Text(
                        "Always allow tools from this server (until you revoke it)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true, trust) }) { Text("Run") } },
        dismissButton = { TextButton(onClick = { onAnswer(false, false) }) { Text("Cancel") } },
    )
}

@Composable
private fun McpAuditRow(entry: McpToolAuditEntry) {
    Text(
        "${entry.operation}  ${entry.server}  ${entry.bytes} B  ${entry.result} (${entry.confirmed})",
        style = MaterialTheme.typography.bodySmall,
        color = if (entry.result == "ok") {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

// ---------------------------------------------------------------------------- pure helpers

internal const val MCP_AUDIT_ROWS = 12

/** The Settings row's one line — static, like the Filesystem row. */
internal const val MCP_SETTINGS_SUMMARY = "Tool servers the assistant may call"

/** One argument per line, ignoring blank lines and surrounding whitespace. */
internal fun parseMcpArgs(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }

/**
 * `KEY=VALUE` (env) or `Name: value` (headers), one per line.
 *
 * The separator is split at its **first** occurrence: a header value contains colons (a URL, an
 * Authorization scheme) and an env value may contain `=`, so splitting at the last would corrupt
 * both. A line without the separator, or with an empty key, is ignored rather than guessed.
 */
internal fun parseMcpKeyValues(text: String, separator: Char): Map<String, String> =
    text.lines().mapNotNull { line ->
        val index = line.indexOf(separator)
        if (index <= 0) return@mapNotNull null
        val key = line.substring(0, index).trim()
        if (key.isEmpty()) null else key to line.substring(index + 1).trim()
    }.toMap()

/** A stable id from a display name, unique against [taken]. */
internal fun uniqueMcpServerId(name: String, taken: Set<String>): String {
    val base = name.lowercase()
        .map { c -> if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_' }
        .joinToString("")
        .trim('_')
        .ifBlank { "server" }
        .take(40)
    if (base !in taken) return base
    var suffix = 2
    while ("$base-$suffix" in taken) suffix++
    return "$base-$suffix"
}

private fun mcpServerDetail(server: McpServerConfig): String = when (server.transport) {
    McpTransportKind.STDIO -> listOf(server.command, server.args.joinToString(" ")).filter { it.isNotBlank() }
        .joinToString(" ") { it }.ifBlank { "command transport" }
    McpTransportKind.HTTP -> server.url.ifBlank { "HTTP transport" }
}

/**
 * The Test button: connect and count the tools without running any of them.
 *
 * **A server configured to run as a program is refused in words**, before any connection is
 * attempted: this platform cannot launch one, and a bare "answered no tools" would leave the
 * user guessing whether the server was broken or the app was.
 */
private suspend fun probeMcpServer(service: AssistantService, server: McpServerConfig): String {
    if (server.transport == McpTransportKind.STDIO) {
        return "\"${server.name}\" is configured to run as a program, which this app cannot do on " +
            "Android. Change it to HTTP and give it the server's URL."
    }
    val tools = service.mcpRegistry.toolsFor(server)
    return if (tools.isEmpty()) {
        "\"${server.name}\" answered no tools. The log names the reason."
    } else {
        "\"${server.name}\": ${tools.size} tool(s) — " +
            tools.take(8).joinToString { it.toolName } +
            if (tools.size > 8) "…" else ""
    }
}
