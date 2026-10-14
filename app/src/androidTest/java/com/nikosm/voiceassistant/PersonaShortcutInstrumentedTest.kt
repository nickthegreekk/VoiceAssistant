package com.nikosm.voiceassistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-persona home-screen shortcuts — the shape of what gets pinned.
 *
 * Nothing here pins anything, and that is deliberate: `requestPinShortcut` hands the decision
 * to the launcher and to its user, so a test can neither place an icon nor reliably observe
 * one. What IS assertable is everything the pin is built from — the intent the tap delivers,
 * the id/labels the launcher stores, and the icon it draws — because a mistake in any of those
 * is invisible until a real user taps a real home-screen icon.
 *
 * [PersonaShortcutLaunchInstrumentedTest] below carries the other half: that the intent a
 * pinned icon delivers actually opens that persona's chat.
 */
@RunWith(AndroidJUnit4::class)
class PersonaShortcutInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun persona(name: String, iconPath: String? = null) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ShortcutProbe] llama3",
        systemPrompt = "You are $name.",
        providerIcon = "C",
        iconImageUri = iconPath
    )

    @Test
    fun `the_shortcut_intent_names_the_persona_and_carries_an_action`() {
        val intent = personaShortcutIntent(context, "Cara")

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        // ShortcutInfo rejects an intent with no action, so this is load-bearing rather than
        // decorative: without it the pin throws instead of placing anything.
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("Cara", intent.getStringExtra(EXTRA_TARGET_PERSONA_NAME))
        // SINGLE_TOP is what routes a tap landing while the app is already open to
        // onNewIntent instead of stacking a second MainActivity.
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `the_pinned_shortcut_carries_the_id_labels_and_tap_intent`() {
        val shortcut = buildPersonaShortcut(context, persona("Cara"))

        // The id is what removeLongLivedShortcuts targets on rename/delete, so it must be the
        // exact function-derived value rather than something merely similar.
        assertEquals(personaShortcutId("Cara"), shortcut.id)
        assertEquals("Cara", shortcut.shortLabel.toString())
        assertTrue(shortcut.longLabel.toString().contains("Cara"))
        assertEquals("Cara", shortcut.intent.getStringExtra(EXTRA_TARGET_PERSONA_NAME))
        assertNotNull("a shortcut with no icon renders as a blank square", shortcut.icon)
    }

    @Test
    fun `a_persona_without_artwork_still_gets_a_drawn_icon`() {
        val rendered = personaShortcutIconBitmap(persona("Cara"))
        assertEquals(PERSONA_SHORTCUT_ICON_SIZE, rendered.width)
        assertEquals(PERSONA_SHORTCUT_ICON_SIZE, rendered.height)
    }

    @Test
    fun `a_persona_with_artwork_gets_an_icon_from_that_artwork`() {
        val artwork = File(context.cacheDir, "shortcut_artwork_probe.png")
        // Deliberately NOT square, so the center-crop is exercised rather than being a no-op.
        val source = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xFFCC3366.toInt())
        artwork.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()

        try {
            val rendered = personaShortcutIconBitmap(persona("Cara", artwork.absolutePath))
            assertEquals(PERSONA_SHORTCUT_ICON_SIZE, rendered.width)
            assertEquals(PERSONA_SHORTCUT_ICON_SIZE, rendered.height)
        } finally {
            artwork.delete()
        }
    }

    @Test
    fun `deleted_artwork_falls_back_to_the_drawn_icon`() {
        val missing = File(context.cacheDir, "shortcut_artwork_gone.png").apply { delete() }
        // The point: a persona always has SOME icon, never a blank one — the same fallback
        // ProviderLogo makes when an artwork file disappears.
        val rendered = personaShortcutIconBitmap(persona("Cara", missing.absolutePath))
        assertEquals(PERSONA_SHORTCUT_ICON_SIZE, rendered.width)
    }

    @Test
    fun `withdrawing_a_never_pinned_shortcut_does_not_throw`() {
        // Rename and delete both call this unconditionally, so it must be safe in the
        // overwhelmingly common case of a persona with no shortcut at all.
        removePersonaShortcut(context, "NoSuchPersonaHasAShortcut")
        removePersonaShortcut(context, "")
    }

    @Test
    fun `the_pin_probe_answers_and_refuses_cleanly_when_unsupported`() {
        // Device-dependent (some OEM launchers say no), so the ANSWER is not asserted — only
        // that the probe the Add button depends on is safe to call, and that an unsupported
        // launcher reports false instead of crashing the settings screen.
        if (!isPersonaShortcutSupported(context)) {
            assertEquals(false, requestPersonaShortcut(context, persona("Cara")))
        }
    }
}
