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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The persona picker driven through the REAL service against a loopback Ollama stub.
 *
 * The Compose tests prove the composable in isolation. These prove the two facts that only
 * come into being once the service is involved:
 *
 *  - a model starred on the models screen is a favorite the picker shows, and the picker's
 *    own star goes through the same `toggleFavoriteModel` and the same persisted
 *    `favorite_models_v2` entry;
 *  - selecting a row writes **byte-identical** the string the pre-change dropdown wrote.
 *
 * The pre-change string is not asserted from memory: [preChangeString] is the old expression
 * itself, kept verbatim, so the picker and this test have to agree on it or the test fails.
 *
 * ServiceIsolationRule, because the catalog cache and favorites are process-global.
 */
@RunWith(AndroidJUnit4::class)
class ModelPickerInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var ollamaBefore: List<ServerConfig> = emptyList()
    private var personasBefore: List<Persona> = emptyList()

    private val serverName = "PickerProbe"
    private val personaName = "PickerProbePersona"

    private val tagsJson = """
        {"models":[
          {"name":"alpha:8b","size":4294967296,
           "details":{"parameter_size":"8.0B","quantization_level":"Q4_K_M"},
           "capabilities":["completion","tools"]},
          {"name":"beta:1b","size":1073741824,
           "details":{"parameter_size":"1.0B","quantization_level":"Q8_0"},
           "capabilities":["completion"]}
        ]}
    """.trimIndent()

    private val alphaShow = """
        {"capabilities":["completion","vision","tools"],
         "details":{"parameter_size":"8.0B","quantization_level":"Q4_K_M"},
         "model_info":{"llama.context_length":131072}}
    """.trimIndent()

    private val betaShow = """
        {"capabilities":["completion"],
         "details":{"parameter_size":"1.0B","quantization_level":"Q8_0"},
         "model_info":{"llama.context_length":8192}}
    """.trimIndent()

    private fun serverFor(stub: ModelCatalogStub) =
        ServerConfig(serverName, stub.url, null, null, AuthType.NONE, null)

    private fun installStub(failTags: Boolean = false): ModelCatalogStub =
        ModelCatalogStub(
            tagsJson,
            mapOf("alpha:8b" to alphaShow, "beta:1b" to betaShow),
            failTags = failTags
        )

    private fun addProbe(stub: ModelCatalogStub): ServerConfig {
        val server = serverFor(stub)
        service.addOllamaBase(server.name, server.url, null, null, AuthType.NONE, null)
        return server
    }

    /**
     * The exact string the pre-change dropdown produced for [model] on this server.
     *
     * This IS the old expression, kept verbatim in a test, so the assertion cannot drift into
     * agreeing with a bug: if the picker ever stopped emitting it, this fails.
     */
    private fun preChangeString(model: String) = "[$serverName] $model"

    private fun favoriteModelsOrEmpty(): Set<String> = service.favoriteModels.value.toSet()

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
        ollamaBefore = service.ollamaBaseUrls.value
        personasBefore = service.personas.value
    }

    @After
    fun unbind() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Removing the probe server also drops its favorites. Personas go by index in
            // reverse, which is the only way back: the originals have to be removed before
            // the probe can be re-added under the same name.
            service.personas.value.indices.reversed().forEach { service.removePersona(it) }
            personasBefore.forEach { service.addPersona(it) }
            service.ollamaBaseUrls.value
                .filter { it.name == serverName }
                .forEach { service.removeOllamaBase(it) }
            if (service.ollamaBaseUrls.value.map { it.name } != ollamaBefore.map { it.name }) {
                service._ollamaBaseUrls.value = ollamaBefore
            }
            service.saveSettings()
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /** Poll the live cache for a real condition; no fixed sleeps. */
    private fun awaitCatalog(
        url: String,
        timeoutMs: Long = 20_000,
        predicate: (ModelCatalogState) -> Boolean
    ): ModelCatalogState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = service.modelCatalogFor(url)
            if (predicate(state)) return state
            Thread.sleep(25)
        }
        return service.modelCatalogFor(url)
    }

    /** Fully enriched, so the picker's rows carry their real chips and spec lines. */
    private fun awaitReady(url: String): ModelCatalogState.Loaded {
        val state = awaitCatalog(url) {
            it is ModelCatalogState.Loaded &&
                it.showStates.values.none { s -> s == ModelShowState.PENDING }
        }
        assertTrue("the catalog never finished loading, was $state", state is ModelCatalogState.Loaded)
        return state as ModelCatalogState.Loaded
    }

    @Test
    fun `the picker lists what the shared catalog loaded from the service`() {
        installStub().use { stub ->
            val server = addProbe(stub)
            service.fetchModelCatalog(server)
            val loaded = awaitReady(stub.url)

            val rows = selectPickerModels(
                entries = loaded.entries,
                serverName = server.name,
                favoriteNames = favoriteModelsOrEmpty(),
                favoritesOnly = false,
                currentModelName = null
            ).map { it.name }
            assertEquals(listOf("alpha:8b", "beta:1b"), rows)

            // /api/show enrichment reached the picker: alpha reports vision here but only
            // completion+tools in /api/tags, which is the disagreement the catalog exists to
            // resolve, and the reason the picker can now show a Vision chip at all.
            assertEquals(
                setOf(ModelCapability.COMPLETION, ModelCapability.VISION, ModelCapability.TOOLS),
                loaded.entries.first { it.name == "alpha:8b" }.capabilities
            )
            assertEquals(131072L, loaded.entries.first { it.name == "alpha:8b" }.contextLength)
        }
    }

    @Test
    fun `starring on the models screen makes the model a picker favorite`() {
        installStub().use { stub ->
            val server = addProbe(stub)
            service.fetchModelCatalog(server)
            val loaded = awaitReady(stub.url)

            // Star it exactly as the models screen's star does.
            val favorite = preChangeString("alpha:8b")
            service.toggleFavoriteModel(favorite)
            try {
                assertTrue(
                    "the star must reach the service's favorite list",
                    service.favoriteModels.value.contains(favorite)
                )
                assertEquals(
                    listOf("alpha:8b", "beta:1b"),
                    selectPickerModels(
                        loaded.entries, server.name, favoriteModelsOrEmpty(), false, null
                    ).map { it.name }
                )
                // ...and the favorites-only default now finds exactly that one.
                assertEquals(
                    listOf("alpha:8b"),
                    selectPickerModels(
                        loaded.entries, server.name, favoriteModelsOrEmpty(), true, null
                    ).map { it.name }
                )
                // Un-starring through the same call takes it back out.
                service.toggleFavoriteModel(favorite)
                assertTrue(
                    "the picker star must un-star too",
                    !service.favoriteModels.value.contains(favorite)
                )
            } finally {
                if (service.favoriteModels.value.contains(favorite)) {
                    service.toggleFavoriteModel(favorite)
                }
            }
        }
    }

    @Test
    fun `a selection writes exactly the string the pre-change dropdown wrote`() {
        installStub().use { stub ->
            val server = addProbe(stub)
            service.fetchModelCatalog(server)
            awaitReady(stub.url)

            // What a row tap produces: the picker hands PersonaEditor this string, and the
            // editor assigns it to `model` unchanged.
            val selected = qualifiedModelName(server.name, "alpha:8b")
            assertEquals(
                "the picker must emit the pre-change string, byte for byte",
                preChangeString("alpha:8b"),
                selected
            )

            // ...and it round-trips into a real persona, with the editor's own prefix parsing
            // still recovering the same connection and the same bare model name as before.
            service.addPersona(
                Persona(
                    name = personaName,
                    themeColor = Color(0xFF4ADE80),
                    model = selected,
                    systemPrompt = ""
                )
            )
            try {
                val stored = service.personas.value.first { it.name == personaName }
                assertEquals(preChangeString("alpha:8b"), stored.model)
                assertEquals(
                    "the connection is recovered exactly as before",
                    serverName,
                    stored.model.substring(1, stored.model.indexOf("]"))
                )
                assertEquals("alpha:8b", bareModelName(stored.model, serverName))
            } finally {
                service.personas.value.indices.reversed()
                    .filter { service.personas.value[it].name == personaName }
                    .forEach { service.removePersona(it) }
            }
        }
    }

    @Test
    fun `an unreachable server still lists the current model of the persona`() {
        installStub(failTags = true).use { stub ->
            val server = addProbe(stub)
            service.fetchModelCatalog(server)
            // Cold and failing: no entries at all, which is what the picker sees.
            val failed = awaitCatalog(stub.url) { it is ModelCatalogState.Failed }
            assertTrue("expected Failed, was $failed", failed is ModelCatalogState.Failed)

            assertEquals(
                "the model the persona is using must remain selectable",
                listOf("alpha:8b"),
                selectPickerModels(
                    emptyList(), server.name, favoriteModelsOrEmpty(), false, "alpha:8b"
                ).map { it.name }
            )
        }
    }
}
