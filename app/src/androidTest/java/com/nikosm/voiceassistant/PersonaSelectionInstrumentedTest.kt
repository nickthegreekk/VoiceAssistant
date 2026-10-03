package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spontaneous-messages follow-up: the persona selection as ONE observable value.
 *
 * `currentPersonaName` was a plain field and the UI kept its own mirror of it, which is how a
 * switch could move the service while the screen kept showing the previous persona. The fix is
 * that `currentPersonaFlow` is now the single derived value, and this class proves it follows
 * every real path that moves the selection — with **no Compose**, which is what makes it
 * deterministic where composing the whole voice screen was not.
 *
 * Every assertion here is about the exact value a consumer of the flow would observe, so
 * passing these is the service-layer proof that the UI (which derives from the same value) is
 * correct. Nothing in this file needs a Compose host, a notification, or a fake server.
 */
@RunWith(AndroidJUnit4::class)
class PersonaSelectionInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private companion object {
        const val PROBE = "ProbeSelectionPersona"
        const val OTHER = "ProbeSelectionOther"
        const val GHOST = "ProbeSelectionGhost"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var personasBefore: List<Persona> = emptyList()
    private var currentPersonaBefore: String? = null
    private var messagesOwnerBefore: String? = null
    private var messagesBefore: List<ChatMessage> = emptyList()
    private var roomsBefore: List<Room> = emptyList()

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
        currentPersonaBefore = service.currentPersonaName
        messagesOwnerBefore = service.messagesOwnerName
        messagesBefore = service._messages.value
        roomsBefore = service.rooms.value

