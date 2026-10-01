package com.nikosm.voiceassistant

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Spontaneous delivery — the notification surface (step 6, plan §4).
 *
 * This file is the ONLY place that posts persona-initiated notifications, and those are
 * deliberately kept away from the foreground service's status UI. The two rules that matter:
 *
 * 1. **A different channel AND different notification ID than the FGS status notification.**
 *    The status notification lives at `NOTIFICATION_ID = 1` on `assistant_service_channel`
 *    (`IMPORTANCE_LOW`, `setOngoing`) and is rewritten ~40 times per session by live-turn
 *    machinery. Delivery uses `SPONTANEOUS_CHANNEL_ID` (`IMPORTANCE_DEFAULT`) and a
 *    per-persona ID from [spontaneousNotificationId]. Same channel/ID would let one stomp
 *    the other — and would fold "mute these check-ins" and "mute the service" into one
 *    indivisible user choice, which is exactly the design consequence §1.2 calls out.
 * 2. **No touch on `AssistantService`'s notification helpers.** Delivery posts directly with
 *    `NotificationManager`, never via `updateNotification`, never with a foreground-service
 *    type, and never while holding audio. A background message must not hijack the
 *    foreground notification while the user is recording or listening (same rule as the turn
 *    itself in `AssistantServiceSpontaneous.kt`).
 */

/** Deep-link extra naming the persona whose chat the tap must open. Read by `MainActivity`. */
internal const val EXTRA_TARGET_PERSONA_NAME = "spontaneousTargetPersona"

/** Distinct channel: independently dismissible, independently silencable. */
internal const val SPONTANEOUS_CHANNEL_ID = "spontaneous_messages_channel"

/** All delivery notifications share one group key so a burst collapses (plan §4.1). */
internal const val SPONTANEOUS_NOTIFICATION_GROUP = "spontaneous_messages"

/**
 * Notification IDs, and why they are what they are.
 *
 * The FGS status notification is hard-wired to ID 1. Delivery IDs derive from the persona
 * name so that (a) messages from one persona UPDATE its notification instead of stacking,
 * and (b) N enabled personas produce N notifications, one per conversational thread, plus
 * the group summary. The base keeps the whole namespace clear of the FGS id and of the
 * summary id below by three orders of magnitude; a hash collision between two persona
 * names would merely merge their cards, which is a rendering artefact, never data loss.
 */
internal const val SPONTANEOUS_SUMMARY_NOTIFICATION_ID = 2
internal const val SPONTANEOUS_NOTIFICATION_ID_BASE = 1_000

/** Stable per-persona delivery id. Pure, so it is pinned on the JVM rather than on-device. */
internal fun spontaneousNotificationId(personaName: String): Int =
    SPONTANEOUS_NOTIFICATION_ID_BASE + (personaName.hashCode() and 0x7FFF)

/**
 * Create the delivery channel. Idempotent — safe to call on every post, which is what makes
 * the worker self-sufficient rather than depending on the service having been created.
 */
internal fun ensureSpontaneousNotificationChannel(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
        android.app.NotificationChannel(
            SPONTANEOUS_CHANNEL_ID,
            "Persona messages",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Messages your personas send on their own initiative" }
    )
}

/**
 * Truncate [text] to its first line, bounded so the collapsed card reads as one thought.
 * Newlines and runs of whitespace inside the preview are flattened; the full message is
 * always available via the expanded `BigTextStyle`.
 */
internal fun spontaneousNotificationPreview(text: String, maxChars: Int = 120): String =
    text.lineSequence().firstOrNull().orEmpty().replace(Regex("\\s+"), " ").trim()
        .let { if (it.length <= maxChars) it else it.take(maxChars - 1).trimEnd() + "…" }

/**
 * Post (or update) the delivery notification for a successfully-sent spontaneous message.
 *
 * Only ever called with [SpontaneousTurnOutcome.Sent.text] — never with silence, a skip,
 * or a failure. Delivery is the caller's job precisely so a non-send can never produce a
 * notification.
 *
 * If notifications are disabled or denied, this is a logged no-op: the message already
 * landed in the persona's history, which is where step 5 ended by design, and nothing here
 * should ever throw back into the worker's re-arm path.
 */
internal fun postSpontaneousNotification(context: Context, personaName: String, text: String) {
    try {
        ensureSpontaneousNotificationChannel(context)
        val manager =
            context.getSystemService(NotificationManager::class.java)
                ?: run {
                    android.util.Log.w("Spontaneous", "No NotificationManager — message kept, notification dropped")
                    return
                }
        if (!manager.areNotificationsEnabled()) {
            android.util.Log.i(
                "Spontaneous",
                "Notifications are off — '${personaName}'s message is in its chat, nothing posted"
            )
            return
        }
        val targetIntent = Intent(context, MainActivity::class.java).apply {
            // O3: SINGLE_TOP routes a tap while the activity is backgrounded to onNewIntent
            // instead of stacking a second instance (see the manifest). CLEAR_TOP also covers
            // the cold path. O14: the extra names the *persona*, not a message — the tap
            // opens that persona's chat, and never scrolls anywhere in particular.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET_PERSONA_NAME, personaName)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            // Per-persona request code: without it, taps on different personas reuse the
            // first intent the system cached and deep-link to the WRONG chat.
            spontaneousNotificationId(personaName),
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            spontaneousNotificationId(personaName),
            NotificationCompat.Builder(context, SPONTANEOUS_CHANNEL_ID)
                .setContentTitle(personaName)
                .setContentText(spontaneousNotificationPreview(text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setGroup(SPONTANEOUS_NOTIFICATION_GROUP)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                // Deliberately NOT setOngoing and never IMPORTANCE_LOW: §4.1 requires an
                // audible, dismissible card, not a status line.
                .build()
        )
        manager.notify(
            SPONTANEOUS_SUMMARY_NOTIFICATION_ID,
            NotificationCompat.Builder(context, SPONTANEOUS_CHANNEL_ID)
                .setContentTitle("Persona messages")
                .setContentText("New messages from your personas")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setGroup(SPONTANEOUS_NOTIFICATION_GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
        )
        android.util.Log.i("Spontaneous", "Delivered notification for '$personaName'")
    } catch (e: Exception) {
        // Never let delivery take down scheduling — the chain must re-arm regardless,
        // and the message is already persisted.
        android.util.Log.w("Spontaneous", "Notification post failed, message kept: ${e.message}")
    }
}

/**
 * Remove a persona's delivery notification (and the summary when nothing remains).
 * Best-effort companion to disarm paths — a cancelled chain must not leave a stale card
 * that opens a chat which then never hears again.
 */
internal fun cancelSpontaneousNotification(context: Context, personaName: String) {
    try {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(spontaneousNotificationId(personaName))
        if (manager.activeNotifications.none {
                it.id != SPONTANEOUS_SUMMARY_NOTIFICATION_ID &&
                    it.notification.group == SPONTANEOUS_NOTIFICATION_GROUP
            }
        ) {
            manager.cancel(SPONTANEOUS_SUMMARY_NOTIFICATION_ID)
        }
    } catch (e: Exception) {
        android.util.Log.w("Spontaneous", "Notification cancel failed: ${e.message}")
    }
}
