package com.nikosm.voiceassistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File

/**
 * Per-persona home-screen shortcuts: one launcher icon that opens straight into that
 * persona's chat.
 *
 * This file adds no launch path of its own. A shortcut is just another producer of the deep
 * link the spontaneous-message notifications already use — the same [EXTRA_TARGET_PERSONA_NAME]
 * extra, read by `MainActivity.handleSpontaneousDeepLink` and applied by
 * `AssistantService.applySpontaneousDeepLink`. A shortcut tap therefore inherits every
 * property that path already has: it interrupts an in-flight turn on another persona exactly
 * as a manual picker tap does, it leaves room mode, and a persona that has since been deleted
 * is consumed harmlessly instead of crashing.
 *
 * **The id IS the persona name**, because [Persona] carries no stable id field. That is what
 * makes the deep link resolve at tap time, and it is also this design's one sharp edge: a
 * rename changes the id, so a pinned icon cannot be carried across it. Renaming therefore
 * WITHDRAWS the shortcut (see `AssistantService.updatePersona`) rather than leaving a dead
 * icon that opens whichever persona happens to be selected.
 */

/**
 * Launcher icon edge, in pixels.
 *
 * Shortcut icons are expected to be at least 192px square; a bitmap smaller than the
 * launcher's requested size is upscaled and looks soft, so this is the size drawn and the
 * ceiling the artwork is downsampled to.
 */
internal const val PERSONA_SHORTCUT_ICON_SIZE = 192

/** Namespaces this app's persona shortcuts, so no other shortcut id can collide with them. */
private const val PERSONA_SHORTCUT_ID_PREFIX = "persona_"

/**
 * Stable shortcut id for [personaName].
 *
 * The raw name, prefixed — deliberately not a hash. Persona names are unique (the app refuses
 * a duplicate add and refuses a rename onto an existing name), so the name is already a
 * collision-free key, and keeping it readable is what makes
 * [ShortcutManagerCompat.removeLongLivedShortcuts] targetable and `dumpsys shortcut` legible.
 * A hash would risk two personas sharing one home-screen icon.
 */
internal fun personaShortcutId(personaName: String): String =
    PERSONA_SHORTCUT_ID_PREFIX + personaName

/**
 * The intent a shortcut tap delivers.
 *
 * `ACTION_VIEW` is not decoration: `ShortcutInfo.Builder` rejects an intent with no action, so
 * the plain intent the notification path builds would not be a legal shortcut at all.
 *
 * The flags match `postSpontaneousNotification`'s: `SINGLE_TOP` routes a tap that lands while
 * the app is already open to `onNewIntent` instead of stacking a second instance, and
 * `NEW_TASK` covers the launcher's cold-start path.
 */
internal fun personaShortcutIntent(context: Context, personaName: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_TARGET_PERSONA_NAME, personaName)
    }


/**
 * The icon's pixels: the persona's own artwork when it has any, otherwise a badge drawn the
 * way [ProviderLogo] draws the in-app one.
 *
 * Split out from [personaShortcutIcon] so the size and fallback contract are assertable
 * without going through `IconCompat` (whose bitmap accessor is `@RestrictTo`).
 *
 * The fill is [Persona.themeColor] rather than the provider color on purpose: a home screen
 * shows many app icons side by side, and the persona's identity color is what tells two of
 * this app's shortcuts apart. A missing or undecodable artwork file falls back to the drawn
 * badge, so a persona always has an icon.
 */
internal fun personaShortcutIconBitmap(persona: Persona): Bitmap {
    val artwork = persona.iconImageUri
        ?.takeIf { it.isNotBlank() }
        ?.let { path -> runCatching { decodeSquareArtwork(File(path)) }.getOrNull() }
    return artwork ?: drawPersonaBadge(persona)
}

/** [personaShortcutIconBitmap] wrapped as the launcher's icon type. */
internal fun personaShortcutIcon(persona: Persona): IconCompat =
    IconCompat.createWithBitmap(personaShortcutIconBitmap(persona))

/**
 * Center-crops an app-owned artwork file to a square [PERSONA_SHORTCUT_ICON_SIZE] bitmap,
 * downsampling on the way in so a full-resolution photo is never fully decoded just to
 * produce an icon.
 *
 * Returns null when the file is gone or undecodable — the caller falls back to the drawn
 * badge rather than showing an empty icon.
 */
