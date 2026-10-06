package com.nikosm.voiceassistant

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

internal const val SHARED_SUBJECT = "Shared article title"
internal const val SHARED_BODY = "the shared words"
private const val EXPECTED_DRAFT = "$SHARED_SUBJECT\n$SHARED_BODY"

/**
 * The launch intent for a TEXT share. Top-level (rather than a member of the harness) because it
 * is evaluated in the subclass's super-call, before any of the subclass's own state exists.
 */
internal fun textShareIntent(): Intent = Intent(targetContext(), MainActivity::class.java).apply {
    action = Intent.ACTION_SEND
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, SHARED_SUBJECT)
    putExtra(Intent.EXTRA_TEXT, SHARED_BODY)
}

/**
 * Share target — the ACTIVITY half, on a device, for a TEXT share.
 *
 * A real `MainActivity` is launched with a real `ACTION_SEND` `text/plain` intent, and two things
 * are pinned:
 *
 *  1. the shared text (subject-prefixed) lands in the composer's DRAFT, and is never auto-sent
 *     (a loopback Ollama stand-in, already the current persona's server, sees no chat request);
 *  2. an Activity recreation does not insert it a second time.
 *
 * (2) is the one that matters: the extras live on the LAUNCH intent, which OUTLIVES the Activity —
 * the same platform fact the deep-link F3 regression turned on. `handleCelesteRequests` removes
 * them on read, so the recreation's `onCreate` finds nothing to re-fire, and the draft is restored
 * by `rememberSaveable` rather than re-populated.
 *
 * The image half of the share target is [ShareTargetImageInstrumentedTest]; the fixture both use
 * is [ShareTargetHarness].
 */
@RunWith(AndroidJUnit4::class)
class ShareTargetInstrumentedTest : ShareTargetHarness(textShareIntent()) {

    @Test
    fun aSharedTextLandsInTheDraftOnceAndIsNeverAutoSent() {
        assertTrue(
            "the shared text must reach the composer's draft; draft=${draftTextOrNull()}",
            waitFor { draftTextOrNull() == EXPECTED_DRAFT }
        )
        assertEquals(
            "the share must land as written, with its subject prefixed",
            EXPECTED_DRAFT, draftTextOrNull()
        )
        assertEquals("a share must never auto-send", emptyList<String>(), stub.chatRequests())

        // Recreate: the launch intent is redelivered with its extras already consumed, so nothing
        // may re-fire, and the saved draft must still hold exactly ONE copy.
        recreateActivity()
        waitForIdle()
        val windowEnd = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < windowEnd) {
            assertEquals(
                "the consumed share re-fired on Activity recreation — the launch intent still " +
                    "carried what it should have consumed",
                EXPECTED_DRAFT, draftTextOrNull()
            )
            Thread.sleep(50)
        }
        assertEquals("still nothing sent after the recreation", emptyList<String>(), stub.chatRequests())
    }
}
