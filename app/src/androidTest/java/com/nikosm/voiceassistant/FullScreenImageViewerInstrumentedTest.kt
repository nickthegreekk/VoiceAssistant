package com.nikosm.voiceassistant

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Single-tap-to-full-screen on a message image.
 *
 * The viewer is a Compose `Dialog` in its own window, so the interesting assertions are
 * cross-root: the thumbnail in the bubble's tree must be tappable, and the tap must produce a
 * node in the DIALOG's tree. A test that only checked the thumbnail would pass even if the
 * dialog never opened.
 *
 * Both image kinds are covered because they are two separate blocks in [ChatMessageBubble]
 * (the user's attachment and the assistant's generation) and either one could silently lose
 * its tap handler.
 *
 * Dismissal is exercised twice on purpose: the close button is an ordinary click, while a
 * single tap on the picture goes through `detectTapGestures`' double-tap window — so the
 * latter needs a real-time wait rather than an immediate assertion.
 */
@RunWith(AndroidJUnit4::class)
class FullScreenImageViewerInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var imageFile: File

    @Before
    fun setUp() {
        imageFile = File(context.cacheDir, "fullscreen_viewer_probe.png")
        // A real, decodable PNG: Coil must actually succeed for the viewer's image node to
        // lay out, so a zero-byte placeholder would not prove anything.
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3366CC.toInt())
        imageFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }

    @After
    fun tearDown() {
        imageFile.delete()
    }

    @Test
    fun tappingAGeneratedImageOpensTheViewerAndTheCloseButtonDismissesIt() {
        composeRule.setContent {
            MaterialTheme {
                ChatMessageBubble(
                    message = ChatMessage(
                        role = "assistant",
                        text = "a serene mountain lake",
                        generatedImagePath = imageFile.absolutePath
                    ),
                    displayText = "a serene mountain lake",
                    personaColor = Color(0xFF4ADE80)
                )
            }
        }

        // The thumbnail's tap target is the clickable Surface, whose semantics merge the
        // AsyncImage's content description — so the description IS the clickable node.
        composeRule.onNodeWithContentDescription("Generated image")
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithContentDescription("Full screen image").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Close").performClick()
        assertViewerGone()
    }

    @Test
    fun tappingAnAttachedImageOpensTheViewerAndASingleTapOnThePictureDismissesIt() {
        composeRule.setContent {
            MaterialTheme {
                ChatMessageBubble(
                    message = ChatMessage(
                        role = "user",
                        text = "",
                        imagePath = imageFile.absolutePath
                    ),
                    displayText = "",
                    personaColor = Color(0xFF4ADE80)
                )
            }
        }

        composeRule.onNodeWithContentDescription("Attached image")
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithContentDescription("Full screen image").assertIsDisplayed()

        // A plain tap on the picture. `detectTapGestures` holds `onTap` back for the length of
        // the double-tap window, so the dismissal lands a moment after performClick returns.
        composeRule.onNodeWithContentDescription("Full screen image").performClick()
        assertViewerGone()
    }

    /**
     * Waits for the viewer's window to go away.
     *
     * `waitUntil` rather than a bare assertion: the dialog leaves through a real-time gesture
     * timeout (see above) and through a `remember` write, neither of which is complete by the
     * time `performClick` returns.
     */
    private fun assertViewerGone() {
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithContentDescription("Full screen image")
                .fetchSemanticsNodes().isEmpty()
        }
    }
}
