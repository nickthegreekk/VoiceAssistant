package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Spontaneous messages — the persona editor's section: the render gate, the controls, and
 * the manual "send now" affordance.
 *
 * ## The gate's source of truth (read this before changing anything here)
 *
 * [spontaneousSectionEligibility] is evaluated by the CALLER against the **service's live**
 * server lists — `service.ollamaServerNames()` / `service.cloudProviderNames()` — and the
 * same verdict is threaded into both the render gate below and the editor's commit, so the
 * two can never disagree. It is never evaluated from a `collectAsState()` snapshot of those
 * same flows.
 *
 * That distinction is load-bearing, and it is why this file exposes a pure function rather
 * than having the section collect for itself: a Compose snapshot starts at the flow's
 * *initial* value and fills in asynchronously from a `LaunchedEffect`, so
 * `MutableStateFlow(emptyList())` reads as `emptyList()` during the editor's FIRST
 * composition and for every frame until the collector delivers. Handing that empty Ollama
 * set to [Persona.spontaneousEligibility] makes a perfectly local persona
 * (`[HomeBox] llama3`) resolve as gateway-only — i.e. **Blocked** — so the section would
 * blink out on the first frame and pop back in. Worse, the editor's commit path runs off
 * the same verdict, so a save landing inside that window would strip a valid opt-in.
 *
 * The service has no such window: it loads its lists in `onCreate`, and the
 * `onServiceConnected` callback (which is what hands the editor a service at all) fires
 * only after `onCreate` returns. Reading the service is therefore correct on every frame,
 * including the first. See `SpontaneousEditorUiInstrumentedTest`, which pins the
 * first-frame case deliberately rather than only the settled state.
 *
 * [spontaneousSectionEligibility] is the pure decision, parameterised on the sets so it can
 * be exercised on the JVM.
 */
internal fun spontaneousSectionEligibility(
    persona: Persona,
    model: String,
    isTranslator: Boolean,
    isImageGenerator: Boolean,
    ollamaServerNames: Set<String>,
    cloudProviderNames: Set<String>
): RoomEligibility {
    val trimmed = model.trim()
    // `isCloud` is re-derived from the LIVE model text plus the LIVE server sets — never
    // taken from `persona.isCloud`, which is stale by construction while the model field is
    // being edited (the editor only writes it on commit).
    //
    // This matters for the REASON, not just the verdict: [Persona.spontaneousEligibility]
    // delegates to `roomEligibility`, whose cloud branch keys off the flag, so a
    // just-typed cloud model with a stale flag would otherwise fall through to the
    // "gateway-only" reason. That reason is true but it lies about *why* — the user is
    // looking at a cloud model, and the gate must say the cloud sentence, not a routing
    // technicality. A gateway-only model (no cloud flag either way) still gets the
    // gateway reason, unchanged.
    val displayServer = if (trimmed.startsWith("[") && trimmed.contains("] ")) {
        trimmed.substring(1, trimmed.indexOf("]"))
    } else {
        null
    }
    val resolvedCloud = persona.isCloud || (displayServer != null && displayServer in cloudProviderNames)
    return persona
        .copy(
            model = trimmed,
            isCloud = resolvedCloud,
            isTranslator = isTranslator,
            isImageGenerator = isImageGenerator
        )
        .spontaneousEligibility(ollamaServerNames, cloudProviderNames)
}

// ---------------------------------------------------------------- slider <-> value

/** Largest selectable gap: 24 hours. */
internal const val SPONTANEOUS_GAP_MAX_MINUTES = 24 * 60

/**
 * Gap slider detent, deliberately equal to the platform floor. Every detent is then a legal
 * value, so the clamp is structural rather than a correction applied behind the user's back.
 */
internal const val SPONTANEOUS_GAP_STEP_MINUTES = SpontaneousPolicy.MIN_GAP_MINUTES

/** Window sliders move in half-hour steps — the finest granularity worth offering. */
internal const val SPONTANEOUS_WINDOW_STEP_MINUTES = 30

internal const val SPONTANEOUS_DAY_MINUTES = 24 * 60

/**
 * The window the toggle materialises the first time it is switched on: 08:00–22:00. Picking
 * a concrete default beats starting an overnight window (start == end == 00:00, which reads
 * as a full day and would surprise nobody until 3am).
 */
internal const val DEFAULT_WINDOW_START_MIN = 8 * 60
internal const val DEFAULT_WINDOW_END_MIN = 22 * 60

/**
 * Snap a gap slider position to the value that gets stored: 15-minute detents, clamped to
 * `[MIN_GAP_MINUTES, SPONTANEOUS_GAP_MAX_MINUTES]`.
 *
 * Clamping here as well as in [SpontaneousPolicy.effectiveGapMinutes] is deliberate
 * belt-and-braces: the policy clamp is what protects the scheduler from a value that
 * arrived by any other route, and this one is what guarantees the editor can never even
 * produce one.
 */
