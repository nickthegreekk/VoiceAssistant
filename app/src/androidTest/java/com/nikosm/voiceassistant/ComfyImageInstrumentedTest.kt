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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The ComfyUI generation flow end to end, against [ComfyUiStub].
 *
 * What these are really pinning:
 *  - the workflow the app POSTs is the template FILLED IN (prompt text in, numeric seed), not the
 *    template verbatim;
 *  - every request carries the entry's credentials and goes to the entry's own endpoints;
 *  - a pending job is polled until it finishes, and a job that vanishes is reported early;
 *  - a failure of any kind — bad password, rejected workflow, failed run, oversized or non-image
 *    download — leaves NO file behind, in `gen_` storage or as a leftover `.part`.
 */
@RunWith(AndroidJUnit4::class)
class ComfyImageInstrumentedTest {

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

    private companion object {
        const val PROBE_SERVER = "ComfyProbeServer"

        /** A minimal API-format workflow carrying both tokens the app fills in. */
        val WORKFLOW = """
            {"3":{"class_type":"KSampler","inputs":{"seed":"%SEED%","steps":20,"text":"%PROMPT%"}},
             "9":{"class_type":"SaveImage","inputs":{"images":["3",0]}}}
        """.trimIndent()
    }

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
        runBlocking { service.awaitPersonaHistoryIo() }
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

    private fun registerComfyServer(
        stub: ComfyUiStub,
        password: String = COMFY_PROBE_PASSWORD
    ): ServerConfig {
        val entry = ServerConfig(
            name = PROBE_SERVER,
            url = stub.url,
            username = COMFY_PROBE_USER,
            password = password,
            authType = AuthType.BASIC,
            apiKey = null,
            backendType = ImageGenBackend.COMFYUI,
            comfyWorkflow = WORKFLOW
        )
        mainSync {
            service._imageGenBases.value =
                imageGenBefore.filterNot { it.name == PROBE_SERVER || it.url == stub.url } + entry
            // Which backend generates is now the ACTIVE one, so a ComfyUI entry only runs when the
            // switch says so. ServiceIsolationRule restores it afterwards.
            service.setActiveImageGenBackend(ImageGenBackend.COMFYUI)
        }
        return entry
    }

