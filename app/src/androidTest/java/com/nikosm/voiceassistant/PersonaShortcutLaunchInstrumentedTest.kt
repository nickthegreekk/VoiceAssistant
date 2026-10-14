package com.nikosm.voiceassistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end half of the home-screen shortcut: the intent a pinned icon delivers really
 * does open that persona's chat.
 *
 * The deep link itself is already proven for the notification path
 * ([SpontaneousDeepLinkActivityInstrumentedTest]). What this class adds is that the SHORTCUT is
 * a valid producer of it — built by the same [personaShortcutIntent] the pin uses, so "what
 * gets pinned" and "what opens" cannot drift apart — and that the result is the promised thing:
 * the named persona selected, with its own history loaded.
 */
@RunWith(AndroidJUnit4::class)
class PersonaShortcutLaunchInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private companion object {
        const val PROBE = "ProbeShortcutLaunch"
        const val OTHER = "ProbeShortcutLaunchOther"
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
        // Must run before the Activity is ever launched: MainScreen auto-requests every
        // still-missing runtime permission on first composition, and that dialog PAUSES
        // MainActivity — which would leave the scenario unable to reach RESUMED.
        silencePermissionDialogs()
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
                Intent(context, AssistantService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        // Join the service's startup history load before this test touches _messages — the same
        // race every service-binding test in this suite has to close.
        runBlocking { service.awaitPersonaHistoryIo() }

        personasBefore = service.personas.value
        currentPersonaBefore = service.currentPersonaName
        messagesOwnerBefore = service.messagesOwnerName
        messagesBefore = service._messages.value

        // Two fresh personas and a real switch AWAY from the shortcut's target, so the tap has
        // somewhere to go and "already there" cannot masquerade as "the shortcut fired".
        onMain {
            service.personas.value
                .filter { it.name == PROBE || it.name == OTHER }
                .forEach { service.removePersona(service.personas.value.indexOf(it)) }
            service.addPersona(probe(OTHER))
            service.addPersona(probe(PROBE))
            service.switchPersona(probe(OTHER))
        }
        assertTrue(
            "probe setup must leave OTHER active",
            waitFor { service.currentPersonaName == OTHER }
        )
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
        // MainActivity.onCreate calls startService(), which promotes AssistantService to a
        // STARTED service that outlives both the scenario closing and the unbind above — so it
        // would otherwise survive into every later class in this process.
        runCatching { context.stopService(Intent(context, AssistantService::class.java)) }
    }

    @Test
    fun `launching_the_shortcut_intent_opens_that_personas_chat`() {
        // Exactly what a pinned icon delivers, built by the function the pin itself uses.
        val shortcutTap = personaShortcutIntent(context, PROBE)

        ActivityScenario.launch<MainActivity>(shortcutTap).use {
            assertTrue(
                "the shortcut must select the persona it names",
                waitFor { service.currentPersonaName == PROBE }
            )
            assertTrue(
                "the switch must settle its history swap, i.e. the chat really opened",
                waitFor { service.messagesOwnerName == PROBE }
            )
        }
    }

    /**
     * `MainScreen` auto-requests every still-missing runtime permission on its first
     * composition, and the system dialog PAUSES MainActivity — which would leave the scenario
     * unable to reach RESUMED. Pre-granting them makes the launch deterministic.
     *
     * The grants are deliberately NOT revoked in tearDown: revoking a runtime permission KILLS
     * the app process, and the instrumentation runs inside that process — a revoke here would
     * take the whole test run down with it. Grant-only matches androidx's own
     * GrantPermissionRule. ACCESS_LOCAL_NETWORK is not included: it is not runtime-grantable.
     */
    private fun silencePermissionDialogs() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val dialogWorthy = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.READ_PHONE_STATE)
        }
        dialogWorthy.forEach { permission ->
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                runCatching { ui.grantRuntimePermission(context.packageName, permission) }
            }
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun probe(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ProbeShortcutLaunch] llama3",
        systemPrompt = "You are $name.",
        isCloud = false
    )

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }
}
