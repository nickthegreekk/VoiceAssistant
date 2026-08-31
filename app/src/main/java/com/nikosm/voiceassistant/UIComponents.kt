package com.nikosm.voiceassistant

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface

@Composable
fun ResponseTimeBadge(
    responseTimeMs: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.2f)),
        modifier = modifier
    ) {
        Text(
            text = "${String.format(java.util.Locale.US, "%.1f", responseTimeMs / 1000.0)}s",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun ProviderLogo(
    icon: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 28.dp,
    isCloud: Boolean = false
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(
                width = 1.5.dp, 
                color = if (isCloud) Color.Red else Color(0xFF4ADE80), 
                shape = CircleShape
            )
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        when (icon) {
            "O" -> Icon(Icons.Default.Memory, null, tint = Color.Black, modifier = Modifier.size(size * 0.6f))
            "G" -> Icon(Icons.Default.Language, null, tint = Color(0xFF4285F4), modifier = Modifier.size(size * 0.6f))
            "A" -> Icon(Icons.Default.Architecture, null, tint = Color(0xFFD97755), modifier = Modifier.size(size * 0.6f))
            "D" -> Icon(Icons.Default.Cyclone, null, tint = Color(0xFF4D6BFE), modifier = Modifier.size(size * 0.6f))
            "T" -> Icon(Icons.Default.Translate, null, tint = Color(0xFF06B6D4), modifier = Modifier.size(size * 0.6f))
            else -> {
                Text(
                    text = icon,
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MicRing(
    state: AssistantState,
    muted: Boolean,
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseDurationMs = when (state) {
        AssistantState.SPEAKING -> 800
        AssistantState.THINKING -> 900
        AssistantState.LISTENING -> 1200
        else -> 3000
    }

    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseDurationMs, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    Box(
        modifier = Modifier
            .size(size * 1.6f)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (!muted && state != AssistantState.IDLE) {
            // Increased to 5 rings for a smoother "trail" effect
            listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f).forEach { delay ->
                val rippleProgress = (progress + delay) % 1f
                val rippleScale = 1f + (rippleProgress * 0.9f)
                
                // alpha starts at 0.8 (close to mic brightness) and fades to 0 as it expands
                val rippleAlpha = (1f - rippleProgress).let { it * it } * 0.7f

                Box(
                    modifier = Modifier
                        .size(size)
                        .scale(rippleScale)
                        .clip(CircleShape)
                        .border(
                            // Border gets thinner as it expands to emphasize the "fading trail"
                            width = (1.0.dp * (1f - rippleProgress)).coerceAtLeast(0.5.dp),
                            color = color.copy(alpha = rippleAlpha), 
                            shape = CircleShape
                        )
                )
            }
        }

        val coreScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = if (state != AssistantState.IDLE) 1.12f else 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = pulseDurationMs / 2, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "coreScale"
        )

        Box(
            modifier = Modifier
                .size(size)
                .scale(coreScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            color.copy(alpha = if (state != AssistantState.IDLE) 0.4f else 0.2f),
                            color.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
                .border(
                    width = 2.5.dp,
                    color = color.copy(alpha = if (state != AssistantState.IDLE) 0.8f else 0.5f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(size * 0.72f)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (muted) "Microphone muted" else "Microphone active",
                    tint = if (muted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else color,
                    modifier = Modifier.size(size * 0.28f)
                )
            }
        }
    }
}

@Composable
fun RoundIconButton(
    modifier: Modifier = Modifier,
    active: Boolean,
    activeColor: Color,
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) activeColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(iconSize)
        )
    }
}
