package com.nikosm.voiceassistant

/**
 * The Android MCP transport factory: **HTTP only**.
 *
 * ## Why stdio is refused rather than ignored
 *
 * The Linux build offers two transports, and one of them is a child process: the app spawns
 * `command` with `args`, and JSON-RPC travels over the process's stdin and stdout. **An Android
 * app cannot do that** — there is no supported way for an app to launch an arbitrary executable
 * the user names. A server configured for stdio therefore has no transport here, and saying so in
 * words is the honest outcome: the settings screen shows the entry, the Test button reports that
 * this platform cannot run it, and the persona that enabled it gets no tools from it.
 *
 * The refusal is **loud and specific** rather than a silent empty list, because the alternative —
 * a server that looks configured, offers nothing, and leaves the user wondering why the assistant
 * "cannot see" it — is the failure mode that makes a feature look broken.
 *
 * The HTTP transport itself is `McpHttpTransport.kt`, ported from the Linux build unchanged apart
 * from its logger.
 */
internal class AndroidMcpTransportFactory(
    private val logger: AppLogger = AndroidAppLogger,
) : McpTransportFactory {

    override fun open(server: McpServerConfig): McpTransport = when (server.transport) {
        McpTransportKind.HTTP -> McpHttpTransport(server, logger)
        McpTransportKind.STDIO -> throw McpTransportException(
            "'${server.name}' is configured to run as a program, which this app cannot do on " +
                "Android. Change it to HTTP and give it the server's URL.",
        )
    }
}