internal fun spontaneousGapFromSlider(value: Float): Int {
    val snapped = Math.round(value / SPONTANEOUS_GAP_STEP_MINUTES) * SPONTANEOUS_GAP_STEP_MINUTES
    return snapped.coerceIn(SpontaneousPolicy.MIN_GAP_MINUTES, SPONTANEOUS_GAP_MAX_MINUTES)
}

/**
 * Snap a window slider position to a minute-of-day: 30-minute detents, clamped to
 * `[0, 1440 - step]` so a bound can never land on the exclusive end of the day.
 */
internal fun spontaneousWindowMinuteFromSlider(value: Float): Int {
    val snapped = Math.round(value / SPONTANEOUS_WINDOW_STEP_MINUTES) * SPONTANEOUS_WINDOW_STEP_MINUTES
    return snapped.coerceIn(0, SPONTANEOUS_DAY_MINUTES - SPONTANEOUS_WINDOW_STEP_MINUTES)
}


// ---------------------------------------------------------------- summaries

/** "08:00" / "22:30" for a minute-of-day. Minutes are taken modulo a day. */
internal fun formatMinuteOfDay(minute: Int): String =
    String.format(java.util.Locale.US, "%02d:%02d", (minute / 60) % 24, minute % 60)

/**
 * Human summary of the delivery window. An overnight window (start > end) is labelled as
 * such rather than shown as a nonsense reversed range; `start == end` reads as a full day,
 * which is what a wrapping zero-length window means — the plan's documented convention.
 */
internal fun spontaneousWindowSummary(start: Int?, end: Int?): String = when {
    start == null && end == null -> "any time of day"
    start != null && end != null ->
        "${formatMinuteOfDay(start)}–${formatMinuteOfDay(end)}" +
            if (start > end) " (overnight)" else ""
    start != null -> "after ${formatMinuteOfDay(start)}"
    else -> "before ${formatMinuteOfDay(end!!)}"
}

