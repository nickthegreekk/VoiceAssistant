package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clamp that keeps a zoomed image from being flung off screen in [FullScreenImageViewer].
 *
 * Only the numbers are pinned here: the viewer's gestures are device-level behaviour, but the
 * geometry they feed is pure, and a wrong limit has exactly one symptom — the user drags a
 * zoomed generation out of view and cannot get it back. Each case therefore states where the
 * limit comes from: a `ContentScale.Fit` image is limited by whichever axis it runs out of
 * first, and panning is possible on an axis only once the zoomed image is larger than the
 * container on that axis.
 */
class FullScreenImageViewerBoundsTest {

    private val delta = 0.001f

    @Test
    fun `nothing is pan-able before a container or image size is known`() {
        assertEquals(0f to 0f, imagePanBounds(0f, 0f, 2f, 4f))
        assertEquals(0f to 0f, imagePanBounds(100f, 100f, 0f, 4f))
        // A negative aspect is the "not decoded yet" sentinel, not a mirrored image.
        assertEquals(0f to 0f, imagePanBounds(100f, 100f, -1f, 4f))
    }

    @Test
    fun `at rest the image is pinned to the centre on both axes`() {
        val (maxX, maxY) = imagePanBounds(100f, 100f, imageAspect = 2f, scale = 1f)
        assertEquals(0f, maxX, delta)
        assertEquals(0f, maxY, delta)
    }

    @Test
    fun `a wide image pans horizontally and only once it outgrows the container`() {
        // 2:1 image in a square container: 100x50 on screen, so zooming to 3x gives 300x150.
        // Horizontally there are (300-100)/2 = 100px of slack; vertically (150-100)/2 = 25px.
        val (maxX, maxY) = imagePanBounds(100f, 100f, imageAspect = 2f, scale = 3f)
        assertEquals(100f, maxX, delta)
        assertEquals(25f, maxY, delta)
    }

    @Test
    fun `a tall image pans vertically and only once it outgrows the container`() {
        // 1:2 image in a square container: 50x100 on screen, so zooming to 2x gives 100x200.
        // Vertically (200-100)/2 = 50px; horizontally the image is exactly the container width.
        val (maxX, maxY) = imagePanBounds(100f, 100f, imageAspect = 0.5f, scale = 2f)
        assertEquals(0f, maxX, delta)
        assertEquals(50f, maxY, delta)
    }

    @Test
    fun `the axis that is still smaller than the container stays locked`() {
        // Square image in a 2:1 container: 100x100 on screen (pillarboxed). At 2x it is
        // 200x200, so there is no horizontal slack left (200 == 200) but 50px vertically.
        val (maxX, maxY) = imagePanBounds(200f, 100f, imageAspect = 1f, scale = 2f)
        assertEquals(0f, maxX, delta)
        assertEquals(50f, maxY, delta)
    }

    @Test
    fun `an image matching the container's aspect pans on both axes`() {
        // The `>=` branch is the one taken when the ratios are equal; the image fills the
        // container exactly, so both axes have the same proportional slack.
        val (maxX, maxY) = imagePanBounds(200f, 100f, imageAspect = 2f, scale = 2f)
        assertEquals(100f, maxX, delta)
        assertEquals(50f, maxY, delta)
    }

    @Test
    fun `more zoom always means at least as much slack`() {
        var previous = imagePanBounds(300f, 500f, imageAspect = 1.5f, scale = 1f)
        for (step in 2..8) {
            val current = imagePanBounds(300f, 500f, imageAspect = 1.5f, scale = step.toFloat())
            assertTrue(current.first >= previous.first)
            assertTrue(current.second >= previous.second)
            previous = current
        }
    }
}
