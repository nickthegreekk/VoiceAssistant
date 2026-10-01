package com.nikosm.voiceassistant

import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Random
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Spontaneous scheduling — the PURE decision layer (step 5, plan §2.2 / §7).
 *
 * ## Why this file has no Android and no network (mostly)
 *
 * Every judgement the scheduler makes is arithmetic on time, preferences and the live
 * guard verdict. Keeping those in one deterministic, side-effect-free module means the
 * interesting behaviour — the window's midnight wrap, the cap's date rollover, and above
 * all the no-catch-up rule — is exhaustively testable on the JVM without WorkManager,
 * without a device and without waiting a single minute.
 *
 * ## The three outcomes, and why classification is total
 *
 * Step 4 made the turn propagate transport errors instead of swallowing them, so a
 * scheduler run lands in exactly one of three places and never in between:
 *
 *  - [SpontaneousDecision.Cancel] — a PRECONDITION is gone (persona deleted, toggle off,
 *    now cloud, now gateway-only, server removed). Nothing is sent and the work is NOT
 *    re-armed; this is the only path that stops the timer.
 *  - [SpontaneousDecision.NotDue] — a SCHEDULE condition (window closed, gap running,
 *    daily cap). Nothing is sent and the work IS re-armed at exactly
 *    [SpontaneousDecision.NotDue.nextAttemptAt].
 *  - [SpontaneousDecision.Proceed] — every gate passed; go and try. If the attempt then
 *    fails at the transport layer it is an INFRASTRUCTURE failure, classified by the
 *    worker, never here.
 *
 * The split is deliberate: preconditions are checked BEFORE any network I/O, so a deleted
 * persona can never be mistaken for an offline server, and an offline server can never
 * silently disable the feature the way [SpontaneousDecision.Cancel] would.
 */

/** What one scheduler run decided. See the file header for the classification contract. */
internal sealed interface SpontaneousDecision {
    /** Precondition gone: send nothing AND drop the work — do not re-arm. */
    data class Cancel(val reason: String) : SpontaneousDecision

    /**
     * Not time yet: nothing is attempted and the work is re-armed at EXACTLY this instant.
     * Never a duration — so a caller cannot misinterpret it as one.
     */
    data class NotDue(val nextAttemptAt: Long) : SpontaneousDecision

    /** Every gate passed: attempt generation now. */
    data object Proceed : SpontaneousDecision
}

/**
 * Everything [evaluateSpontaneousCycle] reads. A single request object rather than ten
 * positional arguments, so a test can say what it means (`.copy(now = ...)`) instead of
 * counting parameters.
 */
internal data class SpontaneousCycleRequest(
    /** Epoch millis "now". */
    val now: Long,
    /** Device time zone — injected, never [ZoneId.systemDefault()], so tests are stable. */
    val zoneId: ZoneId,
    /** False when the persona was deleted/renamed since the work was enqueued. */
    val personaExists: Boolean,
    /** The stored toggle at THIS moment, not at enqueue time. */
    val spontaneousEnabled: Boolean,
    /** Recomputed against the LIVE server lists (plan §6 location #4). */
    val eligibility: RoomEligibility,
    /** Persisted delivery state for this persona. */
    val state: SpontaneousState,
    /** [SpontaneousPolicy.effectiveGapMinutes] converted to millis by the caller. */
    val gapMillis: Long,
    /** [SpontaneousPolicy.effectiveMaxPerDay]. */
    val maxPerDay: Int,
    /**
     * Sum of [SpontaneousState.sentTodayCount] across EVERY persona for today's local date
     * — the O10 global budget. The caller sums it; this module only compares it.
     */
    val globalSentToday: Int,
    /** [SpontaneousPolicy.GLOBAL_MAX_PER_DAY]. */
    val globalMaxPerDay: Int,
    /** Window start in minutes-from-midnight; null = no lower bound. */
    val windowStartMin: Int?,
    /** Window end in minutes-from-midnight; null = no upper bound. */
    val windowEndMin: Int?
)

