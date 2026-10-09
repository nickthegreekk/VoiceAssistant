package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the Add/Edit dialog probes AFTER it saves.
 *
 * This is the one place the probe endpoint is chosen by a CALL SITE rather than by the entry, so it
 * is the one place a test has to drive the real dialog: `imageGenProbePath` was already correct
 * while the save path passed `isImageGen = false`, which sent every image-gen save to the
 * Ollama/gateway health URL (`{base}/api/tags`). An image-gen server answers that with a 404 — one
 * of the false "this address doesn't seem right" snackbars, and a plausible source of the reported
 * 405. Both backends are asserted so neither can regress alone.
 */
@RunWith(AndroidJUnit4::class)
class ImageGenSaveProbeInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    @get:Rule
    val composeRule = createComposeRule()

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
        imageGenBefore = service._imageGenBases.value
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching {
            instrumentation.runOnMainSync {
                service._imageGenBases.value = imageGenBefore
                service.saveSettings()
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    @Test
    fun savingAnA1111EntryProbesOnlyTheModelList() {
        ImageGenStub().use { stub ->
            addThroughTheDialog(stub.url, ImageGenBackend.A1111)

            assertTrue(
                "the post-save probe never reached $IMAGE_GEN_SD_MODELS_PATH",
                waitUntil { stub.requests.any { it.path == IMAGE_GEN_SD_MODELS_PATH } }
            )
            val seen = stub.requests.map { "${it.method} ${it.path}" }
            assertFalse(
                "an image-gen entry must never be probed as an Ollama/gateway server — that is the " +
                    "bug this test exists for; saw: $seen",
                stub.requests.any { it.path.startsWith("/api/") }
            )
            val probe = stub.requests.first { it.path == IMAGE_GEN_SD_MODELS_PATH }
            assertEquals("the probe must be a GET", "GET", probe.method)
            assertEquals(
                "the probe must carry the saved entry's credentials",
                stub.basicHeader,
                probe.headers["authorization"]
            )
        }
    }

    @Test
    fun savingAComfyUiEntryProbesOnlySystemStatsAndStoresItsType() {
        ComfyUiStub().use { stub ->
            addThroughTheDialog(stub.url, ImageGenBackend.COMFYUI)

            assertTrue(
                "the post-save probe never reached $COMFY_SYSTEM_STATS_PATH",
                waitUntil { stub.requests.any { it.path == COMFY_SYSTEM_STATS_PATH } }
            )
            val seen = stub.requests.map { "${it.method} ${it.path}" }
            assertFalse(
                "a ComfyUI entry must not be probed at the A1111 endpoint; saw: $seen",
                stub.requests.any { it.path.startsWith("/sdapi/") }
            )
            assertFalse(
                "nor as an Ollama/gateway server; saw: $seen",
                stub.requests.any { it.path.startsWith("/api/") }
            )
            assertEquals("GET", stub.requests.first { it.path == COMFY_SYSTEM_STATS_PATH }.method)
            assertTrue(
                "the entry must have been stored as ComfyUI, not reset to the default",
                service._imageGenBases.value.any { it.backendType == ImageGenBackend.COMFYUI }
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Adds an entry the way a user does: tap + IN THE SECTION for [backend], type the name and URL,
     * choose Basic and its credentials, tap Add. The section owns the type now, so there is no chip
     * to click — and the "+" that is clicked is the one whose section it is.
     *
     * Credentials are always supplied so the probe is answered 200 — a 401 would be a different
     * test, and would leave a snackbar suspended.
     */
    private fun addThroughTheDialog(url: String, backend: ImageGenBackend) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ServerListSection(
                        title = "${backend.displayName} servers",
                        servers = emptyList(),
                        status = emptyMap(),
                        onAdd = { name, entryUrl, user, pass, authType, apiKey, backendType, workflow ->
                            service.addImageGenBase(name, entryUrl, user, pass, authType, apiKey, backendType, workflow)
                        },
                        onRemove = { },
                        onEdit = { _, _, _, _, _, _, _, _, _ -> },
                        onRefreshHealth = { },
                        onRefreshModels = { },
                        onMoveUp = { },
                        onMoveDown = { },
                        isGateway = false,
                        isImageGen = true,
                        imageGenBackend = backend,
                        service = service,
                        snackbarHostState = remember { SnackbarHostState() },
                        currentThemeColor = Color(0xFF4ADE80)
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Add ${backend.displayName} servers").performClick()
        composeRule.waitForIdle()

        // Field 0 is Name and 1 is URL. Selecting an auth type reveals Username/Password as fields
        // 2 and 3.
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("ProbeSave")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput(url)
        composeRule.onNodeWithText("Basic").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasSetTextAction())[2].performTextInput(COMFY_PROBE_USER)
        composeRule.onAllNodes(hasSetTextAction())[3].performTextInput(COMFY_PROBE_PASSWORD)
        composeRule.onNodeWithText("Add").performClick()
    }

    /** Pumps the compose dispatcher while the post-save probe runs on Dispatchers.IO. */
    private fun waitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            runCatching { composeRule.waitForIdle() }
            Thread.sleep(25)
        }
        return condition()
    }
}
