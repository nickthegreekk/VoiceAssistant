package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spontaneous messages — the cloud guard on the IN-MEMORY write path, proven on-device.
 *
 * The pure decision (`spontaneousEligibility` / `SpontaneousPolicy.sanitize`) and the
 * persistence chokepoint (`SettingsManager.savePersonas`, including `importBackup`) are
 * covered on the JVM by [SpontaneousGuardTest]. What only a real service can show is that
 * `AssistantService.addPersona` / `updatePersona` actually APPLY the guard: the service
 * holds the live `personas` flow the UI renders from, so a persona that reached it enabled
 * would be both visible and schedulable even if storage later corrected it.
 *
 * The realistic regression these pin is the one the plan calls out: `isCloud` is DERIVED
 * from the model string by the persona editor, so a user can enable spontaneous messages on
 * a local persona and then switch its model to a cloud provider without ever revisiting the
 * feature. `updatePersona` is the only thing standing between that sequence and an enabled
 * cloud persona.
 *
 * Each cloud test relies on the probe provider being genuinely registered; the first test
 * pins that, so a cloud test cannot pass for the wrong reason (an unregistered "cloud"
 * provider would also be stripped, but as gateway-only rather than by the cloud rule).
 */
@RunWith(AndroidJUnit4::class)
class SpontaneousWriteGuardInstrumentedTest {

    private companion object {
        const val LOCAL_SERVER = "ProbeOllamaSpontaneous"
        const val CLOUD_PROVIDER = "ProbeCloudSpontaneous"
        const val LOCAL_MODEL = "[$LOCAL_SERVER] llama3"
        const val CLOUD_MODEL = "[$CLOUD_PROVIDER] probe-model"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var personasBefore: List<Persona> = emptyList()
    private var ollamaBefore: List<ServerConfig> = emptyList()
    private var cloudApisBefore: List<CloudApiSetting> = emptyList()
    private var customCloudBefore: List<CloudApiSetting> = emptyList()
    private var currentPersonaBefore: String? = null
    private var messagesOwnerBefore: String? = null

    @Before
    fun bindAssistantService() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { /* not expected here */ }
        }
        connection = conn
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!

        personasBefore = service.personas.value
        ollamaBefore = service._ollamaBaseUrls.value
        cloudApisBefore = service._cloudApis.value
        customCloudBefore = service._customCloudApis.value
        currentPersonaBefore = service.currentPersonaName
        messagesOwnerBefore = service.messagesOwnerName

        // Register the probe server/provider, dropping any probe personas a previous run
        // left behind (a crashed run would otherwise accumulate them).
        mainSync {
            service._ollamaBaseUrls.value =
                ollamaBefore.filterNot { it.name == LOCAL_SERVER } +
                    ServerConfig(LOCAL_SERVER, "http://10.0.0.9:11434")
            service._customCloudApis.value =
                customCloudBefore.filterNot { it.name == CLOUD_PROVIDER } +
                    CloudApiSetting(
                        name = CLOUD_PROVIDER, baseUrl = "https://$CLOUD_PROVIDER.test",
                        apiKey = "probe", icon = "C", color = Color(0xFF4ADE80)
                    )
        }
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching {
            instrumentation.runOnMainSync {
                // Restore through the public API, in reverse so indices stay valid
                // (`_personas` is private, so this is the only way back).
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service._ollamaBaseUrls.value = ollamaBefore
                service._cloudApis.value = cloudApisBefore
                service._customCloudApis.value = customCloudBefore
                service.currentPersonaName = currentPersonaBefore
                service.messagesOwnerName = messagesOwnerBefore
                // addPersona/updatePersona persist, so restoring memory alone is not enough.
                service.saveSettings()
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun localPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = LOCAL_MODEL,
        systemPrompt = "You are $name.", isCloud = false, spontaneousEnabled = enabled
    )

    private fun cloudPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = CLOUD_MODEL,
        systemPrompt = "You are $name.", isCloud = true, spontaneousEnabled = enabled
    )

    private fun stored(name: String): Persona =
        service.personas.value.firstOrNull { it.name == name }
            ?: error("persona '$name' is not in the service's list")

    private fun indexOf(name: String): Int =
        service.personas.value.indexOfFirst { it.name == name }
            .also { require(it >= 0) { "persona '$name' is not in the service's list" } }

    // ---- non-vacuity: the probe really is the routing shape each test assumes ------------

    @Test fun `the probe servers are registered for the roles the tests assume`() {
        val ollama = service.ollamaServerNames()
        val cloud = service.cloudProviderNames()
        assertTrue("$LOCAL_SERVER must be a configured Ollama server", LOCAL_SERVER in ollama)
        assertTrue("$CLOUD_PROVIDER must be a configured cloud provider", CLOUD_PROVIDER in cloud)
        assertTrue(
            "the probe cloud persona must really be CLOUD-eligible, or the cloud tests below " +
                "would pass for the wrong reason (blocked as gateway-only, not as cloud)",
            cloudPersona("Probe", false).roomEligibility(ollama, cloud) == RoomEligibility.Eligible(cloud = true)
        )
    }

    // ---- addPersona ---------------------------------------------------------------------

