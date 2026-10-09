package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "this address is the wrong backend" hint: when it may be claimed, what it says, and which
 * endpoint decides it.
 *
 * The wording is pinned exactly here because it names the section the user has to move the entry to;
 * the probe that decides it is exercised against real stubs in
 * [ImageGenBackendMismatchInstrumentedTest].
 */
class ImageGenBackendMismatchTest {

    @Test
    fun onlyAnUnknownRouteOrMethodSuggestsTheOtherBackend() {
        assertTrue(isImageGenBackendMismatchStatus(404))
        assertTrue(isImageGenBackendMismatchStatus(405))

        for (code in listOf(0, 200, 204, 400, 401, 403, 422, 429, 500, 502, 503)) {
            assertFalse(
                "HTTP $code is not evidence of a wrong backend — only 404/405 say " +
                    "\"a running server with no such route\"",
                isImageGenBackendMismatchStatus(code)
            )
        }
    }

    @Test
    fun theProbeThatDecidesAMismatchIsTheOtherBackendsOwnProbe() {
        assertEquals(COMFY_SYSTEM_STATS_PATH, oppositeImageGenProbePath(ImageGenBackend.A1111))
        assertEquals(IMAGE_GEN_SD_MODELS_PATH, oppositeImageGenProbePath(ImageGenBackend.COMFYUI))

        for (backend in ImageGenBackend.entries) {
            assertNotEquals(
                "the deciding probe must never be the entry's own endpoint, or a working entry " +
                    "would look like a mismatch",
                imageGenProbePath(backend),
                oppositeImageGenProbePath(backend)
            )
        }
    }

    @Test
    fun anA1111EntryThatAnswersComfyUiIsToldToMoveToTheComfyUiSection() {
        assertEquals(
            "This address looks like ComfyUI. " +
                "Add it under ComfyUI servers instead of Automatic1111.",
            imageGenBackendMismatchMessage(ImageGenBackend.A1111)
        )
    }

    @Test
    fun aComfyUiEntryThatAnswersA1111IsToldTheMirrorImage() {
        assertEquals(
            "This address looks like Automatic1111. " +
                "Add it under Automatic1111 servers instead of ComfyUI.",
            imageGenBackendMismatchMessage(ImageGenBackend.COMFYUI)
        )
    }

    @Test
    fun theMessageNamesTheSectionByTheSameNameTheScreenShows() {
        // The hint is only actionable if "… servers" matches a section heading on the Image Gen
        // screen, which is built from the same displayName.
        for (backend in ImageGenBackend.entries) {
            val message = imageGenBackendMismatchMessage(backend)
            val section = "${backend.displayName} servers"
            assertFalse(
                "the entry's own section must not be the one suggested: $message",
                message.contains("under $section")
            )
            val other = if (backend == ImageGenBackend.A1111) ImageGenBackend.COMFYUI else ImageGenBackend.A1111
            assertTrue(
                "the suggestion must name the other section: $message",
                message.contains("under ${other.displayName} servers")
            )
        }
    }
}
