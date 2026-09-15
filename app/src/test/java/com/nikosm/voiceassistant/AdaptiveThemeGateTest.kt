package com.nikosm.voiceassistant

import com.nikosm.voiceassistant.ui.theme.isDynamicColorSupported
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Adaptive Theme (Material You) API-level gate.
 *
 * Material You's dynamic color API landed in Android 12 (API 31). The app's minSdk
 * is 31 today, so no installable device can be below the gate — which is exactly why
 * this is tested here rather than on hardware: it is the only cheap way to prove the
 * older-Android fallback decision, and it keeps the fallback honest if minSdk is ever
 * lowered (the theme must degrade to the static scheme, never crash).
 */
class AdaptiveThemeGateTest {

    @Test
    fun dynamicColorUnsupportedBelowAndroid12() {
        assertFalse("API 21 must not be offered dynamic color", isDynamicColorSupported(21))
        assertFalse("API 29 must not be offered dynamic color", isDynamicColorSupported(29))
        assertFalse("API 30 (Android 11) must not be offered dynamic color", isDynamicColorSupported(30))
    }

    @Test
    fun dynamicColorSupportedFromAndroid12() {
        assertTrue("API 31 (Android 12) supports dynamic color", isDynamicColorSupported(31))
        assertTrue("API 33 (attached test device) supports dynamic color", isDynamicColorSupported(33))
        assertTrue("API 37 (targetSdk) supports dynamic color", isDynamicColorSupported(37))
    }

    /**
     * The Settings switch is *disabled*, not a silent no-op, below Android 12 — so the
     * user is told why the feature they just tapped is unavailable. The same copy is
     * what the composable renders, and it must name the real API level of the device.
     */
    @Test
    fun unavailableOnOldAndroidExplainsWhyInsteadOfSilentlyDoingNothing() {
        val android11 = adaptiveThemeDescription(30)
        assertTrue("must say it is unavailable: $android11", android11.contains("Unavailable"))
        assertTrue("must name the requirement: $android11", android11.contains("Android 12 (API 31)"))
        assertTrue("must report this device's actual API: $android11", android11.contains("API 30"))
        assertTrue(
            "must state the static fallback: $android11",
            android11.contains("standard theme is used instead")
        )
        assertTrue(
            "API 33's copy must not claim unavailability",
            !adaptiveThemeDescription(33).contains("Unavailable")
        )
    }
}