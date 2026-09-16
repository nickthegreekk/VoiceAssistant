package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pins the Stage-2 (playback fraction / word timestamps / reveal-restart) state contract that
 * the three fixes in this change set rest on:
 *
 *  1. **Fraction/timestamp leak.** `_ttsPlaybackFraction` and `_ttsWordTimestamps` are
 *     per-sequence state, not per-session state: [stopAudio] must clear them. Otherwise a
 *     value left over from an earlier Gateway (chunked) turn satisfies the UI's karaoke gate
 *     (`state == SPEAKING && ttsPlaybackFraction != null && voiceDuration > 0`) during an
 *     unrelated later device-voice (eSpeak/System TTS) playback — suppressing that response's
 *     typewriter reveal and/or painting a nonsense highlight computed from a fraction that
 *     belongs to a response which finished playing long ago.
 *  2. **Replay reveal (device voice).** Replaying a device-voice message must re-arm the
 *     classic timed reveal ([RevealRestartRequest]), and the replay entry point must itself
 *     clear the per-sequence state — System TTS never routes through [stopAudio]
 *     (`speakTextOnDevice` requests focus directly), so relying on that teardown alone would
 *     leave a previous turn's fraction/duration readable as this replay's own. The same entry
 *     point clears the state for a LIVE System-TTS turn (the one playback path with no
 *     stopAudio() in front of it).
 *  3. **Scope guard.** A chunked Gateway replay must NOT arm the classic reveal: that path
 *     owns the response with its own karaoke highlight (and the UI deliberately pins the text
 *     in full while a fraction is live).
 *
 * Why instrumented rather than a JVM unit test: the state under test lives in
 * AssistantService's own StateFlows and the replay entry point hops to the service's
 * Looper-backed Main dispatcher, so a real bound service is the only honest harness (this
 * repo has no Robolectric). The service is bound through the production `AssistantBinder`,
 * exactly like MainActivity does.
 */
