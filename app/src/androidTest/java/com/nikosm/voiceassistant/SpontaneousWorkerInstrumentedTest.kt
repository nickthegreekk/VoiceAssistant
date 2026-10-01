package com.nikosm.voiceassistant

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spontaneous scheduling — the WorkManager side, proven on-device (step 5).
 *
 * The decision arithmetic is exhaustively covered on the JVM by
 * [SpontaneousSchedulerTest]; what only a device can show is that the pieces AROUND it
 * behave: that `armSpontaneousWork` really leaves a pending unique-work chain, that a run
 * which ends the chain really leaves nothing behind, and that `SpontaneousState` survives a
 * round trip through encrypted storage and rolls its daily counter on a new date.
 *
 * The worker is driven directly through `TestWorkerBuilder`, which constructs it exactly as
 * WorkManager would (same reflective `(Context, WorkerParameters)` lookup) without waiting
 * out a real delay or needing the platform's scheduler. The chains it arms are inspected on
 * the app's REAL WorkManager instance — the one `androidx.startup` initialises in every
 * process — so the assertions describe what production would actually be holding.
 *
 * Every probe persona's window is placed ~12 hours from the wall clock, so a run is always
 * out of hours whatever time the suite happens to execute. That is deliberate: it is the
 * one scenario that proves the re-arm path without the test ever performing a generation
 * (which would need a reachable Ollama server).
 */
@RunWith(AndroidJUnit4::class)
class SpontaneousWorkerInstrumentedTest {

    private companion object {
        const val SERVER = "ProbeWorkerOllama"
        const val ENABLED = "ProbeWorkerEnabled"
        const val DISABLED = "ProbeWorkerDisabled"
        const val GHOST = "ProbeWorkerGhost"
        val PROBES = listOf(ENABLED, DISABLED, GHOST)
        const val HOUR_MS = 60 * 60 * 1000L
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var settings: SettingsManager
    private var personasBefore: List<Persona> = emptyList()
    private var ollamaBefore: List<ServerConfig> = emptyList()

    @Before
    fun setUp() {
        settings = SettingsManager(context)
        personasBefore = settings.getPersonas().orEmpty()
        ollamaBefore = settings.getOllamaBases().orEmpty()
        // A crashed previous run would otherwise leave a chain ticking against a probe name.
        PROBES.forEach { disarmSpontaneousWork(context, it) }
        settings.saveOllamaBases(
            ollamaBefore.filterNot { it.name == SERVER } +
                ServerConfig(SERVER, "http://10.0.0.9:11434")
        )
    }

    @After
    fun tearDown() {
        runCatching {
            PROBES.forEach { disarmSpontaneousWork(context, it) }
            settings.deleteSpontaneousState(SERVER)
            PROBES.forEach { settings.deleteSpontaneousState(it) }
            settings.savePersonas(personasBefore)
            settings.saveOllamaBases(ollamaBefore)
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A one-hour window positioned ~12 hours ahead of the wall clock, so the run under test
     * is always outside it — at any hour the suite happens to run.
     */
    private fun windowFarFromNow(): Pair<Int, Int> {
        val now = spontaneousMinuteOfDay(System.currentTimeMillis(), ZoneId.systemDefault())
        val start = (now + 12 * 60) % 1440
        return start to (start + 60) % 1440
    }

    private fun saveProbe(name: String, enabled: Boolean, window: Pair<Int, Int>? = null) {
        val persona = Persona(
            name = name,
            themeColor = Color(0xFF4ADE80),
            model = "[$SERVER] llama3",
            systemPrompt = "You are $name.",
            isCloud = false,
            spontaneousEnabled = enabled,
            spontaneousGapMinutes = 15,
            spontaneousWindowStartMin = window?.first,
            spontaneousWindowEndMin = window?.second
        )
        settings.savePersonas(
            settings.getPersonas().orEmpty().filterNot { it.name == name } + persona
        )
        // Non-vacuity: the guard must not have stripped the toggle, or every "re-armed"
        // assertion below would be testing a persona the worker cancels on sight.
        val stored = settings.getPersonas()!!.first { it.name == name }
        assertEquals("probe '$name' must round-trip with the toggle it was saved with",
            enabled, stored.spontaneousEnabled)
    }

    /**
     * Run the worker exactly as WorkManager would, and hand back its result.
     *
     * `TestListenableWorkerBuilder` rather than `TestWorkerBuilder`: the latter is bounded to
     * `Worker`, and everything spontaneous is a `CoroutineWorker` (`ListenableWorker`). The
     * default worker factory performs the same reflective `(Context, WorkerParameters)`
     * constructor lookup WorkManager uses in production, so a worker that cannot be built
     * this way could not be built by the platform either.
     */
    private fun buildWorker(personaName: String?): SpontaneousDeliveryWorker {
        // `from(...)` is the public entry point (the constructor is package-private in the
        // Java source), and it is what binds W to the worker class.
        val builder = TestListenableWorkerBuilder.from(context, SpontaneousDeliveryWorker::class.java)
        if (personaName != null) {
            builder.setInputData(workDataOf(EXTRA_PERSONA_NAME to personaName))
        }
        return builder.build()
    }

    private fun runWorker(personaName: String?): String =
        runBlocking { buildWorker(personaName).doWork() }.toString()

    private fun slotChain(name: String, slot: Int): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(spontaneousWorkName(name, slot))
            .get()

    /** Pending = the request is waiting (or running), i.e. the chain is alive. */
    private fun slotPending(name: String, slot: Int): Boolean =
        slotChain(name, slot).any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }

    private fun pendingChainCount(name: String): Int = spontaneousWorkNames(name).count { nameAt ->
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(nameAt).get().any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    // ---------------------------------------------------------------- arm / disarm

    @Test fun `arming leaves exactly one pending chain per persona`() {
        assertEquals("a stale chain would make the assertions below vacuous", 0, pendingChainCount(ENABLED))
        armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS)
        assertTrue(isSpontaneouslyArmed(context, ENABLED))
        assertEquals(1, pendingChainCount(ENABLED))
        assertTrue(slotPending(ENABLED, 0))
    }

    @Test fun `chains stay separated per persona name`() {
        armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS)
        armSpontaneousWork(context, DISABLED, slot = 0, delayMillis = HOUR_MS)
        assertEquals(1, pendingChainCount(ENABLED))
        assertEquals(1, pendingChainCount(DISABLED))
        disarmSpontaneousWork(context, ENABLED)
        assertTrue("disarming one persona must not touch another",
            waitFor { !isSpontaneouslyArmed(context, ENABLED) && isSpontaneouslyArmed(context, DISABLED) })
    }

    @Test fun `re-arming the same slot replaces rather than accumulating`() {
        repeat(3) { armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS) }
        assertEquals("REPLACE must leave a single chain, not three stacked delays",
            1, pendingChainCount(ENABLED))
    }

