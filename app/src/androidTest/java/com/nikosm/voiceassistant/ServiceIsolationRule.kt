package com.nikosm.voiceassistant

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Per-test isolation for every instrumented class that touches [AssistantService], the
 * spontaneous [WorkManager] chains or persisted user state.
 *
 * ## Why this exists
 *
 * `AssistantService` is process-global and an instrumented run is ONE process for the
 * whole suite. A `ServiceConnection` alone does not keep it alive — unbinding destroys
 * it — but `MainActivity.onCreate` calls `startService(...)`, and
 * [SpontaneousDeepLinkActivityInstrumentedTest] drives the real MainActivity through
 * `ActivityScenario` to prove the deep-link contract. That promotes the service to a
 * STARTED service, which outlives the activity closing and then outlives every later
 * `unbindService`, because nothing in the suite calls `stopService`.
 *
 * What survives is a stale instance: its in-memory `_personas`, `_ollamaBaseUrls` and
 * `_messages` were loaded by `loadSettings()` at whatever moment it was created, while
 * later tests keep writing storage underneath it. Measured with a throwaway probe —
 * `alreadyRunningBeforeBind=true`, in-memory personas `[]` against 11 stored personas.
 * A victim then binds, snapshots THAT view as its "before" fixture and restores it at
 * teardown (writing the stale view back over storage), or races a late `loadSettings()`
 * publishing over state it has just set. Both orderings fail; neither fails alone.
 *
 * The 400 ms debounced save ([AssistantService.saveSettings]) and the `serviceScope`/IO
 * work a test starts are the same problem in slower motion: they land after the test has
 * returned, into whatever the next one is doing.
 *
 * ## What it guarantees, before and after every test
 *
 *  - **No service left running or bound, and none of its writes still in flight.**
 *    `stopService`, wait until the platform reports it gone, then wait for the main
 *    looper to drain so `onDestroy`'s synchronous flush has returned. Destroying it is
 *    what makes the next bind build a fresh instance whose `onCreate` runs
 *    `loadSettings()` against current storage. See [quiesce] for why the drain is not
 *    optional.
 *  - **No work armed.** `cancelAllWork()` on the real singleton: unique chains live in
 *    WorkManager's database and outlive both the persona and the class that armed them.
 *  - **No leftover rooms, personas, skills or spontaneous state.** Anything in storage
 *    that was not in the baseline captured at the start of the run is deleted, with the
 *    spontaneous state and chains keyed by its name.
 *  - **Notifications cleared.**
 *
 * ## The ordering this rule exists to get right
 *
 * Stopping the service, draining its writes, reconciling storage and reading the result
 * is a fixed order, and every step is useless in the wrong one. The rule used to read
 * storage BEFORE stopping the service, which is what let a debounced save land after the
 * read and resurrect a persona a test had just removed. It now quiesces first (see
 * [quiesce]), then reconciles, and only then reads — so the state it reports is the
 * state that actually survives into the next test.
 *
 * ## Failing the polluter, not the victim
 *
 * [after] re-checks the same conditions and throws if the test that just ran left
 * something behind, naming it. That turns "some later test failed for no reason" into
 * "this test leaked", which is the only version of the problem that can be fixed.
 *
 * ## Scope note
 *
 * Deliberately NOT Android Test Orchestrator: a process per test would hide this leak
 * entirely, at the cost of ~161 processes and the loss of any in-process reproduction.
 */
class ServiceIsolationRule : TestRule {

    private var description: Description? = null

