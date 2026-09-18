package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M1 — gateway credentials must resolve for a semantically-equal Backend URL.
 *
 * The bug: every gateway flow matched the persona's Backend URL with a raw
 * `_serverBases.value.find { it.url == url } ?: ServerConfig(..., url)` — a byte-identical
 * comparison with a credential-less fallback. A trailing slash (or a name-based Backend URL,
 * which the audio-chat path already accepted) therefore resolved to NOTHING, and the
 * fallback sent the request without the `Authorization` header: transcription 401'd, or the
 * reply was synthesized unauthenticated and silently never spoken.
 *
 * How this proves it on-device: the stub gateway requires the configured Basic credentials —
 * it records the `Authorization` header of every request, and the assertions read what the
 * app actually SENT. A trailing-slash and a name-based Backend URL must both arrive with the
 * exact `Basic <base64(user:pass)>` header the saved entry holds. The unresolvable cases
 * prove the other half of the fix: no request is sent at all (rather than an unauthenticated
 * one), and the failure is visible in the transcript instead of silent.
 *
 * Cleanup: the bound service outlives this class — `_serverBases`, `_customCloudApis`,
 * `_serverStatus`, `_messages` and the active persona name are restored in tearDown, the
 * probe personas' stored histories are deleted, and the probe URLs are dropped from the
 * failure-cooldown map.
 */