@RunWith(AndroidJUnit4::class)
class Stage2RevealStateInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null
    private var messagesBefore: List<ChatMessage> = emptyList()
    private var silencedWasToggled = false

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
        messagesBefore = service._messages.value
    }

    @After
    fun tearDown() {
        // Stop playback/teardown on Main (playback state lives there), then restore what this
        // class touched — the bound service instance outlives this test class, so sibling
        // instrumented tests in the same run must not inherit its probe history.
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { service.stopAudio() }
        }
        runCatching { service._messages.value = messagesBefore }
        runCatching {
            service._ttsPlaybackFraction.value = null
            service._ttsWordTimestamps.value = null
            service._voiceDuration.value = 0
        }
        if (silencedWasToggled) runCatching { service.toggleSilence() }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /**
     * Fix 1: the teardown every playback entry point funnels through must clear the
     * per-sequence Stage-2 state. Mutation: removing the two clears from stopAudio() fails
     * this — which is precisely the leak that let an earlier Gateway turn's fraction bleed
     * into a later device-voice response in the same session.
     */
    @Test
    fun stopAudioClearsTheStage2FractionAndTimestamps() {
        primeChunkedStage2State()

        InstrumentationRegistry.getInstrumentation().runOnMainSync { service.stopAudio() }

        assertStage2StateCleared("stopAudio() teardown")
    }

    /**
     * Fix 1 + Fix 3 at the entry point that bypasses stopAudio(): a device-voice replay must
     * arm the reveal for the replayed (last) message AND clear the per-sequence state, so the
     * replay cannot inherit the previous response's fraction (stale highlight / suppressed
     * reveal) or its duration (wrong reveal timing — System TTS never publishes one).
     */
    @Test
    fun deviceVoiceReplayArmsTheRevealAndClearsStaleStage2State() {
        val message = ChatMessage("assistant", "Replay reveal probe: device voice, fresh reveal")
        service._messages.value = listOf(ChatMessage("user", "probe"), message)
        primeChunkedStage2State()
        // Muted service: speakTextOnDevice() bails before synthesizing (its own landing is
        // covered elsewhere), so the test asserts routing/state without speaking aloud.
        if (!service.silenced.value) {
            service.toggleSilence()
            silencedWasToggled = true
        }
        val tokenBefore = service._revealRestartRequest.value?.token ?: 0L

        service.replayMessageAudio(message, SYSTEM_TTS_PROBE_PERSONA)

        val request = waitForRevealRestartAbove(tokenBefore)
        assertNotNull(
            "no RevealRestartRequest was published for a device-voice replay — the UI would " +
                "show the message in full with no synced reveal (the reported behavior)",
            request
        )
        assertEquals(
            "the request must address the replayed message (it is the last one here)",
            1L,
            request!!.messageIndex.toLong()
        )
        assertTrue(
            "each request needs a fresh token, otherwise a second replay of the same message " +
                "could not re-arm the reveal: ${request.token} vs $tokenBefore",
            request.token > tokenBefore
        )
        assertStage2StateCleared("device-voice replay entry")
    }

    /**
     * Fix 1b: System TTS is the one playback entry point with no stopAudio() in front of it
     * (`speakTextOnDevice` requests focus itself), so it must clear the per-sequence state on
     * its own. Mutation: drop the three clears at the top of speakTextOnDevice() and this
     * fails — leaving an earlier Gateway turn's fraction (the chunked pipeline deliberately
     * ends at 1f) plus an earlier eSpeak turn's duration to satisfy the UI karaoke gate during
     * this playback: suppressed reveal + a highlight computed from the wrong response.
     */
    @Test
    fun systemTtsEntryClearsStaleStage2State() {
        primeChunkedStage2State()
        if (!service.silenced.value) {
            service.toggleSilence()
            silencedWasToggled = true
        }

        // Muted engine: it bails right after the clears, which is the point — the landing must
        // be clean whether or not anything is actually spoken.
        InstrumentationRegistry.getInstrumentation()
            .runOnMainSync { service.speakTextOnDevice("System TTS entry probe") }

        assertStage2StateCleared("System TTS entry (speakTextOnDevice)")
    }

    /**
     * Fix 3 scope guard: the chunked Gateway replay re-synthesizes through its own karaoke
     * pipeline, so it must not publish a reveal-restart request — the UI would run a
     * typewriter over a response that is being karaoke-highlighted instead.
     */
    @Test
    fun gatewayReplayDoesNotArmTheClassicReveal() {
        val message = ChatMessage("assistant", "Gateway replay probe: karaoke owns this one")
        service._messages.value = listOf(ChatMessage("user", "probe"), message)
        val tokenBefore = service._revealRestartRequest.value?.token ?: 0L

        // Closed loopback port: synthesis fails fast (allowGatewayFailover is off by
        // default), so nothing is played — the assertions below are about state, not audio.
        service.replayMessageAudio(message, GATEWAY_PROBE_PERSONA)
        SystemClock.sleep(1_500)

        assertEquals(
            "a chunked Gateway replay must not publish a reveal-restart request (its karaoke " +
                "highlight is the dynamic element)",
            tokenBefore,
            service._revealRestartRequest.value?.token ?: 0L
        )
        assertStage2StateCleared("gateway replay entry (its own stopAudio() teardown)")
    }

    /** Live fraction/timestamps exactly as the chunked player leaves them mid-response. */
    private fun primeChunkedStage2State() {
        service._ttsPlaybackFraction.value = 0.42f
        service._ttsWordTimestamps.value = """[{"word":"probe","start":0.0,"end":0.5}]"""
        service._voiceDuration.value = 12_345
        assertNotNull(
            "precondition: a live fraction must be observable",
            service.ttsPlaybackFraction.value
        )
        assertNotNull(
            "precondition: timestamps must be observable",
            service.ttsWordTimestamps.value
        )
    }

    private fun assertStage2StateCleared(what: String) {
        assertNull(
            "$what — a fraction from an earlier Gateway turn survived: it satisfies the UI " +
                "karaoke gate (SPEAKING && fraction != null && voiceDuration > 0) during an " +
                "unrelated device-voice playback, suppressing the reveal / painting a nonsense " +
                "highlight",
            service.ttsPlaybackFraction.value
        )
        assertNull("$what — stale word timestamps survived", service.ttsWordTimestamps.value)
        assertEquals("$what — stale duration survived", 0L, service.voiceDuration.value.toLong())
    }

    /** Polls for the replay's request — the emission happens on the service's Main looper. */
    private fun waitForRevealRestartAbove(
        tokenBefore: Long,
        timeoutMs: Long = 5_000
    ): RevealRestartRequest? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val current = service._revealRestartRequest.value
            if (current != null && current.token > tokenBefore) return current
            Thread.sleep(25)
        }
        return null
    }

    private companion object {
        // Voice settings only: the muted service never reaches an engine, so nothing here is
        // audible or network-dependent.
        val SYSTEM_TTS_PROBE_PERSONA = Persona(
            name = "RevealRestartProbe",
            themeColor = Color(0xFF4ADE80),
            model = "probe-model",
            systemPrompt = "",
            voiceMode = VoiceMode.SYSTEM_TTS
        )

        val GATEWAY_PROBE_PERSONA = Persona(
            name = "GatewayRevealProbe",
            themeColor = Color(0xFF60A5FA),
            model = "probe-model",
            systemPrompt = "",
            backendUrl = "http://127.0.0.1:9",
            voiceMode = VoiceMode.GATEWAY
        )
    }
}