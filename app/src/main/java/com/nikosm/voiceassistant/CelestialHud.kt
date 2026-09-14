package com.nikosm.voiceassistant

// Celestial HUD voice screen - the "Option 2" opt-in alternative to the classic
// voice-mode body (MicRing + transcription box + controls row). Voice-mode ONLY:
// when text mode is open the main screen renders the unchanged classic
// ControlBar (chat list + text input), so this composable never touches
// text-mode behavior.
//
// Data flow is deliberately identical to the classic body: the same
// `messages` + `revealedChars` (the TTS-duration-synced reveal animation runs
// in MainVoiceScreen) feed the HUD transcript panel, and the same callbacks
// (mic / stop / text-toggle / mute / silence / hands-free) drive it - zero new
// logic, only presentation.

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CelestialHudBody(
    state: AssistantState,
    personaColor: Color,
    voiceDuration: Int,
    streamingText: String?,
    ttsPlaybackFraction: Float?,
    ttsWordTimestamps: String?,
    muted: Boolean,
    silenced: Boolean,
    handsFreeMode: Boolean,
    sessionUsage: UsageInfo,
    messages: List<ChatMessage>,
    revealedChars: Int,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    onTextModeToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onSilenceToggle: () -> Unit,
    onHandsFreeToggle: () -> Unit
) {
    val mono = FontFamily.Monospace
    val dim = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    val faint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)

    Box(modifier = Modifier.fillMaxSize()) {
        Starfield(personaTint = personaColor, modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("● ${stateLabel(state)}", fontFamily = mono, fontSize = 10.sp,
                    letterSpacing = 2.sp, color = dim)
                Text(usageReadout(sessionUsage), fontFamily = mono, fontSize = 10.sp,
                    letterSpacing = 2.sp, color = faint)
            }

            Box(
                modifier = Modifier.fillMaxWidth().weight(1.15f),
                contentAlignment = Alignment.Center
            ) {
                // Tap the planet = tap-to-talk (start recording when IDLE,
                // stop/transcribe when LISTENING) - same semantics as the
                // classic MicRing tap.
                PlanetStage(
                    state = state,
                    personaColor = personaColor,
                    onTap = onMicClick,
                    modifier = Modifier.size(250.dp)
                )
            }

            HudTranscriptPanel(
                state = state,
                personaColor = personaColor,
                voiceDuration = voiceDuration,
                streamingText = streamingText,
                ttsPlaybackFraction = ttsPlaybackFraction,
                ttsWordTimestamps = ttsWordTimestamps,
                messages = messages,
                revealedChars = revealedChars,
                mono = mono,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).weight(1f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HudIconButton(if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                    if (muted) "Mic muted" else "Mic", muted, personaColor, onMuteToggle)
                HudIconButton(Icons.Default.Hearing,
                    if (handsFreeMode) "Hands-free on" else "Hands-free off",
                    handsFreeMode, personaColor, onHandsFreeToggle)
                val active = state == AssistantState.SPEAKING || state == AssistantState.THINKING
                HudPrimaryButton(active, personaColor, isStop = active, onClick = {
                    if (active) onStopClick() else onMicClick()
                })
                // Order mirrors the classic controls row: silence BEFORE text-mode
                HudIconButton(if (silenced) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    if (silenced) "Silenced" else "Speaking enabled", silenced, personaColor, onSilenceToggle)
                HudIconButton(Icons.Default.Keyboard, "Text mode", false, personaColor, onTextModeToggle)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun stateLabel(state: AssistantState): String = when (state) {
    AssistantState.IDLE -> "IDLE"
    AssistantState.LISTENING -> "LISTENING"
    AssistantState.THINKING -> "COMPUTING"
    AssistantState.SPEAKING -> "TRANSMITTING"
}

private fun usageReadout(usage: UsageInfo): String =
    if (usage.totalTokens > 0) "TOK ${usage.totalTokens}" else "STANDBY"

/** Seeded starfield with a slow twinkle phase - deterministic across recompositions. */
@Composable
private fun Starfield(personaTint: Color, modifier: Modifier) {
    val stars = remember {
        val rng = kotlin.random.Random(7)
        List(110) { Triple(rng.nextFloat(), rng.nextFloat(), rng.nextFloat()) } // x, y, phase
    }
    val transition = rememberInfiniteTransition(label = "stars")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "twinkle"
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        stars.forEach { (x, y, phase) ->
            val tw = 0.22f + 0.5f * (0.5f + 0.5f * sin((phase + t) * 2f * Math.PI.toFloat()))
            drawCircle(
                color = if (phase > 0.82f) personaTint.copy(alpha = tw * 0.8f)
                else Color.White.copy(alpha = tw),
                radius = if (phase > 0.9f) 2.2f else 1.4f,
                center = Offset(x * w, y * h)
            )
        }
    }
}

/** Planet + counter-rotating dashed orbits + riding moon + HUD tick ring. */
@Composable
private fun PlanetStage(
    state: AssistantState,
    personaColor: Color,
    onTap: () -> Unit,
    modifier: Modifier
) {
    val speaking = state == AssistantState.SPEAKING
    val thinking = state == AssistantState.THINKING

    val outerAngle by rememberInfiniteTransition(label = "orbit1").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(22000, easing = LinearEasing)),
        label = "outer"
    )
    val innerAngle by rememberInfiniteTransition(label = "orbit2").animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing)),
        label = "inner"
    )
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0.96f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe"
    )

    Box(
        modifier = modifier.clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(250.dp)) {
            val c = center
            val outerR = min(size.width, size.height) / 2f - 4f
            val innerR = outerR * 0.78f

            // HUD ticks at cardinal points
            listOf(0f, 90f, 180f, 270f).forEach { deg ->
                rotate(deg) {
                    drawLine(
                        color = personaColor.copy(alpha = 0.55f),
                        start = Offset(c.x, c.y - outerR - 6f),
                        end = Offset(c.x, c.y - outerR - 14f),
                        strokeWidth = 2f
                    )
                }
            }

            // Outer dashed orbit + riding moon
            rotate(outerAngle) {
                drawCircle(
                    color = personaColor.copy(alpha = 0.30f), radius = outerR, center = c,
                    style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 12f)))
                )
                translate(left = c.x - 4f, top = c.y - outerR - 4f) {
                    drawCircle(color = personaColor, radius = 4f)
                    drawCircle(color = personaColor.copy(alpha = 0.25f), radius = 9f)
                }
            }

            // Inner dashed orbit (counter-rotating)
            rotate(innerAngle) {
                drawCircle(
                    color = personaColor.copy(alpha = 0.18f), radius = innerR, center = c,
                    style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 14f)))
                )
            }

            // Persona-tinted ambient glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        personaColor.copy(alpha = 0.22f),
                        personaColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = c, radius = outerR
                ),
                radius = outerR * 0.9f, center = c
            )

            // Planet sphere (breathing)
            val r = outerR * 0.42f * breathe
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lerpColor(personaColor, Color.White, 0.55f),
                        personaColor,
                        darken(personaColor, 0.62f)
                    ),
                    center = Offset(c.x - r * 0.34f, c.y - r * 0.4f),
                    radius = r * 1.35f
                ),
                radius = r, center = c
            )
            // Specular highlight
            drawCircle(
                color = Color.White.copy(alpha = 0.28f),
                radius = r * 0.16f,
                center = Offset(c.x - r * 0.34f, c.y - r * 0.4f)
            )
            // Glow ring pulse while speaking/thinking
            if (speaking || thinking) {
                drawCircle(
                    color = personaColor.copy(alpha = 0.25f),
                    radius = r * 1.25f, center = c,
                    style = Stroke(width = 2f)
                )
            }
        }

        Text(
            text = "● ${stateLabel(state)}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            color = personaColor.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
        )
    }
}

