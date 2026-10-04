package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Step 2 through the REAL service: unique names at the chokepoint, and the rename rewrite.
 *
 * Every assertion reads the persisted value back through a fresh [SettingsManager] rather
 * than the service's StateFlow, because the whole point is what survives to disk: an
 * in-memory flow would pass even if the next save overwrote the rewrite, which is exactly
 * the failure mode being guarded against.
 *
 * ServiceIsolationRule, and it now carries `favoriteModels` in its baseline — these tests
 * mutate it through a rename.
 */
@RunWith(AndroidJUnit4::class)
class ServerIdentityInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private val mainUrl = "http://127.0.0.1:1/step2-main"
    private val workUrl = "http://127.0.0.1:1/step2-work"

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
        assertTrue(
            "bindService refused",
            context.bindService(
                Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE
            )
        )
        assertTrue("service never bound", bound.await(30, TimeUnit.SECONDS))
        connection = conn
        service = svc!!
        // Start from a known pair of servers with distinct names, and from no leftover
        // probe personas: `addPersona` REFUSES a duplicate name (with a toast, which then
        // needs a Looper), so a probe left by a previous test would fail the next one for a
        // reason that has nothing to do with what is under test.
        PROBE_NAMES.forEach { name ->
            service.personas.value.indexOfFirst { it.name == name }
                .takeIf { it >= 0 }
                ?.let { idx ->
                    runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        service.removePersona(idx)
                    } }
                }
            service.settingsManager.deletePersonaHistory(name)
        }
        removeIfPresent("Main", mainUrl)
        removeIfPresent("Work", workUrl)
        onMain { service.addOllamaBase("Main", mainUrl) }
        onMain { service.addOllamaBase("Work", workUrl) }
    }

    /**
     * Call a service mutator on the main thread.
     *
     * The rejection paths toast, exactly as the pre-existing duplicate-URL rejection in
     * `updateOllamaBase` already does, and a Toast needs a Looper. In the app these run on
     * Main because that is where the settings UI calls them from; from an instrumentation
     * thread they must be marshalled explicitly.
     */
    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }

    private fun removeIfPresent(name: String, url: String) {
        service.ollamaBaseUrls.value.firstOrNull { it.url == url }
            ?.let { onMain { service.removeOllamaBase(it) } }
    }

    /** A reader that goes to disk, not to the in-memory flow. */
    private fun stored(): SettingsManager = SettingsManager(context)

    private fun storedPersona(name: String): Persona? =
        stored().getPersonas().orEmpty().firstOrNull { it.name == name }

    private fun storedFavorites(): List<String> = stored().getFavoriteModels().orEmpty()

    /**
     * Wait for the debounced `saveSettings()` to reach disk.
     *
     * `persistSettings` is debounced 400ms, so a read straight after a mutation can see
     * the PREVIOUS list. This waits on the real condition (the value we asked for being on
     * disk) rather than sleeping, so it is neither slow nor flaky.
     */
    private fun awaitStoredPersona(name: String, predicate: (Persona?) -> Boolean): Persona? {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val current = storedPersona(name)
            if (current != null && predicate(current)) return current
            Thread.sleep(25)
        }
        return storedPersona(name)
    }

    /** Wait for a predicate over the stored favorites. */
    private fun awaitStoredFavorites(predicate: (List<String>) -> Boolean): List<String> {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val current = storedFavorites()
            if (predicate(current)) return current
            Thread.sleep(25)
        }
        return storedFavorites()
    }

    private fun probe(name: String, model: String, spontaneous: Boolean = false) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = model,
        systemPrompt = "probe",
        isCloud = false,
        spontaneousEnabled = spontaneous
    )

    @After
    fun unbind() {
        // A spontaneous probe arms a REAL chain, keyed by persona name. ServiceIsolationRule
        // reports an armed chain as a leak \u2014 correctly, since the next test would inherit
        // it \u2014 so the probe's own chain is disarmed here rather than left for the rule.
        PROBE_NAMES.forEach { runCatching { disarmSpontaneousWork(context, it) } }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private companion object {
        val PROBE_NAMES = listOf(
            "Step2Renamed", "Step2Spont", "Step2Resave", "Step2Prefixed",
            "Step2Brackets", "Step2Delete"
        )
    }

    // ------------------------------------------------------------ unique names

    @Test
    fun `a_duplicate_ollama_name_is_refused_at_the_chokepoint`() {
        val before = service.ollamaBaseUrls.value.size
        onMain { service.addOllamaBase("Main", "http://127.0.0.1:1/step2-other") }
        val names = service.ollamaBaseUrls.value.map { it.name }
        assertEquals("the list must not grow", before, names.size)
        assertEquals(
            "and the duplicate must not be stashed under a different name",
            listOf("Main", "Work"),
            names.sorted()
        )
    }

    @Test
    fun `a_duplicate_name_differing_only_in_case_is_refused`() {
        val before = service.ollamaBaseUrls.value.size
        onMain { service.addOllamaBase("main", "http://127.0.0.1:1/step2-case") }
        assertEquals(before, service.ollamaBaseUrls.value.size)
    }

    @Test
    fun `editing_a_server_onto_an_existing_name_is_refused`() {
        val work = service.ollamaBaseUrls.value.first { it.name == "Work" }
        onMain { service.updateOllamaBase(work, "Main", "http://127.0.0.1:1/step2-hijack") }
        assertTrue(
            "the rename must not have been applied",
            service.ollamaBaseUrls.value.any { it.name == "Work" && it.url == workUrl }
        )
    }

    @Test
    fun `saving_a_server_without_changing_its_name_is_allowed`() {
        val main = service.ollamaBaseUrls.value.first { it.name == "Main" }
        onMain { service.updateOllamaBase(main, "Main", main.url) }
        assertTrue(service.ollamaBaseUrls.value.any { it.name == "Main" && it.url == mainUrl })
    }

    // ------------------------------------------------------------ rename rewrite

    @Test
    fun `a_rename_rewrites_favorites_AND_personas__read_back_from_storage`() {
        service.addPersona(probe("Step2Renamed", "[Main] llama3"))
        service.toggleFavoriteModel("[Main] llama3")
        service.toggleFavoriteModel("[Work] other:1b")
        assertTrue(
            "[Main] llama3 must be a favorite on disk first",
            awaitStoredFavorites { it.contains("[Main] llama3") }.contains("[Main] llama3")
        )
        assertTrue(
            "the persona must be on disk first",
            awaitStoredPersona("Step2Renamed") { it?.model == "[Main] llama3" } != null
        )

        val main = service.ollamaBaseUrls.value.first { it.name == "Main" }
        onMain { service.updateOllamaBase(main, "Renamed", main.url) }

        // Read from DISK, not the flow.
        assertEquals(
            "the persona's stored model must follow the rename",
            "[Renamed] llama3",
            awaitStoredPersona("Step2Renamed") { it?.model == "[Renamed] llama3" }?.model
        )
        val favs = awaitStoredFavorites { it.none { f -> f.startsWith("[Main]") } }
        assertTrue("the favorite must follow the rename: $favs", favs.contains("[Renamed] llama3"))
        assertFalse("the old prefix must be gone: $favs", favs.any { it.startsWith("[Main]") })
        assertTrue("another server's favorite must be untouched: $favs", favs.contains("[Work] other:1b"))
    }

    @Test
    fun `a_spontaneous_persona_keeps_its_opt_in_across_its_server_s_rename`() {
        service.addPersona(probe("Step2Spont", "[Main] llama3", spontaneous = true))
        assertTrue(
            "precondition: the persona is opted in on disk",
            awaitStoredPersona("Step2Spont") { it?.spontaneousEnabled == true }?.spontaneousEnabled == true
        )

        val main = service.ollamaBaseUrls.value.first { it.name == "Main" }
        onMain { service.updateOllamaBase(main, "Renamed2", main.url) }

        // The guard strips spontaneousEnabled from any persona naming an UNCONFIGURED
        // server. After the rewrite the server exists under its new name, so the opt-in
        // must still be there.
        assertEquals(
            "[Renamed2] llama3",
            awaitStoredPersona("Step2Spont") { it?.model == "[Renamed2] llama3" }?.model
        )
        assertTrue(
            "the rename must not cost the user their spontaneous opt-in",
            awaitStoredPersona("Step2Spont") { it?.spontaneousEnabled == true }?.spontaneousEnabled == true
        )
    }

    @Test
    fun `an_immediate_persona_save_after_the_rename_does_not_undo_it`() {
        service.addPersona(probe("Step2Resave", "[Main] llama3"))
        val main = service.ollamaBaseUrls.value.first { it.name == "Main" }
        onMain { service.updateOllamaBase(main, "Renamed3", main.url) }
        assertEquals(
            "[Renamed3] llama3",
            awaitStoredPersona("Step2Resave") { it?.model == "[Renamed3] llama3" }?.model
        )

        // The editor debounces and then commits a WHOLE persona rebuilt from its snapshot.
        // That save is exactly what would put the old string back if the rewrite had only
        // touched storage and not the in-memory list.
        val idx = service.personas.value.indexOfFirst { it.name == "Step2Resave" }
        assertTrue(idx >= 0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.updatePersona(idx, service.personas.value[idx].copy(systemPrompt = "edited"))
        }
        assertEquals(
            "a later persona save must not restore the old prefix",
            "[Renamed3] llama3",
            awaitStoredPersona("Step2Resave") {
                it?.model == "[Renamed3] llama3" && it.systemPrompt == "edited"
            }?.model
        )
    }

    @Test
    fun `a_name_that_is_a_prefix_of_another_survives_the_rename`() {
        onMain { service.addOllamaBase("Main2", "http://127.0.0.1:1/step2-main2") }
        service.addPersona(probe("Step2Prefixed", "[Main2] llama3"))

        val main = service.ollamaBaseUrls.value.first { it.name == "Main" }
        onMain { service.updateOllamaBase(main, "Renamed4", main.url) }

        assertEquals(
            "renaming Main must not touch the Main2 persona",
            "[Main2] llama3",
            awaitStoredPersona("Step2Prefixed") { it?.model == "[Main2] llama3" }?.model
        )
    }

    @Test
    fun `a_model_name_containing_brackets_survives_the_rename`() {
        val tricky = "[Main] hf.co/x/Qwen[2]-7B-GGUF:q4_k_m"
        service.addPersona(probe("Step2Brackets", tricky))
        val main = service.ollamaBaseUrls.value.first { it.name == "Main" }
        onMain { service.updateOllamaBase(main, "Renamed5", main.url) }
        assertEquals(
            "[Renamed5] hf.co/x/Qwen[2]-7B-GGUF:q4_k_m",
            awaitStoredPersona("Step2Brackets") {
                it?.model == "[Renamed5] hf.co/x/Qwen[2]-7B-GGUF:q4_k_m"
            }?.model
        )
    }

    // ------------------------------------------------------------------- delete

    @Test
    fun `deleting_a_server_drops_its_favorites_and_its_cache__and_leaves_personas_alone`() {
        service.addPersona(probe("Step2Delete", "[Main] llama3"))
        service.toggleFavoriteModel("[Main] llama3")
        service.toggleFavoriteModel("[Work] other:1b")
        // Wait until BOTH are on disk. The post-delete assertion waits on "no [Main]", which
        // is trivially satisfied by an empty list, so without this precondition the test
        // would pass for the wrong reason.
        assertTrue(
            "precondition: both favorites must be persisted",
            awaitStoredFavorites { it.size == 2 }.size == 2
        )
        val main = service.ollamaBaseUrls.value.first { it.name == "Main" }
        service.fetchModelCatalog(main)

        onMain { service.removeOllamaBase(main) }

        val favs = awaitStoredFavorites { f -> f.none { it.startsWith("[Main]") } }
        assertFalse("its favorite must be gone: $favs", favs.any { it.startsWith("[Main]") })
        assertTrue("the other server's favorite must survive: $favs", favs.contains("[Work] other:1b"))
        assertEquals(
            "the persona is deliberately NOT rewritten or deleted",
            "[Main] llama3",
            awaitStoredPersona("Step2Delete") { it?.model == "[Main] llama3" }?.model
        )
        assertEquals(
            "the cache entry must be dropped with the server",
            ModelCatalogState.NotLoaded,
            service.modelCatalogFor(mainUrl)
        )
    }
}
