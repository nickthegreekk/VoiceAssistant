package com.nikosm.voiceassistant

import android.app.WallpaperManager
import android.os.Build
import android.util.Log
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nikosm.voiceassistant.ui.theme.DarkColorScheme
import com.nikosm.voiceassistant.ui.theme.LightColorScheme
import com.nikosm.voiceassistant.ui.theme.baseColorScheme
import com.nikosm.voiceassistant.ui.theme.isDynamicColorSupported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device proof for the Adaptive Theme (Settings > General > Appearance).
 *
 * The interesting failure mode for this feature is a *silent no-op*: the switch flips,
 * the flag persists, but the resolved scheme never actually becomes the wallpaper
 * scheme. So every adaptive case here asserts equality against the platform's own
 * `dynamicDark/LightColorScheme(context)` result, not merely "something changed".
 *
 * A second instrumented test cannot run on the same device, so `sdkInt` is injected:
 * [baseColorScheme] takes it as a parameter precisely so the Android 11-and-below
 * fallback can be executed here on an API 33 device through the real production code
 * path instead of being asserted by inspection.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveThemeInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun resolve(dynamicColor: Boolean, darkTheme: Boolean, sdkInt: Int): ColorScheme {
        val captured = AtomicReference<ColorScheme>()
        composeRule.setContent { captured.set(baseColorScheme(dynamicColor, darkTheme, sdkInt)) }
        composeRule.waitForIdle()
        return captured.get()
    }

    /** Field-wise comparison: ColorScheme is compared by value, never by identity. */
    private fun assertSameScheme(expected: ColorScheme, actual: ColorScheme, tag: String) {
        assertEquals("$tag.primary", expected.primary, actual.primary)
        assertEquals("$tag.onPrimary", expected.onPrimary, actual.onPrimary)
        assertEquals("$tag.secondary", expected.secondary, actual.secondary)
        assertEquals("$tag.tertiary", expected.tertiary, actual.tertiary)
        assertEquals("$tag.background", expected.background, actual.background)
        assertEquals("$tag.surface", expected.surface, actual.surface)
        assertEquals("$tag.onSurface", expected.onSurface, actual.onSurface)
        assertEquals("$tag.error", expected.error, actual.error)
    }

    private fun describe(tag: String, scheme: ColorScheme) {
        Log.i(TAG, "$tag primary=${scheme.primary} background=${scheme.background} " +
            "surface=${scheme.surface} onSurface=${scheme.onSurface} secondary=${scheme.secondary}")
    }

    /** Diagnostic only: what the platform extracted from the current wallpaper. */
    private fun observeWallpaperColors() {
        val colors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        Log.i(TAG, "wallpaperColors=$colors")
    }

    @Test
    fun deviceIsApi31OrNewerSoDynamicColorIsSupported() {
        assertTrue(
            "Attached device reports API ${Build.VERSION.SDK_INT}; dynamic color needs 31+",
            isDynamicColorSupported(Build.VERSION.SDK_INT)
        )
    }

    /** Toggle OFF (the default) must resolve to the app's existing static scheme, exactly. */
    @Test
    fun toggleOffUsesStaticDarkScheme() {
        val resolved = resolve(dynamicColor = false, darkTheme = true, sdkInt = Build.VERSION.SDK_INT)
        assertSameScheme(DarkColorScheme, resolved, "static-dark")
        describe("static-dark", resolved)
    }

    @Test
    fun toggleOffUsesStaticLightScheme() {
        val resolved = resolve(dynamicColor = false, darkTheme = false, sdkInt = Build.VERSION.SDK_INT)
        assertSameScheme(LightColorScheme, resolved, "static-light")
    }

    /** Toggle ON must resolve to the platform's wallpaper scheme — not a no-op. */
    @Test
    fun toggleOnUsesPlatformWallpaperSchemeInDarkMode() {
        val resolved = resolve(dynamicColor = true, darkTheme = true, sdkInt = Build.VERSION.SDK_INT)
        assertSameScheme(dynamicDarkColorScheme(context), resolved, "dynamic-dark")
        assertNotEquals(
            "dynamic dark primary must differ from the static theme",
            DarkColorScheme.primary, resolved.primary
        )
        assertNotEquals(
            "dynamic dark background must differ from the static theme",
            DarkColorScheme.background, resolved.background
        )
        observeWallpaperColors()
        describe("dynamic-dark", resolved)
    }

    @Test
    fun toggleOnUsesPlatformWallpaperSchemeInLightMode() {
        val resolved = resolve(dynamicColor = true, darkTheme = false, sdkInt = Build.VERSION.SDK_INT)
        assertSameScheme(dynamicLightColorScheme(context), resolved, "dynamic-light")
        assertNotEquals(
            "dynamic light primary must differ from the static theme",
            LightColorScheme.primary, resolved.primary
        )
    }

    /** Pre-API-31 hardware: dynamic requested but unavailable → static scheme, no crash. */
    @Test
    fun olderApiFallsBackToStaticSchemeInsteadOfCrashing() {
        // One setContent per test (the rule forbids a second call), so both fallback
        // branches are resolved side by side in a single composition.
        val dark = AtomicReference<ColorScheme>()
        val light = AtomicReference<ColorScheme>()
        composeRule.setContent {
            dark.set(baseColorScheme(dynamicColor = true, darkTheme = true, sdkInt = 30))
            light.set(baseColorScheme(dynamicColor = true, darkTheme = false, sdkInt = 29))
        }
        composeRule.waitForIdle()

        assertSameScheme(DarkColorScheme, dark.get(), "fallback-dark")
        assertSameScheme(LightColorScheme, light.get(), "fallback-light")
        assertTrue("gate must reject API 30", !isDynamicColorSupported(30))
    }

    /**
     * The Settings switch, end to end and on the device: the exact path MainActivity
     * collects (`service.adaptiveTheme`) plus the prefs write that survives a restart.
     *
     * The service is constructed directly and WITHOUT onCreate(), which is what keeps
     * this a focused settings check instead of booting TTS/ONNX/server-health — while
     * still running the REAL setter, the REAL StateFlow, and the REAL SettingsManager.
     */
    @Test
    fun adaptiveThemeToggleDrivesTheServiceFlowAndPersistsBothWays() {
        val service = AssistantService()
        service.settingsManager = SettingsManager(context)
        val original = service.settingsManager.getAdaptiveTheme()
        try {
            assertFalse(
                "the toggle's own default must be OFF, so a user who never touches it " +
                    "keeps the existing static theme (prefs only override this on load)",
                service.adaptiveTheme.value
            )

            service.setAdaptiveTheme(true)
            assertTrue(
                "ON must re-emit on the flow MainActivity collects, so the root scheme " +
                    "re-derives from the wallpaper immediately",
                service.adaptiveTheme.value
            )
            assertTrue(
                "ON must persist — a fresh SettingsManager (i.e. the next launch) sees it",
                SettingsManager(context).getAdaptiveTheme()
            )

            service.setAdaptiveTheme(false)
            assertFalse(
                "OFF must re-emit false, reverting the root scheme to the static theme",
                service.adaptiveTheme.value
            )
            assertFalse(
                "OFF must persist, or the next launch would silently re-enable it",
                SettingsManager(context).getAdaptiveTheme()
            )
        } finally {
            // Never leave a runner-flipped value behind on the developer device.
            service.setAdaptiveTheme(original)
        }
    }

    /**
     * The feature's other half: the wallpaper-derived base must not swallow the persona
     * accent system. Every persona-tinted element (mic ring, message + response-time
     * badges, HUD planet / starfield / controls, settings highlights) is painted
     * straight from `persona.themeColor` and never read back out of the ColorScheme —
     * asserted here against the REAL wallpaper scheme, so a wallpaper that happens to
     * land on a persona colour would be caught rather than silently washing an accent
     * out. The built-in accents must also stay distinct from each other.
     */
    @Test
    fun personaAccentsStayDistinctOnTopOfTheDynamicBaseScheme() {
        val dynamicDark = AtomicReference<ColorScheme>()
        val dynamicLight = AtomicReference<ColorScheme>()
        composeRule.setContent {
            dynamicDark.set(baseColorScheme(dynamicColor = true, darkTheme = true, sdkInt = Build.VERSION.SDK_INT))
            dynamicLight.set(baseColorScheme(dynamicColor = true, darkTheme = false, sdkInt = Build.VERSION.SDK_INT))
        }
        composeRule.waitForIdle()

        val accents = (DEFAULT_PERSONAS + CLOUD_PERSONAS + TRANSLATOR_PERSONA)
            .map { it.name to it.themeColor }
        assertEquals(
            "built-in personas must keep pairwise-distinct accents",
            accents.size, accents.map { it.second }.distinct().size
        )

        val roles = listOf<Pair<String, (ColorScheme) -> Color>>(
            "primary" to { scheme: ColorScheme -> scheme.primary },
            "secondary" to { scheme: ColorScheme -> scheme.secondary },
            "tertiary" to { scheme: ColorScheme -> scheme.tertiary },
            "background" to { scheme: ColorScheme -> scheme.background },
            "surface" to { scheme: ColorScheme -> scheme.surface },
            "surfaceVariant" to { scheme: ColorScheme -> scheme.surfaceVariant }
        )

        listOf("dynamic-dark" to dynamicDark.get(), "dynamic-light" to dynamicLight.get())
            .forEach { (tag, scheme) ->
                describe(tag, scheme)
                accents.forEach { (personaName, accent) ->
                    roles.forEach { (role, read) ->
                        assertNotEquals(
                            "persona '$personaName' accent must stay distinct from $tag.$role",
                            read(scheme), accent
                        )
                    }
                }
            }
    }

    private companion object {
        const val TAG = "AdaptiveThemeTest"
    }
}