    @Test fun `addPersona strips spontaneousEnabled from a cloud persona`() {
        mainSync { service.addPersona(cloudPersona("ProbeCloudAdd", enabled = true)) }
        assertFalse(
            "the service must not hold an enabled cloud persona, even if a caller asks for one",
            stored("ProbeCloudAdd").spontaneousEnabled
        )
    }

    @Test fun `addPersona keeps spontaneousEnabled on a local persona`() {
        // The inverse control: a guard that stripped everything would pass the test above.
        mainSync { service.addPersona(localPersona("ProbeLocalAdd", enabled = true)) }
        assertTrue(stored("ProbeLocalAdd").spontaneousEnabled)
    }

    // ---- updatePersona ------------------------------------------------------------------

    @Test fun `updatePersona strips the flag when a local persona is switched to a cloud model`() {
        // THE realistic sequence: enable the feature on a local persona, then change its
        // model to a cloud provider. isCloud is derived from the model string, so nothing
        // else in the app would notice.
        mainSync { service.addPersona(localPersona("ProbeSwitch", enabled = true)) }
        assertTrue("precondition: the local persona is enabled", stored("ProbeSwitch").spontaneousEnabled)

        mainSync {
            service.updatePersona(
                indexOf("ProbeSwitch"),
                stored("ProbeSwitch").copy(model = CLOUD_MODEL, isCloud = true)
            )
        }
        assertFalse(
            "switching the model to a cloud provider must turn the feature off",
            stored("ProbeSwitch").spontaneousEnabled
        )
    }

    @Test fun `updatePersona keeps the flag while the model stays local`() {
        mainSync { service.addPersona(localPersona("ProbeStay", enabled = true)) }

        mainSync {
            service.updatePersona(
                indexOf("ProbeStay"),
                stored("ProbeStay").copy(systemPrompt = "You are ProbeStay, now chattier.")
            )
        }
        assertTrue(
            "an unrelated edit must not silently switch the feature off",
            stored("ProbeStay").spontaneousEnabled
        )
    }

    @Test fun `a persona whose Ollama server is not configured cannot keep the flag`() {
        mainSync {
            service.addPersona(
                localPersona("ProbeGateway", enabled = true).copy(model = "[NoSuchServer] llama3")
            )
        }
        assertFalse(
            "a gateway-only persona is history-blind and must not be enabled",
            stored("ProbeGateway").spontaneousEnabled
        )
    }

    // ---- arming, not just flagging -----------------------------------------------------
    //
    // Everything above proves the FLAG is right. These prove the flag reaches WorkManager
    // when it is set — the gap `addPersona` had: it armed from `settingsManager.getPersonas()`
    // while `saveSettings()` had only SCHEDULED its debounced write, so a brand-new
    // spontaneous-enabled persona read back as null and was actively DISARMED (and had its
    // notification cancelled) instead of armed, until some later settings load ran the
    // safety net.

    @Test fun `a spontaneous-enabled persona is armed as soon as it is added`() {
        val name = "ProbeArmedOnAdd"
        mainSync { service.addPersona(armedPersona(name)) }

        assertNotPersistedYet(name)
        assertTrue(
            "addPersona must arm the persona it just created, without waiting for a settings " +
                "load — nothing else re-arms it, and the debounced save never calls this",
            waitUntilArmed(name)
        )
    }

    @Test fun `switching spontaneous messages on re-arms the persona immediately`() {
        // The same staleness reached updatePersona: the re-arm read the OLD, still-disabled
        // persona off disk and disarmed it.
        val name = "ProbeArmedOnToggle"
        mainSync { service.addPersona(armedPersona(name).copy(spontaneousEnabled = false)) }
        mainSync {
            service.updatePersona(indexOf(name), stored(name).copy(spontaneousEnabled = true))
        }

        assertNotPersistedYet(name)
        assertTrue(
            "turning the feature on must arm the chain immediately",
            waitUntilArmed(name)
        )
    }

    /**
     * The debounced write cannot have landed yet: `saveSettings()` sleeps
     * `SAVE_DEBOUNCE_MS` on IO, and these assertions still run inside the main-thread block
     * that did the add. If this ever starts failing, the save became synchronous — which
     * would make the tests above pass for the wrong reason (arming off a completed write).
     */
    private fun assertNotPersistedYet(name: String) {
        val storedOnDisk = runCatching {
            service.settingsManager.getPersonas()?.firstOrNull { it.name == name }
        }.getOrNull()
        assertNull(
            "precondition: storage must not know about '$name' yet, or this test would prove " +
                "nothing about arming from memory",
            storedOnDisk
        )
    }

    private fun waitUntilArmed(name: String, timeoutMs: Long = 30_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isSpontaneouslyArmed(context, name)) return true
            SystemClock.sleep(100)
        }
        return isSpontaneouslyArmed(context, name)
    }

    /**
     * A local, ELIGIBLE persona with the feature on. The window spans the whole day so the
     * test does not depend on what time it is on the device — the chain is enqueued either
     * way, but an out-of-window clamp would make the assertion depend on the hour.
     */
    private fun armedPersona(name: String) = localPersona(name, enabled = true).copy(
        spontaneousWindowStartMin = 0,
        spontaneousWindowEndMin = 24 * 60 - 1
    )
}