/** "4h" / "90m". */
internal fun formatGap(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

/** One-line delivery summary for the section subtitle. */
internal fun spontaneousScheduleSummary(
    gapMinutes: Int,
    isDefaultGap: Boolean,
    start: Int?,
    end: Int?
): String =
    "at least ${formatGap(gapMinutes)} apart" +
        (if (isDefaultGap) " (default)" else "") +
        ", ${spontaneousWindowSummary(start, end)}"

// ---------------------------------------------------------------- the section

/**
 * The persona editor's Spontaneous Messages section.
 *
 * [ollamaServerNames] / [cloudProviderNames] MUST be the caller's LIVE
 * `service.ollamaServerNames()` / `service.cloudProviderNames()` values — see the file
 * header for why a collected snapshot must never be passed here.
 */
@Composable
internal fun PersonaSpontaneousSection(
    eligibility: RoomEligibility,
    enabled: Boolean,
    instruction: String,
    gapMinutes: Int,
    windowStartMin: Int?,
    windowEndMin: Int?,
    onEnabledChange: (Boolean) -> Unit,
    onInstructionChange: (String) -> Unit,
    onGapChange: (Int) -> Unit,
    onWindowChange: (Int?, Int?) -> Unit,
    onSendNow: () -> Unit
) {
    if (eligibility !is RoomEligibility.Eligible) {
        // The gate's other half: say WHY, using the same wording constant the guard uses,
        // so the editor and the enforcement can never describe one rule two ways.
        Text(
            text = (eligibility as RoomEligibility.Blocked).reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        return
    }

    // A stored 0 means "use the policy default", so the slider opens on the default rather
    // than at the floor. Dragging it therefore pins a concrete value — which is the honest
    // outcome: the user has now made an explicit choice.
    val isDefaultGap = gapMinutes <= 0
    val sliderGap = SpontaneousPolicy.effectiveGapMinutes(gapMinutes)
        .coerceAtMost(SPONTANEOUS_GAP_MAX_MINUTES)
    val windowOn = windowStartMin != null || windowEndMin != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Let this persona message you on its own", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (enabled) {
                        "Currently ${spontaneousScheduleSummary(sliderGap, isDefaultGap, windowStartMin, windowEndMin)}."
                    } else {
                        "Local (Ollama) personas only."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("spontaneousMasterSwitch")
            )
        }

        if (enabled) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = instruction,
                onValueChange = onInstructionChange,
                label = { Text("What should it talk about? (optional)") },
                placeholder = { Text("e.g. check in naturally about what we were discussing") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
            Text(
                text = "Left blank, it decides for itself from your recent conversation.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            Spacer(Modifier.height(8.dp))
            ParameterSlider(
                label = "Minimum gap between messages",
                value = sliderGap.toFloat(),
                range = SpontaneousPolicy.MIN_GAP_MINUTES.toFloat()..SPONTANEOUS_GAP_MAX_MINUTES.toFloat(),
                onValueChange = { onGapChange(spontaneousGapFromSlider(it)) },
                format = { formatGap(spontaneousGapFromSlider(it)) },
                lowLabel = "Every ${formatGap(SpontaneousPolicy.MIN_GAP_MINUTES)}",
                highLabel = "Every ${formatGap(SPONTANEOUS_GAP_MAX_MINUTES)}"
            )

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Only between certain hours", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Keeps it from messaging you overnight. Android decides the exact moment, so treat these as limits it may run later than.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Switch(
                    checked = windowOn,
                    modifier = Modifier.testTag("spontaneousWindowSwitch"),
                    onCheckedChange = { on ->
                        // Materialise a sensible default window when switched on, clear both
                        // bounds when switched off — `null` on both is the model's "no bound".
                        onWindowChange(
                            if (on) windowStartMin ?: DEFAULT_WINDOW_START_MIN else null,
                            if (on) windowEndMin ?: DEFAULT_WINDOW_END_MIN else null
                        )
                    }
                )
            }

            if (windowOn) {
                ParameterSlider(
                    label = "Do not message before",
                    value = (windowStartMin ?: DEFAULT_WINDOW_START_MIN).toFloat(),
                    range = 0f..(SPONTANEOUS_DAY_MINUTES - SPONTANEOUS_WINDOW_STEP_MINUTES).toFloat(),
                    onValueChange = { onWindowChange(spontaneousWindowMinuteFromSlider(it), windowEndMin) },
                    format = { formatMinuteOfDay(spontaneousWindowMinuteFromSlider(it)) },
                    lowLabel = "00:00",
                    highLabel = "23:30"
                )
                ParameterSlider(
                    label = "Do not message after",
                    value = (windowEndMin ?: DEFAULT_WINDOW_END_MIN).toFloat(),
                    range = 0f..(SPONTANEOUS_DAY_MINUTES - SPONTANEOUS_WINDOW_STEP_MINUTES).toFloat(),
                    onValueChange = { onWindowChange(windowStartMin, spontaneousWindowMinuteFromSlider(it)) },
                    format = { formatMinuteOfDay(spontaneousWindowMinuteFromSlider(it)) },
                    lowLabel = "00:00",
                    highLabel = "23:30"
                )
                Text(
                    text = "An end earlier than the start means an overnight window.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            Text(
                text = "Android decides when background work actually runs. Messages arrive after the " +
                    "minimum gap and inside the hours you pick, but they can be later than you expect — " +
                    "especially while the phone is idle, in Battery Saver, or off the charger.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }

        // Deliberately OUTSIDE the enabled block: while the master switch is off it renders
        // disabled rather than vanishing. Hiding it would make this section's most useful
        // debugging control findable only once the feature is already on — and turning the
        // feature on is exactly why a user comes here (it also mirrors the entry point,
        // which refuses an "off" persona with its own message rather than not existing).
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSendNow,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("spontaneousSendNow")
        ) {
            Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Send me a message now")
        }
    }
}

@Preview(name = "Eligible — disabled", showBackground = true)
@Composable
internal fun PersonaSpontaneousSectionEligiblePreview() {
    val persona = Persona(
        name = "Bob", themeColor = androidx.compose.ui.graphics.Color(0xFF4ADE80),
        model = "[HomeBox] llama3", systemPrompt = "You are Bob."
    )
    MaterialTheme {
        androidx.compose.foundation.layout.Column {
            PersonaSpontaneousSection(
                eligibility = persona.spontaneousEligibility(setOf("HomeBox"), setOf("OpenAI")),
                enabled = false,
                instruction = "",
                gapMinutes = 0,
                windowStartMin = null,
                windowEndMin = null,
                onEnabledChange = {},
                onInstructionChange = {},
                onGapChange = {},
                onWindowChange = { _, _ -> },
                onSendNow = {}
            )
        }
    }
}

@Preview(name = "Blocked — cloud", showBackground = true)
@Composable
internal fun PersonaSpontaneousSectionBlockedPreview() {
    MaterialTheme {
        androidx.compose.foundation.layout.Column {
            PersonaSpontaneousSection(
                eligibility = RoomEligibility.Blocked(SpontaneousPolicy.CLOUD_BLOCK_REASON),
                enabled = false,
                instruction = "",
                gapMinutes = 0,
                windowStartMin = null,
                windowEndMin = null,
                onEnabledChange = {},
                onInstructionChange = {},
                onGapChange = {},
                onWindowChange = { _, _ -> },
                onSendNow = {}
            )
        }
    }
}

