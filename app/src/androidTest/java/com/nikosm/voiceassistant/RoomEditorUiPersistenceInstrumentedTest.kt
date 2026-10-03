package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The room EDITOR itself, driven through the real composable against the real service.
 *
 * RoomPersistenceInstrumentedTest proves the service layer and `rooms_v1` are sound, so this
 * suite exists to pin down WHERE the edit is lost: between the editor's local field state and
 * the service call. It renders [RoomEditor] exactly as RoomSettings does and drives the same
 * controls, then reads the value back out of [SettingsManager.getRooms].
 */
@RunWith(AndroidJUnit4::class)
class RoomEditorUiPersistenceInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null
    private var roomsBefore: List<Room> = emptyList()

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun awaitPersist(
        what: String,
        timeoutMs: Long = 10_000,
        matches: (List<Room>) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val onDisk = runCatching { service.settingsManager.getRooms() }.getOrNull()
            if (onDisk != null && matches(onDisk)) return
            Thread.sleep(50)
        }
        val actual = runCatching {
            service.settingsManager.getRooms()?.joinToString { "${it.name}[${it.memberNames}]" }
        }.getOrNull()
        assertEquals("timed out after ${timeoutMs}ms waiting until $what; disk holds: $actual", "saved", "not saved")
    }

    /** Disk must stay UNCHANGED for [quietMs] — used to prove an edit was dropped. */
    private fun assertStaysUnchanged(what: String, roomId: String, snapshot: Room?, quietMs: Long = 3_000) {
        val deadline = SystemClock.uptimeMillis() + quietMs
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            val onDisk = runCatching { service.settingsManager.getRooms() }
                .getOrNull()?.firstOrNull { it.id == roomId }
            if (onDisk != snapshot) {
                assertEquals("$what: the value changed on disk to $onDisk", snapshot, onDisk)
            }
        }
    }

    private fun seedRoom(): Room {
        val room = Room(name = "Editor Probe", memberNames = listOf("EP1", "EP2", "EP3"))
        mainSync { service.addRoom(room) }
        return room
    }

    private fun persisted(id: String): Room? =
        service.settingsManager.getRooms()?.firstOrNull { it.id == id }

    private fun bind() {
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
        check(
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        ) { "bindService() refused to connect to AssistantService" }
        check(bound.await(30, TimeUnit.SECONDS)) { "AssistantService did not bind within 30s" }
        service = boundService!!
        // Join the service's startup history load before this test touches _messages:
        // onCreate -> loadSettings() -> switchPersona() publishes _messages asynchronously,
        // and a test that assigns _messages while that is still in flight has it
        // overwritten underneath (a replay then finds no message to index and publishes
        // nothing). No-op when no history operation is in flight.
        runBlocking { service.awaitPersonaHistoryIo() }
        roomsBefore = service.rooms.value
    }

    @After
    fun tearDown() {
        runCatching {
            mainSync {
                service._rooms.value = roomsBefore
                service.saveSettings()
            }
        }
        runCatching {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                // Whole-list equality, not just IDs — an ID-only check would pass while the
                // probe left a renamed/reordered/recapped room behind in the real prefs.
                if (service.settingsManager.getRooms() == roomsBefore) break
                Thread.sleep(50)
            }
        }
        // Asserted OUTSIDE the runCatching above, which would otherwise swallow the
        // AssertionError and let a corrupted teardown pass as a green test.
        assertEquals(
            "the probe left the app's real rooms altered on disk",
            roomsBefore,
            service.settingsManager.getRooms()
        )
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun probePersona(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[EditorProbeServer] some-model",
        systemPrompt = "probe",
        isCloud = false,
        backendUrl = "",
        allowGatewayFailover = false,
        voiceMode = VoiceMode.NONE
    )
    /**
     * Renders [RoomEditor] inside a bounded, scrolling parent — what the category page's
     * LazyColumn does in the real app.
     *
     * The editor is deliberately a plain Column now (no nested scrollable, no height cap),
     * so a test that mounts it on its own must supply the scrolling parent itself.
     * `performScrollTo()` in the tests below relies on this too.
     */
    @Composable
    private fun RoomEditorInPage(
        room: Room,
        personas: List<Persona>,
        onClose: () -> Unit,
        service: AssistantService
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            RoomEditor(room = room, personas = personas, onClose = onClose, service = service)
        }
    }

    /**
     * THE REGRESSION THIS EXISTS FOR.
     *
     * PersonaEditor commits every field through a debounced auto-save and, crucially, a
     * DisposableEffect that flushes whatever is still pending when the editor leaves
     * composition — so closing within the debounce window cannot lose an edit. RoomEditor
     * was built to mirror that, but its name/cap fields were LOCAL state committed only by
     * the explicit "Done" button: anything that left composition another way (system back,
     * the category's own back arrow, dismissing Settings outright) discarded the edit with
     * no save at all.
     *
     * So this drives the editor the way a user does — type a name, then close WITHOUT
     * pressing Done — and asserts the name reached disk.
     */
    @Test
    fun editingTheNameAndClosingWithoutDoneStillPersists() {
        bind()
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        var editorOpen by mutableStateOf(true)
        composeRule.setContent {
            androidx.compose.material3.MaterialTheme {
                if (editorOpen) {
                    RoomEditorInPage(
                        room = room,
                        personas = personas,
                        onClose = { editorOpen = false },
                        service = service
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Type a new room name (field 0 is "Room name", field 1 is the cap).
        composeRule.onAllNodes(hasSetTextAction())[0]
            .performScrollTo()
            .performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Renamed In UI")
        composeRule.waitForIdle()

        // Close the way the back arrow / system back / Settings dismissal all do — NOT Done.
        mainSync { editorOpen = false }
        composeRule.waitForIdle()

        awaitPersist("a name typed and closed without Done never reached disk") {
            it.any { r -> r.id == room.id && r.name == "Renamed In UI" }
        }
        assertEquals("Renamed In UI", persisted(room.id)?.name)
    }

    /**
     * The editor's back chevron is reachable BY LABEL and actually exits.
     *
     * The chevron used to carry `contentDescription = null`, which made it two kinds of
     * invisible at once: a TalkBack user had no way to find the editor's only exit, and no
     * test could select it by name — so the "close without Done, still persists" property
     * above had to be exercised by flipping a state flag (`editorOpen = false`) rather than
     * by pressing the control a real user presses. That flag path never runs the button's
     * own `commitNow()` call.
     *
     * This drives the actual affordance, so both the label and the commit it performs are
     * covered at once.
     */
    @Test
    fun theBackChevronIsLabelledAndExitsTheEditorCommittingTheEdit() {
        bind()
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        var editorOpen by mutableStateOf(true)
        composeRule.setContent {
            androidx.compose.material3.MaterialTheme {
                if (editorOpen) {
                    RoomEditorInPage(
                        room = room,
                        personas = personas,
                        onClose = { editorOpen = false },
                        service = service
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Selectable by its label, and a real control rather than decoration: this alone
        // fails if the description is null, renamed, or the icon is not clickable.
        val back = composeRule.onNodeWithContentDescription("Back to rooms list")
        back.assertHasClickAction()

        // Type a name first, so pressing it also proves the exit does not discard the edit.
        composeRule.onAllNodes(hasSetTextAction())[0]
            .performScrollTo()
            .performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Closed By Chevron")
        composeRule.waitForIdle()

        back.performClick()
        composeRule.waitForIdle()

        // The editor is gone...
        composeRule.onAllNodesWithText("Edit room").assertCountEquals(0)
        // ...and the edit it was holding reached disk anyway.
        awaitPersist("an edit closed with the back chevron never reached disk") {
            it.any { r -> r.id == room.id && r.name == "Closed By Chevron" }
        }
    }

    /** The same edit, committed through Done — the path that was already known to work. */
    @Test
    fun editingTheNameAndPressingDonePersists() {
        bind()
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        var closed = false
        composeRule.setContent {
            androidx.compose.material3.MaterialTheme {
                RoomEditorInPage(room = room, personas = personas, onClose = { closed = true }, service = service)
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Committed With Done")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Done").performScrollTo().performClick()
        composeRule.waitForIdle()

        awaitPersist("pressing Done did not reach disk") {
            it.any { r -> r.id == room.id && r.name == "Committed With Done" }
        }
        assertEquals("Committed With Done", persisted(room.id)?.name)
    }

    /** Cap typed then closed without Done — the other local-only field. */
    @Test
    fun editingTheCapAndClosingWithoutDoneStillPersists() {
        bind()
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        var editorOpen by mutableStateOf(true)
        composeRule.setContent {
            androidx.compose.material3.MaterialTheme {
                if (editorOpen) {
                    RoomEditorInPage(room = room, personas = personas, onClose = { editorOpen = false }, service = service)
                }
            }
        }
        composeRule.waitForIdle()

        // Field 1 is the autonomous-cap field.
        composeRule.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("2")
        composeRule.waitForIdle()

        mainSync { editorOpen = false }
        composeRule.waitForIdle()

        awaitPersist("a cap typed and closed without Done never reached disk") {
            it.any { r -> r.id == room.id && r.autoReplyCap == 2 }
        }
        assertEquals(2, persisted(room.id)?.autoReplyCap)
    }

    /** Reorder is a direct service call, not local state — it must never have been broken. */
    @Test
    fun reorderingThroughTheEditorControlPersists() {
        bind()
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        composeRule.setContent {
            androidx.compose.material3.MaterialTheme {
                RoomEditorInPage(room = room, personas = personas, onClose = {}, service = service)
            }
        }
        composeRule.waitForIdle()

        // The down arrow on the first member row (index 0 -> 1).
        composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Move EP1 down"))[0]
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        awaitPersist("a reorder through the editor never reached disk") {
            it.any { r -> r.id == room.id && r.memberNames == listOf("EP2", "EP1", "EP3") }
        }
        assertEquals(listOf("EP2", "EP1", "EP3"), persisted(room.id)?.memberNames)
    }
}