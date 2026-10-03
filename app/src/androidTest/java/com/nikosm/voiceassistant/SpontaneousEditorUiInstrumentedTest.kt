package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spontaneous messages — the editor's RENDER GATE, proven on-device.
 *
 * What this pins is the part no JVM test can reach: the gate reads the SERVICE's live
 * server lists, not the editor's asynchronously-collected copies, so it is correct on the
 * FIRST composition — before the `LaunchedEffect` collectors have delivered and the local
 * snapshots still read `emptyList()`.
 *
 * Why that moment matters: feeding an empty Ollama set to `spontaneousEligibility` makes a
 * perfectly local persona resolve as gateway-only. A gate that read the collected copies
 * would hide the section on the first frame (and, worse, the editor's commit would compute
 * the same verdict and strip a valid opt-in on the first auto-save).
 *
 * The frozen-clock technique: `mainClock.autoAdvance = false` holds the first frame so NO
 * `LaunchedEffect` collector has delivered yet, while the bound service's own lists have
 * been loaded since `onCreate`. Asserting visibility in that state proves the gate never
 * read the collected copies.
 */
@RunWith(AndroidJUnit4::class)
class SpontaneousEditorUiInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private companion object {
        const val LOCAL_SERVER = "ProbeOllamaSpontaneousUI"
        const val CLOUD_PROVIDER = "ProbeCloudSpontaneousUI"
        const val LOCAL_MODEL = "[$LOCAL_SERVER] llama3"
        const val CLOUD_MODEL = "[$CLOUD_PROVIDER] probe-model"
        const val EDITOR_TAG = "spontaneousEditorProbeRoot"

        /** As `SettingsSectionHeader` renders it — `title.uppercase()`. */
        const val SECTION_HEADING = "SPONTANEOUS MESSAGES"

        /** The gate's own label — present ONLY when the section is offered at all. */
        const val TOGGLE_LABEL = "Let this persona message you on its own"
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var personasBefore: List<Persona> = emptyList()
    private var ollamaBefore: List<ServerConfig> = emptyList()
    private var cloudApisBefore: List<CloudApiSetting> = emptyList()
    private var customCloudBefore: List<CloudApiSetting> = emptyList()
    private var currentPersonaBefore: String? = null
    private var messagesOwnerBefore: String? = null