private fun decodeSquareArtwork(file: File): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val target = PERSONA_SHORTCUT_ICON_SIZE
    var sample = 1
    // Same power-of-two walk SettingsManager's decodeDownsampled uses: halve while the
    // result would still be at least the target, so the decode never exceeds it by much.
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null

    val edge = minOf(decoded.width, decoded.height)
    if (edge <= 0) return null
    val square = Bitmap.createBitmap(
        decoded,
        (decoded.width - edge) / 2,
        (decoded.height - edge) / 2,
        edge,
        edge
    )
    val scaled = Bitmap.createScaledBitmap(square, target, target, true)
    // createBitmap/createScaledBitmap may hand back the source itself when the request is a
    // no-op, so each recycle is guarded against taking out a bitmap still in use.
    if (square !== decoded && square !== scaled) square.recycle()
    if (scaled !== decoded && scaled !== square) decoded.recycle()
    return scaled
}

/**
 * A filled circle in the persona's theme color with its provider glyph in white — the same
 * letter [ProviderLogo] shows, with the persona's initial standing in for an empty glyph.
 */
private fun drawPersonaBadge(persona: Persona): Bitmap {
    val size = PERSONA_SHORTCUT_ICON_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f

    canvas.drawCircle(
        radius,
        radius,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = persona.themeColor.toArgb() }
    )

    val glyph = persona.providerIcon.ifBlank { persona.name.take(1) }.take(1).uppercase()
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = size * 0.52f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    // Vertical centering from the font's own metrics, or the glyph sits on its baseline and
    // reads as off-center in a circle.
    val metrics = text.fontMetrics
    canvas.drawText(glyph, radius, radius - (metrics.ascent + metrics.descent) / 2f, text)
    return bitmap
}

/**
 * The shortcut exactly as the launcher will store it.
 *
 * Split out from [requestPersonaShortcut] so the shape (id, labels, icon, intent) is
 * assertable on-device without a launcher actually placing anything.
 */
internal fun buildPersonaShortcut(context: Context, persona: Persona): ShortcutInfoCompat =
    ShortcutInfoCompat.Builder(context, personaShortcutId(persona.name))
        .setShortLabel(persona.name)
        .setLongLabel("Open ${persona.name}'s chat")
        .setIcon(personaShortcutIcon(persona))
        .setIntent(personaShortcutIntent(context, persona.name))
        // Long-lived so the app can withdraw it again: removeLongLivedShortcuts only touches
        // shortcuts marked this way, and a persona that is renamed or deleted must not leave
        // a dead icon on the home screen.
        .setLongLived(true)
        .build()

/** Whether this launcher supports pinning at all — some OEM launchers do not. */
internal fun isPersonaShortcutSupported(context: Context): Boolean =
    ShortcutManagerCompat.isRequestPinShortcutSupported(context)

/**
 * Ask the launcher to pin [persona]'s shortcut.
 *
 * Best-effort and non-throwing: the system shows its own confirmation dialog, and a device
 * that refuses (unsupported launcher, the user declining, a device policy) must leave the
 * settings screen working — the same contract [QuickSettingsTileRow] has with
 * `requestAddTileService`.
 *
 * The return value says whether the REQUEST was accepted, never whether an icon was placed:
 * only the launcher knows that.
 */
internal fun requestPersonaShortcut(context: Context, persona: Persona): Boolean {
    if (!isPersonaShortcutSupported(context)) return false
    return runCatching {
        ShortcutManagerCompat.requestPinShortcut(
            context,
            buildPersonaShortcut(context, persona),
            null
        )
    }.getOrDefault(false)
}

/**
 * Withdraw [personaName]'s shortcut, if one exists.
 *
 * Called when the persona is renamed or deleted. Because the id is the name, either one
 * orphans the pinned icon — and an icon that opens a name which no longer exists would land
 * the user on whichever persona happens to be selected, which is worse than no icon at all.
 * The user can always re-add it from the persona list.
 *
 * Best-effort: a launcher that refuses is not a reason to fail the rename or the delete.
 */
internal fun removePersonaShortcut(context: Context, personaName: String) {
    runCatching {
        ShortcutManagerCompat.removeLongLivedShortcuts(
            context,
            listOf(personaShortcutId(personaName))
        )
    }
}