// ---------------------------------------------------------------- time helpers

/** Device-local minute-of-day (0..1439) for [now]. */
internal fun spontaneousMinuteOfDay(now: Long, zoneId: ZoneId): Int =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId).let { it.hour * 60 + it.minute }

/** Device-local `yyyy-MM-dd` for [now] — the key [SpontaneousState.sentTodayDate] stores. */
internal fun spontaneousToday(now: Long, zoneId: ZoneId): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId).toLocalDate().toString()

/**
 * Is [minuteOfDay] inside the persona's delivery window?
 *
 * The window is OPTIONAL at both ends and may wrap past midnight:
 *
 *  - both null → always allowed (the default; no window configured);
 *  - start only → "after X";
 *  - end only → "before Y";
 *  - start == end → treated as the whole day, matching the editor's "from X back to X"
 *    affordance rather than collapsing to "never" (a zero-width window would be a silent,
 *    permanent off switch);
 *  - start > end → an OVERNIGHT window (e.g. 22:00 → 06:00), exactly the configuration a
 *    person who messages at night would pick. Forgetting this branch is the classic bug
 *    that makes evening windows never fire, so it is called out and tested.
 *
 * Both bounds are inclusive: the sliders step in 30-minute increments and a user who sets
 * "no messages after 22:00" should not be surprised by a message at 21:30, nor robbed of
 * one landing exactly at 22:00.
 *
 * Implemented by widening a null bound to an open-ended sentinel rather than with nested
 * `when` branches, because Kotlin cannot smart-cast `Int?` through a chain of `&&` tests —
 * and the sentinel form collapses every case, including the wrap, into one comparison.
 */
internal fun isInSpontaneousWindow(minuteOfDay: Int, start: Int?, end: Int?): Boolean {
    // No bound at all widens to the whole representable range, which is also why the
    // both-null case needs no separate branch: it is simply always inside.
    val from = start ?: Int.MIN_VALUE
    val to = end ?: Int.MAX_VALUE
    if (from == to) return true // a "zero-width" window is the whole day, not an off switch
    return if (from <= to) minuteOfDay in from..to
    else minuteOfDay >= from || minuteOfDay <= to // start > end: wraps past midnight
}

/**
 * The next instant STRICTLY AFTER [now] at which the local clock reads [startMinute]
 * (minutes from midnight). With no configured start, that is the next local midnight —
 * the only boundary an unwindowed persona has.
 */
internal fun nextWindowOpen(now: Long, zoneId: ZoneId, startMinute: Int?): Long {
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId)
    if (startMinute == null) {
        return local.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    }
    val todayAtStart = local.toLocalDate().atTime(startMinute / 60, startMinute % 60)
    val candidate = if (todayAtStart.isAfter(local)) todayAtStart else todayAtStart.plusDays(1)
    return candidate.atZone(zoneId).toInstant().toEpochMilli()
}

/** Move [candidate] to the next window open if it does not already fall inside the window. */
internal fun clampIntoWindow(
    candidate: Long,
    zoneId: ZoneId,
    startMinute: Int?,
    endMinute: Int?
): Long = if (isInSpontaneousWindow(spontaneousMinuteOfDay(candidate, zoneId), startMinute, endMinute)) {
    candidate
} else {
    nextWindowOpen(candidate, zoneId, startMinute)
}

/**
 * The next instant at which the local DATE changes (midnight), still inside the window if
 * there is one. This is what a reached daily cap waits for — the cap is stored as a date,
 * so its reset moment is midnight, not "start + 24h from whenever we happened to ask".
 */
internal fun nextDailyReset(now: Long, zoneId: ZoneId, startMinute: Int?, endMinute: Int?): Long {
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId)
    val midnight = local.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    return clampIntoWindow(midnight, zoneId, startMinute, endMinute)
}