    @Before
    fun bindAssistantService() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { /* not expected */ }
        }
        connection = conn
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        // Join the service's startup history load before this test touches _messages:
        // onCreate -> loadSettings() -> switchPersona() publishes _messages asynchronously,
        // and a test that assigns _messages while that is still in flight has it
        // overwritten underneath (a replay then finds no message to index and publishes
        // nothing). No-op when no history operation is in flight.
        runBlocking { service.awaitPersonaHistoryIo() }

        personasBefore = service.personas.value
        ollamaBefore = service._ollamaBaseUrls.value
        cloudApisBefore = service._cloudApis.value
        customCloudBefore = service._customCloudApis.value
        currentPersonaBefore = service.currentPersonaName
        messagesOwnerBefore = service.messagesOwnerName
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching {
            instrumentation.runOnMainSync {
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service._ollamaBaseUrls.value = ollamaBefore
                service._cloudApis.value = cloudApisBefore
                service._customCloudApis.value = customCloudBefore
                service.currentPersonaName = currentPersonaBefore
                service.messagesOwnerName = messagesOwnerBefore
                service.saveSettings()
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun localPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = LOCAL_MODEL,
        systemPrompt = "You are $name.", isCloud = false, spontaneousEnabled = enabled
    )

    private fun cloudPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = CLOUD_MODEL,
        systemPrompt = "You are $name.", isCloud = true, spontaneousEnabled = enabled
    )

    /**
     * Opens the REAL editor for a persona already in the service's list, exactly as
     * `PersonaSettings` does — so `remember(persona)` states, the debounce, and the
     * dispose flush all run for real. The persona is read from the live `personas` flow at
     * open time, mirroring the staleness guard rotation leaves behind.
     */
    @Composable
    private fun PersonaEditorScreen(personaName: String) {
        val live = service.personas.value.firstOrNull { it.name == personaName } ?: return
        Surface(modifier = Modifier.fillMaxSize().testTag(EDITOR_TAG)) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PersonaEditor(
                    persona = live,
                    onSave = { updated ->
                        val index = service.personas.value.indexOfFirst { it.name == live.name }
                        if (index != -1) service.updatePersona(index, updated)
                    },
                    onCancel = {},
                    service = service
                )
            }
        }
    }

    private fun installFixtures() {
        mainSync {
            service._ollamaBaseUrls.value =
                ollamaBefore.filterNot { it.name == LOCAL_SERVER } +
                    ServerConfig(LOCAL_SERVER, "http://10.0.0.9:11434")
            service._customCloudApis.value =
                customCloudBefore.filterNot { it.name == CLOUD_PROVIDER } +
                    CloudApiSetting(
                        name = CLOUD_PROVIDER, baseUrl = "https://$CLOUD_PROVIDER.test",
                        apiKey = "probe", icon = "C", color = Color(0xFF4ADE80)
                    )
            personasBefore.mapNotNull { p -> service.personas.value.indexOfFirst { it.name == p.name }.takeIf { it >= 0 } }
                .sortedDescending().forEach { service.removePersona(it) }
            service.addPersona(localPersona("ProbeLocalGate", enabled = false))
            service.addPersona(cloudPersona("ProbeCloudGate", enabled = false))
        }
    }

    // ------------------------------------------------------------------ the gate

    /**
     * Brings the node itself into the editor's viewport, then asserts it. The editor is one
     * long scrollable Column and this section sits below the prompt, Skills and voice — so a
     * node can exist in the tree yet fail `assertIsDisplayed` on viewport bounds. Scrolling
     * the exact node (not the heading above it) is what makes the assertion address what it
     * claims to.
     */
    private fun scrollAndAssert(
        node: androidx.compose.ui.test.SemanticsNodeInteraction
    ): androidx.compose.ui.test.SemanticsNodeInteraction {
        node.performScrollTo()
        composeRule.waitForIdle()
        return node.assertIsDisplayed()
    }

    @Test fun `a local persona is shown the controls`() {
        installFixtures()
        composeRule.setContent { PersonaEditorScreen("ProbeLocalGate") }
        composeRule.waitForIdle()
        scrollAndAssert(composeRule.onNodeWithText(SECTION_HEADING))
        scrollAndAssert(composeRule.onNodeWithText(TOGGLE_LABEL))
        scrollAndAssert(composeRule.onNodeWithTag("spontaneousMasterSwitch"))
        scrollAndAssert(composeRule.onNodeWithTag("spontaneousSendNow"))
    }

    @Test fun `cloud persona is told why rather than shown the controls`() {
        installFixtures()
        composeRule.setContent { PersonaEditorScreen("ProbeCloudGate") }
        composeRule.waitForIdle()
        scrollAndAssert(composeRule.onNodeWithText(SECTION_HEADING))
        // The controls are absent entirely — the gate renders the refusal in their place.
        composeRule.onNodeWithText(TOGGLE_LABEL).assertDoesNotExist()
        composeRule.onNodeWithTag("spontaneousMasterSwitch").assertDoesNotExist()
        composeRule.onNodeWithTag("spontaneousSendNow").assertDoesNotExist()
        scrollAndAssert(composeRule.onNodeWithText(SpontaneousPolicy.CLOUD_BLOCK_REASON))
    }

    // ------------------------------------------------------------------ the first frame

    @Test fun `gate is correct on the first frame before collectors deliver`() {
        installFixtures()
        // HOLD the first frame: no LaunchedEffect collector — including the three that
        // populate this editor's `ollamaBases`/`cloudApis`/`customCloudApis` — has delivered
        // yet, so those local snapshots still read `emptyList()`. The bound service itself
        // was loaded in `onCreate`, long before it could be bound at all.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { PersonaEditorScreen("ProbeLocalGate") }
        composeRule.waitForIdle()
        try {
            // What is asserted is PRESENCE, not display: a gate that read the
            // not-yet-delivered copies would decide Blocked (gateway-only) at this instant
            // and render the refusal instead — so the controls' node would not be in the
            // tree AT ALL. Display is a scroll question, deliberately not coupled to this:
            // scrolling needs frames, and this test exists precisely where frames are frozen.
            assertTrue(LOCAL_SERVER in service.ollamaServerNames())
            composeRule.onNodeWithText(SECTION_HEADING).assertExists()
            composeRule.onNodeWithText(TOGGLE_LABEL).assertExists()
            composeRule.onNodeWithTag("spontaneousMasterSwitch").assertExists()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    // ------------------------------------------------------------------ the toggle persists

    @Test fun `master switch flip reaches the service through the debounce`() {
        installFixtures()
        composeRule.setContent { PersonaEditorScreen("ProbeLocalGate") }
        composeRule.waitForIdle()
        assertFalse(service.personas.value.first { it.name == "ProbeLocalGate" }.spontaneousEnabled)

        // Scroll the SWITCH itself into view first: a click on an off-screen node lands at
        // coordinates outside the visible window, so it silently hits whatever is there
        // instead — no exception, no state change, and a test that reports a false negative.
        val toggle = composeRule.onNodeWithTag("spontaneousMasterSwitch")
        toggle.performScrollTo()
        composeRule.waitForIdle()
        toggle.performClick()
        // The debounce must fire on the compose clock — Thread.sleep would not advance it
        // (see SkillUiInstrumentedTest's mainClockAdvance).
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()

        assertTrue(
            "the toggle must survive the debounce with a live gate — a commit that re-decided " +
                "from not-yet-delivered copies would write `false` straight back over the flip",
            service.personas.value.first { it.name == "ProbeLocalGate" }.spontaneousEnabled
        )
    }
}

