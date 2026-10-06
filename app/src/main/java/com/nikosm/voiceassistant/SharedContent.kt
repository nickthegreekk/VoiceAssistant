package com.nikosm.voiceassistant

import android.content.Intent
import android.net.Uri

/**
 * Share target — the PURE half: what a received `ACTION_SEND` carries, and how it lands in the
 * chat's draft. Kept free of `Context`/`Uri` construction so it is unit-tested on the JVM; the
 * Activity reads the intent and the screen writes the state.
 *
 * Single item only by decision: one plain-text payload or one image, never a wildcard and never
 * `ACTION_SEND_MULTIPLE`.
 */

/** Cap on the text one share may insert. Longer payloads are trimmed and the user is told. */
internal const val MAX_SHARED_TEXT_CHARS = 30_000

/**
 * Semantics tags on the chat composer — the share landing point. Read by
 * `ShareTargetInstrumentedTest`, so the share's effect is asserted on the real UI rather than
 * inferred from the intent.
 */

/** The draft field the shared text lands in. */
internal const val COMPOSER_DRAFT_TAG = "composerDraftField"

/** The pending image attachment's thumbnail; present only while one is attached. */
internal const val COMPOSER_ATTACHMENT_TAG = "composerImageAttachment"

/** The pending attachment's remove ("×") button. */
internal const val COMPOSER_ATTACHMENT_REMOVE_TAG = "composerImageAttachmentRemove"

/** The composer's single trailing button: sends while there is content, else mic/stop. */
internal const val COMPOSER_ACTION_TAG = "composerActionButton"

/** What an accepted `ACTION_SEND` payload is. */
internal sealed interface SharedIntentContent {
    /** The text to append to the draft (already subject-prefixed when that applies). */
    data class Text(val text: String) : SharedIntentContent

    /** An image; the caller reads `EXTRA_STREAM` for the [Uri]. */
    data object Image : SharedIntentContent
}

/**
 * Parse one `ACTION_SEND` payload: `(action, type, EXTRA_TEXT, EXTRA_SUBJECT, EXTRA_STREAM-present)`
 * to text, an image, or nothing.
 *
 * - anything but [Intent.ACTION_SEND] is nothing;
 * - an image MIME type with a stream is an image (a stream-less image intent is nothing — there is
 *   nothing to attach);
 * - a text MIME type yields text. A non-blank subject that is not already in the body is prefixed as
 *   `"subject\ntext"` (and is used alone when the body is blank), so a shared article arrives
 *   with its title. Blank text with no subject is nothing.
 *
 * The stream is reduced to a boolean rather than a `Uri` so the function stays JVM-testable: the
 * caller turns `true` into the real `EXTRA_STREAM` value.
 */
internal fun parseSharedIntent(
    action: String?,
    type: String?,
    extraText: String?,
    extraSubject: String?,
    hasStream: Boolean
): SharedIntentContent? {
    if (action != Intent.ACTION_SEND) return null
    val mime = type?.lowercase().orEmpty()
    if (mime.startsWith("image/")) return if (hasStream) SharedIntentContent.Image else null
    if (!mime.startsWith("text/")) return null

    val body = extraText.orEmpty()
    val subject = extraSubject.orEmpty()
    val withSubject = when {
        subject.isBlank() -> body
        body.isBlank() -> subject
        body.contains(subject) -> body
        else -> "$subject\n$body"
    }
    return SharedIntentContent.Text(withSubject).takeIf { withSubject.isNotBlank() }
}

/** The result of appending a share to the draft. */
internal data class SharedDraftAppend(val draft: String, val truncated: Boolean)

/**
 * Append [shared] to the current draft, separated by a newline, capping the INSERTED text at
 * [MAX_SHARED_TEXT_CHARS]. Never sends: the whole point is that the user adds text and sends.
 */
internal fun appendSharedTextToDraft(draft: String, shared: String): SharedDraftAppend {
    val capped = shared.take(MAX_SHARED_TEXT_CHARS)
    val next = if (draft.isEmpty()) capped else "$draft\n$capped"
    return SharedDraftAppend(next, capped.length < shared.length)
}

/**
 * The one-shot handoff from `MainActivity` to `MainScreen`. Unlike [SharedIntentContent] this can
 * carry the raw `EXTRA_STREAM` [Uri], because the screen materializes it immediately (the grant
 * the share arrived with is temporary) and never stores the URI.
 *
 * Public because [MainScreen] — a public composable — takes it.
 */
sealed interface SharedRequest {
    data class Text(val text: String) : SharedRequest
    data class Image(val uri: Uri) : SharedRequest
}
