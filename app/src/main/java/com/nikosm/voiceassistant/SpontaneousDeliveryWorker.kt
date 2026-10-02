package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.ZoneId
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * Is a chain already pending for [personaName]?
 *
 * The guard that keeps `refreshSpontaneousSchedule` idempotent. WorkManager persists the
 * request across process death and reboot on its own, so re-arming on every service start
 * would be actively harmful: each `REPLACE` restarts the countdown, and a user who opens
 * the app hourly would never see a 4-hour gap elapse. Asking first turns the safety net
 * into a no-op whenever the timer is healthy.
 */
internal fun isSpontaneouslyArmed(context: Context, personaName: String): Boolean =
    runCatching {
        val workManager = WorkManager.getInstance(context)
        spontaneousWorkNames(personaName).any { name ->
            workManager.getWorkInfosForUniqueWork(name).get()
                .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }
    }.getOrDefault(false)

/**
 * Client used ONLY by the reachability probe. Deliberately tiny timeouts — this runs on the
 * path where the answer is most likely "the network is down", and a preflight that waits
 * for a production-quality read timeout would be worse than useless.
 */
private val preflightClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()
}

/**
 * Spontaneous scheduling — the WorkManager side (step 5, plan §2.2 / §7).
 *
 * One **self-rescheduling `OneTimeWorkRequest` per persona**, `Constraints.NONE`. Every run
 * either ends the chain, re-arms it for exactly one computed instant, or performs an attempt
 * and re-arms.
 *
 * The unique name is `spontaneous/<personaName>/<slot>`, with the slot alternating on every
 * hand-off. That detail is not cosmetic: it is what stops a re-arm from cancelling the run
 * that performs it, and its full reasoning is in [spontaneousWorkName].
 *
 * ## Why one-shot and self-rescheduling rather than periodic
 *
 * Periodic work cannot express a delivery window at all, carries a hard 15-minute floor,
 * and would wake the device through the night only to discover it is outside the window.
 * A one-shot with a computed delay states the same thing directly: "next possible moment
 * this persona may speak". The cost is that WorkManager is still not precise — it runs at
 * the next convenient opportunity AFTER the delay — which is why the persona editor calls
 * this a *delivery window* and a *minimum gap* rather than a schedule.
 *
 * WorkManager over AlarmManager chiefly because it is the only candidate that reschedules
 * itself across reboot: this app has no boot receiver, so anything else would silently stop
 * working after a restart. It also needs no custom `Application` class — its default
 * initializer arrives via the `androidx.startup` ContentProvider.
 */

private const val TAG = "Spontaneous"

/** Unique-work prefix. Names are `spontaneous/<personaName>/<slot>` — see [spontaneousWorkName]. */
internal const val SPONTANEOUS_WORK_PREFIX = "spontaneous/"

/** Input key carrying the persona name this run is responsible for. */
internal const val EXTRA_PERSONA_NAME = "personaName"

/** Input key carrying the slot this run occupies. */
internal const val EXTRA_SLOT = "slot"

/**
 * The unique-work name for one *chain* of [personaName].
 *
 * ## Why there are two names per persona instead of one
 *
 * The obvious design — a single `spontaneous/<personaName>` with
 * `ExistingWorkPolicy.REPLACE` — is a landmine. REPLACE cancels **any** uncompleted work
 * holding that name, and the run doing the re-arming holds it too, so every single arm
 * would cancel the run that made it. That is survivable only if the arm is the very last
 * thing the run does, which makes an invisible invariant out of statement ordering: add one
 * `suspend` call after it and the work silently dies mid-flight, with the successor already
 * queued and nothing in the logs.
 *
 * Alternating between two slots removes the constraint instead of documenting it. A run in
 * slot 0 arms slot 1 and vice versa, so REPLACE can never see the caller's own name, and
 * **nothing about a run's statement order matters any more**. [alternateSpontaneousSlot] is
 * the whole rule.
 *
 * The pair is not a per-persona *fan-out*: at steady state exactly one slot holds an
 * uncompleted request, and a run always arms the other. Both are cleaned up together by
 * [disarmSpontaneousWork] / [spontaneousWorkNames].
 */