/** Corner-bracketed mono transcript panel - mirrors the classic mini box data flow. */
@Composable
private fun HudTranscriptPanel(
    state: AssistantState,
    personaColor: Color,
    voiceDuration: Int,
    streamingText: String?,
    ttsPlaybackFraction: Float?,
    ttsWordTimestamps: String?,
    messages: List<ChatMessage>,
    revealedChars: Int,
    mono: FontFamily,
    modifier: Modifier
) {
    // Karaoke sync: the text is always fully visible; the sync engine places a
    // persona-colored highlight on the currently-spoken word (WordTimedText).
    // Scroll is split in two: the arrival effect below jumps to the newest
    // content when it lands (message/stream updates) — but NOT while the
    // chunked player is driving a highlight, where scroll ownership belongs to
    // the progress-driven teleprompter effect under it (the old
    // jump-to-bottom-and-pin left the spoken word off-screen at the top).
    val speaking = state == AssistantState.SPEAKING
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size, revealedChars, streamingText) {
        if (speaking && ttsPlaybackFraction != null) return@LaunchedEffect
        if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue)
    }
    // Auto-teleprompter: the panel shows ONLY the current response, so the
    // scrollable height IS the response — mapping the playback fraction
    // linearly onto maxValue keeps the highlighted word in view as it walks
    // from the top of the text to the bottom. Plain scrollTo (no animation):
    // fraction emissions land every ~150 ms, so each step is a few pixels, and
    // isScrollInProgress stays reserved for real user drags (which briefly win
    // over the auto-track).
    LaunchedEffect(ttsPlaybackFraction) {
        val f = ttsPlaybackFraction ?: return@LaunchedEffect
        if (!speaking) return@LaunchedEffect
        if (scroll.isScrollInProgress) return@LaunchedEffect
        if (scroll.maxValue > 0) {
            scroll.scrollTo((f * scroll.maxValue).roundToInt())
        }
    }

    Box(modifier) {
        // Corner brackets
        Canvas(modifier = Modifier.fillMaxSize()) {
            val b = 14f
            val col = personaColor.copy(alpha = 0.65f)
            fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(col, Offset(x, y), Offset(x + dx, y), strokeWidth = 2f)
                drawLine(col, Offset(x, y), Offset(x, y + dy), strokeWidth = 2f)
            }
            bracket(0f, 0f, b, b)
            bracket(size.width, 0f, -b, b)
            bracket(0f, size.height, b, -b)
            bracket(size.width, size.height, -b, -b)
        }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "TRANSMITTING - SPOKEN TEXT",
                fontFamily = mono, fontSize = 9.sp, letterSpacing = 2.sp,
                color = personaColor.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    // Required for the DstIn fade masks to apply to THIS layer
                    // instead of erasing the whole window behind it.
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        // Top fade-IN and bottom fade-OUT: opaque across the
                        // middle so the text stays fully visible between edges.
                        // (The first attempt had these inverted, erasing the
                        // middle 88% - the reported black box.)
                        drawRect(
                            brush = Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black),
                            blendMode = BlendMode.DstIn
                        )
                        drawRect(
                            brush = Brush.verticalGradient(0.92f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn
                        )
                    }
                    .verticalScroll(scroll)
            ) {
                Column {
                    // Only the CURRENT response - not the conversation history.
                    // The HUD transcript is a spoken-text display, not a chat log.
                    val currentAssistant = messages.lastOrNull()?.takeIf {
                        it.role == "assistant" && !it.isError
                    }
                    if (currentAssistant != null) {
                        WordTimedText(
                            text = currentAssistant.text,
                            durationMs = voiceDuration,
                            speaking = speaking,
                            ttsPlaybackFraction = ttsPlaybackFraction,
                            ttsWordTimestamps = ttsWordTimestamps,
                            fontFamily = mono,
                            color = MaterialTheme.colorScheme.onBackground,
                            highlightColor = personaColor
                        )
                    } else if (state == AssistantState.THINKING) {
                        Text(
                            "...",
                            fontFamily = mono, fontSize = 13.sp,
                            color = personaColor.copy(alpha = 0.7f)
                        )
                    } else {
                        Text(
                            "STANDBY - awaiting input",
                            fontFamily = mono, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                        )
                    }
                } // end Column (vertical stacking inside scroll box)
            }
        }
    }
}

