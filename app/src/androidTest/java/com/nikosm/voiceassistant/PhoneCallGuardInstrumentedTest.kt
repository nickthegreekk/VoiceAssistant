package com.nikosm.voiceassistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
/**
 * Pins the proximity barge-in CALL GUARD fix: [AssistantService.isPhoneCallActive] must be
 * able to genuinely detect a ringing/off-hook call, and must degrade to "no call" — never
 * crash, never block — for a user who declines the permission.
 *
 * The bug this covers: the guard read `TelephonyManager.callState`, which for an app
 * targeting API 31+ (this app targets 37) is permission-gated. Without a manifest
 * `READ_PHONE_STATE` declaration the framework threw
 * `SecurityException("getCallState API requires READ_PHONE_STATE for API version 31+")` on
 * every trigger, the guard's own catch converted that to `false` = "no call", and so the
 * guard could never skip: waving a hand over the phone during a real call stopped the
 * assistant's playback. The fix is the manifest declaration plus requesting it in the
 * existing multi-permission launcher.
 *
 * WHAT IS REAL HERE: the service is bound through the production `AssistantBinder`, the
 * playback under test is a real `MediaPlayer` started by the production `playAudioFile()`,
 * the interrupt under test is the production `proximityConfirmRunnable` (armed and posted
 * exactly as the production sensor path does), the permission state is the device's real
 * state (granted/denied through the framework's own app-op gate), and the
 * graceful-degradation contract is exercised on the production default reader.
 *
 * WHAT IS SIMULATED, AND WHY: the OS call STATE itself. A physical device exposes no API to
 * fabricate CALL_STATE_OFFHOOK/RINGING (there is no emulator console here — this runs on a
 * real Redmi Note 9 Pro — and placing a real call is neither scriptable nor acceptable), so
 * the state is supplied through [AssistantService.callStateReader], the single seam the
 * production guard reads through. Everything downstream of that one value is production
 * code. A real ringing/off-hook call was NOT placed, so the end-to-end assertion is
 * "the production trigger skips when the guard reports an active call", not "…when the
 * carrier delivers a real call".
 *
 * WHY THE PERMISSION IS NEVER REVOKED: revoking a runtime permission kills the app process,
 * and this instrumentation runs in that process — the test run itself would die. Denial is
 * therefore reproduced through the app-op gate the framework itself consults
 * (`TelecomServiceImpl.canReadPhoneState` ends in
 * `AppOpsManager.noteOp(...) == MODE_ALLOWED`), which refuses the read while the permission
 * stays granted. That is the same refusal the user-declined case produces, and it is
 * process-safe. Verified on the device under test: with the op denied (uid-scoped) and the
 * permission granted, the read throws
 * `SecurityException: getCallState API requires READ_PHONE_STATE for API version 31+`
 * through `TelecomManager.getCallState` -> `ITelecomService.getCallStateUsingPackage` ->
 * `TelecomServiceImpl$1.getCallStateUsingPackage` — the pre-fix failure, reproduced exactly.
 * Two device quirks the commands below depend on: only the `--uid` form of `appops set`
 * takes effect (the package-scoped form is a silent no-op), and `appops reset` does NOT
 * clear a uid-scoped entry, so the restore is an explicit `allow` (which is this device's
 * default for the op while the permission is granted) rather than the CLI's `default`/`reset`.
 *
 * The user-facing permission DIALOG is also not exercised (the grant is performed with
 * `pm grant` to keep the run deterministic); what is verified is that the declaration makes
 * the grant possible and that the guard works with it.
 */
