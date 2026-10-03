package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private companion object {
        const val SERVER = "ProbeWorkerOllama"
        const val ENABLED = "ProbeWorkerEnabled"
        const val DISABLED = "ProbeWorkerDisabled"
        const val GHOST = "ProbeWorkerGhost"
        const val DELETE = "ProbeWorkerDelete"
        const val RENAME_OLD = "ProbeWorkerRenameOld"
        const val RENAME_NEW = "ProbeWorkerRenameNew"
        val PROBES = listOf(ENABLED, DISABLED, GHOST, DELETE, RENAME_OLD, RENAME_NEW)
        const val HOUR_MS = 60 * 60 * 1000L
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var settings: SettingsManager
    private var ollamaBefore: List<ServerConfig> = emptyList()

    @Before
    fun setUp() {
        settings = SettingsManager(context)
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
            // No savePersonas/saveOllamaBases restore HERE. This @After runs while the
            // service is still alive, and a rename in the test body leaves a debounced
            // saveSettings() in flight: that flush lands AFTER this restore and puts the
            // renamed persona straight back, which is exactly the ServiceIsolationRule
            // failure this used to cause intermittently. ServiceIsolationRule now stops
            // the service, drains that flush and reconciles storage to the run baseline
            // itself, in that order, so restoring here was both redundant and harmful.
            // ollamaBefore is still captured and applied in setUp: this class adds a
            // probe server there, and the rule's baseline does not cover server lists.
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

    private fun probePersona(name: String, enabled: Boolean, window: Pair<Int, Int>? = null): Persona =
        Persona(
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

    private fun saveProbe(name: String, enabled: Boolean, window: Pair<Int, Int>? = null) {
        val persona = probePersona(name, enabled, window)
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

    // ------------------------------------------------- service-level lifecycle helpers (F1/F2)

    private var serviceConnection: ServiceConnection? = null

    /**
     * Bind the REAL AssistantService, the same way production binds it. The cases that use
     * this drive the service's own lifecycle methods (removePersona, updatePersona,
     * importBackup) rather than the SettingsManager/WorkManager primitives, because what
     * they pin is wiring: that the service paths route the persona's spontaneous residue
     * through the shared cleanup surfaces.
     */
    private fun bindAssistantService(): AssistantService {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { /* not expected */ }
        }
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        serviceConnection = conn
        return boundService!!
    }

    private fun unbindAssistantService() {
        serviceConnection?.let { runCatching { context.unbindService(it) } }
        serviceConnection = null
    }

    /** Run [block] on the main thread and hand its result back (the service's StateFlow writes
     *  and lifecycle methods are main-thread affairs, same as the sibling test classes). */
    private fun <T> onMain(block: () -> T): T {
        val result = arrayOfNulls<Any?>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }

    /** The ids of [name]'s pending (enqueued or running) requests — a chain's identity. */
    private fun pendingWorkIds(name: String): Set<UUID> =
        spontaneousWorkNames(name).flatMap { workName ->
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get()
        }.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            .map { it.id }
            .toSet()

    // ---------------------------------------------------------------- arm / disarm

    @Test fun `arming leaves exactly one pending chain per persona`() {
        assertEquals("a stale chain would make the assertions below vacuous", 0, pendingChainCount(ENABLED))
        armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS)
        // Every read below waits on the condition rather than firing once: enqueueUniqueWork
        // commits asynchronously, so an immediate read races its own insert.
        assertTrue(waitFor { isSpontaneouslyArmed(context, ENABLED) })
        assertTrue(waitFor { pendingChainCount(ENABLED) == 1 })
        assertTrue(waitFor { slotPending(ENABLED, 0) })
    }

    @Test fun `chains stay separated per persona name`() {
        armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS)
        armSpontaneousWork(context, DISABLED, slot = 0, delayMillis = HOUR_MS)
        assertTrue(waitFor { pendingChainCount(ENABLED) == 1 && pendingChainCount(DISABLED) == 1 })
        disarmSpontaneousWork(context, ENABLED)
        assertTrue("disarming one persona must not touch another",
            waitFor { !isSpontaneouslyArmed(context, ENABLED) && isSpontaneouslyArmed(context, DISABLED) })
    }

    @Test fun `re-arming the same slot replaces rather than accumulating`() {
        repeat(3) { armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS) }
        assertTrue("REPLACE must leave a single chain, not three stacked delays",
            waitFor { pendingChainCount(ENABLED) == 1 })
    }

    @Test fun `disarming clears both slots`() {
        armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS)
        armSpontaneousWork(context, ENABLED, slot = 1, delayMillis = HOUR_MS)
        assertTrue(waitFor { slotPending(ENABLED, 0) && slotPending(ENABLED, 1) })
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
        assertTrue(waitFor { slotPending(ENABLED, 0) })

        // Exactly what a run in slot 0 does when it re-arms.
        armSpontaneousWork(context, ENABLED, slot = alternateSpontaneousSlot(0), delayMillis = HOUR_MS)

        assertTrue("arming from slot 0 must not cancel slot 0", waitFor { slotPending(ENABLED, 0) })
        assertTrue("the successor must land in the other slot", waitFor { slotPending(ENABLED, 1) })
    }

    @Test fun `isSpontaneouslyArmed sees either slot`() {
        assertFalse(isSpontaneouslyArmed(context, ENABLED))
        armSpontaneousWork(context, ENABLED, slot = 1, delayMillis = HOUR_MS)
        assertTrue("a chain living in slot 1 must still count as armed",
            waitFor { isSpontaneouslyArmed(context, ENABLED) })
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

    // -------------------------------------------------- F1: the service-level lifecycle wiring
    // The JVM cases in SettingsManagerCorruptionTest prove the shared cleanup surfaces
    // themselves; what only a device can add is that AssistantService's own persona
    // lifecycle methods really route through them.

    @Test fun `a deletion through the real removePersona drops the spontaneous state with it`() {
        val service = bindAssistantService()
        try {
            // Arm the probe explicitly (armSpontaneousWork, the primitive the rest of this
            // class uses) rather than relying on addPersona's internal arm, which races the
            // debounced settings persist — a pre-existing quirk outside F1's scope. What is
            // under test is the teardown, not how the chain came to exist.
            onMain { service.addPersona(probePersona(DELETE, enabled = true, window = windowFarFromNow())) }
            armSpontaneousWork(context, DELETE, slot = 0, delayMillis = HOUR_MS)
            assertTrue("precondition: the probe must hold a live chain",
                waitFor { pendingChainCount(DELETE) == 1 })
            settings.recordSpontaneousSent(DELETE, at = 9_000L, todayDate = "2026-01-01")
            assertEquals("precondition: the probe really carries state",
                9_000L, settings.spontaneousStateFor(DELETE).lastSentAt)

            val index = service.personas.value.indexOfFirst { it.name == DELETE }
            assertTrue("the added probe must be in the service's list", index >= 0)
            onMain { service.removePersona(index) }

            assertTrue(
                "the deleted persona's state entry must die with it (removePersona → deletePersonaHistory)",
                waitFor { settings.spontaneousStateFor(DELETE) == SpontaneousState() }
            )
            assertFalse("the state map must not carry the deleted persona", DELETE in settings.getSpontaneousStates())
            assertTrue("and its chain must be torn down with it",
                waitFor { pendingChainCount(DELETE) == 0 })
        } finally {
            unbindAssistantService()
        }
    }

    @Test fun `a rename through the real updatePersona carries the spontaneous state to the new name`() {
        val service = bindAssistantService()
        try {
            onMain { service.addPersona(probePersona(RENAME_OLD, enabled = true, window = windowFarFromNow())) }
            armSpontaneousWork(context, RENAME_OLD, slot = 0, delayMillis = HOUR_MS)
            // Waited, not read straight after arming: enqueueUniqueWork() returns an
            // Operation that commits asynchronously, so an immediate read can see the
            // work database before its own insert lands. That is a race in the READ, not
            // a slow arm, and it is why every other precondition in this class polls
            // (see waitFor). Same bound, same helper - no new timeout.
            assertTrue("precondition: the probe must hold a live chain",
                waitFor { pendingChainCount(RENAME_OLD) == 1 })
            settings.recordSpontaneousSent(RENAME_OLD, at = 5_000L, todayDate = "2026-03-03")
            settings.recordSpontaneousSent(RENAME_OLD, at = 6_000L, todayDate = "2026-03-03")
            val before = settings.spontaneousStateFor(RENAME_OLD)
            assertEquals("precondition: the probe really has pacing to lose", 2, before.sentTodayCount)

            val index = service.personas.value.indexOfFirst { it.name == RENAME_OLD }
            assertTrue("the added probe must be in the service's list", index >= 0)
            onMain {
                service.updatePersona(index, service.personas.value[index].copy(name = RENAME_NEW))
            }

            // The migration itself is synchronous inside updatePersona's rename branch, so
            // these are direct reads, not waits: lastSentAt and sentTodayCount must ARRIVE
            // under the new name, never reset to "never sent".
            val moved = settings.spontaneousStateFor(RENAME_NEW)
            assertEquals("lastSentAt must survive the rename", before.lastSentAt, moved.lastSentAt)
            assertEquals("and the daily counter", before.sentTodayCount, moved.sentTodayCount)
            assertEquals("and the date that counter belongs to", before.sentTodayDate, moved.sentTodayDate)
            assertEquals("the old key must be gone",
                SpontaneousState(), settings.spontaneousStateFor(RENAME_OLD))
            assertTrue("the old name's chain must be disarmed",
                waitFor { pendingChainCount(RENAME_OLD) == 0 })
        } finally {
            unbindAssistantService()
        }
    }

    // -------------------------------------------------- F2: the import reconciliation
    // importBackup on the REAL service: loadSettings' safety net only ever arms the
    // un-armed, so without the post-import reconciliation WorkManager keeps holding the
    // PRE-import schedule. These cases pin both halves of the fix on the app's real
    // WorkManager instance.

    @Test fun `an import that changes the gap re-arms the chain fresh rather than on the old schedule`() {
        saveProbe(ENABLED, enabled = true, window = windowFarFromNow())
        // Capture the backup BEFORE touching the service (see the comment at the probe
        // publish below for why the order matters).
        val export = settings.exportBackup()
        val service = bindAssistantService()
        try {
            // An earlier class's MainActivity leaves the service STARTED for the whole
            // instrumentation process, so this bind can attach to an instance whose
            // in-memory lists predate this class's setUp. The reconciliation needs the
            // probe in the service's OWN persona list (that snapshot is what it compares
            // the restored set against) and the probe's server in its OWN ollama list (that
            // is what eligibility is judged against), so publish both here — addOllamaBase
            // is URL-guarded, so this is a no-op when the view is already fresh.
            // Order note: publishing the probe can strip its opt-in on the way in, because
            // the service's server list may only gain the probe server a moment later —
            // which is exactly why `export` was taken BEFORE this, and why the import
            // (which overwrites the stored persona list from the captured payload) is the
            // thing that decides the restored state.
            if (service.personas.value.none { it.name == ENABLED }) {
                onMain { service.addPersona(probePersona(ENABLED, enabled = true, window = windowFarFromNow())) }
            }
            onMain { service.addOllamaBase(SERVER, "http://10.0.0.9:11434") }
            // The armed precondition, established through the scheduling primitive: what is
            // under test is what the import does to an ALREADY armed chain, and arming
            // directly keeps the precondition independent of the eligibility-aware paths'
            // timing. (A REPLACE from the publishes above is harmless — it lands before the
            // ids below are captured, and anything landing after can only leave a fresh id
            // pending, which the assertions below accept.)
            armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS)
            assertTrue("precondition: the probe must hold a live chain before the import",
                waitFor { pendingChainCount(ENABLED) == 1 })
            val armedByLoad = pendingWorkIds(ENABLED)
            assertEquals(1, armedByLoad.size)

            // Hand-craft the imported backup from the export captured above: this device's
            // own payload, with only the probe's gap changed (15 → 45) — the scenario of
            // restoring an edited backup over a live install. Anchored on the probe's own
            // object so nothing else moves.
            assertTrue("precondition: the probe must carry the gap being edited",
                Regex("\"name\":\"$ENABLED\".*?\"spontaneousGapMinutes\":15").containsMatchIn(export))
            // Transform-based replacement rather than a "$1 45" backreference string: a
            // digit directly after the group reference would be read as part of the group
            // number, and a separating space would leak whitespace into the JSON. The
            // probe's name occurs exactly once in the export, so replace == replaceFirst.
            val modified = Regex("(\"name\":\"$ENABLED\".*?\"spontaneousGapMinutes\":)15")
                .replace(export) { it.groupValues[1] + "45" }
            assertTrue(onMain { service.importBackup(modified) })
            // Non-vacuity on the import side: the restore really landed the new gap.
            assertEquals("the restored probe must carry the imported gap", 45,
                settings.getPersonas()!!.first { it.name == ENABLED }.spontaneousGapMinutes)

            // The reconciliation is async (service coroutines + WorkManager), so poll.
            // The identity assertions are the point: the OLD request — the one carrying the
            // pre-import schedule — must no longer be pending, and a NEW request (the forced
            // now + gap arm) must be pending in its place. WorkInfo exposes no delay, so
            // the fresh timing itself is pinned at the JVM layer (SpontaneousSchedulerTest's
            // nextSpontaneousAttempt cases); what only the device can show is that the
            // stale request was actually dropped instead of running out. (REPLACE cancels
            // AND DELETES the pending request it replaces, so unlike the disarm-only case
            // the old id may be gone from the unique-work history entirely — "no longer
            // pending" is the contract, not "queryable as CANCELLED".)
            assertTrue("the old schedule must be dropped, not left to finish its stale cycle",
                waitFor { pendingChainCount(ENABLED) == 1 && armedByLoad.none { it in pendingWorkIds(ENABLED) } })
            val reArmed = pendingWorkIds(ENABLED)
            assertEquals(1, reArmed.size)
            assertTrue("the re-arm must be a NEW request, not the old one",
                armedByLoad.none { it in reArmed })
        } finally {
            unbindAssistantService()
        }
    }

    @Test fun `an import that disables a persona disarms its chain immediately`() {
        saveProbe(ENABLED, enabled = true, window = windowFarFromNow())
        val export = settings.exportBackup()
        val service = bindAssistantService()
        try {
            // Same live-service-safe publishes as the case above (probe into the service's
            // own persona list, probe server into its own ollama list, export captured
            // before either) — this case's whole point is that an armed persona the import
            // switches off must be disarmed, so the chain really has to exist first and
            // the persona really has to be one the reconciliation is tracking.
            if (service.personas.value.none { it.name == ENABLED }) {
                onMain { service.addPersona(probePersona(ENABLED, enabled = true, window = windowFarFromNow())) }
            }
            onMain { service.addOllamaBase(SERVER, "http://10.0.0.9:11434") }
            armSpontaneousWork(context, ENABLED, slot = 0, delayMillis = HOUR_MS)
            assertTrue("precondition: the probe must be armed before the import",
                waitFor { pendingChainCount(ENABLED) == 1 })
            val armedBeforeImport = pendingWorkIds(ENABLED)
            assertEquals(1, armedBeforeImport.size)

            assertTrue("precondition: the probe must carry the toggle being flipped",
                Regex("\"name\":\"$ENABLED\".*?\"spontaneousEnabled\":true").containsMatchIn(export))
            val modified = Regex("(\"name\":\"$ENABLED\".*?\"spontaneousEnabled\":)true")
                .replace(export) { it.groupValues[1] + "false" }
            assertTrue(onMain { service.importBackup(modified) })
            // Non-vacuity on the import side: the restore really switched the persona off.
            assertFalse("the restored probe must really be switched off",
                settings.getPersonas()!!.first { it.name == ENABLED }.spontaneousEnabled)

            // Without the reconciliation this chain stays armed and only ends when its next
            // run fires, finds the persona switched off, and self-cancels — one stale
            // execution away. The fix tears it down with the import instead. Nothing
            // pending under EITHER slot is the contract; how WorkManager files the request
            // it cancelled (a later REPLACE deletes the record outright) is not this test's
            // business.
            assertTrue("the chain must be gone, not left to fire once and self-cancel",
                waitFor { pendingChainCount(ENABLED) == 0 })
        } finally {
            unbindAssistantService()
        }
    }
}


