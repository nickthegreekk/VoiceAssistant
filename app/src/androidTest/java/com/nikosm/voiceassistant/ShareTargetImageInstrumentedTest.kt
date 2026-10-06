package com.nikosm.voiceassistant

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inserts a tiny PNG into MediaStore as THIS TEST PACKAGE's own media and grants the app read
 * access to it — as close to a gallery share as a test can get, and the same two things a real
 * sender hands over: a `content://` URI and a temporary read grant. The app has to copy what it
 * needs while that grant is alive, exactly as it does in production.
 *
 * The row belongs to the test package, which is what makes the grant possible at all (an app may
 * grant a URI it owns). [ShareTargetImageInstrumentedTest] deletes it again.
 */
internal fun insertSharedImageProbe(): Uri {
    val resolver = targetContext().contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "celeste_share_probe.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = requireNotNull(
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    ) { "MediaStore refused the probe image" }

    // The probe must be both realistically SIZED and realistically CONTENTFUL. The bug this test
    // pins is a race between the materialization and the recomposition that cancels the effect
    // that started it, so the read has to outlive a frame (~16ms) by a margin, every run:
    //   * a solid colour inflates in a few milliseconds (a 6MP solid PNG was measured at well
    //     under one frame, and the pre-fix code then passed by luck);
    //   * noise inflates an order of magnitude slower, which is the same order as a photograph —
    //     and a photograph is what a user actually shares.
    val width = 2000
    val height = 1400
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val noise = ByteArray(width * height * 4)
    java.util.Random(42).nextBytes(noise)
    val pixels = IntArray(width * height)
    java.nio.ByteBuffer.wrap(noise).asIntBuffer().get(pixels)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    resolver.openOutputStream(uri).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(out) { "no probe stream" })
    }
    bitmap.recycle()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Publish it: a pending row is not readable by anyone else, grant or no grant.
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
    }
    targetContext().grantUriPermission(
        SHARE_APP_PACKAGE,
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
    )
    return uri
}

/**
 * The launch intent for an IMAGE share: a MediaStore URI plus the read grant it arrived with.
 *
 * Explicitly component-targeted like the text intent, so no chooser appears mid-test; the
 * manifest's image MIME filter itself is exercised by the device checks, which share an image
 * through an implicit `ACTION_SEND`.
 */
internal fun imageShareIntent(stream: Uri): Intent = Intent(targetContext(), MainActivity::class.java).apply {
    action = Intent.ACTION_SEND
    type = "image/png"
    putExtra(Intent.EXTRA_STREAM, stream)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}


/**
 * Share target — the ACTIVITY half, on a device, for an IMAGE share.
 *
 * A real `MainActivity` is launched with a real `ACTION_SEND` `image/png` intent carrying a
 * MediaStore URI the app holds only a temporary read grant for, and three things are pinned:
 *
 *  1. the image lands as a pending attachment — exactly ONE file is materialized for it, and an
 *     Activity recreation neither loses the attachment nor materializes the share again;
 *  2. SENDING it reuses that file, so one shared image costs one file for its whole life (the
 *     send used to re-encode the already-materialized JPEG and strand the original);
 *  3. REMOVING it deletes that file.
 *
 * (1) is a regression test for a real bug: the share effect consumed the request before
 * materializing, and consuming changed one of that effect's OWN keys, so the effect was cancelled
 * at its only suspension point. The file was written, the attachment never appeared, and the user
 * was told the image could not be read. See the comment above the effect in `MainVoiceScreen`.
 *
 * The fixture — bound service, loopback Ollama stand-in, seeded persona, rule chain — lives in
 * [ShareTargetHarness]; the text half is [ShareTargetInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class ShareTargetImageInstrumentedTest : ShareTargetHarness(imageShareIntent(insertSharedImageProbe())) {

    @After
    fun deleteProbeImage() {
        // The probe is media this test package owns, so it can always remove it: no probe image
        // may be left behind in the user's gallery.
        sharedStreamUri?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    @Test
    fun aSharedImageLandsAsAPendingAttachmentOnceAndSurvivesRecreation() {
        awaitPendingAttachment()
        assertOneFileForTheShare()

        // Recreate: the attachment is restored from the composer's saved state, and the consumed
        // launch intent must not send the materialization round a second time.
        recreateActivity()
        waitForIdle()
        val windowEnd = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < windowEnd) {
            assertEquals(
                "the attachment must survive an Activity recreation",
                1, pendingAttachmentCount()
            )
            assertEquals(
                "the recreation must not materialize the shared image again; listing: $lastMsgImageListing",
                1, filesAddedByShare().size
            )
            Thread.sleep(50)
        }
    }

    @Test
    fun sendingTheAttachmentReusesTheMaterializedFileAndLeavesNoSecondCopy() {
        awaitPendingAttachment()
        assertOneFileForTheShare()
        val afterShare = filesAddedByShare()

        // The real send: text into the real draft, then the real send button.
        typeIntoDraft("what is this")
        waitForIdle()
        clickTagged(COMPOSER_ACTION_TAG)
        waitForIdle()

        assertTrue(
            "the turn must reach the persona's server",
            waitFor { stub.chatRequests().isNotEmpty() }
        )
        assertTrue(
            "the send must consume the pending attachment",
            waitFor { pendingAttachmentCount() == 0 }
        )
        assertTrue(
            "the user turn must be stamped with the image it was sent with",
            waitFor { sentImagePath() != null }
        )
        val sent = sentImagePath()!!
        assertTrue(
            "the message must point at the file the share materialized, was $sent",
            afterShare.contains(File(sent).name)
        )
        assertEquals(
            "the send must REUSE the attachment's file, not re-encode it into a second one; " +
                "listing after the send was: $lastMsgImageListing",
            afterShare, filesAddedByShare()
        )
    }

    @Test
    fun removingThePendingAttachmentDeletesItsFile() {
        awaitPendingAttachment()
        assertOneFileForTheShare()

        clickTagged(COMPOSER_ATTACHMENT_REMOVE_TAG)
        waitForIdle()

        assertTrue(
            "the attachment must leave the composer",
            waitFor { pendingAttachmentCount() == 0 }
        )
        assertTrue(
            "removing the attachment must delete its file; left behind: ${filesAddedByShare()}; " +
                "listing: $lastMsgImageListing",
            waitFor { filesAddedByShare().isEmpty() }
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun awaitPendingAttachment() = assertTrue(
        "the shared image must reach the composer as a pending attachment",
        waitFor {
            // Drive the composition explicitly. The read is deliberately slow enough to outlast a
            // frame, so a pending recomposition is waiting to be applied here — and applying it is
            // what cancels the share effect mid-read under the pre-fix code. Waiting passively
            // instead lets the app idle its way past the race.
            waitForIdle()
            pendingAttachmentCount() == 1
        }
    )

    /** The share added exactly one materialized file — no more, no fewer. */
    private fun assertOneFileForTheShare() = assertEquals(
        "one shared image must cost exactly one file; storage listing was: $lastMsgImageListing",
        1, filesAddedByShare().size
    )

    /** The message images this test's share added to app-private storage. */
    private fun filesAddedByShare(): Set<String> = msgImageNames() - msgImageNamesBefore

    /** The pending image the last user turn was sent with, once the send has recorded it. */
    private fun sentImagePath(): String? =
        service.messages.value.lastOrNull { it.role == "user" }?.imagePath
}