internal fun spontaneousWorkName(personaName: String, slot: Int): String =
    "$SPONTANEOUS_WORK_PREFIX$personaName/${slot and 1}"

/** Both names belonging to [personaName], for queries and bulk cancellation. */
internal fun spontaneousWorkNames(personaName: String): List<String> =
    listOf(spontaneousWorkName(personaName, 0), spontaneousWorkName(personaName, 1))

/**
 * The slot that run made in [slot] must arm next. An involution, and the same value the run
 * itself did not occupy — which is precisely what makes self-cancellation unrepresentable.
 */
internal fun alternateSpontaneousSlot(slot: Int): Int = (slot and 1) xor 1

/**
 * Arm the next run for [personaName] in [slot] to fire [delayMillis] from now.
 *
 * A pure function of its arguments: no ordering requirement, no interaction with any other
 * run. That is the entire point of the slot scheme in [spontaneousWorkName], and it is why
 * this function carries no contract note about where a caller may put it.
 *
 * **Total by construction (F4).** WorkManager is infrastructure and its calls can throw for
 * reasons outside our control — `getInstance` when the singleton is not initialized,
 * `enqueueUniqueWork` on a shutdown race or an OEM quirk — and neither of this function's
 * callers can afford a propagated throw (see the totality note on [disarmSpontaneousWork]
 * for exactly what each caller loses). A failure here is logged, not propagated, and costs
 * only "the previous chain (if any) stands": the worker's own precondition guards are the
 * backstop, so a stale survivor ends itself instead of ever sending anything.
 */
internal fun armSpontaneousWork(context: Context, personaName: String, slot: Int, delayMillis: Long) {
    if (personaName.isBlank()) return
    runCatching {
        val request = OneTimeWorkRequestBuilder<SpontaneousDeliveryWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.NONE)
            .setInputData(workDataOf(EXTRA_PERSONA_NAME to personaName, EXTRA_SLOT to (slot and 1)))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            spontaneousWorkName(personaName, slot),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }.onSuccess {
        // Only claimed when it actually armed — a swallowed failure must not also log a lie.
        Log.d(TAG, "Armed '$personaName' slot ${slot and 1} in ${delayMillis.coerceAtLeast(0L)}ms")
    }.onFailure {
        Log.w(TAG, "Failed to arm spontaneous work for '$personaName' slot ${slot and 1}", it)
    }
}

/**
 * Drop every chain for [personaName].
 *
 * Cancels BOTH slots, because "which slot is live" is deliberately not knowable from the
 * outside — that is the cost of the scheme in [spontaneousWorkName], and it is paid once,
 * here, rather than at every call site. A run in flight is cancelled too, which is the
 * intended meaning of the three callers (toggle off, persona deleted or renamed): the user
 * changed their mind about something the run was started for.
 *
 * **Total by construction (F4),** same reason as [armSpontaneousWork]. The two call sites
 * are what make a propagated throw here unacceptable, and they fail in opposite ways:
 *
 *  - [refreshSpontaneousSchedule] calls this (via [removeSpontaneousSchedule]) inside
 *    `serviceScope.launch` on Dispatchers.Main with **no CoroutineExceptionHandler**, where an
 *    uncaught throw takes the whole process down — a failed toggle or persona delete would
 *    crash the app, not merely lose a timer;
 *  - the worker's own `rearm()` calls [armSpontaneousWork] from inside `doWork()`, where an
 *    uncaught throw fails that run and silently ends the chain — the one outcome this
 *    feature's guard-and-log design exists to avoid, since the persona then never speaks
 *    again and nothing anywhere says why.
 *
 * A swallowed failure is logged rather than dropped, and it degrades to "the old schedule
 * stands", which the worker's own guards ([evaluateSpontaneousCycle]'s Cancel) then resolve:
 * a surviving chain whose persona is gone, switched off or ineligible ends itself on its
 * next run instead of ever sending anything.
 */