        // Two personas, so a switch has somewhere to go and a wrong target is distinguishable
        // from "already there".
        onMain {
            service.personas.value
                .filter { it.name == PROBE || it.name == OTHER }
                .forEach { service.removePersona(service.personas.value.indexOf(it)) }
            service.addPersona(persona(OTHER))
            service.addPersona(persona(PROBE))
            service.switchPersona(persona(OTHER))
        }
        assertTrue("probe setup must leave OTHER selected", waitFor { service.currentPersonaName == OTHER })
        // Settle the swap so the mid-swap assertions below start from a known state.
        assertTrue("probe setup must settle the history owner", waitFor { service.messagesOwnerName == OTHER })
    }

    @After
    fun tearDown() {
        runCatching {
            onMain {
                // Room state first: the picker cases below can leave a room OPEN, and a
                // still-open room would make the next test render room mode over its own
                // chat — the very confusion this class is about.
                service.leaveRoom()
                service._rooms.value = roomsBefore
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service._messages.value = messagesBefore
                service.currentPersonaName = currentPersonaBefore
                service.messagesOwnerName = messagesOwnerBefore
                service.saveSettings()
            }
        }
        // Debounced saves mean the probe room could otherwise land on disk AFTER this class
        // restored the list, leaving a phantom room in the user's own rooms_v1. Wait the
        // restore out — same protection (and the same reason) as RoomPersistenceInstrumentedTest.
        waitFor { runCatching { service.settingsManager.getRooms() }.getOrNull() == roomsBefore }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /** A room created through the production path, with exactly [members] as its members. */
    private fun seedRoom(name: String, members: List<String>): Room {
        val room = Room(name = name, memberNames = members)
        onMain { service.addRoom(room) }
        assertTrue(
            "the probe room must really be in the service's list",
            waitFor { service.rooms.value.any { it.id == room.id } }
        )
        return room
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun persona(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ProbeSelectionOllama] llama3",
        systemPrompt = "You are $name.",
        isCloud = false
    )

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** The flow's value, awaited to [name]. `null` means "no selection". */
    private fun awaitSelection(name: String?, timeoutMs: Long = 10_000): Persona? {
        waitFor(timeoutMs) { service.currentPersonaFlow.value?.name == name }
        return service.currentPersonaFlow.value
    }

    // ---- the flow follows every path that moves the selection ----------------------------

    @Test fun `the flow follows a switch`() {
        assertEquals("precondition: the switch must be a real move", OTHER, awaitSelection(OTHER)?.name)
        onMain { service.switchPersona(persona(PROBE)) }
        assertEquals(PROBE, awaitSelection(PROBE)?.name)
    }

    @Test fun `the flow and the synchronous getter are the same resolution`() {
        // One join, one instance: the value a collector sees and the value a service-internal
        // pull sees cannot be different objects, let alone different personas.
        val fromFlow = awaitSelection(OTHER)
        assertSame(
            "the flow and currentPersona must resolve to the identical instance",
            service.currentPersona, fromFlow
        )
    }

    @Test fun `an edit to the active persona is reflected in the flow`() {
        // Exercises the LIST half of the combine, not just the name half: the editor
        // republishes the whole list on every keystroke, and the selection must observe it.
        //
        // Waits on the CONTENT, not the name — an edit does not move the selection, so
        // awaitSelection (which waits for the name) would return before the emission arrived.
        val idx = service.personas.value.indexOfFirst { it.name == OTHER }
        assertTrue("precondition: the active persona must be findable", idx >= 0)
        assertEquals("You are $OTHER.", service.currentPersonaFlow.value?.systemPrompt)

        onMain { service.updatePersona(idx, service.personas.value[idx].copy(systemPrompt = "edited")) }

        assertTrue(
            "an edit to the ACTIVE persona must reach the flow",
            waitFor { service.currentPersonaFlow.value?.systemPrompt == "edited" }
        )
        assertEquals(OTHER, service.currentPersonaFlow.value?.name)
    }

    @Test fun `the flow follows a rename of the active persona`() {
        val renamed = "ProbeSelectionRenamed"
        onMain { service.switchPersona(persona(PROBE)) }
        assertEquals(PROBE, awaitSelection(PROBE)?.name)

        val idx = service.personas.value.indexOfFirst { it.name == PROBE }
        onMain { service.updatePersona(idx, service.personas.value[idx].copy(name = renamed)) }

        assertEquals(
            "a rename of the ACTIVE persona must move the selection with it, not orphan it",
            renamed, awaitSelection(renamed)?.name
        )
    }

    @Test fun `deleting the active persona re-points the flow`() {
        onMain { service.switchPersona(persona(PROBE)) }
        assertEquals(PROBE, awaitSelection(PROBE)?.name)

        val idx = service.personas.value.indexOfFirst { it.name == PROBE }
        onMain { service.removePersona(idx) }

        val fallback = service.currentPersonaName
        assertTrue("the service must never be left pointing at a deleted persona", fallback != null)
        assertEquals(
            "and the fallback must be a persona that is really in the list",
            service.personas.value.first().name, fallback
        )
        assertEquals(fallback, awaitSelection(fallback)?.name)
    }

    @Test fun `deleting the last persona leaves the flow with no selection`() {
        // Also the non-vacuity control for every other assertion here: the flow genuinely can
        // be null, so "it equals persona X" is never true by default.
        onMain { service.personas.value.indices.reversed().forEach { service.removePersona(it) } }
        assertTrue("precondition: the persona list must be empty", service.personas.value.isEmpty())
        assertNull(awaitSelection(null))
    }

    @Test fun `adding an unselected persona does not disturb the selection`() {
        val before = awaitSelection(OTHER)
        onMain { service.addPersona(persona("ProbeSelectionAdded")) }
        Thread.sleep(250)
        assertEquals(OTHER, service.currentPersonaFlow.value?.name)
        // The IDENTICAL instance, not merely an equal one: an unrelated list change must not
        // re-derive the selection into a fresh value that would recompose its consumers.
        assertSame(before, service.currentPersonaFlow.value)
    }

    // ---- the spontaneous-message deep link, which is what exposed all of this -------------

    @Test fun `a deep link moves the flow`() {
        // THE regression from the spontaneous-messages work: the tap switched the service and
        // left the screen behind, because the screen mirrored the selection by hand. Now the
        // selection IS the observable, so this test is the whole proof.
        assertEquals(OTHER, awaitSelection(OTHER)?.name)
        onMain { service.applySpontaneousDeepLink(PROBE) }
        assertEquals(PROBE, awaitSelection(PROBE)?.name)
    }

    @Test fun `a deep link for a missing persona moves nothing`() {
        val before = awaitSelection(OTHER)
        onMain { service.applySpontaneousDeepLink(GHOST) }
        Thread.sleep(250)
        assertEquals(OTHER, service.currentPersonaFlow.value?.name)
        assertSame(before, service.currentPersonaFlow.value)
    }

    // ---- the four call sites that had to change, pinned individually ----------------------
    //
    // Each test below drives the EXACT service call the corresponding MainScreen call site now
    // makes — nothing more — and asserts the selection the screen will derive. They are the
    // per-site proof that removing the local assignment did not remove the behaviour.

    @Test fun `the translator add-and-select sequence resolves to a real persona`() {
        // Site 6, MainScreen's `onTranslatorClick` else-branch: no translator exists yet, so it
        // adds the default one and selects it. This is the case Q7 traced: the assignment used
        // to happen BEFORE addPersona, which was safe only because both completed in the same
        // synchronous lambda. Deriving, it is safe unconditionally — and this pins why.
        onMain {
            service.personas.value.filter { it.isTranslator }
                .forEach { service.removePersona(service.personas.value.indexOf(it)) }
        }
        assertTrue("precondition: no translator may remain", service.personas.value.none { it.isTranslator })

        onMain {
            service.addPersona(TRANSLATOR_PERSONA)
            service.switchPersona(TRANSLATOR_PERSONA)
        }

        val resolved = awaitSelection(TRANSLATOR_PERSONA.name)
        assertTrue("the added translator must be a real, stored persona", resolved != null)
        assertTrue("and it must be the translator, not a namesake", resolved!!.isTranslator)
        assertTrue(
            "resolution must hand back the STORED instance, not the constant",
            service.personas.value.any { it === resolved }
        )
    }

    @Test fun `the language bar call reaches the flow with no local copy`() {
        // Site 7. The bar used to ALSO assign a local `currentPersona.copy(...)`. The only
        // write left is `updatePersona(idx, copy)`, so this pins everything the screen now
        // depends on: the same call updates the stored persona, and the selection resolves to
        // it carrying the new language.
        val translator = selectATranslator()
        val idx = service.personas.value.indexOfFirst { it.name == translator.name }
        assertTrue("precondition: the selected translator must be findable", idx >= 0)

        onMain {
            service.updatePersona(idx, service.personas.value[idx].copy(targetLanguage = "French"))
        }

        assertTrue(
            "the language edit must reach the selection's flow",
            waitFor { service.currentPersonaFlow.value?.targetLanguage == "French" }
        )
        assertEquals(translator.name, service.currentPersonaFlow.value?.name)
        assertTrue(
            "and it must be the stored instance, not a local copy",
            service.personas.value.any { it === service.currentPersonaFlow.value }
        )
    }

    @Test fun `a selection naming a persona that is not in the list does not resolve`() {
        // The state MainScreen's reconciliation effect exists for: the list is non-empty, but
        // the stored name matches nothing. The screen then asks the service to select the first
        // persona. Without that the derived value would render DEFAULT_PERSONAS[0] while the
        // service had no persona to send with — the exact screen/sender divergence this whole
        // change is about.
        assertTrue("precondition: the list must be non-empty", service.personas.value.isNotEmpty())
        onMain { service.currentPersonaName = GHOST }

        assertNull("the synchronous getter must not invent a persona", service.currentPersona)
        assertTrue(
            "nor may the flow, which is what the screen reads",
            waitFor { service.currentPersonaFlow.value == null }
        )
    }

    /** Find-or-add the default translator and select it — the two translator call sites. */
    private fun selectATranslator(): Persona {
        onMain {
            service.personas.value.filter { it.isTranslator }
                .forEach { service.removePersona(service.personas.value.indexOf(it)) }
            service.addPersona(TRANSLATOR_PERSONA)
            service.switchPersona(TRANSLATOR_PERSONA)
        }
        val resolved = awaitSelection(TRANSLATOR_PERSONA.name)
        assertTrue("precondition: a translator must be selected", resolved != null)
        return resolved!!
    }

    @Test fun `the flow follows the name before the history owner catches up`() {
        // The join derives from the NAME, so the selection is the selection — never the
        // transcript. switchPersona moves the name synchronously and loads the incoming
        // history off Main, so inside this one main-thread block the two legitimately differ;
        // the flow must already report the incoming persona.
        onMain {
            service.switchPersona(persona(PROBE))
            assertEquals(PROBE, service.currentPersonaName)
            assertEquals(
                "the recorded history owner lags until the swap settles",
                OTHER, service.messagesOwnerName
            )
        }
        assertEquals(PROBE, awaitSelection(PROBE)?.name)
        assertTrue("the swap must settle", waitFor { service.messagesOwnerName == PROBE })
    }

    // ---- the picker's selection must also step out of room mode ---------------------------
    //
    // Room mode renders IN PLACE of the persona chat, and the picker sheet is reachable from
    // the room header too — so a bare switchPersona moved the selection while the room
    // transcript stayed on screen, and the tap read as a dead no-op. The selection itself was
    // never broken, which the first case proves as an executable precondition before pinning
    // the fix, so these can never pass by the switch simply failing instead.

    @Test fun `picking a persona from inside a room exits room mode into the normal chat of that persona`() {
        val room = seedRoom("ProbeSelectionRoomOutside", listOf(OTHER))
        onMain { service.switchRoom(room) }
        assertTrue("precondition: room mode must actually be on",
            waitFor { service.activeRoomId.value == room.id })
        assertTrue("precondition: the picked persona must NOT be in this room",
            PROBE !in room.memberNames)

        // The diagnosis, executable: the selection itself follows a switch even while a room
        // is open — which is exactly why the bug looked like nothing happened. Were this to
        // stop holding, the bug would have a second cause and the fix below would be passing
        // for the wrong reason.
        onMain { service.switchPersona(persona(PROBE)) }
        assertEquals("precondition: the bare switch must move the selection under room mode",
            PROBE, awaitSelection(PROBE)?.name)
        assertEquals("precondition: and that is the bug — the room is still open",
            room.id, service.activeRoomId.value)
        onMain { service.switchPersona(persona(OTHER)) }
        assertEquals(OTHER, awaitSelection(OTHER)?.name)

        // The fixed picker entry point: one tap, the outcome "Leave" then selecting PROBE
        // would have given.
        onMain { service.switchPersonaFromPicker(persona(PROBE)) }

        assertEquals("the tap must select the persona it was given", PROBE, awaitSelection(PROBE)?.name)
        assertTrue("and it must exit room mode",
            waitFor { service.activeRoomId.value == null })
        assertNull("with no room left selected", service.activeRoomId.value)
    }

    @Test fun `picking a persona that is a member of the active room still exits to the private chat`() {
        // Membership must not turn the tap into a no-op either: the outcome is the persona's
        // PRIVATE chat, not the room — the room is where they were, not where they are now.
        val room = seedRoom("ProbeSelectionRoomInside", listOf(PROBE, OTHER))
        onMain { service.switchRoom(room) }
        assertTrue("precondition: room mode must actually be on",
            waitFor { service.activeRoomId.value == room.id })
        assertTrue("precondition: the picked persona must really be a member",
            PROBE in room.memberNames)

        onMain { service.switchPersonaFromPicker(persona(PROBE)) }

        assertEquals("the tap must select the persona it was given", PROBE, awaitSelection(PROBE)?.name)
        assertTrue("membership must not keep the user inside the room",
            waitFor { service.activeRoomId.value == null })
        assertTrue("and leaving must not delete the room — it is still there to re-enter",
            service.rooms.value.any { it.id == room.id })
    }
}
