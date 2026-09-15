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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regressions for the concurrency bookkeeping around cloud model fetches — the two bug
 * classes that already shipped once and were only protected by code comments afterwards:
 *
 *  1. **Live-map clobbering** (#3 (cloud), B3 (chat), #3 (health)). A fetch used to
 *     snapshot `_serverStatus` when its coroutine started and write that snapshot back
 *     wholesale when it finished, so the last provider to finish erased every sibling's
 *     entry. The fix is a per-entry read-modify-write of the LIVE map on Main; these tests
 *     run overlapping fetches with *different* outcomes and require every entry to survive.
 *  2. **A premature loading clear** (M1 / the Cline early return). The in-flight counter
 *     was decremented twice on the `CL` path, so a fetch that was still running saw
 *     `_isLoadingModels` flip back to false and its spinner vanish mid-flight.
 *
 * Why instrumented rather than a JVM unit test: the code under test is only reachable
 * through a real AssistantService (`fetchCloudModels` is a member and the state under test
 * is its own StateFlows), and the service needs a real Looper-backed Main dispatcher. This
 * repo has no Robolectric, so the device is the only honest harness; the service is bound
 * through the production `AssistantBinder` exactly like MainActivity does.
 *
 * Determinism: providers are served by loopback stub HTTP servers ([StubCloudServer]) that
 * can *hold* a response until the test releases it, so "these two fetches overlap" is
 * engineered rather than assumed and no assertion depends on scheduling luck.
 */
@RunWith(AndroidJUnit4::class)
class AssistantServiceConcurrencyInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null
    private val stubs = mutableListOf<StubCloudServer>()

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
            context.bindService(
                Intent(context, AssistantService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        // onCreate() (loadSettings / pricing sync) fires the service's own fetches. Let them
        // drain so that a failure below is this test's doing and not theirs; if they are
        // still running when the probes start, every assertion here stays valid (a
        // background fetch can only make the loading flag more set, never less).
        awaitQuiescence()
    }

    @After
    fun unbindAssistantService() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        stubs.forEach { it.close() }
        stubs.clear()
    }

    private fun stub(statusCode: Int, body: String, holdResponse: Boolean = false): StubCloudServer =
        StubCloudServer(statusCode, body, holdResponse).also { stubs.add(it) }

    /** A custom ("C") provider pointing at a stub — no API key needed on that path. */
    private fun provider(name: String, baseUrl: String): CloudApiSetting =
        CloudApiSetting(name, baseUrl, "", "C", Color(0xFF808080), isEditableUrl = true)

    private fun awaitQuiescence(timeoutMs: Long = 30_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (service.isLoadingModels.value && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(25)
        }
    }

    private fun waitUntil(description: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        // Report the live state on the way out: this is the difference between "an entry
        // went missing" and "an entry was overwritten", which is the whole point here.
        assertTrue(
            "timed out after ${timeoutMs}ms waiting for $description " +
                "(serverStatus=${service.serverStatus.value}, " +
                "cloudProviderModels=${service.fetchedCloudModels.value.keys}, " +
                "isLoadingModels=${service.isLoadingModels.value})",
            condition()
        )
    }

    private fun seedStatusOnMain(name: String, status: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service._serverStatus.value = service._serverStatus.value + (name to status)
        }
    }

    /**
     * Three overlapping providers — A fails slowly (its response is held), B fails fast,
     * C succeeds — each writing a *different* entry. Under the old snapshot-and-write-back
     * code the last one to finish published a map that predated its siblings' writes, so at
     * least one of these entries went missing; per-entry read-modify-write keeps them all.
     *
     * The held response also pins the loading flag: A is genuinely in flight (no thread is
     * asleep waiting on it) while B and C have already recorded their outcomes.
     */
    @Test
    fun overlappingProviderFetchesKeepEveryEntryAndTheLoadingFlag() {
        val slowFailure = stub(500, """{"error":"probe-a"}""", holdResponse = true)
        val fastFailure = stub(503, """{"error":"probe-b"}""")
        val success = stub(200, """{"data":[{"id":"probe-c-model"}]}""")

        val slowApi = provider("ConcurrencyProbeA", slowFailure.baseUrl)
        val fastApi = provider("ConcurrencyProbeB", fastFailure.baseUrl)
        val okApi = provider("ConcurrencyProbeC", success.baseUrl)

        service.fetchCloudModels(slowApi)
        assertTrue(
            "probe A's request never reached its stub",
            slowFailure.requestArrived.await(20, TimeUnit.SECONDS)
        )

        service.fetchCloudModels(fastApi)
        service.fetchCloudModels(okApi)

        waitUntil("probe B's failure and probe C's models to be applied") {
            service.serverStatus.value[fastApi.name] == "Fetch failed: 503" &&
                service.fetchedCloudModels.value.containsKey(okApi.name)
        }

        assertFalse(
            "probe A recorded a status while its response was still held",
            service.serverStatus.value.containsKey(slowApi.name)
        )
        assertTrue(
            "isLoadingModels was cleared while probe A's fetch was still in flight — the " +
                "in-flight count was decremented more than once",
            service.isLoadingModels.value
        )

        slowFailure.releaseResponse()
        waitUntil("probe A's failure to be applied") {
            service.serverStatus.value[slowApi.name] == "Fetch failed: 500"
        }

        val status = service.serverStatus.value
        assertEquals(
            "probe A's completion clobbered probe B's entry with a stale map: $status",
            "Fetch failed: 503",
            status[fastApi.name]
        )
        assertEquals("probe A's own entry: $status", "Fetch failed: 500", status[slowApi.name])
        assertFalse(
            "a successful fetch must clear only its own status entry: $status",
            status.containsKey(okApi.name)
        )
        assertEquals(
            "probe C's successful model list was clobbered by a sibling: " +
                "${service.fetchedCloudModels.value}",
            listOf("[${okApi.name}] probe-c-model"),
            service.fetchedCloudModels.value[okApi.name]
        )

        waitUntil("the in-flight count to return to zero") { !service.isLoadingModels.value }
    }

    /**
     * The `CL` (Cline static list) path takes no HTTP at all: it writes CLINE_BOT_MODELS and
     * returns. It used to decrement the counter on that early return *and* in its `finally`,
     * so a fetch still in flight had the loading flag cleared under it. Probe D's response is
     * held here, so it is definitely still running while the CL fetch is processed.
     */
    @Test
    fun clineStaticListFetchLeavesTheInFlightCounterToTheOtherFetch() {
        val slow = stub(200, """{"data":[{"id":"probe-d-model"}]}""", holdResponse = true)
        val slowApi = provider("ConcurrencyProbeD", slow.baseUrl)
        val clineApi = CloudApiSetting(
            "ConcurrencyProbeCline",
            "https://api.cline.bot/api/v1",
            "",
            "CL",
            Color(0xFF6366F1)
        )

        // A stale error from an earlier attempt: refreshing the list must clear that one
        // entry — and only that one.
        seedStatusOnMain(clineApi.name, "failed: 500")
        assertEquals("failed: 500", service.serverStatus.value[clineApi.name])

        service.fetchCloudModels(slowApi)
        assertTrue(
            "probe D's request never reached its stub",
            slow.requestArrived.await(20, TimeUnit.SECONDS)
        )
        assertTrue("probe D's fetch should set the loading flag", service.isLoadingModels.value)

        service.fetchCloudModels(clineApi)
        waitUntil("the Cline static list to be applied", 10_000) {
            service.fetchedCloudModels.value.containsKey(clineApi.name)
        }

        // A double decrement leaves the flag *clear*, not merely cleared for an instant, so
        // polling for a while cannot miss it.
        val pollDeadline = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < pollDeadline) {
            assertTrue(
                "the CL early return cleared isLoadingModels while probe D was still in " +
                    "flight — the single decrement must stay in the finally",
                service.isLoadingModels.value
            )
            Thread.sleep(20)
        }

        assertNull(
            "the CL branch must clear only its own stale status entry: " +
                "${service.serverStatus.value}",
            service.serverStatus.value[clineApi.name]
        )
        assertEquals(
            "the CL branch must publish the static Cline list for its own provider",
            CLINE_BOT_MODELS.map { "[${clineApi.name}] $it" },
            service.fetchedCloudModels.value[clineApi.name]
        )

        slow.releaseResponse()
        waitUntil("probe D's successful model list") {
            service.fetchedCloudModels.value.containsKey(slowApi.name)
        }
        waitUntil("the in-flight count to return to zero") { !service.isLoadingModels.value }
    }

    /**
     * The 30s health sweep and forceCheckHealth run on Dispatchers.IO, but they write
     * `_serverStatus` like any other writer and must apply their result on Main. This asserts
     * that structurally rather than by racing: the Main looper is parked, the stub answers
     * the health check, and the check's entry must NOT be visible until Main is released. An
     * off-Main writer — the bug this covers — has nothing to wait for, so its entry shows up
     * while Main is still parked.
     */
    @Test
    fun healthCheckStatusWriteIsAppliedOnMainAndKeepsSiblings() {
        val health = stub(200, """{"models":[]}""")
        val target = ServerConfig(name = "ConcurrencyProbeHealth", url = health.baseUrl)
        val sibling = "ConcurrencyProbeSibling"
        seedStatusOnMain(sibling, "failed: 503")

        val instrument = InstrumentationRegistry.getInstrumentation()
        val mainParked = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val holder = Thread {
            instrument.runOnMainSync {
                mainParked.countDown()
                releaseMain.await(10, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue("could not park the Main looper", mainParked.await(10, TimeUnit.SECONDS))

        try {
            service.forceCheckHealth(target, isGateway = false)
            assertTrue(
                "the health check never reached its stub",
                health.requestArrived.await(20, TimeUnit.SECONDS)
            )
            assertTrue(
                "the stub never answered the health check",
                health.responseSent.await(20, TimeUnit.SECONDS)
            )
            // The response is in hand and the check is waiting on its Main hop, so with Main
            // parked its entry cannot be published yet.
            Thread.sleep(500)
            assertFalse(
                "checkServerHealth wrote _serverStatus off Main (its entry appeared while " +
                    "the Main looper was parked): ${service.serverStatus.value}",
                service.serverStatus.value.containsKey(target.url)
            )
        } finally {
            releaseMain.countDown()
            holder.join(10_000)
        }

        waitUntil("the health check's status to be applied on Main", 10_000) {
            service.serverStatus.value[target.url] == "Online"
        }
        assertEquals(
            "the health check clobbered a sibling entry: ${service.serverStatus.value}",
            "failed: 503",
            service.serverStatus.value[sibling]
        )
    }
}

/**
 * A loopback-only stand-in for one provider's model endpoint, scripted with a single reply
 * so a test can give two providers different outcomes and tell whose write landed where.
 *
 * `holdResponse` parks a request inside the server (after the request head is read, before
 * anything is written) until [releaseResponse] is called: that is what makes the overlaps in
 * these tests engineered instead of timing-dependent. Plain HTTP is used because the app
 * talks to LAN servers that way already and opts into cleartext
 * (`android:usesCleartextTraffic="true"`).
 */
private class StubCloudServer(
    private val statusCode: Int,
    private val responseBody: String,
    private val holdResponse: Boolean = false
) {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** e.g. `http://127.0.0.1:41234` — a custom provider appends `/models` itself. */
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    /** Fires once a request head has been read: the fetch is genuinely in flight. */
    val requestArrived = CountDownLatch(1)

    /** Fires once the scripted reply has been written and the socket closed. */
    val responseSent = CountDownLatch(1)

    private val released = CountDownLatch(1)

    init {
        val port = server.localPort
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break // close() tore the listener down
                }
                Thread({ serve(client) }, "stub-cloud-$port").apply { isDaemon = true }.start()
            }
        }, "stub-cloud-accept-$port").apply { isDaemon = true }.start()
    }

    fun releaseResponse() = released.countDown()

    fun close() {
        released.countDown() // unblock any held worker so the test cannot leak a thread
        runCatching { server.close() }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                readRequestHead(socket.getInputStream())
                requestArrived.countDown()
                if (holdResponse) released.await(60, TimeUnit.SECONDS)
                writeResponse(socket.getOutputStream())
            }
        } catch (e: IOException) {
            // The test closed the socket mid-flight; nothing to report.
        } finally {
            responseSent.countDown()
        }
    }

    private fun readRequestHead(input: InputStream) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next == -1) return
            head.append(next.toChar())
        }
    }

    private fun writeResponse(output: OutputStream) {
        val body = responseBody.toByteArray(Charsets.UTF_8)
        val reason = if (statusCode == 200) "OK" else "Stub Error"
        val head = "HTTP/1.1 $statusCode $reason\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }
}
