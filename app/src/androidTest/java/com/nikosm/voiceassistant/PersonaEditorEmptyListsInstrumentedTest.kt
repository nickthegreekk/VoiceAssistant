package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Closing the editor on its FIRST frame — before the cloud/server lists it collects have
 * delivered — must not rewrite the persona's cloud identity.
 *
 * The editor's `cloudApis`/`customCloudApis`/`ollamaBases` are collected INSIDE it by a
 * `LaunchedEffect`, so on the first composition they still read `emptyList()`. A builder that
 * derived `isCloud`/`providerIcon` from THOSE (empty) copies would, on a close inside that
 * window, store `isCloud=false` and the local badge for a persona whose model is a cloud
 * provider. The shared builder therefore takes its cloud inputs from the SERVICE's
 * already-loaded state (the same source `spontaneousSectionEligibility` reads).
 *
 * The frozen-clock technique (as `SpontaneousEditorUiInstrumentedTest`): `autoAdvance = false`
 * holds the first frame so no `LaunchedEffect` collector has delivered, while the bound
 * service's own lists have been loaded since `onCreate`. The editor is then unmounted — its
 * dispose flush is the only commit — and the STORED persona is read back.
 */
@RunWith(AndroidJUnit4::class)
class PersonaEditorEmptyListsInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val CLOUD_PROVIDER = "ProbeCloudEmptyLists"
        const val CLOUD_MODEL = "[$CLOUD_PROVIDER] probe-model"
        const val PERSONA = "ProbeCloudPersona"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null
    private var personasBefore: List<Persona> = emptyList()
    private var customCloudBefore: List<CloudApiSetting> = emptyList()

    private fun mainSync(block: () -> Unit) =
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
        runBlocking { service.awaitPersonaHistoryIo() }
        personasBefore = service.personas.value
        customCloudBefore = service._customCloudApis.value
    }

    @After
    fun tearDown() {
        runCatching {
            mainSync {
                service.personas.value.indexOfFirst { it.name == PERSONA }
                    .takeIf { it >= 0 }?.let { service.removePersona(it) }
                service._customCloudApis.value = customCloudBefore
                service.saveSettings()
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun storedPersona(): Persona? =
        service.settingsManager.getPersonas().orEmpty().firstOrNull { it.name == PERSONA }

    private fun awaitStoredPersona(predicate: (Persona) -> Boolean): Persona? {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val p = storedPersona()
            if (p != null && predicate(p)) return p
            Thread.sleep(25)
        }
        return storedPersona()
    }

    private fun installFixtures() {
        mainSync {
            service.personas.value.indexOfFirst { it.name == PERSONA }
                .takeIf { it >= 0 }?.let { service.removePersona(it) }
            // The provider is configured in the SERVICE, loaded since onCreate — exactly the
            // state the editor's collected copies will not have delivered on the first frame.
            service._customCloudApis.value =
                customCloudBefore.filterNot { it.name == CLOUD_PROVIDER } +
                    CloudApiSetting(
                        name = CLOUD_PROVIDER, baseUrl = "https://$CLOUD_PROVIDER.test",
                        apiKey = "probe", icon = "C", color = Color(0xFF4ADE80)
                    )
            // The persona already carries the identity the loaded provider yields, so
            // "unchanged" is the same thing as "correct".
            service.addPersona(
                Persona(
                    name = PERSONA,
                    themeColor = Color(0xFF4ADE80),
                    model = CLOUD_MODEL,
                    systemPrompt = "You are $PERSONA.",
                    isCloud = true,
                    providerIcon = "C",
                    spontaneousEnabled = false
                )
            )
        }
    }

    /** The persona is captured OUTSIDE composition (no StateFlow read in the composable). */
    @Composable
    private fun Editor(snapshot: Persona, open: Boolean) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (open) {
                    PersonaEditor(
                        persona = snapshot,
                        onSave = { updated ->
                            val index = service.personas.value.indexOfFirst { it.name == PERSONA }
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
    fun closingOnTheFirstFrameLeavesTheCloudIdentityUnchanged() {
        installFixtures()
        val opened = service.personas.value.first { it.name == PERSONA }
        assertTrue("fixture must be a cloud persona", opened.isCloud)
        assertEquals("fixture must carry the cloud badge", "C", opened.providerIcon)
        assertFalse("fixture must not hold a cloud-inedible opt-in", opened.spontaneousEnabled)

        var open by mutableStateOf(true)
        // Hold the first frame: no LaunchedEffect collector — including the three that populate
        // this editor's `cloudApis`/`customCloudApis`/`ollamaBases` — has delivered, so those
        // local snapshots still read emptyList().
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { Editor(opened, open) }
        try {
            composeRule.waitForIdle()
            // Close IMMEDIATELY, still frozen, then advance exactly ONE frame so the unmount —
            // and therefore the dispose flush — runs while the collected lists are still empty.
            mainSync { open = false }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        // Proof the unmount (and its flush) actually ran: the editor's model field is gone.
        composeRule.onNodeWithTag("personaModelField").assertDoesNotExist()

        val stored = service.personas.value.firstOrNull { it.name == PERSONA }
        assertTrue("the persona must still be in the service's stored list; stored=$stored", stored != null)
        assertTrue(
            "isCloud must be UNCHANGED — the builder must read the SERVICE's loaded providers, " +
                "not the editor's not-yet-delivered empty copies; stored=$stored",
            stored!!.isCloud
        )
        assertEquals("providerIcon must be UNCHANGED; stored=$stored", "C", stored.providerIcon)
        assertFalse("spontaneousEnabled must be UNCHANGED; stored=$stored", stored.spontaneousEnabled)
    }
}
