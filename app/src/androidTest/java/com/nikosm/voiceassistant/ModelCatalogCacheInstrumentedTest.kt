package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The service-level model catalog cache, driven against a loopback Ollama stub.
 *
 * These are the four states [ModelCatalogState] exists for, plus the two behaviours that
 * are easy to get wrong and are therefore pinned here:
 *
 *  - a failed REFRESH keeps the last good list, marked stale, instead of emptying it;
 *  - a failed /api/show for ONE model leaves that model on its tags values while every
 *    other model still resolves.
 *
 * ServiceIsolationRule, because these tests bind the real service and the cache is
 * process-global.
 */
@RunWith(AndroidJUnit4::class)
class ModelCatalogCacheInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    // Two models: `good` resolves from /api/show, `bad` has no show body so the stub
    // answers 404 for it alone.
    private val tagsJson = """
        {"models":[
          {"name":"good:8b","size":4294967296,
           "details":{"parameter_size":"8.0B","quantization_level":"Q4_K_M"},
           "capabilities":["completion","tools"]},
          {"name":"bad:1b","size":1073741824,
           "details":{"parameter_size":"1.0B","quantization_level":"Q8_0"},
           "capabilities":["completion"]}
        ]}
    """.trimIndent()

    private val goodShow = """
        {"capabilities":["completion","vision","tools"],
         "details":{"parameter_size":"8.0B","quantization_level":"Q4_K_M"},
         "model_info":{"llama.context_length":131072}}
    """.trimIndent()

    private fun serverFor(stub: ModelCatalogStub) = ServerConfig("CatalogProbe", stub.url)

    private fun bind() {
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
    }

    @After
    fun unbind() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /** Poll a real condition on the live cache; no fixed sleeps. */
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

    @Test
    fun `an_unfetched_server_reads_as_NotLoaded`() {
        bind()
        assertEquals(
            ModelCatalogState.NotLoaded,
            service.modelCatalogFor("http://127.0.0.1:1/never-touched")
        )
    }

    @Test
    fun `a_cold_fetch_goes_NotLoaded_to_Loading_to_Loaded`() {
        bind()
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            val server = serverFor(stub)
            assertEquals(ModelCatalogState.NotLoaded, service.modelCatalogFor(stub.url))

            service.fetchModelCatalog(server)

            val loaded = awaitCatalog(stub.url) { it is ModelCatalogState.Loaded }
            assertTrue(
                "expected Loaded, was ${service.modelCatalogFor(stub.url)}",
                loaded is ModelCatalogState.Loaded
            )
            loaded as ModelCatalogState.Loaded
            assertEquals(listOf("good:8b", "bad:1b"), loaded.entries.map { it.name })
            assertTrue("a fresh load is not stale", !loaded.stale)
            assertNull(loaded.degradedReason)
        }
    }

    @Test
    fun `show_is_called_for_every_model_not_lazily`() {
        bind()
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            }
            val shown = stub.requests.filter { it.path.endsWith("/api/show") }
                .mapNotNull { Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(it.body)?.groupValues?.get(1) }
            assertEquals(
                "every model must be enriched, not just the visible ones",
                listOf("bad:1b", "good:8b"),
                shown.sorted()
            )
        }
    }

    @Test
    fun `show_wins_the_merge_and_size_survives_from_tags`() {
        bind()
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            // The tags-level publish is ALSO a Loaded, with everything PENDING. Waiting
            // only for Loaded would race the enrichment and flake on a loaded device.
            val loaded = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            } as ModelCatalogState.Loaded
            val good = loaded.entries.first { it.name == "good:8b" }
            // tags said [completion, tools]; show adds vision.
            assertTrue(ModelCapability.VISION in good.capabilities.orEmpty())
            // context length exists only in show.
            assertEquals(131072L, good.contextLength)
            // size exists only in tags and must not be erased by the merge.
            assertEquals(4294967296L, good.sizeBytes)
        }
    }

    @Test
    fun `a_failed_show_for_one_model_leaves_only_that_model_on_its_tags_values`() {
        bind()
        // `good:8b` has a show body; `bad:1b` does not, so the stub 404s it alone.
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            val loaded = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            } as ModelCatalogState.Loaded

            assertEquals(ModelShowState.RESOLVED, loaded.showStates["good:8b"])
            assertEquals("the one that failed must say so", ModelShowState.FAILED, loaded.showStates["bad:1b"])

            val bad = loaded.entries.first { it.name == "bad:1b" }
            assertEquals(
                "a failed model keeps its tags capabilities",
                setOf(ModelCapability.COMPLETION),
                bad.capabilities
            )
            assertNull("and gains no context length from the failed show", bad.contextLength)
            assertEquals("but keeps its tags size", 1073741824L, bad.sizeBytes)

            assertEquals(
                "one model's failure must not empty the list",
                2,
                loaded.entries.size
            )
        }
    }

    @Test
    fun `a_cold_fetch_against_a_failing_server_yields_Failed`() {
        bind()
        ModelCatalogStub(tagsJson, failTags = true).use { stub ->
            service.fetchModelCatalog(serverFor(stub))
            val state = awaitCatalog(stub.url) { it is ModelCatalogState.Failed }
            assertTrue("expected Failed, was $state", state is ModelCatalogState.Failed)
        }
    }

    @Test
    fun `a_failed_REFRESH_keeps_the_last_good_list_marked_stale`() {
        bind()
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            val server = serverFor(stub)
            service.fetchModelCatalog(server)
            // Wait for the enrichment to finish, not just for the tags-level publish, so
            // the refresh below really is a refresh of a settled list.
            val first = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            } as ModelCatalogState.Loaded
            assertTrue("a first good load is not stale", !first.stale)

            // The server goes away, then the user hits refresh.
            stub.failTagsNow = true
            service.fetchModelCatalog(server, force = true)

            val after = awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.stale
            }
            assertTrue(
                "a failed refresh must not replace the list with Failed",
                after is ModelCatalogState.Loaded
            )
            after as ModelCatalogState.Loaded
            assertTrue("the surviving list must be marked stale", after.stale)
            assertNotNull("and must say why", after.degradedReason)
            assertEquals(
                "the entries themselves must be the last good ones",
                listOf("good:8b", "bad:1b"),
                after.entries.map { it.name }
            )
        }
    }

    @Test
    fun `a_second_fetch_without_force_does_not_re-request`() {
        bind()
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            val server = serverFor(stub)
            service.fetchModelCatalog(server)
            awaitCatalog(stub.url) {
                it is ModelCatalogState.Loaded && it.showStates.values.none { s -> s == ModelShowState.PENDING }
            }
            val afterFirst = stub.requests.size
            service.fetchModelCatalog(server)
            Thread.sleep(500)
            assertEquals(
                "a cached server must not be re-fetched without a user action",
                afterFirst,
                stub.requests.size
            )
        }
    }

    @Test
    fun `dropping_a_cache_entry_by_URL_removes_it`() {
        bind()
        ModelCatalogStub(tagsJson, mapOf("good:8b" to goodShow)).use { stub ->
            val server = serverFor(stub)
            service.fetchModelCatalog(server)
            awaitCatalog(stub.url) { it is ModelCatalogState.Loaded }

            service.dropModelCatalog(stub.url)

            assertEquals(
                ModelCatalogState.NotLoaded,
                service.modelCatalogFor(stub.url)
            )
        }
    }
}
