package com.nikosm.voiceassistant

import android.app.PendingIntent
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Quick Settings tile's pure decisions — the two platform facts a device run would otherwise
 * be the only witness to:
 *
 *  - a LOCKED device must unlock first, so the voice turn can never begin over the lock screen;
 *  - the launch intent must reuse a running Celeste (`SINGLE_TOP`) and must be able to start from
 *    the tile service's non-Activity context (`NEW_TASK`), through an IMMUTABLE `PendingIntent`.
 *
 * The rest of the tile (the `TileService` itself, the icon, the manifest declaration) is
 * verified by reading and by the device checks.
 */
class CelesteTileTest {

    @Test
    fun `an unlocked tap launches directly`() {
        assertEquals(CelesteTileTapAction.LAUNCH, celesteTileTapAction(isLocked = false))
    }

    @Test
    fun `a locked tap unlocks before launching`() {
        assertEquals(CelesteTileTapAction.UNLOCK_THEN_LAUNCH, celesteTileTapAction(isLocked = true))
    }

    @Test
    fun `the launch intent reuses the running activity and starts from a service context`() {
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            celesteTileLaunchFlags()
        )
    }

    @Test
    fun `the pending intent is immutable and updated in place`() {
        assertEquals(
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            celesteTilePendingIntentFlags()
        )
    }
}
