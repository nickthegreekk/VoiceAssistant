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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end authentication proof for the image-generation 401 fix.
 *
 * The bug: an A1111 server started with `--api-auth` rejected every generation with a bare,
 * credential-less request, while Settings → Servers → Image Gen's "Test Connection" (which
 * passes the full ServerConfig) succeeded. Against [ImageGenStub] — which answers 401 unless
 * the correct Basic header arrives — these tests assert that a dedicated-persona generation,
 * an inline "create an image of …" trigger, a Regenerate, and the Test Connection probe each
 * reach their endpoint WITH the header and produce an image; that a wrong password shows the
 * specific bubble and writes no `gen_` file; and that an unresolvable server fails visibly
 * without sending anything.
 */
@RunWith(AndroidJUnit4::class)
class ImageGenAuthInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var imageGenBefore: List<ServerConfig> = emptyList()
    private var personaNameBefore: String? = null
    private var messagesOwnerBefore: String? = null
    private var messagesBefore: List<ChatMessage> = emptyList()

    @Before
    fun bindAssistantService() {
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
            context.bindService(
                Intent(context, AssistantService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        runBlocking { service.awaitPersonaHistoryIo() }
        // performImageGeneration checks ACCESS_LOCAL_NETWORK before it sends anything. On the
        // test device it is auto-granted (like INTERNET); attempt a grant anyway and ignore a
        // refusal — a genuinely missing permission fails the test loudly, not silently.
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        }
        imageGenBefore = service._imageGenBases.value
        personaNameBefore = service.currentPersonaName
        messagesOwnerBefore = service.messagesOwnerName
        messagesBefore = service._messages.value
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.runOnMainSync { service.stopEverything() } }
        runCatching {
            instrumentation.runOnMainSync {
                service._messages.value = messagesBefore
                service.currentPersonaName = personaNameBefore
                service.messagesOwnerName = messagesOwnerBefore
                service._imageGenBases.value = imageGenBefore
                service.saveSettings()
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    // ------------------------------------------------------------------ fixtures

    /** Registers a probe image-gen entry pointing at [stub] and returns it. */
    private fun registerImageGenServer(
        stub: ImageGenStub,
        password: String = IMAGE_GEN_PROBE_PASSWORD
    ): ServerConfig {
        val entry = ServerConfig(PROBE_SERVER, stub.url, IMAGE_GEN_PROBE_USER, password, AuthType.BASIC)
        mainSync {
            // The probe is PREPENDED, not appended. With a stale pin now falling back (and the
            // inline trigger ignoring the chat URL entirely), the ACTIVE type's FIRST entry is what
            // a request uses — and a fresh install already holds a default "Local A1111" entry at
            // http://0.0.0.0:7860 that would otherwise outrank the stub.
            service._imageGenBases.value = listOf(entry) +
                imageGenBefore.filterNot { it.name == PROBE_SERVER || it.url == stub.url }
            // Explicit, not assumed: the active backend is in-memory service state that another
            // test class may have moved, and every case here is an A1111 one.
            service.setActiveImageGenBackend(ImageGenBackend.A1111)
        }
        return entry
    }

    private fun probePersona(name: String, backendUrl: String, isImageGenerator: Boolean) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "",
        systemPrompt = "Image-gen auth probe persona.",
        isCloud = false,
        isImageGenerator = isImageGenerator,
        backendUrl = backendUrl,
        allowGatewayFailover = false,
        voiceMode = VoiceMode.GATEWAY,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false
    )

    private fun genFiles(): Set<String> =
        (context.cacheDir.listFiles { f -> f.name.startsWith("gen_") } ?: emptyArray()).map { it.name }.toSet()

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun waitUntil(timeoutMs: Long = 20000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun dedicatedImageGeneratorPersonaGenerationReachesTxt2imgWithTheHeader() {
        ImageGenStub().use { stub ->
            registerImageGenServer(stub)
            // Name-based Backend URL exercises the resolver's name branch end-to-end.
            val persona = probePersona("ProbeDedicated", PROBE_SERVER, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            service.sendTextMessageToServer("a serene mountain lake", persona)

            assertTrue(
                "generation never reached /sdapi/v1/txt2img",
                waitUntil { stub.requests.any { it.path.startsWith(IMAGE_GEN_TXT2IMG_PATH) } }
            )
            val hit = stub.requests.first { it.path.startsWith(IMAGE_GEN_TXT2IMG_PATH) }
            assertEquals(
                "the dedicated-persona generation must carry the configured entry's Basic header",
                stub.basicHeader,
                hit.headers["authorization"]
            )
            assertTrue(
                "an image must be written to gen_ storage",
                waitUntil { genFiles().size == genBefore.size + 1 }
            )
            assertTrue(
                "the generation must publish an assistant message carrying the generated image",
                waitUntil { service._messages.value.any { it.generatedImagePath != null } }
            )
        }
    }

    @Test
    fun inlineCreateImageTriggerFromANormalPersonaReachesTxt2imgWithTheHeader() {
        ImageGenStub().use { stub ->
            registerImageGenServer(stub)
            // The persona's Backend URL is where its CHAT goes — an Ollama-style address that
            // matches no Image Gen entry. The inline trigger must IGNORE it and use the active
            // generator (A1111), which is where the probe entry is; honouring it is what used to
            // produce "No image server is configured for this persona".
            val persona = probePersona("ProbeInline", "http://192.168.1.44:11434/", isImageGenerator = false)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            service.sendTextMessageToServer("create an image of a red bicycle", persona)

            assertTrue(
                "the inline trigger never reached /sdapi/v1/txt2img",
                waitUntil { stub.requests.any { it.path.startsWith(IMAGE_GEN_TXT2IMG_PATH) } }
            )
            val hit = stub.requests.first { it.path.startsWith(IMAGE_GEN_TXT2IMG_PATH) }
            assertEquals(stub.basicHeader, hit.headers["authorization"])
            // BYTE-IDENTITY PROOF for the A1111 path, on the platform the request actually leaves
            // from. Android's org.json is LinkedHashMap-backed, so this literal is insertion order
            // and any change to the A1111 body — a reordered, renamed, added or removed field —
            // fails here. The JVM unit test asserts the same field set order-independently,
            // because the JVM's org.json artifact is HashMap-backed and would pin the wrong order.
            assertEquals(
                "the A1111 body must be exactly what it has always been",
                """{"prompt":"a red bicycle","negative_prompt":"$IMAGE_GEN_NEGATIVE_PROMPT","steps":20,"width":512,"height":512}""",
                hit.body
            )
            assertTrue(waitUntil { genFiles().size == genBefore.size + 1 })
        }
    }

    @Test
    fun regenerateResendsWithTheHeaderAndReplacesTheImage() {
        ImageGenStub().use { stub ->
            registerImageGenServer(stub)
            val persona = probePersona("ProbeRegenerate", stub.url, isImageGenerator = true)
            mainSync {
                service.addPersona(persona)
                service.switchPersona(persona)
            }
            runBlocking { service.awaitPersonaHistoryIo() }

            val oldFile = File(context.cacheDir, "gen_probe_old_${System.currentTimeMillis()}.png")
                .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            mainSync {
                service._messages.value = listOf(
                    ChatMessage("assistant", "a serene mountain lake", generatedImagePath = oldFile.absolutePath)
                )
            }

            service.regenerateImageMessage(0)

            assertTrue(
                "regenerate never reached /sdapi/v1/txt2img",
                waitUntil { stub.requests.any { it.path.startsWith(IMAGE_GEN_TXT2IMG_PATH) } }
            )
            val hit = stub.requests.first { it.path.startsWith(IMAGE_GEN_TXT2IMG_PATH) }
            assertEquals(stub.basicHeader, hit.headers["authorization"])
            assertTrue(
                "regenerate must publish a fresh image message",
                waitUntil {
                    service._messages.value.any {
                        it.generatedImagePath != null && it.generatedImagePath != oldFile.absolutePath
                    }
                }
            )
            assertFalse("regenerate must delete the superseded image file", oldFile.exists())
        }
    }

    @Test
    fun testConnectionAndGenerationSendTheSameAuthorizationHeader() {
        ImageGenStub().use { stub ->
            val entry = registerImageGenServer(stub)

            val result = runBlocking { service.testServerConnection(entry, isGateway = false, isImageGen = true) }
            assertTrue("Test Connection must succeed against an authenticated stub", result.success)

            val persona = probePersona("ProbeParity", stub.url, isImageGenerator = true)
            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            val probe = stub.requests.first { it.path.startsWith(IMAGE_GEN_SD_MODELS_PATH) }
            val gen = stub.requests.first { it.path.startsWith(IMAGE_GEN_TXT2IMG_PATH) }
            assertEquals(
                "Test Connection must authenticate against the stub",
                stub.basicHeader,
                probe.headers["authorization"]
            )
            assertEquals(
                "the generation must send the SAME Authorization header Test Connection sends",
                stub.basicHeader,
                gen.headers["authorization"]
            )
        }
    }

    @Test
    fun wrongPasswordShowsTheAuthBubbleAndWritesNoImage() {
        ImageGenStub().use { stub ->
            // The stub expects IMAGE_GEN_PROBE_PASSWORD; this entry carries a different one,
            // so every request is answered 401, exactly as A1111 --api-auth does for a bad
            // password.
            registerImageGenServer(stub, password = "not-the-password")
            val persona = probePersona("ProbeWrongPassword", stub.url, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            val last = service._messages.value.last()
            assertTrue("the failure must be published as an error bubble", last.isError)
            assertEquals(IMAGE_GEN_AUTH_FAILURE_MESSAGE, last.text)
            assertEquals(
                "a rejected generation must not write a gen_ image",
                genBefore.size,
                genFiles().size
            )
        }
    }

    @Test
    fun aStalePinFallsBackToTheActiveGeneratorInsteadOfFailing() {
        ImageGenStub().use { stub ->
            // The persona pins an address that matches no entry — the reported "I edited my server
            // and now the persona says it is not configured". The request must still go out, through
            // the active generator, rather than refusing.
            registerImageGenServer(stub)
            val persona = probePersona("ProbeStalePin", "http://10.9.9.9:7860", isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            val hit = stub.requests.firstOrNull { it.path.startsWith(IMAGE_GEN_TXT2IMG_PATH) }
            assertNotNull("a stale pin must fall back to the active generator, not refuse", hit)
            assertEquals("the fallback must use the configured entry's credentials", stub.basicHeader, hit!!.headers["authorization"])
            assertTrue("the fallback must produce an image", waitUntil { genFiles().size == genBefore.size + 1 })
            assertTrue(
                "and it must not be reported as a failure: ${service._messages.value.last().text}",
                !service._messages.value.last().isError
            )
        }
    }

    @Test
    fun noEntryOfTheActiveTypeFailsVisiblyNamingTheTypeAndSendingNothing() {
        ImageGenStub().use { stub ->
            // Nothing configured at all: this is the case a fallback cannot rescue, so it must fail
            // with an actionable message about the BACKEND, not about the persona.
            mainSync {
                service._imageGenBases.value = emptyList()
                service.setActiveImageGenBackend(ImageGenBackend.A1111)
            }
            val persona = probePersona("ProbeNoEntries", stub.url, isImageGenerator = true)
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            assertTrue("no request may reach any server with nothing configured", stub.requests.isEmpty())
            val last = service._messages.value.last()
            assertTrue("this must surface an error bubble", last.isError)
            assertTrue(
                "the error must name where to fix it: ${last.text}",
                last.text.contains("Settings → Servers → Image Gen")
            )
            assertTrue(
                "the error must name the active TYPE: ${last.text}",
                last.text.contains(DEFAULT_IMAGE_GEN_BACKEND.displayName)
            )
            assertTrue(
                "the error must not blame the persona: ${last.text}",
                !last.text.contains("persona", ignoreCase = true)
            )
        }
    }

    @Test
    fun editingTheServerUrlRepointsThePersonaAndGenerationStillAuthenticates() {
        ImageGenStub().use { stub ->
            val entry = registerImageGenServer(stub)
            val persona = probePersona("ProbeUrlEdit", stub.url, isImageGenerator = true)
            mainSync { service.addPersona(persona) }

            // A distinct URL string that still reaches the same stub ("localhost" == 127.0.0.1).
            // Editing the URL is what Settings → Servers → Image Gen does.
            val newUrl = stub.url.replace("127.0.0.1", "localhost")
            mainSync {
                service.updateImageGenBase(
                    entry, entry.name, newUrl,
                    IMAGE_GEN_PROBE_USER, IMAGE_GEN_PROBE_PASSWORD, AuthType.BASIC, null
                )
            }

            // The persona must have followed the edit — otherwise it points at an address no
            // entry matches and the request goes out credential-less (the original 401).
            val stored = service.personas.value.first { it.name == "ProbeUrlEdit" }
            assertEquals("the persona must follow the edited server URL", newUrl, stored.backendUrl)

            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }
            runBlocking { service.performImageGeneration("a serene mountain lake", stored) }

            val hit = stub.requests.firstOrNull { it.path.startsWith(IMAGE_GEN_TXT2IMG_PATH) }
            assertNotNull("generation must reach the edited server", hit)
            assertEquals(
                "the generation must carry the edited entry's Basic header",
                stub.basicHeader,
                hit!!.headers["authorization"]
            )
            assertTrue(
                "the generation must produce an image",
                waitUntil { genFiles().size == genBefore.size + 1 }
            )
        }
    }

    private companion object {
        const val PROBE_SERVER = "ImageGenAuthProbeServer"
    }
}
