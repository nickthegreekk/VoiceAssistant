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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spontaneous messages — the deep link's ACTIVITY half, proven on-device.
 *
 * MainScreen's effect trusts two platform facts it cannot verify itself:
 *
 * 1. `MainActivity` carries `android:launchMode="singleTop"`, so a notification tap while
 *    the activity exists routes to `onNewIntent` instead of stacking a second instance.
 * 2. The delivery notification's `PendingIntent` names exactly the persona — the same name
 *    the state-holder is keyed by — so the tap the activity routes is the tap that was made.
 *
 * Both are asserted where they live: the manifest's launchMode survives package parsing,
 * and the extra survives an Intent round-trip through a `PendingIntent` built the way
 * `postSpontaneousNotification` builds it.
 *
 * The recreation test below adds the third platform fact: the launch intent OUTLIVES the
 * Activity instance, so a consumed tap must be removed from it or every recreation
 * (rotation, dark-mode toggle, font-scale change, "Don't keep activities") re-delivers the
 * extra to the new instance's onCreate and re-fires the deep link.
 */
@RunWith(AndroidJUnit4::class)
class SpontaneousDeepLinkActivityInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private companion object {
        const val PROBE = "ProbeActivityRecreate"
        const val OTHER = "ProbeActivityRecreateOther"
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
        // Must run before the Activity is ever launched — see the method comment.
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

        // Two fresh personas and a real switch AWAY from the deep link's target, so the tap
        // has somewhere to go and "already there" cannot masquerade as "the link fired".
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
        // This class is the one place in the suite that drives the real MainActivity, and
        // MainActivity.onCreate calls startService(): that promotes AssistantService to a
        // STARTED service, which outlives the ActivityScenario closing and outlives the
        // unbind above — so it would survive into every later class in this process with an
        // in-memory view loaded at an arbitrary moment, and the next class to bind would
        // inherit it. Unbinding is not enough; the started service has to be stopped.
        runCatching { context.stopService(Intent(context, AssistantService::class.java)) }
    }

    /**
     * `MainScreen` auto-requests every still-missing runtime permission on its first
     * composition (`LaunchedEffect(Unit)`), and the system permission dialog PAUSES
     * MainActivity — which deadlocks `ActivityScenario.recreate()`, since the relaunched
     * instance can never reach RESUMED underneath a dialog. Pre-granting the dialog-worthy
     * runtime permissions makes the recreation deterministic.
     *
     * The grants are deliberately NOT revoked in tearDown: revoking a runtime permission
     * KILLS the app process (logcat: ActivityManager "permissions revoked"), and the
     * instrumentation runs inside that process — a revoke here takes the whole test run
     * down with it. Grant-only matches androidx's own GrantPermissionRule, which also never
     * revokes. ACCESS_LOCAL_NETWORK is deliberately not included: it is not a
     * runtime-grantable permission, and requesting it draws no dialog.
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
                // grantRuntimePermission is a synchronous binder call under the automation's
                // shell identity — effective the moment it returns. (pm grant never kills the
                // process; only a revoke does.)
                runCatching { ui.grantRuntimePermission(context.packageName, permission) }
            }
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun probe(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ProbeActivityDeepLink] llama3",
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

    @Test fun `MainActivity really is singleTop in the installed manifest`() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA
        )
        assertEquals(
            "a second MainActivity instance per tap would orphan the deep-link state",
            android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP,
            info.launchMode
        )
    }

    @Test fun `the posted intent carries the persona name the effect reads`() {
        // Mirrors postSpontaneousNotification's construction: same action-less explicit
        // intent, same extra key, same immutability. If a future edit renames the key on
        // one side, this fails instead of the tap silently dropping its payload.
        val name = "ProbeActivityDeepLink"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET_PERSONA_NAME, name)
        }
        val pending = android.app.PendingIntent.getActivity(
            context,
            spontaneousNotificationId(name),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // A PendingIntent is not introspectable, so prove the round-trip on the parcelable
        // half: what goes into the extra is what comes back out, under the same key the
        // activity reads.
        val reparcelled = Intent().apply { putExtras(intent) }
        assertTrue(reparcelled.hasExtra(EXTRA_TARGET_PERSONA_NAME))
        assertEquals(name, reparcelled.getStringExtra(EXTRA_TARGET_PERSONA_NAME))
        pending.cancel()
    }

    @Test fun `a recreation after the tap must not re-fire the deep link`() {
        // F3 regression pin. `handleSpontaneousDeepLink` used to leave EXTRA_TARGET_PERSONA_NAME
        // in the launch intent after reading it. That intent OUTLIVES the Activity — every
        // recreation (rotation, dark-mode toggle, font-scale change, "Don't keep activities";
        // ActivityScenario.recreate() drives the same relaunch-with-the-same-intent mechanism)
        // redelivers it to the new instance's onCreate, which re-read the still-present extra
        // and re-fired the tap: a second applySpontaneousDeepLink → switchPersona() →
        // stopEverything(), aborting whatever was in progress after the tap.
        //
        // The countable signal is the chat request sequence (currentChatRequestSeq): exactly
        // one deep-link fire = exactly one switchPersona = exactly one stopEverything = exactly
        // one bump, and markRoomRoundStopped (stopEverything's room hook) deliberately bumps
        // nothing. Nothing else in this scenario starts a chat, stops anything, or runs a room
        // round — the probe personas are fresh, silent, and have no WorkManager chain due
        // within the test's lifetime.
        assertTrue(
            "precondition: the tap must have somewhere to switch from",
            OTHER == service.currentPersonaName
        )
        val seqBeforeTap = service.currentChatRequestSeq()

        // Same intent shape the delivery notification's PendingIntent delivers: explicit
        // component, persona name under the one key the activity reads.
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_TARGET_PERSONA_NAME, PROBE)
        }

        ActivityScenario.launch<MainActivity>(tapIntent).use { scenario ->
            // 1. The tap IS honoured on the original launch: the persona switches, the
            //    history swap settles, and exactly one chat-generation bump accounts for it.
            assertTrue(
                "the deep link must fire on launch",
                waitFor { service.currentPersonaName == PROBE }
            )
            assertTrue(
                "the switch must settle its history swap",
                waitFor { service.messagesOwnerName == PROBE }
            )
            assertEquals(
                "exactly one fire on the original launch",
                1,
                service.currentChatRequestSeq() - seqBeforeTap
            )

            // 2. ... and exactly once ACROSS the recreation too. With F3 unfixed, the new
            //    instance's onCreate re-reads the still-present extra and the effect fires
            //    again as soon as its service bind lands (binder callbacks resolve in
            //    milliseconds, well inside this window); the total would become 2. Watch
            //    the total for a settle window rather than snapshotting after recreate(), so
            //    a re-fire landing between recreate() returning and any later read is caught
            //    no matter when it lands.
            scenario.recreate()
            scenario.onActivity { /* barrier: the new instance is fully resumed */ }
            val fireWindowEnd = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < fireWindowEnd) {
                assertEquals(
                    "the consumed deep link re-fired on Activity recreation — the launch " +
                        "intent still carried the extra the tap should have consumed",
                    1,
                    service.currentChatRequestSeq() - seqBeforeTap
                )
                Thread.sleep(50)
            }
            assertEquals(
                "and the tapped persona must still be selected after the recreation",
                PROBE,
                service.currentPersonaName
            )
        }
    }
}
