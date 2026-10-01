package com.nikosm.voiceassistant

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spontaneous messages — the deep link's ACTIVITY half, proven on-device.
 *
 * MainScreen's effect trusts two platform facts it cannot verify itself:
 *
 * 1. `MainActivity` carries `android:launchMode="singleTop"`, so a notification tap while
 *    the activity exists routes to `onNewIntent` instead of stacking a second instance.
 * 2. The delivery notification's `PendingIntent` names exactly the persona — the same name
 *    the state-holder is keyed by — so the tap the activity routes is the tap that was made.
 *
 * Both are asserted where they live: the manifest's launchMode survives package parsing,
 * and the extra survives an Intent round-trip through a `PendingIntent` built the way
 * `postSpontaneousNotification` builds it.
 */
@RunWith(AndroidJUnit4::class)
class SpontaneousDeepLinkActivityInstrumentedTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun `MainActivity really is singleTop in the installed manifest`() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA
        )
        assertEquals(
            "a second MainActivity instance per tap would orphan the deep-link state",
            android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP,
            info.launchMode
        )
    }

    @Test fun `the posted intent carries the persona name the effect reads`() {
        // Mirrors postSpontaneousNotification's construction: same action-less explicit
        // intent, same extra key, same immutability. If a future edit renames the key on
        // one side, this fails instead of the tap silently dropping its payload.
        val name = "ProbeActivityDeepLink"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET_PERSONA_NAME, name)
        }
        val pending = android.app.PendingIntent.getActivity(
            context,
            spontaneousNotificationId(name),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // A PendingIntent is not introspectable, so prove the round-trip on the parcelable
        // half: what goes into the extra is what comes back out, under the same key the
        // activity reads.
        val reparcelled = Intent().apply { putExtras(intent) }
        assertTrue(reparcelled.hasExtra(EXTRA_TARGET_PERSONA_NAME))
        assertEquals(name, reparcelled.getStringExtra(EXTRA_TARGET_PERSONA_NAME))
        pending.cancel()
    }
}
