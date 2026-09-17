package com.nikosm.voiceassistant

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pins the A4 fix: a request that lands on `AudioManager.AUDIOFOCUS_REQUEST_DELAYED` is
 * retried a small, bounded number of times over a short window (~3 retries, 500ms apart, so
 * ~1.5s worst case) instead of being given up on immediately, while `GRANTED` and `FAILED`
 * keep their exact previous behavior and latency.
 *
 * WHY THIS IS A SEAMED TEST, NOT A REAL DELAYED REQUEST: on Android 13 the framework returns
 * `AUDIOFOCUS_REQUEST_DELAYED` only while it cannot reassign focus — that is, while the top of
 * the audio-focus stack is a LOCKED owner (`MediaFocusControl.canReassignAudioFocus()`), i.e.
 * the telephony voice-comm client during ringing / an off-hook call (`IN_VOICE_COMM_FOCUS_ID`)
 * or a system app holding `AUDIOFOCUS_FLAG_LOCK`. A normal app cannot fabricate either: there
 * is no public API to inject a call, and `AUDIOFOCUS_FLAG_LOCK` is refused to non-system
 * callers. So one `cmd audio` freeze or a real ringing call would be the only way to reach the
 * real thing, and neither is scriptable/repeatable here. The framework answer is therefore
 * supplied through [AssistantService.audioFocusRequester], the single seam the production
 * request path goes through.
 *
 * WHAT IS REAL HERE: the service is bound through the production `AssistantBinder`; every case
 * drives the production `requestAssistantFocus()` / `retryDelayedAudioFocus()` — the retry
 * loop, its bounds, its wait, its exit conditions, the routing, the request-object reuse, the
 * `audioFocusRequest` bookkeeping and the `FAILED` cleanup are all production code; and the
 * request actually re-issued is the production `AudioFocusRequest` (built by the production
 * builder, with its attributes/listener), not a stand-in. The `GRANTED` control case runs
 * against the device's own `MediaFocusControl` with the seam at its default (a real
 * `audioManager.requestAudioFocus()` call), which is what proves the default path is the
 * untouched production call.
 *
 * WHAT IS NOT PROVEN HERE, HONESTLY: that a real locked-focus owner (a ringing call) clears
 * inside the window, since no real call is placed. What is proven is the contract the retry
 * depends on — DELAYED is retried, a non-DELAYED answer inside the window is honored, the
 * window is bounded, and the exhausted case degrades exactly as before. Timing assertions are
 * one-sided lower bounds (a 500ms wait cannot be faked) plus exact framework-call counts.
 */
@RunWith(AndroidJUnit4::class)
class AudioFocusDelayedRetryInstrumentedTest {

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context: Context
        get() = instrumentation.targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    /** The service's own production requester (the real framework call), captured for restore. */
    private lateinit var defaultAudioFocusRequester: (AudioFocusRequest) -> Int

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
        defaultAudioFocusRequester = service.audioFocusRequester

        // Speaker routing keeps the GRANTED branch off the earpiece/communication-device path,
        // so the routing under test is the plain one and no communication device is left
        // selected on the device by this class.
        if (service.earpieceMode.value) {
            service.toggleEarpieceMode()
            earpieceWasToggled = true
        }

