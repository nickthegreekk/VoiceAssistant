package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Skills UI driven through the REAL composables against the REAL service, on device.
 *
 * Two jobs, and the second is the important one:
 *
 *  1. Screenshots of each surface, captured from the live UI (never a mock) into the app's
 *     external files dir so they can be pulled off the device and looked at.
 *  2. [aSkillToggleSurvivesAnEditAndRenameInTheSameSession] — the regression guard for the
 *     clobber hazard. `PersonaEditor` commits a WHOLE `Persona` rebuilt from the snapshot it
 *     was opened with, on a 500ms debounce AND again on dispose. `enabledSkillIds` was in
 *     neither copy, so any skill the user switched on was written straight back to its
 *     pre-toggle value the moment they touched another field — or closed the editor. This
 *     test performs exactly that sequence and reads the value back out of the service.
 */
@RunWith(AndroidJUnit4::class)
class SkillUiInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var skillsBefore: List<Skill> = emptyList()
    private var personasBefore: List<Persona> = emptyList()

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
            context.bindService(
                Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE
            )
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        // Join the service's startup history load before this test touches _messages:
        // onCreate -> loadSettings() -> switchPersona() publishes _messages asynchronously,
        // and a test that assigns _messages while that is still in flight has it
        // overwritten underneath (a replay then finds no message to index and publishes
        // nothing). No-op when no history operation is in flight.
        runBlocking { service.awaitPersonaHistoryIo() }
        skillsBefore = service.skills.value
        personasBefore = service.personas.value
    }

    @After
    fun tearDown() {
        mainSync {
            service._skills.value = skillsBefore
            service.personas.value.indices.reversed().forEach { service.removePersona(it) }
            personasBefore.forEach { service.addPersona(it) }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /**
     * Advances the compose test clock and idles, so a debounce that runs on a coroutine
     * delay actually fires. `Thread.sleep` alone would not, because the compose clock is
     * what drives those delays in a test.
     */
    private fun mainClockAdvance(millis: Long) {
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    // ---- fixtures ---------------------------------------------------------------------

    private val summarize = Skill(
        id = "shot-summarize",
        name = "Summarizer",
        triggerPhrases = listOf("summarize this", "tl;dr"),
        instructions = "Answer in at most three bullet points."
    )
    private val poemy = Skill(
        id = "shot-poemy",
        name = "Poemy",
        triggerPhrases = listOf("summarize this as a poem", "make this a poem"),
        instructions = "Rewrite the reply as a short rhyming poem."
    )

    /** Deliberately trigger-less and instruction-less: the "can never fire" warning surface. */
    private val draft = Skill(
        id = "shot-draft",
        name = "Unfinished Idea",
        triggerPhrases = emptyList(),
        instructions = ""
    )

    private val bob = Persona(
        name = "Bob", themeColor = Color(0xFF4ADE80), model = "[S] m",
        systemPrompt = "You are Bob.", enabledSkillIds = listOf(summarize.id)
    )
    private val cara = Persona(
        name = "Cara", themeColor = Color(0xFF60A5FA), model = "[S] m",
        systemPrompt = "You are Cara."
    )

    private fun installFixtures() = mainSync {
        service._skills.value = listOf(summarize, poemy, draft)
        service.personas.value.indices.reversed().forEach { service.removePersona(it) }
        service.addPersona(bob)
        service.addPersona(cara)
    }

    // ---- screenshots ------------------------------------------------------------------

    /**
     * Writes a screenshot to the app's external files dir, where `adb pull` can reach it.
     *
     * The suite is meant to be run with `am instrument` rather than `connectedAndroidTest`,
     * because the Gradle task UNINSTALLS the app when it finishes — which would take
     * /sdcard/Android/data/<pkg> and every screenshot in it with it.
     */
    private fun shot(name: String) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        java.io.File(dir, "$name.png").outputStream().use { out ->
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Composable
    private fun SkillsScreen() {
        val skills by service.skills.collectAsState()
        val personas by service.personas.collectAsState()
        // Wrapped in a scrollable Column because that is what SettingsCategoryScreen puts
        // around a category body: it is the single scroller the editors are designed to
        // rely on. Without it these screens would have no scroll at all, and
        // performScrollTo would have nothing to scroll.
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                SkillSettings(
                    service = service,
                    skills = skills,
                    personas = personas,
                    currentThemeColor = Color(0xFF4ADE80)
                )
            }
        }
    }

    @Test
    fun captureSkillsSurfaces() {
        installFixtures()
        composeRule.setContent { SkillsScreen() }
        composeRule.waitForIdle()

        // 1. The library list, with two working skills and the usage count on each row.
        shot("01-skills-list")

        // 2. The editor on a working skill: the one-per-line trigger field.
        composeRule.onNodeWithText("Summarizer").performClick()
        composeRule.waitForIdle()
        shot("02-skill-editor-triggers")

        // 3. The editor on the trigger-less draft: the "can never fire" warning.
        composeRule.onNodeWithContentDescription("Back to skills list").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Unfinished Idea").performClick()
        composeRule.waitForIdle()
        shot("03-skill-editor-no-triggers")

        // 4. Delete on a skill Bob has enabled: the confirmation names him.
        composeRule.onNodeWithContentDescription("Back to skills list").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Summarizer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete skill").performScrollTo().performClick()
        composeRule.waitForIdle()
        shot("04-skill-delete-confirms")
    }

    /**
     * Renders PersonaEditor over the fixtures, faithfully reproducing how PersonaSettings
     * wires it.
     *
     * The persona is captured ONCE and never re-read, because that is exactly what
     * `PersonaSettings` does: it holds `editingPersona: Pair<Int, Persona>?` and its staleness
     * guard is a one-shot `LaunchedEffect(Unit)` that runs at mount. A harness that re-read
     * the live `personas` flow would re-key `PersonaEditor`'s `remember(persona)` state on
     * every service write and quietly absorb exactly the staleness this test exists to
     * catch — it would pass with the bug present.
     *
     * [open] is driven from outside Compose so the editor can be UNMOUNTED (which is what
     * runs the dispose flush) without calling setContent a second time.
     */
    @Composable
    private fun PersonaEditorScreen(open: Boolean, snapshot: Persona) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (open) {
                    PersonaEditor(
                        persona = snapshot,
                        onSave = { updated ->
                            val index =
                                service.personas.value.indexOfFirst { it.name == snapshot.name }
                            if (index != -1) service.updatePersona(index, updated)
                        },
                        onCancel = {},
                        service = service
                    )
                }
            }
        }
    }

    @Test
    fun capturePersonaSkillSection() {
        installFixtures()
        composeRule.setContent {
            PersonaEditorScreen(open = true, snapshot = service.personas.value.first { it.name == "Bob" })
        }
        composeRule.waitForIdle()

        // 5. The Skills section inside the persona editor, showing the one enabled skill.
        // Scrolled to the SECTION BODY (not just its header) so the enabled row, its switch
        // and the "Add skill" row are all on screen in the shot.
        composeRule.onNodeWithText("Add skill").performScrollTo()
        composeRule.waitForIdle()
        shot("05-persona-skills-section")

        // 6. The add-skill picker, offering the skills this persona does not have.
        composeRule.onNodeWithText("Add skill").performScrollTo().performClick()
        composeRule.waitForIdle()
        shot("06-persona-add-skill-dialog")
    }

    /**
     * The clobber regression: a skill the user switched on must SURVIVE the rest of their
     * edit, including a rename, in the same editor session.
     *
     * This is the sequence that used to lose it. `PersonaEditor` holds the persona it was
     * opened with and rebuilds the WHOLE `Persona` from it on a 500ms debounce and again on
     * dispose. `enabledSkillIds` was absent from both copies, so each of those commits wrote
     * the value frozen at open time — silently reverting the toggle. Renaming is the worst
     * case specifically because `setSkillEnabledForPersona(persona.name, …)` targets the
     * pre-rename name, so the service can no longer re-apply the toggle either: after the
     * rename commits, the local value is the ONLY thing carrying it.
     *
     * Asserted against the SERVICE, not the UI, because that is where the loss was visible:
     * the switch would still look on while the saved persona had lost the skill.
     */
    @Test
    fun aSkillToggleSurvivesAnEditAndRenameInTheSameSession() {
        installFixtures()
        // Bob starts with the Summarizer and nothing else.
        mainSync {
            service.setSkillEnabledForPersona("Bob", poemy.id, true)
        }
        assertEquals(listOf(summarize.id, poemy.id), service.personas.value.first { it.name == "Bob" }.enabledSkillIds)

        var editorOpen by mutableStateOf(true)
        val snapshot = service.personas.value.first { it.name == "Bob" }
        composeRule.setContent { PersonaEditorScreen(open = editorOpen, snapshot = snapshot) }
        composeRule.waitForIdle()

        // Turn a skill on through the real UI.
        composeRule.onNodeWithText("Add skill").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Unfinished Idea").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitForIdle()

        // Now edit a DIFFERENT field in the same session — the rename. Field 0 is the
        // persona-name field, the same addressing the RoomEditor UI tests use.
        composeRule.onAllNodes(hasSetTextAction())[0]
            .performScrollTo()
            .performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Bob Renamed")
        composeRule.waitForIdle()

        // Let the 500ms debounce fire, then unmount the editor so the dispose flush runs too
        // — both commit paths must carry the new enabledSkillIds.
        mainClockAdvance(1500)
        mainSync { editorOpen = false }
        composeRule.waitForIdle()
        mainClockAdvance(500)

        val after = service.personas.value.first { it.name == "Bob Renamed" }
        assertEquals(
            "the skill toggled on in this session was reverted by the editor's own commit — " +
                "the debounce or the dispose flush wrote a stale enabledSkillIds back",
            listOf(summarize.id, poemy.id, draft.id),
            after.enabledSkillIds
        )
    }
}