@RunWith(AndroidJUnit4::class)
class PhoneCallGuardInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    /** The service's own production reader, captured so teardown can put it back. */
    private var defaultCallStateReader: (() -> Int?)? = null
    private var silencedWasToggled = false
    private var earpieceWasToggled = false

    @Before
    fun bindAssistantService() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) { /* not expected here */ }
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
        defaultCallStateReader = service.callStateReader

        // Grant the permission up front, for EVERY test in this class: the app-op denial test
        // below must be able to attribute the refusal to the app-op gate alone, which is only
        // unambiguous while the permission itself is granted. Failure here is reported by
        // readPhoneStateIsDeclaredAndGrantable() with a precise message.
        shell("pm grant $APP_ID ${Manifest.permission.READ_PHONE_STATE}")
        // A refused read is the opposite of what these tests assert, so the app-op is
        // normalised to allowed too. NOTE the --uid scoping: on this device the
        // package-scoped form (`appops set PKG OP MODE`) is a silent no-op, and only the
        // uid-scoped form actually takes effect. A previous killed run could otherwise
        // leave the op denied and fail every run that follows.
        shell("appops set --uid $APP_ID READ_PHONE_STATE allow")

        // The guard's trigger path checks earpiece mode before the call state, and the
        // playback under test must not take the mute bail-out.
        if (service.silenced.value) {
            service.toggleSilence()
            silencedWasToggled = true
        }
        if (service.earpieceMode.value) {
            service.toggleEarpieceMode()
            earpieceWasToggled = true
        }
    }

    @After
    fun tearDown() {
        runCatching { shell("appops set --uid $APP_ID READ_PHONE_STATE allow") }
        restoreDefaultCallStateReader()
        runCatching { setPrivateFlag("proximityRegistered", false) }
        runCatching { setPrivateFlag("proximityNearArmed", false) }
        runCatching {
            instrumentation.runOnMainSync { service.stopEverything() }
        }
        if (silencedWasToggled) runCatching { service.toggleSilence() }
        if (earpieceWasToggled) runCatching { service.toggleEarpieceMode() }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /**
     * The declaration itself: READ_PHONE_STATE must be in the built manifest and must be a
     * permission this app can actually be granted (a `pm grant` against an undeclared
     * permission fails). This is the assertion that fails loudly if the manifest line is ever
     * dropped — without it the guard is dead again, silently.
     */
    @Test
    fun readPhoneStateIsDeclaredAndGrantable() {
        val info = context.packageManager.getPackageInfo(APP_ID, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList().orEmpty()
        assertTrue(
            "READ_PHONE_STATE is missing from the built manifest — isPhoneCallActive()'s " +
                "call-state read will throw SecurityException on every trigger and the " +
                "proximity barge-in call guard will silently never skip during a call",
            requested.contains(Manifest.permission.READ_PHONE_STATE)
        )
        shell("pm grant $APP_ID ${Manifest.permission.READ_PHONE_STATE}")
        assertEquals(
            "the declaration exists but the permission could not be granted",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
        )
    }

    /**
     * The root cause: with the permission granted, the REAL telephony read (the exact call
     * the production guard makes) no longer throws, and the guard agrees with what the device
     * reports. Before the manifest declaration this read threw SecurityException on every
     * single trigger.
     */
    @Test
    fun grantedPermissionMakesTheRealCallStateReadWorkAndTheGuardAgreesWithIt() {
        val state = rawCallState()   // must not throw
        assertTrue(
            "the real call-state read returned an unknown state: $state",
            state == TelephonyManager.CALL_STATE_IDLE ||
                state == TelephonyManager.CALL_STATE_RINGING ||
                state == TelephonyManager.CALL_STATE_OFFHOOK
        )
        assertEquals(
            "the guard must report exactly what the device reports",
            state == TelephonyManager.CALL_STATE_OFFHOOK ||
                state == TelephonyManager.CALL_STATE_RINGING,
            service.isPhoneCallActive()
        )
    }

    /**
     * The guard's decision, driven through the production function: only ringing and off-hook
     * are "a call is active"; idle and an unavailable telephony service are not.
     */
    @Test
    fun onlyRingingAndOffHookAreTreatedAsAnActiveCall() {
        setCallStateReaderOnMain { TelephonyManager.CALL_STATE_OFFHOOK }
        assertTrue(
            "an off-hook call must be treated as an active call (otherwise the barge-in " +
                "interrupts playback while the user is on the phone)",
            service.isPhoneCallActive()
        )

        setCallStateReaderOnMain { TelephonyManager.CALL_STATE_RINGING }
        assertTrue("a ringing call must be treated as an active call", service.isPhoneCallActive())

        setCallStateReaderOnMain { TelephonyManager.CALL_STATE_IDLE }
        assertFalse(
            "an idle device must NOT be treated as an active call (that would disable the " +
                "barge-in gesture entirely)",
            service.isPhoneCallActive()
        )

        setCallStateReaderOnMain { null }
        assertFalse(
            "a missing telephony service must be treated as no call",
            service.isPhoneCallActive()
        )
    }

    /**
     * Graceful degradation, on the production catch: any failure of the read — the
     * framework's own SecurityException, a dead telephony service, anything unexpected —
     * resolves to "no call" instead of propagating into the sensor callback. The exception
     * message is the framework's exact one, so this is the refusal a declining user gets.
     */
    @Test
    fun aRefusedCallStateReadDegradesToNoCallInsteadOfThrowing() {
        setCallStateReaderOnMain {
            throw SecurityException(
                "getCallState API requires READ_PHONE_STATE for API version 31+"
            )
        }
        assertFalse(
            "a failed call-state read must degrade to 'no call' — it must never propagate " +
                "into the proximity sensor callback",
            service.isPhoneCallActive()
        )
    }

    /**
     * The user-facing bug, end to end: real playback running, the production proximity
     * confirm runnable fires, and with a call reported active the interrupt is SKIPPED. The
     * second half repeats the identical trigger with no call and asserts the interrupt DOES
     * fire — without it, a guard that always skipped would pass the first half.
     */
    @Test
    fun anActiveCallSkipsTheProximityBargeInDuringRealPlayback() {
        val probe = File(context.cacheDir, "proximity_guard_probe.wav")
        probe.writeBytes(buildSilentPcmWav(seconds = 60, sampleRate = 24_000))
        try {
            instrumentation.runOnMainSync { service.playAudioFile(probe) }
            assertTrue(
                "precondition: the probe playback must reach SPEAKING (focus granted, not silenced)",
                awaitState(AssistantState.SPEAKING)
            )
            assertNotNull("precondition: a live player must exist", service.currentPlayer)

            // A call is off-hook: the runnable must return BEFORE stopEverything().
            setCallStateReaderOnMain { TelephonyManager.CALL_STATE_OFFHOOK }
            armAndFireProximityTrigger()

            assertNotNull(
                "the proximity barge-in interrupted playback while a call was active — the " +
                    "call guard did not skip (this is the reported bug)",
                service.currentPlayer
            )
            assertEquals(
                "the skipped trigger left the service in the wrong state",
                AssistantState.SPEAKING,
                service.assistantState.value
            )

            // Identical trigger, no call in progress: must genuinely interrupt.
            setCallStateReaderOnMain { TelephonyManager.CALL_STATE_IDLE }
            armAndFireProximityTrigger()

            assertNull(
                "the proximity barge-in never fires with no call in progress — the guard is " +
                    "stuck skipping, which would make the gesture useless",
                service.currentPlayer
            )
            assertEquals(
                "the fired trigger must land the service in IDLE",
                AssistantState.IDLE,
                service.assistantState.value
            )
        } finally {
            probe.delete()
        }
    }

    /**
     * The pre-fix dead guard, reproduced on-device without revoking anything: with the
     * permission granted but the app-op not allowed, the framework refuses the read exactly
     * as it did when the permission was missing entirely — and the production guard absorbs
     * that refusal as "no call" rather than throwing. The app-op is restored over `default`
     * in the finally, so the sibling tests are unaffected.
     */
    @Test
    fun aFrameworkRefusedReadStillDegradesGracefully() {
        val setOutput = shell("appops set --uid $APP_ID READ_PHONE_STATE ignore")
        val effective = shell("appops get --uid $APP_ID READ_PHONE_STATE").trim()
        assertTrue(
            "the app-op denial did not take effect, so this test cannot reproduce the " +
                "pre-fix refusal (set said '$setOutput', readback said '$effective')",
            effective.contains("ignore") || effective.contains("deny")
        )
        try {
            val outcome = runCatching { rawCallState() }
            assertTrue(
                "expected the framework to refuse the call-state read once the app-op is " +
                    "denied, but it returned ${outcome.getOrNull()} — the pre-fix dead-guard " +
                    "mechanism would not be reproduced by this test",
                outcome.exceptionOrNull() is SecurityException
            )
            assertFalse(
                "a framework-refused call-state read must degrade to 'no call', not throw",
                service.isPhoneCallActive()
            )
        } finally {
            shell("appops set --uid $APP_ID READ_PHONE_STATE allow")
        }
        // Restored: the real read is live again for the remainder of the run.
        val state = rawCallState()
        assertEquals(
            "the guard must agree with the device again once the app-op is restored",
            state == TelephonyManager.CALL_STATE_OFFHOOK ||
                state == TelephonyManager.CALL_STATE_RINGING,
            service.isPhoneCallActive()
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    /**
     * The production guard's own data source, called directly. This runs in the app's
     * process under the app's uid, so the framework's permission/app-op check sees exactly
     * what the guard sees.
     */
    private fun rawCallState(): Int =
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState

    private fun setCallStateReaderOnMain(reader: () -> Int?) {
        instrumentation.runOnMainSync { service.callStateReader = reader }
    }

    private fun restoreDefaultCallStateReader() {
        val default = defaultCallStateReader ?: return
        runCatching { instrumentation.runOnMainSync { service.callStateReader = default } }
    }

    /**
     * Arms and fires the production trigger.
     *
     * In production the sensor callback arms the private `proximityNearArmed` /
     * `proximityRegistered` flags and then posts the private `proximityConfirmRunnable` to
     * `mainHandler` after the debounce window. Neither the proximity sensor nor those fields
     * can be driven from a test, so they are set by reflection and the runnable is POSTED
     * (not invoked directly) so its execution context matches production exactly. Reflection
     * is safe here because the debug app under test is not minified; a rename of these
     * members fails this test loudly rather than silently passing.
     */
    private fun armAndFireProximityTrigger() {
        instrumentation.runOnMainSync {
            setPrivateFlag("proximityRegistered", true)
            setPrivateFlag("proximityNearArmed", true)
            val trigger = AssistantService::class.java
                .getDeclaredField("proximityConfirmRunnable")
                .apply { isAccessible = true }
                .get(service) as Runnable
            service.mainHandler.post(trigger)
        }
        // Barrier: mainHandler is FIFO on the main looper, so this returns only after the
        // posted trigger — and therefore after any stopEverything() it performed.
        instrumentation.runOnMainSync { }
    }

    private fun setPrivateFlag(name: String, value: Boolean) {
        AssistantService::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .setBoolean(service, value)
    }

    private fun awaitState(expected: AssistantState, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.assistantState.value == expected) return true
            SystemClock.sleep(25)
        }
        return service.assistantState.value == expected
    }

    /** Runs a shell command as the shell user, waits for it to finish, and returns its output. */
    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                String(stream.readBytes(), Charsets.UTF_8)
            }
        }
    }

    private companion object {
        const val APP_ID = "com.nikosm.voiceassistant"

        /**
         * Canonical 44-byte RIFF/WAVE header for 16-bit PCM mono, zero-filled data section
         * (zero-valued 16-bit PCM samples ARE digital silence, and MediaPlayer still reports
         * a real duration). Long enough that the clip cannot finish inside any assertion
         * window — that is what makes "interrupt mid-playback" deterministic.
         */
        fun buildSilentPcmWav(seconds: Int, sampleRate: Int): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = seconds * sampleRate * blockAlign
            val wav = ByteArray(44 + dataSize)

            fun ascii(offset: Int, text: String) {
                text.forEachIndexed { i, c -> wav[offset + i] = c.code.toByte() }
            }

            fun intLe(offset: Int, value: Int) {
                wav[offset] = (value and 0xFF).toByte()
                wav[offset + 1] = ((value shr 8) and 0xFF).toByte()
                wav[offset + 2] = ((value shr 16) and 0xFF).toByte()
                wav[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }

            fun shortLe(offset: Int, value: Int) {
                wav[offset] = (value and 0xFF).toByte()
                wav[offset + 1] = ((value shr 8) and 0xFF).toByte()
            }

            ascii(0, "RIFF")
            intLe(4, 36 + dataSize)
            ascii(8, "WAVE")
            ascii(12, "fmt ")
            intLe(16, 16)
            shortLe(20, 1)                    // PCM
            shortLe(22, channels)
            intLe(24, sampleRate)
            intLe(28, sampleRate * blockAlign)
            shortLe(32, blockAlign)
            shortLe(34, bitsPerSample)
            ascii(36, "data")
            intLe(40, dataSize)
            return wav
        }
    }
}