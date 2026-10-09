package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Image Gen screen's two-section layout, its switch, and what a rotation does to them.
 *
 * Composed on its own through [ImageGenSettings] rather than through the whole Servers page: the
 * switch and the two typed sections are the subject here, and `MainScreen` carries the long-running
 * animations that make composition tests flaky — the same reason the other screens' tests compose
 * their screen directly.
 */
@RunWith(AndroidJUnit4::class)
class ImageGenSettingsComposeTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    @Before
    fun bind() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) { }
        }
        connection = conn
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
    }

    @After
    fun tearDown() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /** Two A1111 entries and one ComfyUI entry, with distinct names so each row is findable. */
    private fun entries() = listOf(
        ServerConfig("Local A1111", "http://192.168.1.50:7860"),
        ServerConfig("Backup A1111", "http://192.168.1.51:7860"),
        ServerConfig("Attic Comfy", "http://192.168.1.9:8188", backendType = ImageGenBackend.COMFYUI)
    )

    private fun render(restoration: StateRestorationTester) {
        restoration.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ImageGenSettings(
                        service = service,
                        imageGen = entries(),
                        status = emptyMap(),
                        currentThemeColor = Color(0xFF4ADE80),
                        snackbarHostState = remember { SnackbarHostState() }
                    )
                }
            }
        }
    }

    @Test
    fun eachSectionListsItsOwnEntriesAndTheHintFollowsTheSwitch() {
        // Explicit: the active backend is service state another test class may have moved, and this
        // test asserts which entry the hint names.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.setActiveImageGenBackend(ImageGenBackend.A1111)
        }
        render(StateRestorationTester(composeRule))
        composeRule.waitForIdle()

        // Every entry is rendered, under the section for its own type, and each section has its OWN
        // "+" — named after the section — so an entry's type can never be picked wrong.
        composeRule.onNodeWithText("Local A1111").assertIsDisplayed()
        composeRule.onNodeWithText("Backup A1111").assertIsDisplayed()
        composeRule.onNodeWithText("Attic Comfy").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add ${ImageGenBackend.A1111.displayName} servers").assertExists()
        composeRule.onNodeWithContentDescription("Add ${ImageGenBackend.COMFYUI.displayName} servers").assertExists()

        // The hint names the FIRST entry of the active type — the one a request will actually use.
        composeRule.onNode(hasText("Image requests use “Local A1111”", substring = true)).assertExists()

        composeRule.onNodeWithText(ImageGenBackend.COMFYUI.displayName).performClick()
        composeRule.waitForIdle()

        composeRule.onNode(hasText("Image requests use “Attic Comfy”", substring = true)).assertExists()
        assertEquals(
            "the switch must reach the persisted setting, not just the UI",
            ImageGenBackend.COMFYUI,
            service.activeImageGenBackend.value
        )
    }

    @Test
    fun theActiveChoiceAndTheOpenDialogSurviveARotation() {
        val restoration = StateRestorationTester(composeRule)
        render(restoration)
        composeRule.waitForIdle()

        // Switch to ComfyUI and start adding an entry in THAT section.
        composeRule.onNodeWithText(ImageGenBackend.COMFYUI.displayName).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Add ${ImageGenBackend.COMFYUI.displayName} servers").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("RotateProbe")

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        // The active generator lives on the service, so it cannot be lost; the dialog's typed name
        // is `rememberSaveable`, so it comes back; and the section the dialog belongs to is a
        // PARAMETER of the composable, so it cannot drift to the other backend either.
        composeRule.onNode(hasText("Image requests use “Attic Comfy”", substring = true)).assertExists()
        composeRule.onNodeWithText("Add Server").assertExists()
        composeRule.onNodeWithText("RotateProbe").assertExists()
        composeRule.onNodeWithContentDescription("Add ${ImageGenBackend.COMFYUI.displayName} servers").assertExists()
    }
}