@RunWith(AndroidJUnit4::class)
class GatewayCredentialResolutionInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var serverBasesBefore: List<ServerConfig> = emptyList()
    private var customCloudApisBefore: List<CloudApiSetting> = emptyList()
    private var serverStatusBefore: Map<String, String> = emptyMap()
    private var personaNameBefore: String? = null
    private var messagesBefore: List<ChatMessage> = emptyList()
    private val scratchFiles = mutableListOf<File>()

    @Before
    fun bindAssistantService() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) { /* not expected here */ }
        }
        connection = conn
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(
                Intent(context, AssistantService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        serverBasesBefore = service._serverBases.value
        customCloudApisBefore = service._customCloudApis.value
        serverStatusBefore = service._serverStatus.value
        personaNameBefore = service.currentPersonaName
        messagesBefore = service._messages.value
        // Probe URLs are random loopback ports, so purge every loopback entry from the
        // failure-cooldown map — a stale cooldown must not survive into another test.
        serverFailCooldownUntilMillis.keys
            .filter { it.startsWith(LOOPBACK_PREFIX) }
            .forEach { serverFailCooldownUntilMillis.remove(it) }
        service.settingsManager.deletePersonaHistory(PROBE_PERSONA)
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.runOnMainSync { service.stopEverything() } }
        runCatching {
            instrumentation.runOnMainSync {
                service._messages.value = messagesBefore
                service.currentPersonaName = personaNameBefore
                // L5: the history owner follows the restored selection — persistSettings() below
                // saves the (owner, messages) pair, so leaving the owner on the probe persona's
                // name would file this test's transcript under the probe key.
                service.messagesOwnerName = personaNameBefore
                service._serverBases.value = serverBasesBefore
                service._customCloudApis.value = customCloudApisBefore
                service._serverStatus.value = serverStatusBefore
                service.saveSettings()
            }
        }
        serverFailCooldownUntilMillis.keys
            .filter { it.startsWith(LOOPBACK_PREFIX) }
            .forEach { serverFailCooldownUntilMillis.remove(it) }
        runCatching { service.settingsManager.deletePersonaHistory(PROBE_PERSONA) }
        runCatching { service._streamingText.value = null }
        scratchFiles.forEach { runCatching { it.delete() } }
        scratchFiles.clear()
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    // ---- resolution success: the credentials actually reach the server ---------------------

    @Test
    fun trailingSlashBackendUrlResolvesTheSavedCredentials() {
        CredentialProbeServer().use { gateway ->
            ProbeNoOpServer().use { cloud ->
                registerGateway(gateway, withBasicAuth = true)
                registerCloudProvider(cloud)
                val persona = cloudPersona(backendUrl = "${gateway.url}/")

                driveOneVoiceTurn(persona, gateway)

                assertTrue(
                    "no /transcribe request reached the gateway at all",
                    gateway.requests.any { it.path.startsWith("/transcribe") }
                )
                assertTrue(
                    "no /synthesize request reached the gateway at all",
                    gateway.requests.any { it.path.startsWith("/synthesize") }
                )
                val expected = basicHeader()
                assertEquals(
                    "M1: a trailing-slash Backend URL must still resolve the saved credentials for transcription",
                    expected,
                    gateway.requests.first { it.path.startsWith("/transcribe") }.headers["authorization"]
                )
                assertEquals(
                    "M1: a trailing-slash Backend URL must still resolve the saved credentials for synthesis",
                    expected,
                    gateway.requests.first { it.path.startsWith("/synthesize") }.headers["authorization"]
                )
                val messages = service.messages.value
                assertEquals(
                    "the voice turn must complete against the authenticated gateway",
                    "user" to PROBE_TRANSCRIPT,
                    messages[0].role to messages[0].text
                )
                assertEquals("assistant", messages[1].role)
                assertEquals(PROBE_ANSWER, messages[1].text)
            }
        }
    }

    @Test
    fun nameBasedBackendUrlResolvesTheSavedCredentials() {
        CredentialProbeServer().use { gateway ->
            ProbeNoOpServer().use { cloud ->
                registerGateway(gateway, withBasicAuth = true)
                registerCloudProvider(cloud)
                // The audio-chat path already accepted a gateway NAME as the Backend URL;
                // the gateway flows must resolve it the same way.
                val persona = cloudPersona(backendUrl = PROBE_GATEWAY)

                driveOneVoiceTurn(persona, gateway)

                assertTrue(
                    "M1: a name-based Backend URL never reached the configured gateway — it used to fail " +
                        "with 'not in the configured gateway list'",
                    gateway.requests.any { it.path.startsWith("/transcribe") }
                )
                assertTrue(
                    "M1: a name-based Backend URL must also reach the gateway for synthesis",
                    gateway.requests.any { it.path.startsWith("/synthesize") }
                )
                val expected = basicHeader()
                assertEquals(
                    expected,
                    gateway.requests.first { it.path.startsWith("/transcribe") }.headers["authorization"]
                )
                assertEquals(
                    expected,
                    gateway.requests.first { it.path.startsWith("/synthesize") }.headers["authorization"]
                )
            }
        }
    }

    // ---- resolution failure: nothing unauthenticated goes out, and it says so ---------------

    @Test
    fun unresolvableBackendUrlNeverSendsAnUnauthenticatedSynthesis() {
        CredentialProbeServer().use { configuredGateway ->
            // Reachable but NOT configured: the pre-fix fallback would have reached exactly
            // this host with no Authorization header.
            StrayGatewayServer().use { strayGateway ->
                ProbeNoOpServer().use { cloud ->
                    registerGateway(configuredGateway, withBasicAuth = false)
                    registerCloudProvider(cloud)
                    val persona = cloudPersona(
                        backendUrl = strayGateway.url,
                        allowFailover = true
                    )

                    driveOneVoiceTurn(persona, configuredGateway)

                    assertEquals(
                        "M1: the unconfigured gateway must receive NO synthesis request — the old " +
                            "credential-less fallback sent one and swallowed its 401",
                        0,
                        strayGateway.requestCount()
                    )
                    assertTrue(
                        "M1: the unresolvable Backend URL must fail VISIBLY in the transcript",
                        service.messages.value.any { it.isError && it.text.contains(strayGateway.url) }
                    )
                    assertTrue(
                        "the reply text must still be preserved even when it cannot be spoken",
                        service.messages.value.any { it.role == "assistant" && it.text == PROBE_ANSWER }
                    )
                }
            }
        }
    }

    @Test
    fun voiceTestRefusesAnUnresolvableGatewayInsteadOfSendingAnUnauthenticatedRequest() {
        StrayGatewayServer().use { strayGateway ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                service.currentPersonaName = PROBE_PERSONA
                // L5: the history owner moves with the selection (as switchPersona does), so the
                // debounced persistSettings() below keys this test's history off the probe
                // persona — the key tearDown deletes — and not off the bound persona's name.
                service.messagesOwnerName = PROBE_PERSONA
            }

            instrumentation.runOnMainSync {
                service.testAudio(
                    "voice test probe",
                    VoiceMode.GATEWAY,
                    strayGateway.url,
                    "English",
                    false,
                    "kokoro",
                    "af_heart"
                )
            }
            // Give a pre-fix build the chance to actually issue its request.
            SystemClock.sleep(1_000)

            assertEquals(
                "M1: a voice test against an unconfigured Backend URL must be REFUSED — the old " +
                    "credential-less fallback sent an unauthenticated /synthesize that 401'd",
                0,
                strayGateway.requestCount()
            )
        }
    }

    // ---- harness ---------------------------------------------------------------------------

    private fun registerGateway(stub: AutoCloseableUrl, withBasicAuth: Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service._serverBases.value =
                serverBasesBefore.filterNot { it.name == PROBE_GATEWAY } +
                    if (withBasicAuth) {
                        ServerConfig(
                            name = PROBE_GATEWAY,
                            url = stub.url,
                            username = PROBE_USER,
                            password = PROBE_PASSWORD,
                            authType = AuthType.BASIC
                        )
                    } else {
                        ServerConfig(name = PROBE_GATEWAY, url = stub.url)
                    }
        }
    }

    private fun registerCloudProvider(stub: AutoCloseableUrl) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service._customCloudApis.value =
                customCloudApisBefore.filterNot { it.name == PROBE_PROVIDER } +
                    CloudApiSetting(
                        name = PROBE_PROVIDER,
                        baseUrl = stub.url,
                        icon = "C",
                        color = PROBE_COLOR
                    )
        }
    }

    /** One full cloud voice turn: transcribe via the gateway, then answer + synthesize. */
    private fun driveOneVoiceTurn(persona: Persona, gateway: CredentialProbeServer) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.currentPersonaName = persona.name
            // L5: the history owner moves with the selection, exactly as switchPersona does —
            // this test's probe turns are saved under the probe persona's key (which tearDown
            // deletes), not under whichever persona the service bound with.
            service.messagesOwnerName = persona.name
            service._messages.value = emptyList()
        }
        val file = File(context.cacheDir, "probe_recording_${System.currentTimeMillis()}.wav")
        file.writeBytes(ByteArray(64) { 0 })
        scratchFiles += file
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.sendAudioToServer(file, persona)
        }
        awaitService(25_000, "the voice turn never produced a user+assistant pair") {
            service.messages.value.size >= 2 &&
                service.messages.value.any { it.role == "assistant" && !it.isError } &&
                gateway.requests.any { it.path.startsWith("/transcribe") }
        }
        // Let the second phase (synthesis / the visible error) settle.
        awaitService(25_000, "the voice turn never finished its gateway phase") {
            service.messages.value.any { it.isError } ||
                gateway.requests.any { it.path.startsWith("/synthesize") } ||
                service.messages.value.size >= 3
        }
        SystemClock.sleep(250)
    }

    private fun awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("$failure (state=${service.assistantState.value}, messages=${service.messages.value})", condition())
    }

    private fun cloudPersona(backendUrl: String, allowFailover: Boolean = false) = Persona(
        name = PROBE_PERSONA,
        themeColor = PROBE_COLOR,
        model = "[$PROBE_PROVIDER] probe-model",
        systemPrompt = "Credential resolution probe persona.",
        isCloud = true,
        providerIcon = "C",
        backendUrl = backendUrl,
        allowGatewayFailover = allowFailover,
        voiceMode = VoiceMode.GATEWAY,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false,
        maxTokens = 256,
        numCtx = 8192
    )

    /** The exact header `Credentials.basic(user, pass)` produces. */
    private fun basicHeader(): String =
        "Basic " + android.util.Base64.encodeToString(
            "$PROBE_USER:$PROBE_PASSWORD".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

    private companion object {
        const val PROBE_PERSONA = "GatewayCredentialResolutionProbePersona"
        const val PROBE_GATEWAY = "CredentialResolutionProbeGateway"
        const val PROBE_PROVIDER = "CredentialResolutionProbeCloud"
        const val PROBE_USER = "nikos"
        const val PROBE_PASSWORD = "s3cret"
        val PROBE_COLOR = Color(0xFF4ADE80)
    }
}