/** Small square HUD icon button (glyph in a hairline-bordered rounded square). */
@Composable
private fun HudIconButton(
    glyph: ImageVector,
    description: String,
    active: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = if (active) 0.14f else 0.05f))
            .border(
                width = 1.dp,
                color = tint.copy(alpha = if (active) 0.45f else 0.16f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            glyph, description,
            tint = tint.copy(alpha = if (active) 0.95f else 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Central primary button: mic normally, solid STOP square while active. */
@Composable
private fun HudPrimaryButton(
    active: Boolean,
    tint: Color,
    isStop: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(
                color = if (isStop) tint.copy(alpha = 0.15f) else Color.Transparent,
                shape = CircleShape
            )
            .then(
                if (isStop) Modifier
                else Modifier.background(
                    brush = Brush.radialGradient(
                        listOf(tint.copy(alpha = 0.35f), tint.copy(alpha = 0.08f))
                    ),
                    shape = CircleShape
                )
            )
            .border(1.dp, tint.copy(alpha = 0.5f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isStop) {
            Box(modifier = Modifier.size(20.dp).background(tint, RoundedCornerShape(4.dp)))
        } else {
            Icon(Icons.Default.Mic, "Mic", tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

// --- small color helpers (no external deps) ---
private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)

private fun darken(c: Color, factor: Float): Color = Color(
    red = c.red * factor,
    green = c.green * factor,
    blue = c.blue * factor,
    alpha = c.alpha
)


/**
 * Stage-2 karaoke transcript: text is ALWAYS fully visible; the chunked sync
 * engine (ttsActiveWordRange) positions a persona-colored highlight on the
 * word currently being spoken. The old reveal/hide machinery (spaces for
 * unspoken words) is gone — nothing ever disappears mid-response.
 */
@Composable
private fun WordTimedText(
    text: String,
    durationMs: Int,
    speaking: Boolean,
    ttsPlaybackFraction: Float?,
    ttsWordTimestamps: String?,
    fontFamily: FontFamily,
    color: Color,
    highlightColor: Color
) {
    if (text.isBlank()) {
        Text(
            text = "",
            fontFamily = fontFamily,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        return
    }
    // Text is ALWAYS fully visible — no reveal ticker, no fallback fraction.
    // The ONLY dynamic element is the highlight on the currently-spoken word;
    // the panel's scroll follows it via the playback-fraction teleprompter
    // effect in HudTranscriptPanel (progress-driven, linear on maxValue).

    // Stage-2 karaoke: when the chunked player is active, the sync engine
    // reports the char range of the word currently being spoken; the text is
    // never hidden — the highlight rides on top of the fully-visible text.
    // Non-chunked paths: plain text, no highlight.
    val activeRange = if (ttsPlaybackFraction != null && speaking) {
        ttsActiveWordRange(text, ttsWordTimestamps, ttsPlaybackFraction, durationMs)
    } else null

    Text(
        text = highlightedAnnotated(text, activeRange, highlightColor),
        fontFamily = fontFamily,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = color,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
