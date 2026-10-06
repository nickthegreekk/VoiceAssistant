package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate on both destructive message-image decisions: REUSING a pending attachment's file on
 * send instead of re-encoding it into a second file, and DELETING that file when the user
 * removes the attachment.
 *
 * The false cases are the point of this file. A path that wrongly passes would let the send path
 * claim a file the app does not own, or let the remove button delete the user's own photo — so
 * the picker's content URI, a persona's artwork, a file one level down and a `..` traversal all
 * have to be refused.
 *
 * `artworkFileName` is pinned too: the predicate keys off the naming the WRITER uses, and if that
 * naming ever changed, the only symptom would be silence — the predicate would simply stop
 * matching, quietly turning "reuse the attachment's file" back into "write a second file".
 */
class MessageImagePathTest {

    private val dir = "/data/user/0/com.nikosm.voiceassistant/files/persona_images"

    /** The real name of a device-materialized message image, kept verbatim from a run. */
    private val realName = "msg_1791282705681_02e5cf11.jpg"

    // ------------------------------------------------------------------ accepted

    @Test
    fun `the name the writer produces is the one this predicate is built for`() {
        assertEquals(
            realName,
            artworkFileName(MESSAGE_IMAGE_SLOT, 1791282705681L, "02e5cf11")
        )
        assertTrue(isMaterializedMessageImagePath("$dir/$realName", dir))
    }

    @Test
    fun `a directory given with a trailing slash still matches`() {
        assertTrue(isMaterializedMessageImagePath("$dir/$realName", "$dir/"))
    }

    // ------------------------------------------------------------------ refused

    @Test
    fun `a persona icon or background is not a message image`() {
        assertFalse(isMaterializedMessageImagePath("$dir/icon_1_aaaa.jpg", dir))
        assertFalse(isMaterializedMessageImagePath("$dir/background_1_aaaa.jpg", dir))
    }

    @Test
    fun `the same filename outside the images directory is not ours`() {
        assertFalse(isMaterializedMessageImagePath("/data/user/0/com.nikosm.voiceassistant/files/$realName", dir))
        assertFalse(isMaterializedMessageImagePath("/sdcard/Download/$realName", dir))
    }

    @Test
    fun `a file one directory below the images directory is not ours`() {
        assertFalse(isMaterializedMessageImagePath("$dir/sub/$realName", dir))
    }

    @Test
    fun `a traversal out of the images directory is not ours`() {
        // String shape only, which is exactly why it works: `parent` is the un-normalized
        // ".../persona_images/.." and cannot equal the directory.
        assertFalse(isMaterializedMessageImagePath("$dir/../files/$realName", dir))
    }

    @Test
    fun `a photo picker content uri's path is not ours`() {
        // Uri.path of content://media/external/images/media/1000001113.
        assertFalse(isMaterializedMessageImagePath("/external/images/media/1000001113", dir))
    }

    @Test
    fun `a name that does not carry the slot prefix is not ours`() {
        assertFalse(isMaterializedMessageImagePath("$dir/msgfoo_1_aaaa.jpg", dir))
        assertFalse(isMaterializedMessageImagePath("$dir/x_msg_1_aaaa.jpg", dir))
        assertFalse(isMaterializedMessageImagePath("$dir/Msg_1_aaaa.jpg", dir))
    }

    @Test
    fun `a bare filename with no directory is not ours`() {
        assertFalse(isMaterializedMessageImagePath(realName, dir))
    }

    @Test
    fun `a missing path or directory is not ours`() {
        assertFalse(isMaterializedMessageImagePath(null, dir))
        assertFalse(isMaterializedMessageImagePath("", dir))
        assertFalse(isMaterializedMessageImagePath("   ", dir))
        assertFalse(isMaterializedMessageImagePath("$dir/$realName", null))
        assertFalse(isMaterializedMessageImagePath("$dir/$realName", ""))
    }
}
