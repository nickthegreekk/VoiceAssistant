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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
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

        personasBefore = service.personas.value
        currentPersonaBefore = service.currentPersonaName
        messagesOwnerBefore = service.messagesOwnerName
        messagesBefore = service._messages.value

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
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service._messages.value = messagesBefore
                service.currentPersonaName = currentPersonaBefore
                service.messagesOwnerName = messagesOwnerBefore
                service.saveSettings()
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
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
}
