package com.nikosm.voiceassistant

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Per-message timestamps — what the USER sees, and what the Direct-Ollama model sees.
 *
 * Two different jobs that share one clock and one "unknown means unknown" rule, and are
 * deliberately kept apart because they answer different questions:
 *
 *  - [formatMessageStamp] is display: today reads as a bare time ("14:32"), anything older
 *    carries its date too ("Oct 1, 2026 14:32"). It is pure and takes `now` as a parameter so
 *    the day-boundary behavior is testable without waiting for midnight.
 *  - [historyTextWithTimestamp] is model-facing: the FULL date and time, always, because a
 *    local model reading a transcript has no other way to know that "we said that" and "we
 *    said that yesterday" are different claims. Only Direct-Ollama requests use it — see
 *    [Persona.stampsHistoryWithTimestamps].
 *
 * Neither ever invents a time. A message whose [ChatMessage.timestamp] is 0 (every message
 * written before the field existed) renders without a stamp and reaches the model verbatim,
 * which is strictly better than showing a plausible lie.
 */

/**
 * The stamp to render under a bubble: time-only when the message is from today, date and time
 * otherwise, and `null` when the message has no known time at all.
 *
 * [nowMillis] and [zone] are parameters rather than reads of the system clock so the
 * today/not-today boundary is decidable in a test — the whole rule *is* "same local date".
 */
internal fun formatMessageStamp(
    epochMillis: Long,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String? {
    if (epochMillis <= 0L) return null
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val isToday = moment.toLocalDate() == Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val pattern = if (isToday) TIME_ONLY else DATE_AND_TIME
    return DateTimeFormatter.ofPattern(pattern, locale).format(moment)
}

/** `formatMessageStamp` for a message, using the current time. */
internal fun formatMessageStamp(message: ChatMessage, nowMillis: Long = System.currentTimeMillis()): String? =
    formatMessageStamp(message.timestamp, nowMillis)

/**
 * The content a Direct-Ollama request sends for this message: its text prefixed with the full
 * date and time, e.g. `[Oct 1, 2026 14:32] what did we decide?`.
 *
 * ALWAYS the full date — never the display rule's today-only shortening. The two look similar
 * and are deliberately not shared: the UI hides a date the reader can already see, while the
 * model cannot see anything and would read a bare time as "today" no matter how old the
 * message is.
 *
 * A message with no timestamp passes through untouched (see the file comment).
 *
 * The returned string is what must reach `budgetedHistory`, so these prefixes are counted by
 * the same token estimate as the rest of the history rather than added after budgeting.
 */
internal fun ChatMessage.historyTextWithTimestamp(zone: ZoneId = ZoneId.systemDefault()): String {
    if (timestamp <= 0L) return text
    val stamp = DateTimeFormatter
        .ofPattern(MODEL_STAMP, Locale.US)
        .format(Instant.ofEpochMilli(timestamp).atZone(zone))
    return "[$stamp] ${stripLeadingModelStamps(text)}"
}
// ---------------------------------------------------------------------------
// Recognising a stamp the MODEL wrote
// ---------------------------------------------------------------------------

/**
 * One leading model-facing stamp, anchored at the very start of the text.
 *
 * The shape mirrors [MODEL_STAMP] — `[MMM d, yyyy HH:mm]` — because that is exactly what
 * the model is shown and therefore exactly what it imitates. Deliberately looser where a
 * model's imitation drifts: extra or missing spaces, a full month name, a trailing dot, a
 * period after the year. `\p{L}` rather than `[A-Za-z]`, because a model echoing a
 * localized transcript can reply with a non-Latin month ("Οκτ 3, 2026 12:29").
 *
 * Anchored, and requiring the full date-time shape, so ordinary prose in brackets is not
 * collateral damage: `[Note] ask Sam`, `[1] see below` and a `[...]` in the MIDDLE of a
 * sentence all fail to match and are left exactly as they are.
 */
private val LEADING_MODEL_STAMP = Regex(
    """^\[\s*\p{L}{2,9}\.?\s+\d{1,2}\s*,\s*\d{4}\.?\s+\d{1,2}\s*:\s*\d{2}\s*]\s*"""
)

/**
 * While a reply streams, the accumulated text may stop halfway through a stamp
 * ("[Oct 3, 20"). Requires a letter after the bracket — so `[1] see below` streams straight
 * through — and the absence of `]`, so anything already closed is never held back.
 */
private val PARTIAL_LEADING_STAMP = Regex(
    """^\[\s*\p{L}{1,9}\.?\s*(?:\d{0,2}\s*,?\s*)?(?:\d{0,4}\.?\s*)?(?:\d{0,2}\s*:\s*\d{0,2}\s*)?$"""
)

/**
 * Remove every complete stamp from the front of [text], and nothing else.
 *
 * Pure and total, so it is safe to call on every streaming chunk. Text that does not start
 * with a stamp comes back unchanged, which is what keeps `[Note] …` intact.
 */
internal fun stripLeadingModelStamps(text: String): String {
    var current = text
    while (true) {
        val stripped = LEADING_MODEL_STAMP.replaceFirst(current, "")
        if (stripped == current) return current
        current = stripped
    }
}

/** `true` while [text] could still grow into a leading stamp — i.e. is a partial one. */
internal fun couldBecomeLeadingModelStamp(text: String): Boolean =
    PARTIAL_LEADING_STAMP.containsMatchIn(text)

/**
 * What a streaming Direct-Ollama reply should SHOW: nothing yet while the text could still
 * turn out to be a stamp, otherwise the reply with any complete stamp removed.
 *
 * Holding the partial rather than stripping it is what stops the user seeing "[Oct 3, 20"
 * blink and then vanish. Pure, so each chunk is independent and no stream has to be
 * replayed.
 */
internal fun displaySafeStreamedReply(text: String): String =
    if (couldBecomeLeadingModelStamp(text)) "" else stripLeadingModelStamps(text)

/**
 * The one line added to the system prompt of a STAMPED Direct-Ollama persona.
 *
 * A local model reading a transcript full of `[Oct 3, 2026 14:32] …` will sometimes start
 * its own answer the same way; the app strips that echo anyway
 * ([stripLeadingModelStamps]), but saying who owns the format is what stops it being
 * produced. Deliberately one sentence, and deliberately NOT added to cloud or gateway
 * requests — those never see stamped history, so for them this would be an unexplained
 * instruction.
 */
internal const val MODEL_STAMP_INSTRUCTION =
    "Messages below may start with a [date time] stamp added by the app. Never write a " +
        "stamp yourself, and never repeat one you are shown; answer directly."

private const val TIME_ONLY = "HH:mm"

/**
 * Stamp a message with the moment it was created. Idempotent: an already-stamped message is
 * returned untouched, so an edit or a re-append cannot move its time.
 *
 * Writers call this as they append. A message that reaches the transcript without it simply
 * shows no time — see [ChatMessage.timestamp] for why that is the intended failure mode.
 */
internal fun ChatMessage.stampedAt(now: Long = System.currentTimeMillis()): ChatMessage =
    if (timestamp > 0L) this else copy(timestamp = now)

private const val DATE_AND_TIME = "MMM d, yyyy HH:mm"

/** Locale.US on purpose: this string is read by a model, not by a user, so it must not vary
 *  with the phone's locale (a Turkish device would otherwise send "1 Eki 2026" to a model that
 *  has no reason to know it). The user-facing stamps above do localize. */
private const val MODEL_STAMP = "MMM d, yyyy HH:mm"