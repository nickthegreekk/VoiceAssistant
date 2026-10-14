package com.nikosm.voiceassistant

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import java.io.File

/** The image's resting size — the viewer never lets the user zoom out past it. */
private const val MIN_SCALE = 1f

/** The hard ceiling on pinch-zoom, so a generation cannot be blown up into mush. */
private const val MAX_SCALE = 8f

/** Where a double-tap lands when the image is at its resting size. */
private const val DOUBLE_TAP_SCALE = 3f

/**
 * The largest pan, in pixels, that still keeps a `ContentScale.Fit` image covering its
 * container — one value per axis, symmetric about the centre.
 *
 * This is what stops a zoomed image from being flung off screen and lost: the pan the gesture
 * hands us is added to the current offset and then clamped to these bounds, so at rest
 * (scale 1, and at any scale where the image is still smaller than its container on an axis)
 * the limit is exactly 0 and the image snaps back to centre.
 *
 * Pure in / pure out — no Compose or Android types — so the geometry is pinned by
 * [FullScreenImageViewerBoundsTest] on the JVM rather than eyeballed on a device.
 *
 * @param imageAspect the image's own width/height ratio (its intrinsic size, not the
 *  container's). A non-positive value means "not loaded yet" and yields no panning.
 */
internal fun imagePanBounds(
    containerWidth: Float,
    containerHeight: Float,
    imageAspect: Float,
    scale: Float
): Pair<Float, Float> {
    if (containerWidth <= 0f || containerHeight <= 0f || imageAspect <= 0f) return 0f to 0f

    // How `ContentScale.Fit` lays the image out: it is limited by whichever axis it runs out
    // of first, leaving letterboxing on the other.
    val containerAspect = containerWidth / containerHeight
    val displayedWidth: Float
    val displayedHeight: Float
    if (imageAspect >= containerAspect) {
        // Wider than the container: width-limited, letterboxed vertically.
        displayedWidth = containerWidth
        displayedHeight = containerWidth / imageAspect
    } else {
        // Taller than the container: height-limited, pillarboxed horizontally.
        displayedHeight = containerHeight
        displayedWidth = containerHeight * imageAspect
    }

    val maxX = ((displayedWidth * scale - containerWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((displayedHeight * scale - containerHeight) / 2f).coerceAtLeast(0f)
    return maxX to maxY
}

/**
 * A black, edge-to-edge overlay showing [file] at its full resolution.
 *
 * Opened by tapping a message image (see [ChatMessageBubble]). Interaction:
 *  - **pinch** to zoom (1x..[MAX_SCALE]x) and drag to pan while zoomed;
 *  - **double-tap** toggles between the resting size and [DOUBLE_TAP_SCALE]x;
 *  - **single tap** or the close button dismisses it.
 *
 * `decorFitsSystemWindows = false` lets the black surface run under the status and
 * navigation bars, so the picture really is full-bleed rather than inset.
 */
@Composable
fun FullScreenImageViewer(
    file: File,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // The image's intrinsic ratio, learned from Coil once the decode succeeds. 1f until then.
    var imageAspect by remember { mutableFloatStateOf(1f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { containerSize = it }
        ) {
            AsyncImage(
                model = file,
                contentDescription = "Full screen image",
                contentScale = ContentScale.Fit,
                onSuccess = { success ->
                    val width = success.result.image.width
                    val height = success.result.image.height
                    if (width > 0 && height > 0) imageAspect = width.toFloat() / height.toFloat()
                },
                modifier = Modifier
                    .fillMaxSize()
                    // Registered BEFORE the transform detector on purpose: a movement-free tap
                    // produces no slop, so the transform detector never consumes it and the tap
                    // still arrives here. A drag/zoom passes slop, is consumed by the detector
                    // below, and therefore never reads as a dismiss.
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onDismiss() },
                            onDoubleTap = {
                                scale = if (scale > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE
                                // Either way the image re-centres, which is also what the
                                // clamped pan below would force on the next gesture.
                                offsetX = 0f
                                offsetY = 0f
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            val (maxX, maxY) = imagePanBounds(
                                containerWidth = containerSize.width.toFloat(),
                                containerHeight = containerSize.height.toFloat(),
                                imageAspect = imageAspect,
                                scale = newScale
                            )
                            scale = newScale
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

