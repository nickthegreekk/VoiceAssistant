package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The "this address is the other backend" diagnosis, end to end against real stubs.
 *
 * An entry whose own endpoint answers 404/405 is ONLY accused of sitting in the wrong section when
 * the other backend's probe on the same address actually succeeds — so each direction is tested as a
 * pair: the positive case (the stub for the other API answers) and the wording that reaches the user,
 * on Test connection (which the Save path shares) and on generation.
 *
 * The negative cases matter just as much: a 404 from an address that answers NEITHER backend must
 * stay the ordinary "address doesn't seem right" error, and a rejected credential must never be
 * reinterpreted as a wrong type.
 */
@RunWith(AndroidJUnit4::class)
class ImageGenBackendMismatchInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null
    private var imageGenBefore: List<ServerConfig> = emptyList()

    @Before
    fun bind() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) { }
        }
        connection = conn
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        // performImageGeneration checks ACCESS_LOCAL_NETWORK before it sends anything; the test
        // device auto-grants it like INTERNET, and a genuine refusal fails loudly rather than
        // silently.
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        }
        imageGenBefore = service._imageGenBases.value
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.runOnMainSync { service.stopEverything() } }
        runCatching {
            instrumentation.runOnMainSync {
                service._messages.value = emptyList()
                service._imageGenBases.value = imageGenBefore
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    // ------------------------------------------------------------------ fixtures

    /** A minimal API-format workflow, so a ComfyUI-typed entry can reach its submit step. */
    private val workflow = """
        {"3":{"class_type":"KSampler","inputs":{"seed":"%SEED%","steps":20,"text":"%PROMPT%"}},
         "9":{"class_type":"SaveImage","inputs":{"images":["3",0]}}}
    """.trimIndent()

    private fun entry(
        name: String,
        url: String,
        backendType: ImageGenBackend,
        password: String = IMAGE_GEN_PROBE_PASSWORD
    ) = ServerConfig(
        name = name,
        url = url,
        username = IMAGE_GEN_PROBE_USER,
        password = password,
        authType = AuthType.BASIC,
        backendType = backendType,
        comfyWorkflow = if (backendType == ImageGenBackend.COMFYUI) workflow else null
    )

    /** Installs [config] as the only entry and returns a persona pinned to it. */
    private fun useEntry(config: ServerConfig): Persona {
        mainSync {
            service._imageGenBases.value = listOf(config)
            service.setActiveImageGenBackend(config.backendType)
            service._messages.value = emptyList()
        }
        return probePersona("MismatchProbe", config.url, isImageGenerator = true)
    }

    private fun probePersona(name: String, backendUrl: String, isImageGenerator: Boolean) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "",
        systemPrompt = "Backend-mismatch probe persona.",
        isCloud = false,
        isImageGenerator = isImageGenerator,
        backendUrl = backendUrl,
        allowGatewayFailover = false,
        voiceMode = VoiceMode.GATEWAY,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false
    )

    private fun testConnection(config: ServerConfig) =
        runBlocking { service.testServerConnection(config, isGateway = false, isImageGen = true) }

    private fun genFiles(): Set<String> =
        (context.cacheDir.listFiles { f -> f.name.startsWith("gen_") } ?: emptyArray()).map { it.name }.toSet()

    private fun lastMessage() = service._messages.value.last()

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun waitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    // ------------------------------------------------------------------ A1111 entry, ComfyUI address

    @Test
    fun anA1111EntryPointedAtComfyUiIsDiagnosedOnTestConnection() {
        ComfyUiStub().use { comfy ->
            // The entry says A1111 (so the probe is /sdapi/v1/sd-models), the address is ComfyUI.
            val config = entry("WrongType", comfy.url, ImageGenBackend.A1111)

            val result = testConnection(config)

            assertFalse("the A1111 probe must fail against a ComfyUI server", result.success)
            assertEquals(404, result.httpCode)
            assertEquals(
                imageGenBackendMismatchMessage(ImageGenBackend.A1111),
                result.backendMismatch
            )
            // The Save path and the Test Connection button share this mapping, so what they show is
            // exactly this string.
            assertEquals(
                imageGenBackendMismatchMessage(ImageGenBackend.A1111),
                serverConnectionFailureMessage(result, config.effectiveAuthType)
            )

            // Both halves are on the wire: the entry's own probe failed, and the DECIDING request was
            // ComfyUI's probe on the same address, carrying the same credentials.
            assertTrue(
                "the A1111 probe must have been attempted first",
                comfy.requests.any { it.path == IMAGE_GEN_SD_MODELS_PATH }
            )
            val deciding = comfy.requests.firstOrNull { it.path == COMFY_SYSTEM_STATS_PATH }
            assertNotNull("the diagnosis must ask ComfyUI's own endpoint", deciding)
            assertEquals("GET", deciding!!.method)
            assertEquals(
                "the deciding probe must authenticate exactly like the entry",
                comfy.basicHeader,
                deciding.headers["authorization"]
            )
        }
    }

    @Test
    fun anA1111EntryPointedAtComfyUiIsDiagnosedDuringGeneration() {
        ComfyUiStub().use { comfy ->
            val config = entry("WrongType", comfy.url, ImageGenBackend.A1111)
            val persona = useEntry(config)
            val genBefore = genFiles()

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            assertTrue(
                "the generation must have been attempted",
                comfy.requests.any { it.path == IMAGE_GEN_TXT2IMG_PATH }
            )
            assertTrue(
                "the diagnosis must have been made during generation",
                comfy.requests.any { it.path == COMFY_SYSTEM_STATS_PATH }
            )
            assertTrue("this must surface an error bubble", lastMessage().isError)
            assertEquals(
                imageGenBackendMismatchMessage(ImageGenBackend.A1111),
                lastMessage().text
            )
            assertEquals("a failed generation must write no image", genBefore, genFiles())
        }
    }

    // ------------------------------------------------------------------ ComfyUI entry, A1111 address

    @Test
    fun aComfyUiEntryPointedAtA1111IsDiagnosedOnTestConnection() {
        ImageGenStub().use { a1111 ->
            val config = entry("WrongType", a1111.url, ImageGenBackend.COMFYUI)

            val result = testConnection(config)

            assertFalse("the ComfyUI probe must fail against an A1111 server", result.success)
            assertEquals(404, result.httpCode)
            assertEquals(
                imageGenBackendMismatchMessage(ImageGenBackend.COMFYUI),
                result.backendMismatch
            )
            assertEquals(
                imageGenBackendMismatchMessage(ImageGenBackend.COMFYUI),
                serverConnectionFailureMessage(result, config.effectiveAuthType)
            )

            assertTrue(
                "the ComfyUI probe must have been attempted first",
                a1111.requests.any { it.path == COMFY_SYSTEM_STATS_PATH }
            )
            val deciding = a1111.requests.firstOrNull { it.path == IMAGE_GEN_SD_MODELS_PATH }
            assertNotNull("the diagnosis must ask A1111's own endpoint", deciding)
            assertEquals("GET", deciding!!.method)
            assertEquals(a1111.basicHeader, deciding.headers["authorization"])
        }
    }

    @Test
    fun aComfyUiEntryPointedAtA1111IsDiagnosedDuringGeneration() {
        ImageGenStub().use { a1111 ->
            val config = entry("WrongType", a1111.url, ImageGenBackend.COMFYUI)
            val persona = useEntry(config)
            val genBefore = genFiles()

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            // ComfyUI's generation is submit-then-poll, so the failure is at POST /prompt.
            assertTrue(
                "the submission must have been attempted",
                a1111.requests.any { it.path == COMFY_PROMPT_PATH }
            )
            assertTrue(
                "the diagnosis must have been made during generation",
                a1111.requests.any { it.path == IMAGE_GEN_SD_MODELS_PATH }
            )
            assertTrue("this must surface an error bubble", lastMessage().isError)
            assertEquals(
                "the submit failure must be reported as a wrong backend, not as a ComfyUI HTTP error",
                imageGenBackendMismatchMessage(ImageGenBackend.COMFYUI),
                lastMessage().text
            )
            assertEquals("a failed generation must write no image", genBefore, genFiles())
        }
    }

    // ------------------------------------------------------------------ negative controls

    @Test
    fun a404ThatAnswersNeitherBackendStaysAnOrdinaryUrlError() {
        ImageGenStub().use { stub ->
            // A reachable server under a path that serves neither API: the A1111 probe 404s AND the
            // deciding probe 404s, so there is no evidence of a wrong backend and the user must not
            // be told to move the entry anywhere.
            val config = entry("NeitherApi", "${stub.url}/nonexistent", ImageGenBackend.A1111)

            val result = testConnection(config)

            assertEquals(404, result.httpCode)
            assertNull("nothing answered, so there is nothing to suggest", result.backendMismatch)
            assertEquals(
                "Server responded, but this address doesn't seem right — check the URL",
                serverConnectionFailureMessage(result, config.effectiveAuthType)
            )
            // The deciding probe WAS sent — this is a negative result of asking, not a skipped question.
            assertTrue(
                "the diagnosis must still have asked the other backend",
                stub.requests.any { it.path == "/nonexistent$COMFY_SYSTEM_STATS_PATH" }
            )
        }
    }

    @Test
    fun aRejectedCredentialIsNeverBlamedOnTheBackend() {
        ImageGenStub().use { stub ->
            // 401 is not a mismatch: a rejected credential says nothing about which API is behind the
            // address, so the deciding probe must not even be sent.
            val config = entry("WrongPassword", stub.url, ImageGenBackend.A1111, password = "not-the-password")

            val result = testConnection(config)

            assertEquals(401, result.httpCode)
            assertNull(result.backendMismatch)
            assertEquals(
                "Authentication failed — check your username/password",
                serverConnectionFailureMessage(result, config.effectiveAuthType)
            )
            assertFalse(
                "a 401 must never trigger the mismatch probe",
                stub.requests.any { it.path == COMFY_SYSTEM_STATS_PATH }
            )
        }
    }

    @Test
    fun aCorrectlyTypedEntryIsNeverAccusedOfTheOtherBackend() {
        ImageGenStub().use { a1111 ->
            val config = entry("RightTypeA1111", a1111.url, ImageGenBackend.A1111)
            val result = testConnection(config)

            assertTrue("a correctly-typed entry must pass Test connection", result.success)
            assertNull("and it must carry no hint at all", result.backendMismatch)
            assertFalse(
                "a working entry must not be cross-probed",
                a1111.requests.any { it.path == COMFY_SYSTEM_STATS_PATH }
            )
        }
        ComfyUiStub().use { comfy ->
            val config = entry("RightTypeComfy", comfy.url, ImageGenBackend.COMFYUI)
            val result = testConnection(config)

            assertTrue("a correctly-typed ComfyUI entry must pass Test connection", result.success)
            assertNull(result.backendMismatch)
        }
    }
}
