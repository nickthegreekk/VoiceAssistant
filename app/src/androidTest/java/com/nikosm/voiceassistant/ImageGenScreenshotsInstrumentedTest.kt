package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Captures the two screens this feature changed, for review.
 *
 * Written to the app's internal files, then copied out with
 * `adb exec-out run-as com.nikosm.voiceassistant cat files/screenshots/<name> > <local>`, which
 * works on any API level and needs no storage permission. Each test prints its path and size so a
 * run log records exactly what was captured.
 */
@RunWith(AndroidJUnit4::class)
class ImageGenScreenshotsInstrumentedTest {

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

    /** A representative list: two A1111 entries and one ComfyUI entry. */
    private fun entries() = listOf(
        ServerConfig("Local A1111", "http://192.168.1.50:7860"),
        ServerConfig("Backup A1111", "http://192.168.1.51:7860"),
        ServerConfig("Attic Comfy", "http://192.168.1.9:8188", backendType = ImageGenBackend.COMFYUI)
    )

    private fun capture(name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT $name -> ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        assertTrue("the capture must not be empty", bitmap.width > 0 && bitmap.height > 0)
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun imageGenSettingsShowsTheSwitchAndBothSections() {
        // Deterministic: the switch's position must not depend on which test ran before this one.
        mainSync { service.setActiveImageGenBackend(ImageGenBackend.A1111) }
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
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
        composeRule.waitForIdle()
        capture("image-gen-settings.png")
    }

    @Test
    fun personaEditorShowsFollowTheActiveGenerator() {
        // The default a persona is created with: no pin at all, so it follows the switch.
        capturePersonaEditor("persona-editor-follow.png", backendUrl = "")
    }

    @Test
    fun personaEditorShowsTheStalePinNote() {
        // The reported bug's state: the persona still pins an address no entry has. It now says so
        // and names the server that will be used instead.
        capturePersonaEditor("persona-editor-stale-pin.png", backendUrl = "http://192.168.1.50:9999")
    }

    private fun capturePersonaEditor(name: String, backendUrl: String) {
        // The editor describes what will actually be used, so the services list and the active
        // backend must both be populated before it mounts.
        mainSync {
            service._imageGenBases.value = entries()
            service.setActiveImageGenBackend(ImageGenBackend.COMFYUI)
        }
        val persona = Persona(
            name = "Painter",
            themeColor = Color(0xFF4ADE80),
            model = "",
            systemPrompt = "You paint what the user asks for, in one image.",
            isImageGenerator = true,
            backendUrl = backendUrl
        )
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        PersonaEditor(persona = persona, onSave = { }, onCancel = { }, service = service)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // The image-backed block sits below the name and the two checkboxes, so scroll it into view
        // before capturing — otherwise the screenshot would be of the top of the editor.
        composeRule.onNodeWithText("Image Backend").performScrollTo()
        composeRule.waitForIdle()
        capture(name)
    }
}