internal fun disarmSpontaneousWork(context: Context, personaName: String) {
    if (personaName.isBlank()) return
    runCatching {
        val workManager = WorkManager.getInstance(context)
        spontaneousWorkNames(personaName).forEach { workManager.cancelUniqueWork(it) }
    }.onFailure {
        Log.w(TAG, "Failed to disarm spontaneous work for '$personaName'", it)
    }
}


// ---------------------------------------------------------------- service binding

/** How long to wait for `AssistantService` to connect before giving up on this run. */
private const val BIND_TIMEOUT_MS = 15_000L

/**
 * Bind `AssistantService` for the duration of [block], then unbind — always, on every path.
 *
 * ## Why binding rather than starting
 *
 * The turn code lives on `AssistantService` because it owns the message store, the live
 * server list and the credential lookup. O1 was explicit that this feature must NOT start
 * the foreground service: its only registered type is `microphone`, which is wrong for a
 * text timer and illegal to start from the background anyway. `bindService(BIND_AUTO_CREATE)`
 * creates or attaches to the service WITHOUT running `onStartCommand`, so `startForeground`
 * (which `AssistantService` only calls from `onStartCommand`) is never reached.
 *
 * When the app is already open this attaches to the live instance, which also means the
 * turn's `PersonaHistorySink` picks up its LIVE mode if the target persona is the one on
 * screen. When it is not, the service is created for the attempt and destroyed on unbind.
 *
 * Binding is deliberately deferred until after the schedule and preflight checks, so the
 * expensive construction (TTS, VAD detector, a pricing sync) only happens when a message is
 * genuinely about to be generated.
 */
internal suspend fun <T> useAssistantService(
    context: Context,
    block: suspend (AssistantService) -> T
): T {
    val connected = CompletableDeferred<AssistantService>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AssistantService.AssistantBinder)?.getService()
            if (service != null) connected.complete(service)
            else connected.completeExceptionally(IllegalStateException("AssistantService binder unavailable"))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected.completeExceptionally(IllegalStateException("AssistantService disconnected"))
        }
    }

    val bound = runCatching {
        context.bindService(
            Intent(context, AssistantService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }.getOrDefault(false)

    if (!bound) {
        runCatching { context.unbindService(connection) }
        throw IllegalStateException("bindService refused for AssistantService")
    }

    try {
        val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() }
            ?: throw IllegalStateException("AssistantService did not connect within ${BIND_TIMEOUT_MS}ms")
        return block(service)
    } finally {
        runCatching { context.unbindService(connection) }
    }
}

// ---------------------------------------------------------------- the worker

/**
 * One scheduler run. Always returns [CoroutineWorker.Result.success] — including when it
 * decides not to send — because a **failed** run would make WorkManager apply its own
 * exponential backoff to a chain that already knows exactly when to come back.
 *
 * The three outcomes from [evaluateSpontaneousCycle] map onto the plan's classification
 * (§2.5) like this:
 *
 * | outcome | action | re-arm? |
 * |---|---|---|
 * | not due (window / gap / caps) | report success | yes, at the computed instant |
 * | precondition gone | report success | **no** — the chain ends |
 * | attempt made, whatever happened | report success | yes, one full gap from now |
 *
 * The last row is where step 4's contract pays off: `runSpontaneousTurn` throws on transport
 * errors and returns normally on silence, so this worker never has to guess which happened.
 * A throw is logged and re-armed; it is never `Result.retry()` (plan §2.5 forbids the
 * backoff, which would desynchronise the gap) and never a catch-up (there is no slot list to
 * replay — the next attempt is computed from this moment).
 *
 * Public (not `internal`) because WorkManager constructs workers reflectively, by looking up
 * a `(Context, WorkerParameters)` constructor on the class named in the request.
 */
class SpontaneousDeliveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val random = Random()

    override suspend fun doWork(): Result {
        val personaName = inputData.getString(EXTRA_PERSONA_NAME)
        if (personaName.isNullOrBlank()) {
            // A request we cannot attribute is not a reason to keep a chain alive.
            return Result.success()
        }
        // Which slot this run occupies. Defaults to 0, which simply means an untagged or
        // hand-built request arms slot 1 — still the other one, so still safe.
        val slot = inputData.getInt(EXTRA_SLOT, 0)

        val settings = SettingsManager(applicationContext)
        val zone = ZoneId.systemDefault()
        val evaluatedAt = System.currentTimeMillis()
        val persona = settings.getPersonas()?.find { it.name == personaName }
        val servers = settings.getOllamaBases().orEmpty()
        val ollamaNames = servers.mapTo(mutableSetOf()) { it.name }
        val cloudNames = (settings.getCloudApis().orEmpty() + settings.getCustomCloudApis().orEmpty())
            .mapTo(mutableSetOf()) { it.name }
        val allStates = settings.getSpontaneousStates()
        val today = spontaneousToday(evaluatedAt, zone)

        // --- 1. resolve & guard (plan §2.2 step 1 / §6 location #4) ------------
        // Everything below re-reads storage and the LIVE server lists, so a persona that
        // was renamed, deleted, re-routed to cloud or had its server removed since this
        // work was enqueued is caught here rather than by an exception later on.
        val decision = evaluateSpontaneousCycle(
            SpontaneousCycleRequest(
                now = evaluatedAt,
                zoneId = zone,
                personaExists = persona != null,
                spontaneousEnabled = persona?.spontaneousEnabled == true,
                eligibility = persona?.spontaneousEligibility(ollamaNames, cloudNames)
                    ?: RoomEligibility.Blocked("The persona no longer exists."),
                state = allStates[personaName] ?: SpontaneousState(),
                gapMillis = SpontaneousPolicy.effectiveGapMinutes(persona?.spontaneousGapMinutes ?: 0) * 60_000L,
                maxPerDay = SpontaneousPolicy.effectiveMaxPerDay(persona?.spontaneousMaxPerDay ?: 0),
                globalSentToday = allStates.values
                    .filter { it.sentTodayDate == today }
                    .sumOf { it.sentTodayCount },
                globalMaxPerDay = SpontaneousPolicy.GLOBAL_MAX_PER_DAY,
                windowStartMin = persona?.spontaneousWindowStartMin,
                windowEndMin = persona?.spontaneousWindowEndMin
            )
        )

        return when (decision) {
            is SpontaneousDecision.Cancel -> {
                // Do NOT cancelUniqueWork: we are that unique work. Returning without
                // re-arming IS the cancel — nothing is left under the name.
                Log.i(TAG, "Spontaneous for '$personaName' cancelled: ${decision.reason}")
                Result.success()
            }

            is SpontaneousDecision.NotDue -> {
                rearm(personaName, decision.nextAttemptAt, persona, zone, slot)
                Result.success()
            }

            SpontaneousDecision.Proceed -> attempt(
                personaName = personaName,
                persona = persona!!,
                settings = settings,
                servers = servers,
                evaluatedAt = evaluatedAt,
                zone = zone,
                slot = slot
            )
        }
    }

    /**
     * Plan §2.2 steps 4–6: **preflight → generate + persist → re-arm from now**.
     *
     * Every exit ends in `Result.success()` and a re-arm one full gap from this moment,
     * except the "server is gone" case, which is a precondition and ends the chain.
     */
    private suspend fun attempt(
        personaName: String,
        persona: Persona,
        settings: SettingsManager,
        servers: List<ServerConfig>,
        evaluatedAt: Long,
        zone: ZoneId,
        slot: Int
    ): Result {
        val baseUrl = resolveSpontaneousTarget(persona, servers).baseUrl
        if (baseUrl == null) {
            // Direct-Ollama eligibility implied a configured server; losing it since then is
            // the same class of precondition as a deletion — no message, and no timer left
            // waiting for a configuration that will not come back on its own.
            Log.w(TAG, "Spontaneous for '$personaName' cancelled — no direct Ollama server")
            return Result.success()
        }

        // --- 4. reachability preflight ---------------------------------------
        // Cheapest possible check, and it runs BEFORE the service is constructed, so an
        // offline phone costs a single short GET rather than TTS/VAD/pricing setup.
        if (!preflightSpontaneousServer(baseUrl, servers, preflightClient)) {
            Log.i(TAG, "Spontaneous for '$personaName' skipped — '$baseUrl' unreachable")
            rearmAfterAttempt(personaName, persona, settings, evaluatedAt, zone, slot)
            return Result.success()
        }

        // --- 5. generate + persist --------------------------------------------
        val turnOutcome: SpontaneousTurnOutcome? = try {
            useAssistantService(applicationContext) { service -> service.runSpontaneousTurn(persona) }
        } catch (cancelled: CancellationException) {
            // The WORK itself is being stopped (constraint lost, app going away). Rethrowing
            // keeps WorkManager's bookkeeping honest and stops us re-arming work we no longer
            // own. Step 4 is why this can only ever be an external cancel, not a swallowed
            // transport error: those are caught by the branch below instead.
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Spontaneous for '$personaName' failed — ${describeSpontaneousFailure(error)}")
            null
        }

        // --- 5b. deliver ------------------------------------------------------
        // Delivery lives here, attached to the turn's OUTCOME, not inside the turn. Only a
        // real send produces a notification — never silence, never a skip, never a failure —
        // and the send-now button never reaches this call at all, because its user is
        // already looking at the conversation the card would open.
        if (turnOutcome is SpontaneousTurnOutcome.Sent) {
            postSpontaneousNotification(applicationContext, personaName, turnOutcome.text)
        }

        // --- 6. re-arm --------------------------------------------------------
        // `runSpontaneousTurn` wrote `lastSentAt` only if a message really went out; silence
        // and failure leave it untouched. Either way the next attempt is one full gap from
        // NOW — never `Result.retry()`, never a replay of a missed slot.
        rearmAfterAttempt(personaName, persona, settings, evaluatedAt, zone, slot)
        return Result.success()
    }

    /**
     * Re-arm one gap after the attempt just made, taking a real send into account when the
     * turn managed one (so the gap is measured from the message, not from when we started).
     */
    private fun rearmAfterAttempt(
        personaName: String,
        persona: Persona,
        settings: SettingsManager,
        attemptedAt: Long,
        zone: ZoneId,
        slot: Int
    ) {
        val now = System.currentTimeMillis()
        val lastActivity = maxOf(attemptedAt, settings.spontaneousStateFor(personaName).lastSentAt)
        val target = nextSpontaneousAttempt(
            lastAttemptAt = lastActivity,
            gapMillis = SpontaneousPolicy.effectiveGapMinutes(persona.spontaneousGapMinutes) * 60_000L,
            now = now,
            zoneId = zone,
            windowStartMin = persona.spontaneousWindowStartMin,
            windowEndMin = persona.spontaneousWindowEndMin
        )
        rearm(personaName, target, persona, zone, slot)
    }

    /**
     * Schedule the next run at absolute instant [targetAt], spreading it by up to
     * [SPONTANEOUS_JITTER_MAX_MS] (dropped when the jitter would leave the window).
     *
     * The destination slot is [alternateSpontaneousSlot] of this run's — never this run's
     * own name, which is what makes it safe to call from anywhere.
     */
    private fun rearm(
        personaName: String,
        targetAt: Long,
        persona: Persona?,
        zone: ZoneId,
        slot: Int
    ) {
        val now = System.currentTimeMillis()
        val at = withSpontaneousJitter(
            base = targetAt,
            zoneId = zone,
            windowStartMin = persona?.spontaneousWindowStartMin,
            windowEndMin = persona?.spontaneousWindowEndMin,
            random = random
        )
        armSpontaneousWork(applicationContext, personaName, alternateSpontaneousSlot(slot), at - now)
    }
}