        // Start every case from "no focus held": the service can be reused from an earlier test
        // in this process, and the FAILED path's cleanup is asserted against that state.
        service.abandonAssistantFocus()
    }

    @After
    fun tearDown() {
        runCatching {
            instrumentation.runOnMainSync { service.audioFocusRequester = defaultAudioFocusRequester }
        }
        runCatching { service.abandonAssistantFocus() }
        if (earpieceWasToggled) runCatching { service.toggleEarpieceMode() }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /**
     * Installs the scripted framework answers: the Nth framework call gets `answers[N]`, and
     * every call past the end repeats `answers.last()` (so a single value means "always").
     * Returns the recorded request instances, in call order, so the cases can assert both the
     * number of attempts and that each attempt re-issued the SAME request object.
     */
    private fun scriptFocusAnswers(vararg answers: Int): MutableList<AudioFocusRequest> {
        require(answers.isNotEmpty()) { "at least one scripted answer is required" }
        val calls = mutableListOf<AudioFocusRequest>()
        var index = 0
        instrumentation.runOnMainSync {
            service.audioFocusRequester = { request ->
                calls.add(request)
                answers[minOf(index++, answers.size - 1)]
            }
        }
        return calls
    }

    /**
     * Runs the production request path the way production does — on the main thread — and times
     * only the production call itself (the timer starts inside the main-thread runnable, so
     * handler queueing is not measured).
     */
    private fun requestAssistantFocusOnMainThread(): Pair<Boolean, Long> {
        var granted = false
        var elapsed = 0L
        instrumentation.runOnMainSync {
            val startedAt = SystemClock.elapsedRealtime()
            granted = service.requestAssistantFocus()
            elapsed = SystemClock.elapsedRealtime() - startedAt
        }
        return granted to elapsed
    }

    /**
     * The fix's happy path: two DELAYED answers, then a GRANTED. The production path must keep
     * re-issuing the SAME request across the window and honor the GRANTED it lands on — the
     * `true` that also lets playback proceed and applies the speaker routing.
     */
    @Test
    fun delayedThenGranted_isRetriedAndHonoredWithinTheWindow() {
        val calls = scriptFocusAnswers(
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED,
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED,
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        )

        val (granted, elapsed) = requestAssistantFocusOnMainThread()

        assertTrue(
            "a GRANTED arriving inside the retry window must be honored (granted=$granted, elapsed=${elapsed}ms)",
            granted
        )
        assertEquals(
            "the initial attempt must be retried once per DELAYED and stop at the GRANTED — no extra attempts",
            3,
            calls.size
        )
        val held = service.audioFocusRequest
        assertNotNull("the granted request must stay installed on the service", held)
        assertTrue(
            "every attempt must re-issue the SAME request instance the service holds",
            calls.all { it === held }
        )
        assertTrue(
            "the retries must really wait between attempts (elapsed=${elapsed}ms for two 500ms waits)",
            elapsed >= 900
        )
    }

    /**
     * The give-up path, unchanged: a window that stays DELAYED must still report no focus and
     * leave the request registered (the caller logs and lands in IDLE, exactly as before the
     * fix) — the retry only buys time, it does not invent a grant.
     */
    @Test
    fun delayedExhausted_givesUpAfterABoundedWindowExactlyAsBefore() {
        val calls = scriptFocusAnswers(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)

        val (granted, elapsed) = requestAssistantFocusOnMainThread()

        assertFalse("an exhausted window must still report no focus, as before the fix", granted)
        assertEquals(
            "one initial attempt plus 3 bounded retries — never an unbounded loop",
            4,
            calls.size
        )
        assertTrue(
            "the bounded window must really elapse (~1.5s of waits, elapsed=${elapsed}ms)",
            elapsed >= 1400
        )
        assertNotNull(
            "a still-pending delayed request is left registered, exactly as before the fix",
            service.audioFocusRequest
        )
    }

    /**
     * DELAYED then FAILED: the loop must stop at the first non-DELAYED answer, and that FAILED
     * must run the same request cleanup as a first-attempt FAILED (nothing left registered).
     */
    @Test
    fun delayedThenFailed_stopsRetryingAndCleansUp() {
        val calls = scriptFocusAnswers(
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED,
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        )

        val (granted, elapsed) = requestAssistantFocusOnMainThread()

        assertFalse("a FAILED inside the window must still report no focus", granted)
        assertEquals("the loop must stop at the first non-DELAYED answer", 2, calls.size)
        assertNull(
            "a FAILED inside the window must run the same cleanup as a first-attempt FAILED",
            service.audioFocusRequest
        )
        assertTrue("exactly one wait must have elapsed (elapsed=${elapsed}ms)", elapsed >= 400)
    }

    /**
     * Control: a first-attempt GRANTED must be untouched — not retried, and not left sitting in
     * the window. Entering the loop costs at least one 500ms wait, so the elapsed bound below is
     * a real discriminator, not a formality.
     */
    @Test
    fun grantedOnFirstAttempt_neverEntersTheRetryWindow() {
        val calls = scriptFocusAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        val (granted, elapsed) = requestAssistantFocusOnMainThread()

        assertTrue("an uncontended request must still be granted on the first attempt", granted)
        assertEquals("a GRANTED must not be retried", 1, calls.size)
        assertTrue(
            "a GRANTED must not sit in the retry window (elapsed=${elapsed}ms)",
            elapsed < 500
        )
    }

    /**
     * Control: a first-attempt FAILED must be untouched too — not retried, no window latency, and
     * the pre-existing cleanup (drop the request that just failed) still runs.
     */
    @Test
    fun failedOnFirstAttempt_isNotRetriedAndStillClearsTheRequest() {
        val calls = scriptFocusAnswers(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        val (granted, elapsed) = requestAssistantFocusOnMainThread()

        assertFalse("FAILED must still report no focus", granted)
        assertEquals("FAILED must not be retried", 1, calls.size)
        assertTrue(
            "FAILED must not sit in the retry window (elapsed=${elapsed}ms)",
            elapsed < 500
        )
        assertNull(
            "the FAILED path must still drop the request it just installed",
            service.audioFocusRequest
        )
    }

    /**
     * The window is a parameterized decision, not a hidden constant: with attempts=1 and a 100ms
     * delay the loop must make exactly that one attempt and return what it was answered.
     */
    @Test
    fun retryWindowIsParameterizedAndStopsAtItsBound() {
        val calls = scriptFocusAnswers(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener { })
            .build()

        var result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var elapsed = 0L
        instrumentation.runOnMainSync {
            val startedAt = SystemClock.elapsedRealtime()
            result = service.retryDelayedAudioFocus(request, attempts = 1, delayMs = 100L)
            elapsed = SystemClock.elapsedRealtime() - startedAt
        }

        assertEquals(
            "a DELAYED answer to the only retry must be returned as DELAYED",
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED,
            result
        )
        assertEquals("attempts=1 must issue exactly one retry, not the default three", 1, calls.size)
        assertTrue("the requested delay must be honored (elapsed=${elapsed}ms)", elapsed >= 90)
    }

    /**
     * The seam's default, left unscripted: this is the production direct
     * `audioManager.requestAudioFocus(request)` against the device's own MediaFocusControl, so
     * the REAL granted path runs end to end and the default is proven to be the untouched
     * production call rather than a stub.
     */
    @Test
    fun realFrameworkGrantPathIsUnchanged() {
        instrumentation.runOnMainSync { service.audioFocusRequester = defaultAudioFocusRequester }

        val (granted, elapsed) = requestAssistantFocusOnMainThread()

        assertTrue(
            "the device's own framework must grant an uncontended GAIN_TRANSIENT request (granted=$granted)",
            granted
        )
        assertTrue(
            "a real GRANTED must not enter the retry window (elapsed=${elapsed}ms)",
            elapsed < 500
        )
    }
}