/**
 * The no-catch-up rule, in one expression.
 *
 * The next attempt is `max(lastAttemptAt + gap, now)` — never `lastAttemptAt + gap` alone.
 * That single `max` is what makes a phone that slept for two days fire exactly ONCE when it
 * wakes instead of replaying every slot it slept through: an already-elapsed gap collapses
 * onto `now` rather than pointing into the past.
 *
 * [lastAttemptAt] is "the last time this persona was engaged with", and the SAME function
 * serves all three callers precisely because of the `max`:
 *
 *  - the decision's gap branch passes `state.lastSentAt` (the gap is running, so the sum is
 *    in the future and the `max` never engages);
 *  - the worker, after ANY attempt, passes the attempt time — a send, a silence and a
 *    transport failure all re-arm a full gap from now, so a persona that keeps saying
 *    nothing and a server that stays offline each cost at most one probe per gap instead of
 *    turning into a tight retry loop;
 *  - arming a freshly-enabled persona passes `now`, so the FIRST message arrives after one
 *    gap rather than the instant the toggle is flipped.
 *
 * The state only ever records real sends (`SettingsManager.recordSpontaneousSent`), so
 * silence never pushes `lastSentAt` forward — the pacing lives in the re-arm, not in the
 * stored counter.
 */
internal fun nextSpontaneousAttempt(
    lastAttemptAt: Long,
    gapMillis: Long,
    now: Long,
    zoneId: ZoneId,
    windowStartMin: Int?,
    windowEndMin: Int?
): Long = clampIntoWindow(
    candidate = maxOf(lastAttemptAt + gapMillis, now),
    zoneId = zoneId,
    startMinute = windowStartMin,
    endMinute = windowEndMin
)

/**
 * Decide, in this order: **gone → window → cap → gap → proceed**.
 *
 * The ordering is a design choice, and it is stated plainly here rather than implied:
 *
 *  1. Preconditions first. A deleted or cloud-routed persona must not be re-armed "for
 *     later" — there is no later for it — so this is the one branch that ENDS the chain,
 *     and it must win over every "postpone" branch below it. This is plan §6 location #4,
 *     re-evaluated at EXECUTION time against the live server lists rather than against an
 *     enqueue-time snapshot.
 *  2. Window, then caps, then gap — all three are "postpone" outcomes, and [NotDue] always
 *     carries an instant that is already clamped into the window, so no ordering among them
 *     can ever let a message slip out of hours. What the order changes is only how many
 *     no-op wake-ups it takes to converge.
 *  3. The one measurable consequence, acknowledged rather than hidden: checking the window
 *     first means a persona whose gap expires shortly AFTER its window opens incurs one
 *     extra do-nothing run (at the opening, then again when the gap expires). The gap-first
 *     alternative would compute that single instant directly. It is kept window-first
 *     because it mirrors the plan's step list — and because the cost is at most one wasted
 *     run per persona per day, in exchange for a function whose branches can be read in the
 *     same order the plan describes them.
 */
