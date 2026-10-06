package com.nikosm.voiceassistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile "Celeste" — a one-shot voice-start action.
 *
 * Deliberately does NOT touch [AssistantService] (a tile's process may be created just to draw
 * it, and the FGS types are wrong for a launcher): all it does is open [MainActivity] with a
 * one-shot extra, and the app — which owns the service — decides whether a voice turn may start.
 *
 * The tile has a STATIC INACTIVE state: it is an action, not a toggle, so it never lights up.
 */

/** The one-shot extra the tile sets on its launch intent (consumed by `MainActivity`). */
internal const val EXTRA_CELESTE_START_VOICE = "com.nikosm.voiceassistant.extra.CELESTE_START_VOICE"

/** What a tile tap must do first. */
internal enum class CelesteTileTapAction { UNLOCK_THEN_LAUNCH, LAUNCH }

/**
 * A tap on a LOCKED device must go through `unlockAndRun`, so the voice turn can never begin over
 * the lock screen (the launch itself would otherwise run behind the keyguard).
 */
internal fun celesteTileTapAction(isLocked: Boolean): CelesteTileTapAction =
    if (isLocked) CelesteTileTapAction.UNLOCK_THEN_LAUNCH else CelesteTileTapAction.LAUNCH

/** NEW_TASK (the launch comes from a service context) + SINGLE_TOP (reuse the running activity). */
internal fun celesteTileLaunchFlags(): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP

/** Immutable + update-current, so the retained PendingIntent can never be hijacked or go stale. */
internal fun celesteTilePendingIntentFlags(): Int =
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

/**
 * `upsideDownCake` is API 34, but the constant does not exist in older SDKs' android.jar, so the
 * comparison is spelled with the literal the way this project's other SDK gates are.
 */
private const val API_34 = 34

class CelesteTileService : TileService() {

    /** Static inactive state — the tile is an action, never a toggle. */
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        when (celesteTileTapAction(isLocked)) {
            CelesteTileTapAction.UNLOCK_THEN_LAUNCH -> unlockAndRun { launchCeleste() }
            CelesteTileTapAction.LAUNCH -> launchCeleste()
        }
    }

    private fun launchCeleste() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = celesteTileLaunchFlags()
            putExtra(EXTRA_CELESTE_START_VOICE, true)
        }
        if (Build.VERSION.SDK_INT >= API_34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, celesteTilePendingIntentFlags())
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