/** The only surface a stub needs here: an address to point a persona at, and close(). */
private interface AutoCloseableUrl : AutoCloseable {
    val url: String
}

/** One recorded request: the target path, lowercased header names, and the raw body. */
private data class RecordedGatewayRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: String
)

/**
 * Loopback stub base: records every request (line, headers, body) and answers with whatever
 * the subclass scripts for that path. Plain HTTP on 127.0.0.1, same as the app's own LAN
 * gateways — the instrumentation shares the app process, so no adb reverse is needed.
 */
private abstract class RecordingServer : AutoCloseableUrl {

    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    override val url: String = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<RecordedGatewayRequest>()

    /** @return HTTP status and body for [path], or null for a 404. */
    protected abstract fun reply(path: String): Pair<Int, String?>?

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break // close() tore the listener down
                }
                Thread({ handle(client) }, "recording-server-$port").apply { isDaemon = true }.start()
            }
        }, "recording-server-accept-$port").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    fun requestCount(): Int = requests.size

    private fun handle(connection: Socket) {
        try {
            connection.use { client ->
                val input = BufferedInputStream(client.inputStream)
                val requestLine = readAsciiLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: return
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = input.read(body, read, contentLength - read)
                    if (count <= 0) break
                    read += count
                }
                val path = requestLine.split(' ').getOrNull(1) ?: "/"
                requests += RecordedGatewayRequest(path, headers, String(body, 0, read, Charsets.UTF_8))

                val scripted = reply(path)
                val output = client.getOutputStream()
                if (scripted == null) {
                    output.write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                } else {
                    val (status, payload) = scripted
                    val bytes = (payload ?: "").toByteArray(Charsets.UTF_8)
                    val reason = when (status) {
                        200 -> "OK"
                        401 -> "Unauthorized"
                        else -> "Stub Error"
                    }
                    output.write(
                        (
                            "HTTP/1.1 $status $reason\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                    )
                    output.write(bytes)
                }
                output.flush()
            }
        } catch (e: Exception) {
            // The test closed the socket mid-flight; nothing to report.
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return if (builder.isEmpty()) null else builder.toString()
            if (next == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(next.toChar())
        }
    }
}

/**
 * The configured gateway: serves `/transcribe` with a transcript and `/synthesize` with the
 * gateway's "voice unavailable for this language" JSON note (so synthesis completes with no
 * audio and no MediaPlayer — credentials, not playback, are what these tests observe).
 */
private class CredentialProbeServer : RecordingServer() {
    override fun reply(path: String): Pair<Int, String?>? = when {
        path.startsWith("/transcribe") -> 200 to "{\"text\":\"$PROBE_TRANSCRIPT\"}"
        path.startsWith("/synthesize") ->
            200 to "{\"note\":\"Voice unavailable for this language.\"}"
        else -> null
    }
}

/**
 * A reachable gateway that is deliberately NOT in the configured list. Pre-fix, the
 * credential-less `ServerConfig(..., url)` fallback routed synthesis here and this 401 was
 * swallowed as "no audio"; post-fix it must never be contacted at all.
 */
private class StrayGatewayServer : RecordingServer() {
    override fun reply(path: String): Pair<Int, String?>? = when {
        path.startsWith("/synthesize") -> 401 to "{\"error\":\"unauthorized\"}"
        path.startsWith("/transcribe") -> 200 to "{\"text\":\"$PROBE_TRANSCRIPT\"}"
        else -> null
    }
}

/** The cloud provider: answers an OpenAI-compatible chat completion. */
private class ProbeNoOpServer : RecordingServer() {
    override fun reply(path: String): Pair<Int, String?>? = when {
        path.endsWith("/chat/completions") ->
            200 to "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"$PROBE_ANSWER\"}}]," +
                "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}"
        else -> null
    }
}

private const val PROBE_TRANSCRIPT = "what is the capital of France"
private const val PROBE_ANSWER = "probe assistant reply"
private const val LOOPBACK_PREFIX = "http://127.0.0.1:"