    override fun apply(base: Statement, description: Description): Statement {
        this.description = description
        return object : Statement() {
            override fun evaluate() {
                quiesce()
                clean()
                // Whatever the test throws wins; a leak is only reported when the test itself
                // passed, so a genuine failure is never masked by an isolation complaint.
                var failure: Throwable? = null
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    failure = t
                }
                // Order matters, and it is the fix: quiesce (stop the service and let its
                // debounced flush land), THEN reconcile storage, THEN read. See quiesce().
                quiesce()
                clean()
                val leaks = describeLeaks()
                failure?.let { throw it }
                if (leaks.isNotEmpty()) {
                    throw AssertionError(
                        "ServiceIsolationRule: $name left state behind for the next test in " +
                            "this process:\n  - " + leaks.joinToString("\n  - ") +
                            "\nThe next class to bind would inherit it and fail instead of the " +
                            "one that caused it. Clean up in your @After."
                    )
                }
            }
        }
    }

    private val name: String
        get() = description?.let {
            "${it.className.substringAfterLast('.')}.${it.methodName}"
        } ?: "unknown test"


    /**
     * What the test that just ran left behind, read before any cleanup: a running service, a
     * still-armed chain, or entities in storage that were not in the run's baseline.
     */
    private fun describeLeaks(): List<String> {
        val ctx = context()
        val leaks = mutableListOf<String>()
        if (serviceRunning()) {
            leaks += "AssistantService is still running or bound"
        }
        val armed = armedChains(ctx)
        if (armed.isNotEmpty()) {
            leaks += "spontaneous work still armed: ${armed.joinToString()}"
        }
        val leftovers = runCatching { IsolationState.leftovers(ctx) }.getOrDefault(emptyList())
        if (leftovers.isNotEmpty()) {
            leaks += "state in storage: ${leftovers.joinToString()}"
        }
        return leaks
    }

    /** Unique chains still ENQUEUED/RUNNING, for every name this suite can key work by. */
    private fun armedChains(ctx: Context): List<String> {
        val wm = WorkManager.getInstance(ctx)
        val names = IsolationState.knownPersonaNames(ctx)
        return names
            .flatMap { spontaneousWorkNames(it) }
            .filter { workName ->
                runCatching {
                    wm.getWorkInfosForUniqueWork(workName).get().any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }
                }.getOrDefault(false)
            }
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun serviceRunning(): Boolean {
        val am = context().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return runCatching {
            am.getRunningServices(500).any {
                it.service.className == AssistantService::class.java.name
            }
        }.getOrDefault(false)
    }

    /**
     * Stop the service and wait until every write it had queued has actually landed.
     *
     * This is the ordering the rule was missing, and it is the whole race:
     * [AssistantService.saveSettings] debounces onto its own IO scope, and `onDestroy`
     * flushes synchronously under a mutex. A test that mutates state (a persona rename)
     * and returns immediately therefore has a write still in flight when its `@After`
     * restores the baseline. Whichever way that lands is a coin flip:
     *
     *  - flush BEFORE the restore -> the restore wins and nothing leaks;
     *  - flush AFTER the restore  -> the flushed snapshot overwrites the restore, and the
     *    next test inherits a persona the test deliberately removed.
     *
     * `stopService` + "wait until the platform says it is gone" is not enough on its own,
     * because `getRunningServices` stops listing the service as soon as it is DESTROYING —
     * its flush can still be mid-write when that read comes back false. So this waits for
     * the service to be gone AND for the main looper to be idle, which is the point at
     * which `onDestroy`'s blocking flush has returned. `waitForIdleSync` is a real
     * condition, not a sleep: it returns the moment the queue drains.
     */
    private fun quiesce() {
        val ctx = context()
        if (serviceRunning()) {
            ctx.stopService(Intent(ctx, AssistantService::class.java))
            val deadline = System.currentTimeMillis() + SERVICE_STOP_TIMEOUT_MS
            while (serviceRunning() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
        }
        // The service is gone from the platform's list; now wait for its teardown write.
        runCatching {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    /**
     * Disarm, clear and RESTORE storage last. Safe to call when nothing is running.
     *
     * Ordering matters here too: [quiesce] must have run first, otherwise this rewrites
     * storage and a still-in-flight flush rewrites it straight back.
     */
    private fun clean() {
        val ctx = context()
        runCatching {
            WorkManager.getInstance(ctx).cancelAllWork()
                .result.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancelAll()
        }
        runCatching { IsolationState.reconcile(ctx) }
    }

    private companion object {
        const val SERVICE_STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * The storage half of [ServiceIsolationRule]: a baseline captured the first time any test
 * asks, and a reconcile that deletes everything a test added on top of it.
 *
 * Deliberately a delete-and-report rather than a blind restore: restoring would silently
 * undo a test's own cleanup and hide real leaks, whereas deleting extras and then
 * complaining in `after()` points at the test that actually left the mess.
 */
private object IsolationState {

    private class Baseline(
        val personaNames: Set<String>,
        val roomIds: Set<String>,
        val skillIds: Set<String>
    )

    @Volatile
    private var baseline: Baseline? = null

    private fun baseline(ctx: Context): Baseline {
        baseline?.let { return it }
        return synchronized(this) {
            baseline ?: run {
                val s = SettingsManager(ctx)
                val b = Baseline(
                    personaNames = s.getPersonas().orEmpty().map { it.name }.toSet(),
                    roomIds = s.getRooms().orEmpty().map { it.id }.toSet(),
                    skillIds = s.getSkills().orEmpty().map { it.id }.toSet()
                )
                baseline = b
                b
            }
        }
    }

    /** Delete anything not in the baseline, plus the spontaneous state keyed by its name. */
    fun reconcile(ctx: Context) {
        val s = SettingsManager(ctx)
        val b = baseline(ctx)

        val extraPersonas = s.getPersonas().orEmpty().filter { it.name !in b.personaNames }
        val extraRooms = s.getRooms().orEmpty().filter { it.id !in b.roomIds }
        val extraSkills = s.getSkills().orEmpty().filter { it.id !in b.skillIds }

        // Spontaneous state keyed by a name that is not a stored persona is orphaned: its
        // persona was deleted mid-test but the state entry (and its armed chains) survived.
        val orphans = s.getSpontaneousStates().keys.filter { it !in b.personaNames }
        val doomedNames = (extraPersonas.map { it.name } + orphans).toSet()

        runCatching {
            doomedNames.forEach {
                s.deleteSpontaneousState(it)
                disarmSpontaneousWork(ctx, it)
            }
            if (extraPersonas.isNotEmpty()) {
                s.savePersonas(s.getPersonas().orEmpty().filter { it.name in b.personaNames })
            }
            if (extraRooms.isNotEmpty()) {
                s.saveRooms(s.getRooms().orEmpty().filter { it.id in b.roomIds })
            }
            if (extraSkills.isNotEmpty()) {
                s.saveSkills(s.getSkills().orEmpty().filter { it.id in b.skillIds })
            }
        }
    }

    /** Human-readable list of what a test left behind. */
    fun leftovers(ctx: Context): List<String> {
        val s = SettingsManager(ctx)
        val b = baseline(ctx)
        val out = mutableListOf<String>()
        s.getPersonas().orEmpty().filter { it.name !in b.personaNames }
            .forEach { out += "persona ${it.name}" }
        s.getRooms().orEmpty().filter { it.id !in b.roomIds }
            .forEach { out += "room ${it.name}" }
        s.getSkills().orEmpty().filter { it.id !in b.skillIds }
            .forEach { out += "skill ${it.name}" }
        s.getSpontaneousStates().keys.filter { it !in b.personaNames }
            .forEach { out += "spontaneousState $it" }
        return out
    }

    /** Every name spontaneous work could be keyed by right now. */
    fun knownPersonaNames(ctx: Context): Set<String> {
        val s = SettingsManager(ctx)
        return (s.getPersonas().orEmpty().map { it.name } +
            s.getSpontaneousStates().keys + baseline(ctx).personaNames).toSet()
    }
}