internal fun evaluateSpontaneousCycle(request: SpontaneousCycleRequest): SpontaneousDecision {
    // --- preconditions -------------------------------------------------------
    if (!request.personaExists) return SpontaneousDecision.Cancel("The persona no longer exists.")
    if (!request.spontaneousEnabled) {
        return SpontaneousDecision.Cancel("Spontaneous messaging is off for this persona.")
    }
    if (request.eligibility is RoomEligibility.Blocked) {
        return SpontaneousDecision.Cancel(request.eligibility.reason)
    }

    // --- window --------------------------------------------------------------
    val minuteOfDay = spontaneousMinuteOfDay(request.now, request.zoneId)
    if (!isInSpontaneousWindow(minuteOfDay, request.windowStartMin, request.windowEndMin)) {
        return SpontaneousDecision.NotDue(
            nextWindowOpen(request.now, request.zoneId, request.windowStartMin)
        )
    }

    // --- daily cap -----------------------------------------------------------
    val today = spontaneousToday(request.now, request.zoneId)
    val sentToday = if (request.state.sentTodayDate == today) request.state.sentTodayCount else 0
    val capResetAt = nextDailyReset(
        request.now, request.zoneId, request.windowStartMin, request.windowEndMin
    )
    if (request.maxPerDay > 0 && sentToday >= request.maxPerDay) {
        return SpontaneousDecision.NotDue(capResetAt)
    }

    // --- global cap (O10) ----------------------------------------------------
    // A persona that loses the race for the last slot of the day is NOT DUE, never
    // CANCELLED: the budget refills tomorrow, so the timer must survive to see it.
    if (request.globalMaxPerDay > 0 && request.globalSentToday >= request.globalMaxPerDay) {
        return SpontaneousDecision.NotDue(capResetAt)
    }

    // --- gap -----------------------------------------------------------------
    if (request.now < request.state.lastSentAt + request.gapMillis) {
        return SpontaneousDecision.NotDue(
            nextSpontaneousAttempt(
                lastAttemptAt = request.state.lastSentAt,
                gapMillis = request.gapMillis,
                now = request.now,
                zoneId = request.zoneId,
                windowStartMin = request.windowStartMin,
                windowEndMin = request.windowEndMin
            )
        )
    }

    return SpontaneousDecision.Proceed
}

// ---------------------------------------------------------------- schedule diffing

/**
 * Do two versions of a persona produce a DIFFERENT spontaneous schedule?
 *
 * Used by `AssistantService.updatePersona` to decide whether a save needs to touch
 * WorkManager at all. The distinction matters because `ExistingWorkPolicy.REPLACE` both
 * restarts the countdown AND cancels whatever is currently running — so an edit to the
 * system prompt, the temperature or the theme colour must leave the chain completely
 * alone, while a change to the gap, the window, the cap, the model or the toggle must
 * reset it.
 *
 * Deliberately includes [Persona.name]: the unique-work key IS the name, so a rename
 * silently orphans the old chain and requires arming the new one.
 */
internal fun spontaneousScheduleDiffers(a: Persona, b: Persona): Boolean =
    a.name != b.name ||
        a.spontaneousEnabled != b.spontaneousEnabled ||
        a.spontaneousGapMinutes != b.spontaneousGapMinutes ||
        a.spontaneousWindowStartMin != b.spontaneousWindowStartMin ||
        a.spontaneousWindowEndMin != b.spontaneousWindowEndMin ||
        a.spontaneousMaxPerDay != b.spontaneousMaxPerDay ||
        a.model != b.model

// ---------------------------------------------------------------- jitter

/** Upper bound on re-arm jitter: enough to stop personas synchronising, small enough to be imperceptible. */
internal const val SPONTANEOUS_JITTER_MAX_MS: Long = 5 * 60_000L

/**
 * Spread [base] forward by up to [SPONTANEOUS_JITTER_MAX_MS], but only while the result
 * stays inside the window.
 *
 * Jitter exists so that N personas sharing the same default 4-hour gap do not ask
 * WorkManager to wake the device at an identical instant for days on end. Dropping the
 * jitter when it would leave the window keeps the promise the persona editor prints out
 * loud — nothing outside your window — instead of letting a random offset quietly break it.
 *
 * Randomness is injected so the whole thing is a deterministic unit test.
 */
internal fun withSpontaneousJitter(
    base: Long,
    zoneId: ZoneId,
    windowStartMin: Int?,
    windowEndMin: Int?,
    random: Random
): Long {
    if (windowStartMin == null && windowEndMin == null) {
        return base + (random.nextDouble() * SPONTANEOUS_JITTER_MAX_MS).toLong()
    }
    val jittered = base + (random.nextDouble() * SPONTANEOUS_JITTER_MAX_MS).toLong()
    return if (isInSpontaneousWindow(spontaneousMinuteOfDay(jittered, zoneId), windowStartMin, windowEndMin)) {
        jittered
    } else {
        base
    }
}