    private fun probePersona(name: String, backendUrl: String, isImageGenerator: Boolean) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "",
        systemPrompt = "ComfyUI probe persona.",
        isCloud = false,
        isImageGenerator = isImageGenerator,
        backendUrl = backendUrl,
        allowGatewayFailover = false,
        voiceMode = VoiceMode.GATEWAY,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false
    )

    /** Finished images only — a `.part` file in flight is deliberately NOT counted as one. */
    private fun genFiles(): Set<String> =
        (context.cacheDir.listFiles { f -> f.name.startsWith("gen_") && !f.name.endsWith(".part") } ?: emptyArray())
            .map { it.name }.toSet()

    private fun partialFiles(): List<File> =
        context.cacheDir.listFiles { f -> f.name.endsWith(".part") }?.toList() ?: emptyList()

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun waitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    private fun lastMessage() = service._messages.value.last()

    // ------------------------------------------------------------------ the happy path

    @Test
    fun aDedicatedComfyUiPersonaSubmitsPollsDownloadsAndPublishesTheImage() {
        ComfyUiStub().use { stub ->
            registerComfyServer(stub)
            val persona = probePersona("ComfyDedicated", PROBE_SERVER, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            // 1. The workflow was SUBMITTED, filled in, with the entry's credentials.
            val submit = stub.requests.firstOrNull { it.path == COMFY_PROMPT_PATH }
            assertNotNull("the generation never reached POST $COMFY_PROMPT_PATH", submit)
            assertEquals("POST", submit!!.method)
            assertEquals(stub.basicHeader, submit.headers["authorization"])
            val submitted = JSONObject(submit.body)
            val nodes = submitted.getJSONObject("prompt")
            assertEquals(
                "the template's %PROMPT% must be replaced by the text the user asked for",
                "a serene mountain lake",
                nodes.getJSONObject("3").getJSONObject("inputs").getString("text")
            )
            assertTrue("a client id must be sent alongside the workflow", submitted.has("client_id"))
            assertTrue(
                "the seed must be substituted as a NUMBER, not the literal token",
                nodes.getJSONObject("3").getJSONObject("inputs").get("seed") is Number
            )

            // 2. /history was polled, and 3. the image was fetched through /view with its own tuple.
            assertTrue(
                "the job was never polled",
                stub.requests.any { it.path == "$COMFY_HISTORY_PATH/${stub.promptId}" }
            )
            val view = stub.lastViewQuery
            assertNotNull("the image was never fetched through $COMFY_VIEW_PATH", view)
            assertEquals(COMFY_STUB_FILENAME, view!!["filename"])
            assertEquals("output", view["type"])

            // 4. It landed in gen_ storage and in the transcript, like any A1111 image.
            assertTrue("an image must be written to gen_ storage", waitUntil { genFiles().size == genBefore.size + 1 })
            val message = lastMessage()
            assertFalse("a successful generation must not be an error bubble", message.isError)
            assertEquals("a serene mountain lake", message.text)
            assertTrue(
                "the message must point at the generated file, which is what every downstream feature keys off",
                message.generatedImagePath?.startsWith(context.cacheDir.absolutePath) == true
            )
        }
    }

    @Test
    fun theInlineCreateImageTriggerAlsoGoesThroughComfyUi() {
        ComfyUiStub().use { stub ->
            registerComfyServer(stub)
            // A normal persona whose Backend URL is where its CHAT goes. The inline trigger must
            // ignore it and follow the active generator, which is ComfyUI here.
            val persona = probePersona("ComfyInline", "http://192.168.1.44:11434", isImageGenerator = false)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            service.sendTextMessageToServer("create an image of a red bicycle", persona)

            assertTrue(
                "the inline trigger never submitted to ComfyUI",
                waitUntil { stub.requests.any { it.path == COMFY_PROMPT_PATH } }
            )
            assertTrue("the inline trigger must produce an image", waitUntil { genFiles().size == genBefore.size + 1 })
        }
    }

    @Test
    fun flippingTheSwitchSendsGenerationToTheOtherBackend() {
        ImageGenStub().use { a1111 ->
            ComfyUiStub().use { comfy ->
                val a1111Entry = ServerConfig(
                    name = "Switch A1111", url = a1111.url, username = COMFY_PROBE_USER,
                    password = COMFY_PROBE_PASSWORD, authType = AuthType.BASIC,
                    backendType = ImageGenBackend.A1111
                )
                val comfyEntry = ServerConfig(
                    name = "Switch Comfy", url = comfy.url, username = COMFY_PROBE_USER,
                    password = COMFY_PROBE_PASSWORD, authType = AuthType.BASIC,
                    backendType = ImageGenBackend.COMFYUI, comfyWorkflow = WORKFLOW
                )
                mainSync {
                    // Exactly these two entries: the resolver takes the FIRST entry of the active
                    // type, and a fresh install's default "Local A1111" (http://0.0.0.0:7860) would
                    // otherwise win the A1111 half of this test.
                    service._imageGenBases.value = listOf(a1111Entry, comfyEntry)
                    service.setActiveImageGenBackend(ImageGenBackend.A1111)
                }
                // No pin at all: the ACTIVE backend is the only thing that decides.
                val persona = probePersona("SwitchPersona", "", isImageGenerator = true)
                val genBefore = genFiles()
                mainSync { service._messages.value = emptyList() }

                runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

                assertTrue(
                    "with A1111 active the A1111 stub must receive the request",
                    waitUntil { a1111.requests.any { it.path == IMAGE_GEN_TXT2IMG_PATH } }
                )
                assertTrue("with A1111 active ComfyUI must not be touched", comfy.requests.isEmpty())
                assertTrue("the first generation must produce an image", waitUntil { genFiles().size == genBefore.size + 1 })
                val afterFirst = genFiles().size

                // Flip the switch: same persona, same prompt, no pin — the other backend now.
                mainSync { service.setActiveImageGenBackend(ImageGenBackend.COMFYUI) }
                runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

                assertTrue(
                    "with ComfyUI active the ComfyUI stub must receive the submission",
                    waitUntil { comfy.requests.any { it.path == COMFY_PROMPT_PATH } }
                )
                assertEquals(
                    "the A1111 stub must not be used again after the switch",
                    1,
                    a1111.requests.count { it.path == IMAGE_GEN_TXT2IMG_PATH }
                )
                assertTrue("the second generation must produce an image", waitUntil { genFiles().size == afterFirst + 1 })
            }
        }
    }

    @Test
    fun aStalePinnedServerFallsBackToTheActiveGenerator() {
        ComfyUiStub().use { stub ->
            registerComfyServer(stub)
            // What the persona still points at after its entry was edited to the ComfyUI address.
            val persona = probePersona("ComfyStalePin", "http://192.168.1.50:7860", isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            assertTrue(
                "a stale pin must fall back to the active generator, not refuse",
                waitUntil { stub.requests.any { it.path == COMFY_PROMPT_PATH } }
            )
            assertTrue("the fallback must produce an image", waitUntil { genFiles().size == genBefore.size + 1 })
            assertFalse("a fallback is not a failure: ${lastMessage().text}", lastMessage().isError)
        }
    }

    @Test
    fun regenerateReplacesTheImageThroughComfyUi() {
        ComfyUiStub().use { stub ->
            registerComfyServer(stub)
            val persona = probePersona("ComfyRegenerate", stub.url, isImageGenerator = true)
            mainSync {
                service.addPersona(persona)
                service.switchPersona(persona)
            }
            runBlocking { service.awaitPersonaHistoryIo() }

            val old = File(context.cacheDir, "gen_probe_old_${System.currentTimeMillis()}.png")
                .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            mainSync {
                service._messages.value = listOf(
                    ChatMessage("assistant", "a serene mountain lake", generatedImagePath = old.absolutePath)
                )
            }

            service.regenerateImageMessage(0)

            assertTrue(
                "regenerate never reached ComfyUI",
                waitUntil { stub.requests.any { it.path == COMFY_PROMPT_PATH } }
            )
            assertTrue(
                "regenerate must republish the image",
                waitUntil { service._messages.value.any { it.generatedImagePath != null && it.generatedImagePath != old.absolutePath } }
            )
            assertFalse("regenerate must delete the superseded image", old.exists())
        }
    }

    // ------------------------------------------------------------------ failures leave no file

    @Test
    fun aWrongPasswordShowsTheAuthBubbleAndWritesNoImage() {
        ComfyUiStub().use { stub ->
            registerComfyServer(stub, password = "not-the-password")
            val persona = probePersona("ComfyWrongPassword", stub.url, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            val message = lastMessage()
            assertTrue("a 401 must be an error bubble", message.isError)
            assertEquals(IMAGE_GEN_AUTH_FAILURE_MESSAGE, message.text)
            assertEquals("a rejected generation must not write a gen_ image", genBefore.size, genFiles().size)
            assertTrue("...nor leave a partial file", partialFiles().isEmpty())
        }
    }

    @Test
    fun aRejectedWorkflowNamesTheNodeAndTheServersReason() {
        ComfyUiStub().use { stub ->
            stub.rejectWorkflow = true
            registerComfyServer(stub)
            val persona = probePersona("ComfyRejected", stub.url, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            val text = lastMessage().text
            assertTrue("the bubble must name the failing node: $text", text.contains("Node 6 (CheckpointLoaderSimple)"))
            assertTrue("...and carry the server's own reason: $text", text.contains("Value not in list"))
            assertEquals(genBefore.size, genFiles().size)
            assertFalse(
                "a workflow rejected at submit time must never be polled",
                stub.requests.any { it.path.startsWith("$COMFY_HISTORY_PATH/") }
            )
        }
    }

    @Test
    fun aJobThatVanishesFromTheQueueFailsEarlyWithAClearMessage() {
        ComfyUiStub().use { stub ->
            stub.loseJob = true
            registerComfyServer(stub)
            val persona = probePersona("ComfyLost", stub.url, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            val text = lastMessage().text
            assertTrue("the lost-job failure must be readable: $text", text.contains(COMFY_JOB_LOST_MESSAGE))
            assertTrue(
                "the empty history must have been disambiguated against $COMFY_QUEUE_PATH",
                stub.requests.any { it.path == COMFY_QUEUE_PATH }
            )
            assertEquals(genBefore.size, genFiles().size)
            assertNull("a lost job must never reach /view", stub.lastViewQuery)
        }
    }

    @Test
    fun aFailedRunSurfacesTheRunsOwnMessageAndWritesNoImage() {
        ComfyUiStub().use { stub ->
            stub.failJob = true
            registerComfyServer(stub)
            val persona = probePersona("ComfyFailed", stub.url, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            val text = lastMessage().text
            assertTrue("the failure must quote the run: $text", text.contains("stub failure"))
            assertTrue("...and say where it happened: $text", text.contains("node 9, SaveImage"))
            assertEquals(genBefore.size, genFiles().size)
            assertNull("a failed run's image must not be fetched", stub.lastViewQuery)
        }
    }

    // ------------------------------------------------------------------ polling and the download cap

    @Test
    fun aJobThatIsPendingAtFirstIsPolledUntilItFinishes() {
        ComfyUiStub().use { stub ->
            stub.pollsBeforeDone = 3
            registerComfyServer(stub)
            val persona = probePersona("ComfySlow", stub.url, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            assertTrue(
                "a job whose history is empty at first must still finish",
                waitUntil { genFiles().size == genBefore.size + 1 }
            )
            assertTrue(
                "the loop must have polled through the empty answers (saw ${stub.historyPolls.get()})",
                stub.historyPolls.get() >= 3
            )
        }
    }

    @Test
    fun anOversizedImageIsRefusedWhileStreamingAndLeavesNoFile() {
        ComfyUiStub().use { stub ->
            // No Content-Length, so the cap must be enforced on the bytes as they arrive rather
            // than by refusing up front — that fast path is a different branch.
            stub.omitContentLength = true
            stub.imageBytes = ByteArray(COMFY_MAX_IMAGE_BYTES + 1)
            registerComfyServer(stub)
            val persona = probePersona("ComfyTooLarge", stub.url, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            val text = lastMessage().text
            assertTrue("the cap must be reported: $text", text.contains(COMFY_IMAGE_TOO_LARGE_MESSAGE))
            assertEquals("an oversized image must not reach gen_ storage", genBefore.size, genFiles().size)
            assertTrue("...and must not leave a partial file behind: ${partialFiles()}", partialFiles().isEmpty())
        }
    }

    @Test
    fun aNonImageAnswerIsRefusedRatherThanSavedAsAPng() {
        ComfyUiStub().use { stub ->
            stub.imageContentType = "text/html"
            registerComfyServer(stub)
            val persona = probePersona("ComfyHtml", stub.url, isImageGenerator = true)
            val genBefore = genFiles()
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            val text = lastMessage().text
            assertTrue("the answer's real type must be named: $text", text.contains("text/html"))
            assertEquals(genBefore.size, genFiles().size)
            assertTrue("...and no partial file may survive: ${partialFiles()}", partialFiles().isEmpty())
        }
    }

    @Test
    fun anEntryWithNoWorkflowFailsVisiblyWithoutSendingAnything() {
        ComfyUiStub().use { stub ->
            val entry = ServerConfig(
                name = PROBE_SERVER,
                url = stub.url,
                username = COMFY_PROBE_USER,
                password = COMFY_PROBE_PASSWORD,
                authType = AuthType.BASIC,
                apiKey = null,
                backendType = ImageGenBackend.COMFYUI,
                comfyWorkflow = null
            )
            mainSync {
                service._imageGenBases.value = imageGenBefore.filterNot { it.name == PROBE_SERVER } + entry
                // ComfyUI must be the active backend, or the resolver never reaches the workflow
                // check this test is about.
                service.setActiveImageGenBackend(ImageGenBackend.COMFYUI)
            }
            val persona = probePersona("ComfyNoWorkflow", stub.url, isImageGenerator = true)
            mainSync { service._messages.value = emptyList() }

            runBlocking { service.performImageGeneration("a serene mountain lake", persona) }

            assertTrue(
                "the bubble must say where to add a workflow: ${lastMessage().text}",
                lastMessage().text.contains(COMFY_NO_WORKFLOW_MESSAGE)
            )
            assertTrue("nothing may be sent for an entry with no workflow", stub.requests.isEmpty())
        }
    }
}
