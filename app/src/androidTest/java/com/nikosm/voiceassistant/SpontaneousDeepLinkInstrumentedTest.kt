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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spontaneous messages — the notification-tap deep link, proven at the SERVICE layer.
 *
 * `applySpontaneousDeepLink` owns everything stateful about a tap: which persona is selected
 * (O14 — the persona, never a message), and that room mode steps aside so the promised
 * conversation is what the user lands on. It lives on the service precisely so this coverage
 * needs no Compose host.
 *
 * Worth stating plainly: an earlier revision of this coverage composed the whole `MainScreen`
 * against the live service and asserted the rendered persona name. That passed standalone —
 * and was mutation-verified there, failing exactly when the deep link stopped updating the UI
 * mirror — but it could not run reliably inside the full suite, where the Compose host's root
 * registry came back empty for that test while a sibling canary in the same class passed. A
 * test that cries wolf is worse than none, so it was replaced by this one plus the two tests
 * around it. The single UI line it no longer covers, `currentPersona = persona` in
 * `MainScreen`'s deep-link effect, is recorded as a known gap in the plan rather than left
 * implied.
 */
@RunWith(AndroidJUnit4::class)
class SpontaneousDeepLinkInstrumentedTest {

    private companion object {
        const val PROBE = "ProbeDeepLinkPersona"
        const val OTHER = "ProbeDeepLinkOther"
        const val GHOST = "ProbeDeepLinkGhost"
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

        // Two eligible personas, so a switch has somewhere to go and a wrong target is
        // distinguishable from "already there".
        onMain {
            service.personas.value
                .filter { it.name == PROBE || it.name == OTHER }
                .forEach { service.removePersona(service.personas.value.indexOf(it)) }
            service.addPersona(probe(OTHER))
            service.addPersona(probe(PROBE))
            service.switchPersona(probe(OTHER))
        }
        assertTrue("probe setup must leave OTHER active", waitFor { service.currentPersonaName == OTHER })
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

    private fun probe(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ProbeDeepLinkOllama] llama3",
        systemPrompt = "You are $name.",
        isCloud = false,
        spontaneousEnabled = true,
        spontaneousGapMinutes = 15
    )

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    // ---- the deep link itself ------------------------------------------------------------

    @Test fun `a tap selects the named persona`() {
        assertEquals("precondition: the tap must be a real switch", OTHER, service.currentPersonaName)
        var selected: Persona? = null
        onMain { selected = service.applySpontaneousDeepLink(PROBE) }

        assertEquals("the returned persona is what the UI mirrors", PROBE, selected?.name)
        assertTrue("the service must end up on the tapped persona",
            waitFor { service.currentPersonaName == PROBE })
    }

    @Test fun `a tap for a persona that no longer exists is consumed without switching`() {
        var selected: Persona? = null
        onMain { selected = service.applySpontaneousDeepLink(GHOST) }

        assertNull("nothing to open, so nothing is returned to mirror", selected)
        assertEquals("and nothing may move", OTHER, service.currentPersonaName)
    }

    @Test fun `a tap leaves room mode`() {
        // Room mode renders in place of the persona chat, so a notification tap that did not
        // leave the room would land the user on the room's transcript instead of the
        // conversation the card promised.
        onMain { service._activeRoomId.value = "probe-deep-link-room" }
        assertTrue("precondition: room mode must actually be on",
            waitFor { service.activeRoomId.value != null })

        onMain { service.applySpontaneousDeepLink(PROBE) }

        assertTrue("the tap must leave room mode", waitFor { service.activeRoomId.value == null })
        assertTrue("and select the tapped persona", waitFor { service.currentPersonaName == PROBE })
    }
}