// ---------------------------------------------------------------- target + preflight

/** The Ollama endpoint a spontaneous attempt will talk to. */
internal data class SpontaneousTarget(
    /** Configured server name parsed from the `"[Server] model"` form; null for a bare model. */
    val serverName: String?,
    /** Model id with the display prefix stripped. */
    val actualModel: String,
    /** Base URL to hit, or null when this persona has no direct Ollama server. */
    val baseUrl: String?
)

/**
 * Split `"[Server] model"` into server, model and URL — the SAME parse `roomEligibility`
 * and `runSpontaneousTurn` perform, extracted so the preflight probe and the turn can
 * never disagree about which server is being contacted.
 */
internal fun resolveSpontaneousTarget(persona: Persona, servers: List<ServerConfig>): SpontaneousTarget {
    val rawModel = persona.model.trim()
    val serverName = if (rawModel.startsWith("[") && rawModel.contains("] ")) {
        rawModel.substring(1, rawModel.indexOf("]"))
    } else {
        null
    }
    val actualModel = if (serverName != null) rawModel.substringAfter("] ") else rawModel
    return SpontaneousTarget(
        serverName = serverName,
        actualModel = actualModel,
        baseUrl = serverName?.let { name -> servers.find { it.name == name }?.url }
    )
}

/**
 * Cheap liveness probe: `GET {baseUrl}/api/tags`.
 *
 * Runs BEFORE the service is bound and before any generation, for two reasons. It costs a
 * few hundred milliseconds instead of constructing `AssistantService` (which builds TTS, a
 * VAD detector and kicks off a pricing sync), and it turns "the phone has no network" into
 * an already-classified outcome instead of an exception thrown halfway through a real turn.
 *
 * Returns `true` on ANY HTTP status: a 401 still proves the host is up, and letting the
 * turn report the auth problem as itself is more useful than calling the server offline.
 */
internal fun preflightSpontaneousServer(
    baseUrl: String,
    servers: List<ServerConfig>,
    client: OkHttpClient
): Boolean {
    val stripped = baseUrl.trim().removeSuffix("/")
    // The gateway (8880) is not an Ollama backend — same refusal the turn makes.
    if (stripped.isBlank() || stripped.contains(":8880")) return false
    val request = Request.Builder().url("$stripped/api/tags").get()
    servers.find { it.url == stripped }?.let { config ->
        when (config.effectiveAuthType) {
            AuthType.NONE -> Unit
            AuthType.BASIC -> if (!config.username.isNullOrBlank()) {
                request.header("Authorization", Credentials.basic(config.username, config.password ?: ""))
            }
            AuthType.API_KEY -> if (!config.apiKey.isNullOrBlank()) {
                request.header("Authorization", "Bearer ${config.apiKey}")
            }
        }
    }
    return try {
        client.newCall(request.build()).execute().use { true }
    } catch (e: IOException) {
        android.util.Log.i("Spontaneous", "Preflight failed for $stripped: ${e.message}")
        false
    }
}

/**
 * Turn a failure thrown by `runSpontaneousTurn` into a log label.
 *
 * Step 4's contract is what keeps this small: the turn propagates transport errors instead
 * of swallowing them, and NEVER throws for "the persona had nothing to say" (silence
 * returns normally). So everything arriving here is infrastructure, and the only remaining
 * question is whether it deserves a retry — which plan §2.5 answers with a flat no: never
 * `Result.retry()`, re-arm from now instead of catching up.
 *
 * The return is a description, deliberately NOT a [SpontaneousDecision]: an offline server,
 * a malformed reply or an over-full context must never silently turn the feature OFF.
 * Only a missing persona, a flipped toggle or a failed guard may do that, and all three are
 * decided before any network call is made.
 */
internal fun describeSpontaneousFailure(error: Throwable): String =
    if (error is IOException) "unreachable (${error.message ?: error.javaClass.simpleName})"
    else error.message ?: error.javaClass.simpleName



