package com.nikosm.voiceassistant.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Static fallback schemes — the app's identity colors. They are `internal` rather
// than private so the instrumented Adaptive Theme tests can assert exact equality
// (i.e. that the static theme really is what gets used when dynamic is off or the
// platform cannot provide dynamic color).
internal val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = Color.White,
    onSurface = Color.White
)

internal val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = LightBackground,
    surface = LightSurface,
    onBackground = Color.Black,
    onSurface = Color.Black
)

// Adaptive Theme (Settings > General > Appearance). Material You's dynamic-color
// API (`dynamicLightColorScheme` / `dynamicDarkColorScheme`) arrived with Android
// 12 / API 31, so below that the app must fall back to the static scheme instead
// of pretending it applied. The app's minSdk is 31 today, which makes this gate
// always true on an installable device — it is kept (and unit-tested) so that
// lowering minSdk later degrades into a documented fallback rather than a crash,
// and so the Settings toggle can explain the reason instead of silently no-opping.
internal fun isDynamicColorSupported(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/**
 * Resolves the base [ColorScheme] the app paints with.
 *
 * [sdkInt] is a parameter (defaulted at the call site to [Build.VERSION.SDK_INT])
 * purely so both branches — dynamic and the older-API fallback — are reachable
 * from an instrumented test on a real device.
 */
@Composable
internal fun baseColorScheme(dynamicColor: Boolean, darkTheme: Boolean, sdkInt: Int): ColorScheme =
    when {
        dynamicColor && isDynamicColorSupported(sdkInt) -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        // Fallback: unsupported platform, or the Adaptive Theme toggle is off.
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

@Composable
fun VoiceAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Adaptive Theme: when the Settings toggle is on (and the platform supports it)
    // the base scheme is derived from the user's wallpaper. Persona accents are NOT
    // remapped here — callers keep painting `persona.themeColor` directly (mic ring,
    // badges, HUD starfield/planet), so each persona stays visually distinct on top
    // of whatever the wallpaper produces.
    dynamicColor: Boolean = false, // Disabled by default to maintain the green/amber vibe
    content: @Composable () -> Unit
) {
    val colorScheme = baseColorScheme(dynamicColor, darkTheme, Build.VERSION.SDK_INT)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