    @Test fun `disarming clears both slots`() {
        armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS)
        armSpontaneousWork(context, ENABLED, slot = 1, delayMillis = HOUR_MS)
        assertTrue(slotPending(ENABLED, 0))
        assertTrue(slotPending(ENABLED, 1))
        disarmSpontaneousWork(context, ENABLED)
        assertTrue("a leftover slot would resurrect a chain the user switched off",
            waitFor { !slotPending(ENABLED, 0) && !slotPending(ENABLED, 1) })
    }

    /**
     * The regression test for the landmine the slot scheme exists to remove.
     *
     * Under a single `spontaneous/<name>` with REPLACE, arming from inside a run cancels that
     * very run. That cannot be caught by driving the worker directly — a
     * `TestListenableWorkerBuilder` worker has no WorkManager job for the cancel to land on —
     * so the property is asserted where it IS observable: **the name a run arms is never the
     * name it occupies**, which is the only reason the cancel can't happen.
     *
     * This test fails against the single-name design, and would go on failing if
     * [alternateSpontaneousSlot] were ever "simplified" back to returning its argument.
     */
    @Test fun `a re-arm arms the other slot so it cannot cancel its own chain`() {
        armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS)
        assertTrue(slotPending(ENABLED, 0))

        // Exactly what a run in slot 0 does when it re-arms.
        armSpontaneousWork(context, ENABLED, slot = alternateSpontaneousSlot(0), delayMillis = HOUR_MS)

        assertTrue("arming from slot 0 must not cancel slot 0", slotPending(ENABLED, 0))
        assertTrue("the successor must land in the other slot", slotPending(ENABLED, 1))
    }

    @Test fun `isSpontaneouslyArmed sees either slot`() {
        assertFalse(isSpontaneouslyArmed(context, ENABLED))
        armSpontaneousWork(context, ENABLED, slot = 1, delayMillis = HOUR_MS)
        assertTrue("a chain living in slot 1 must still count as armed",
            isSpontaneouslyArmed(context, ENABLED))
    }

    // ---------------------------------------------------------------- the run

    @Test fun `a run for a missing persona ends the chain`() {
        // GHOST is never saved, so the worker's first guard is the one under test: this must
        // be a CANCEL (chain ends), never a re-arm that would keep a dead timer alive forever.
        val result = runWorker(GHOST)
        assertTrue("a scheduler run must never report failure: $result", result.contains("Success"))
        assertEquals("nothing may be left pending for a persona that is gone",
            0, pendingChainCount(GHOST))
        assertEquals("a cancelled run must not fabricate delivery state",
            SpontaneousState(), settings.spontaneousStateFor(GHOST))
    }

    @Test fun `a run for a switched-off persona ends the chain`() {
        saveProbe(DISABLED, enabled = false)
        val result = runWorker(DISABLED)
        assertTrue(result.contains("Success"))
        assertEquals(0, pendingChainCount(DISABLED))
    }

    @Test fun `an out-of-window run is re-armed`() {
        // The control for the two tests above, and the reason they are not vacuous: an
        // eligible, switched-on persona DOES leave a chain behind — so "0 pending" above is
        // the cancel path doing its job, not the worker failing to arm anything at all.
        val window = windowFarFromNow()
        saveProbe(ENABLED, enabled = true, window = window)
        assertEquals(0, pendingChainCount(ENABLED))

        val result = runWorker(ENABLED)
        assertTrue(result.contains("Success"))
        assertTrue(
            "an out-of-window run must arm the next attempt and end this one",
            waitFor { pendingChainCount(ENABLED) == 1 }
        )
        assertEquals("re-arm must not stack chains", 1, pendingChainCount(ENABLED))
        assertEquals(
            "a run that sent nothing must not move lastSentAt",
            SpontaneousState(), settings.spontaneousStateFor(ENABLED)
        )
    }

    @Test fun `repeated out-of-window runs keep one chain`() {
        val window = windowFarFromNow()
        saveProbe(ENABLED, enabled = true, window = window)
        repeat(3) { runWorker(ENABLED) }
        assertTrue(waitFor { pendingChainCount(ENABLED) == 1 })
        assertEquals(1, pendingChainCount(ENABLED))
    }

    @Test fun `an unattributed run arms nothing`() {
        // A request with no persona name is un-attributable: it must not silently adopt some
        // other persona's chain, and it must not keep one alive.
        val result = runWorker(null)
        assertTrue(result.contains("Success"))
        assertEquals(0, pendingChainCount(ENABLED))
        assertEquals(0, pendingChainCount(GHOST))
    }

    // ---------------------------------------------------------------- delivery state

    @Test fun `delivery state rolls its counter on a new date`() {
        settings.deleteSpontaneousState(ENABLED)
        assertEquals(SpontaneousState(), settings.spontaneousStateFor(ENABLED))

        settings.recordSpontaneousSent(ENABLED, at = 1_000L, todayDate = "2026-01-01")
        settings.recordSpontaneousSent(ENABLED, at = 2_000L, todayDate = "2026-01-01")
        val sameDay = settings.spontaneousStateFor(ENABLED)
        assertEquals(2, sameDay.sentTodayCount)
        assertEquals(2_000L, sameDay.lastSentAt)
        assertEquals("2026-01-01", sameDay.sentTodayDate)

        // A new local date must RESET the counter. Storing the date rather than clearing the
        // count elsewhere is what keeps that a pure comparison in the decision function.
        settings.recordSpontaneousSent(ENABLED, at = 3_000L, todayDate = "2026-01-02")
        val nextDay = settings.spontaneousStateFor(ENABLED)
        assertEquals(1, nextDay.sentTodayCount)
        assertEquals("2026-01-02", nextDay.sentTodayDate)

        // And it must survive a fresh SettingsManager, i.e. actually reach the prefs file.
        assertEquals(nextDay, SettingsManager(context).spontaneousStateFor(ENABLED))
    }

    @Test fun `recording one persona spares another`() {
        settings.deleteSpontaneousState(ENABLED)
        settings.deleteSpontaneousState(DISABLED)
        settings.recordSpontaneousSent(ENABLED, at = 5_000L, todayDate = "2026-01-01")
        settings.recordSpontaneousSent(DISABLED, at = 6_000L, todayDate = "2026-01-01")

        assertEquals(5_000L, settings.spontaneousStateFor(ENABLED).lastSentAt)
        assertEquals(6_000L, settings.spontaneousStateFor(DISABLED).lastSentAt)

        settings.deleteSpontaneousState(ENABLED)
        assertEquals("deleting one entry must leave the map intact",
            6_000L, settings.spontaneousStateFor(DISABLED).lastSentAt)
        assertEquals(SpontaneousState(), settings.spontaneousStateFor(ENABLED))
    }
}


