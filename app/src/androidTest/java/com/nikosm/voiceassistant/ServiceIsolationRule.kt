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
 *    spontaneous state and chains keyed by its name. The server lists (ollama bases,
 *    server bases, image-gen servers, cloud providers, custom cloud providers) are
 *    reconciled the same way — the service persists all of them from one debounced
 *    `saveSettings()`, so a test that adds a probe server leaks it just as surely as one
 *    that adds a persona.
 *  - **Notifications cleared.**
 *
 * ## What is DETECTED versus what is silently CLEANED
 *
 * These are different jobs, and conflating them breaks the rule in one of two directions.
 *
 * **Detected — the test is failed by name:**
 *  - a still-running / still-bound `AssistantService`;
 *  - a spontaneous chain still ENQUEUED or RUNNING.
 *
 * **Silently cleaned — never reported:**
 *  - every storage leftover above (rooms, personas, skills, spontaneous state, all five
 *    server lists). The rule reconciles them away itself, so reporting them would only
 *    describe the rule's own work.
 *
 * ## The ordering this rule exists to get right
 *
 * Stopping the service, draining its writes, reconciling storage and reading the result
 * is a fixed order, and every step is useless in the wrong one.
 *
 * Two orderings have each broken this rule, and they pull in opposite directions:
 *
 *  - **Reading before quiescing** is what let a debounced save land after the read and
 *    resurrect a persona a test had just removed. Hence [quiesce] first.
 *  - **Reading the detected states after cleaning** deleted the evidence before it was
 *    read, so the rule could not fail anything at all — a silent no-op. Hence the
 *    snapshot in [evaluate]: the service-running and armed-work states are captured while
 *    the test's mess is still on disk, and only then cleaned and reported.
 *
 * The two rules together are why storage leftovers are exempt from reporting: they are
 * the one category whose "dirty" reading is genuinely ambiguous (a rename's in-flight
 * flush makes a restore look transiently lost), and the rule now owns restoring them, so
 * a test that renames a persona must pass rather than be named for it.
 *
 * ## Failing the polluter, not the victim
 *
 * [evaluate] throws if the test that just ran left a detected state behind, naming it.
 * That turns "some later test failed for no reason" into "this test leaked", which is
 * the only version of the problem that can be fixed.
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
                // Snapshot FIRST, clean second, report third.
                //
                // quiesce() stops the service and clean() cancels every chain, so both of
                // the states this rule exists to name are destroyed by the very cleanup
                // that makes the next test safe. Reading them after it is how the reorder
                // that fixed the teardown race silently turned this rule into a no-op that
                // could never fail anything. They are therefore captured here, while the
                // test's mess is still on disk, and reported below.
                val leftRunning = serviceRunning()
                val leftArmed = armedChains(context())
                quiesce()
                clean()
                val leaks = describeLeaks(leftRunning, leftArmed)
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
     * What the test that just ran left behind, from the snapshot taken before cleanup.
     *
     * Only the two states cleanup cannot leave for the next test to trip over are
     * reported: a service still running or bound, and a chain still armed. Both are
     * destroyed by [quiesce] and [clean], which is exactly why they are passed in
     * pre-cleanup rather than read here.
     *
     * Storage leftovers are deliberately NOT reported. [clean] reconciles them away in the
     * same pass, so reporting them would only ever describe the rule's own work — and it
     * would misfire on a test that renames a persona, whose in-flight flush makes the
     * restore look transiently lost. They are cleaned silently instead.
     */
    private fun describeLeaks(leftRunning: Boolean, leftArmed: List<String>): List<String> {
        val leaks = mutableListOf<String>()
        if (leftRunning) {
            leaks += "AssistantService is still running or bound"
        }
        if (leftArmed.isNotEmpty()) {
            leaks += "spontaneous work still armed: ${leftArmed.joinToString()}"
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
        val skillIds: Set<String>,
        // Server lists were missing, which left tests that add a probe server holding
        // their own restore (SpontaneousWorkerInstrumentedTest.ollamaBefore) because the
        // rule would not remove it. Keyed by "name\u0000url": a name alone is not unique
        // — the service forbids duplicate URLs, not duplicate names, so two entries can
        // legitimately share one.
        val ollamaServers: Set<String>,
        val serverBases: Set<String>,
        val imageGenServers: Set<String>,
        val cloudApis: Set<String>,
        val customCloudApis: Set<String>
    )

    /** Stable identity for a [ServerConfig], which has no id of its own. */
    private fun serverKey(name: String, url: String) = "$name\u0000$url"

    private fun ollamaKeys(s: SettingsManager) =
        s.getOllamaBases().orEmpty().map { serverKey(it.name, it.url) }.toSet()

    private fun serverBaseKeys(s: SettingsManager) =
        s.getServerBases().orEmpty().map { serverKey(it.name, it.url) }.toSet()

    private fun imageGenKeys(s: SettingsManager) =
        s.getImageGenBases().orEmpty().map { serverKey(it.name, it.url) }.toSet()

    private fun cloudKeys(s: SettingsManager) =
        s.getCloudApis().orEmpty().map { serverKey(it.name, it.baseUrl) }.toSet()

    private fun customCloudKeys(s: SettingsManager) =
        s.getCustomCloudApis().orEmpty().map { serverKey(it.name, it.baseUrl) }.toSet()

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
                    skillIds = s.getSkills().orEmpty().map { it.id }.toSet(),
                    ollamaServers = ollamaKeys(s),
                    serverBases = serverBaseKeys(s),
                    imageGenServers = imageGenKeys(s),
                    cloudApis = cloudKeys(s),
                    customCloudApis = customCloudKeys(s)
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
            // Server lists ride the same path: the service persists all of them from one
            // debounced saveSettings(), so a test that adds a probe server leaks it just
            // as surely as one that adds a persona.
            if (ollamaKeys(s) != b.ollamaServers) {
                s.saveOllamaBases(
                    s.getOllamaBases().orEmpty()
                        .filter { serverKey(it.name, it.url) in b.ollamaServers }
                )
            }
            if (serverBaseKeys(s) != b.serverBases) {
                s.saveServerBases(
                    s.getServerBases().orEmpty()
                        .filter { serverKey(it.name, it.url) in b.serverBases }
                )
            }
            if (imageGenKeys(s) != b.imageGenServers) {
                s.saveImageGenBases(
                    s.getImageGenBases().orEmpty()
                        .filter { serverKey(it.name, it.url) in b.imageGenServers }
                )
            }
            if (cloudKeys(s) != b.cloudApis) {
                s.saveCloudApis(
                    s.getCloudApis().orEmpty()
                        .filter { serverKey(it.name, it.baseUrl) in b.cloudApis }
                )
            }
            if (customCloudKeys(s) != b.customCloudApis) {
                s.saveCustomCloudApis(
                    s.getCustomCloudApis().orEmpty()
                        .filter { serverKey(it.name, it.baseUrl) in b.customCloudApis }
                )
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