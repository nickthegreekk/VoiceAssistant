package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
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
 * The persona editor's COMMIT, driven through the real composable against the real service.
 *
 * The debounced auto-save and the dispose-time flush used to build the Persona separately: the
 * debounce derived `isCloud`/`providerIcon` from the model, the flush preserved the values of the
 * persona as it was OPENED. So switching a persona from a local model to a cloud provider and
 * closing the editor inside the 500ms debounce window stored the STALE pair. Both paths now go
 * through `Persona.withEditorFields`, so this can no longer happen.
 *
 * The test does exactly that sequence: type a cloud model, close IMMEDIATELY (the compose clock
 * is never advanced past the debounce, so only the dispose flush runs), then read the STORED
 * persona back.
 */
@RunWith(AndroidJUnit4::class)
class PersonaEditorCommitInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Before
    fun bind() {
        val bound = CountDownLatch(1)
        var svc: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                svc = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connection = conn
        assertTrue(
            "bindService refused",
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        )
        assertTrue("service never bound", bound.await(30, TimeUnit.SECONDS))
        service = svc!!
    }

    @After
    fun unbind() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /** Reads the persona from DISK, which is where the commit (debounce or flush) lands. */
    private fun storedPersona(name: String): Persona? =
        service.settingsManager.getPersonas().orEmpty().firstOrNull { it.name == name }

    private fun awaitStoredPersona(name: String, predicate: (Persona) -> Boolean): Persona? {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val p = storedPersona(name)
            if (p != null && predicate(p)) return p
            Thread.sleep(25)
        }
        return storedPersona(name)
    }

    /** Renders the real editor the way PersonaSettings does, with [open] driven from outside. */
    @Composable
    private fun Editor(snapshot: Persona, open: Boolean) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                if (open) {
                    PersonaEditor(
                        persona = snapshot,
                        onSave = { updated ->
                            val index = service.personas.value.indexOfFirst { it.name == snapshot.name }
                            if (index != -1) service.updatePersona(index, updated)
                        },
                        onCancel = {},
                        service = service
                    )
                }
            }
        }
    }

    @Test
    fun closingWithinTheDebounceWindowStillCommitsTheCloudIdentity() {
        // A cloud provider the editor resolves against, added BEFORE it mounts so the editor's
        // collectors have delivered by the first settled frame.
        onMain {
            service.addCustomCloudApi("ProbeCloud", "http://127.0.0.1:1/probe-cloud", "k")
            service.addPersona(
                Persona(
                    name = "CommitProbe",
                    themeColor = Color(0xFF4ADE80),
                    model = "[HomeBox] llama3",
                    systemPrompt = "You are CommitProbe.",
                    isCloud = false,
                    providerIcon = "O"
                )
            )
        }
        val snapshot = service.personas.value.first { it.name == "CommitProbe" }

        var open by mutableStateOf(true)
        composeRule.setContent { Editor(snapshot, open) }
        composeRule.waitForIdle()

        // Switch the model local → cloud through the REAL model field.
        composeRule.onNodeWithTag("personaModelField").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("personaModelField").performTextInput("[ProbeCloud] some-model")
        composeRule.waitForIdle()

        // Close IMMEDIATELY. The compose clock is deliberately NOT advanced, so the 500ms
        // debounce has not fired — only the dispose flush commits.
        onMain { open = false }
        composeRule.waitForIdle()

        val stored = awaitStoredPersona("CommitProbe") { it.model == "[ProbeCloud] some-model" }
        assertTrue("the model edit must reach disk; stored=$stored", stored != null)
        assertTrue(
            "isCloud must be DERIVED from the cloud model, not the opened persona's false; stored=$stored",
            stored!!.isCloud
        )
        assertEquals(
            "providerIcon must be the derived cloud badge, not the opened persona's local 'O'; stored=$stored",
            "C",
            stored.providerIcon
        )
    }
}
