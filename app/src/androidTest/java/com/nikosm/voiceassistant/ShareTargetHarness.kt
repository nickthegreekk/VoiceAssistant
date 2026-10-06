package com.nikosm.voiceassistant

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** The app under test. Also the package `run-as` is asked about, for reading private files. */
internal const val SHARE_APP_PACKAGE = "com.nikosm.voiceassistant"

/** Loopback Ollama stand-in: the persona the fixture installs points its model at this. */
internal const val SHARE_PROBE_SERVER = "ShareStubServer"

/** The persona the fixture switches to, so a share lands somewhere the test can see it. */
internal const val SHARE_PROBE_PERSONA = "ShareStubPersona"

internal const val SHARE_DRAFT_TIMEOUT_MS = 30_000L

/** The target app's own Context, resolved without a test instance. */
internal fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

/**
 * Shared fixture for the share target's device tests: a real `MainActivity` launched by a real
 * `ACTION_SEND` intent, a bound [AssistantService] pointed at a loopback Ollama stand-in, and the
 * Compose rule the assertions read through.
 *
 * The launch intent is a CONSTRUCTOR PARAMETER rather than an overridable member on purpose: the
 * rule built from it is initialized in this class's own constructor, so an `abstract val` (or an
 * open method) would be read before the subclass had initialized anything. Subclasses pass a
 * fully-built intent instead — `textShareIntent()` or `imageShareIntent(…)` — and add assertions.
 *
 * The fixture has to be in place BEFORE that launch: the current persona must already point at
 * the stub, and the first-run welcome has to be behind us, or the share would land on a screen
 * the composer is not on. That is what the rule chain buys.
 */
abstract class ShareTargetHarness(private val launchIntent: Intent) {

    protected val context: Context
        get() = targetContext()

    /** The `EXTRA_STREAM` the launch intent carried; tests that put something there clean it up. */
    protected val sharedStreamUri: Uri?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launchIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            launchIntent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    protected lateinit var service: AssistantService
    private var connection: ServiceConnection? = null
    private var personasBefore: List<Persona> = emptyList()
    private var serversBefore: List<ServerConfig> = emptyList()
    private var currentPersonaBefore: String? = null

    /** Loopback stand-in for the current persona's Ollama server. */
    protected val stub = Stub()

    private val serviceIsolation = ServiceIsolationRule()

    /**
     * The message images already in app-private storage when the test body starts, so a test can
     * assert what THIS share added. Snapshotted in [prelude], i.e. before the Activity launches.
     */
    protected var msgImageNamesBefore: Set<String> = emptySet()
        private set

    /**
     * Permissions + fixtures, BEFORE the Activity is ever created:
     *
     *  - `MainScreen` requests every still-missing runtime permission on its first composition,
     *    and the resulting dialog pauses the Activity — pre-granting keeps the recreation
     *    deterministic (grant-only: revoking a permission kills the process);
     *  - the first-run welcome would otherwise own the screen and hold the share pending;
     *  - the current persona must already be one whose model routes to [stub], so an accidental
     *    auto-send would actually arrive somewhere this test can see.
     */
    private val prelude = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    grantRuntimePermissions()
                    bindAndSeed()
                    msgImageNamesBefore = msgImageNames()
                    try {
                        base.evaluate()
                    } finally {
                        unbind()
                    }
                }
            }
    }

    private val activityRule = ActivityScenarioRule<MainActivity>(launchIntent)

    private val composeRule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        AndroidComposeTestRule(activityRule) { rule ->
            // Same shape as the library's own (internal) `getActivityFromTestRule`: `onActivity`
            // does not return the activity, so it is captured through a holder.
            var activity: MainActivity? = null
            rule.scenario.onActivity { activity = it }
            activity!!
        }

    // Rule fields have to be public for JUnit to see them, even though every subclass is in this
    // module: the runner reflects over them field by field, outside this package.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(serviceIsolation)
        .around(prelude)
        .around(composeRule)

    @After
    fun closeStub() {
        stub.close()
    }

    // ------------------------------------------------------------------ verbs the tests use

    protected fun waitForIdle() = composeRule.waitForIdle()

    /** Recreates the Activity in place (rotation, "don't keep activities"). */
    protected fun recreateActivity() = composeRule.activityRule.scenario.recreate()

    protected fun clickTagged(tag: String) = composeRule.onNodeWithTag(tag).performClick()

    protected fun typeIntoDraft(text: String) =
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(text)

    /**
     * How many pending image attachments the composer is showing. -1 while there is no
     * composition yet, so `waitFor { pendingAttachmentCount() == 1 }` keeps polling instead of
     * throwing on the very first look.
     */
    protected fun pendingAttachmentCount(): Int = runCatching {
        composeRule.onAllNodesWithTag(COMPOSER_ATTACHMENT_TAG).fetchSemanticsNodes().size
    }.getOrDefault(-1)

    /** The composer's draft, or null while the field is not composed yet. */
    protected fun draftTextOrNull(): String? = runCatching {
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text
    }.getOrNull()

    /**
     * The names of the message images currently in app-private storage.
     *
     * Read through the device shell's `run-as`, which is the only way instrumentation can look at
     * another app's private files. That is also why it is safe to lean on here and nowhere else:
     * every build this suite installs is a debug one, and `run-as` only unlocks the data dir of a
     * debuggable app. Should it ever stop working, the set comes back EMPTY and the callers
     * assert on it explicitly, so the failure is loud rather than a silently passing test — and
     * [lastMsgImageListing] carries the raw output into the failure message.
     */
    protected fun msgImageNames(): Set<String> {
        val listing = shellOutput("run-as $SHARE_APP_PACKAGE ls -1 files/persona_images")
        lastMsgImageListing = listing.ifBlank { "<no output>" }
        return listing.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("${MESSAGE_IMAGE_SLOT}_") }
            .toSet()
    }

    /** Raw output of the last [msgImageNames] listing, so a count failure explains itself. */
    protected var lastMsgImageListing: String = ""
        private set

    /** Runs [command] as the device shell and returns its output. */
    protected fun shellOutput(command: String): String {
        // NOTE: `executeShellCommand` splits [command] on whitespace and execs it directly — it
        // does NOT go through `sh -c`. Quotes and pipes are therefore literal arguments, not
        // syntax: `sh -c 'ls -1 …'` silently yields nothing, while `… ls -1 …` works. Measured,
        // not assumed: the quoting version returned an empty listing for every test run.
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }

    protected fun waitFor(timeoutMs: Long = SHARE_DRAFT_TIMEOUT_MS, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }


    // ------------------------------------------------------------------ fixtures

    private fun grantRuntimePermissions() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val dialogWorthy = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.READ_PHONE_STATE)
        }
        dialogWorthy.forEach { permission ->
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                runCatching { ui.grantRuntimePermission(context.packageName, permission) }
            }
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /**
     * Binds the service and leaves it BOUND for the whole test, so `MainActivity` attaches to this
     * already-seeded instance instead of constructing a fresh one that reloads storage.
     */
    private fun bindAndSeed() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { /* not expected */ }
        }
        connection = conn
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        runBlocking { service.awaitPersonaHistoryIo() }

        personasBefore = service.personas.value
        serversBefore = service._ollamaBaseUrls.value
        currentPersonaBefore = service.currentPersonaName

        onMain {
            // The first-run welcome must not be standing in front of the composer.
            service.setFirstRunComplete()
            // Drop a stale probe server from a previous run: its port is dead now and
            // addOllamaBase is URL-guarded, so re-adding the name would be a no-op.
            service._ollamaBaseUrls.value =
                service._ollamaBaseUrls.value.filterNot { it.name == SHARE_PROBE_SERVER }
            service.addOllamaBase(SHARE_PROBE_SERVER, stub.url)
            service.personas.value.filter { it.name == SHARE_PROBE_PERSONA }
                .forEach { service.removePersona(service.personas.value.indexOf(it)) }
            val persona = Persona(
                name = SHARE_PROBE_PERSONA,
                themeColor = Color(0xFF4ADE80),
                model = "[$SHARE_PROBE_SERVER] probe-model",
                systemPrompt = "You are a probe.",
                isCloud = false
            )
            service.addPersona(persona)
            service.switchPersona(persona)
        }
        assertTrue(
            "the stub persona must become the current persona before the share lands",
            waitFor { service.currentPersonaName == SHARE_PROBE_PERSONA }
        )
    }

    private fun unbind() {
        runCatching {
            onMain {
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service._ollamaBaseUrls.value = serversBefore
                service.currentPersonaName = currentPersonaBefore
                service.saveSettings()
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        // `MainActivity.onCreate` calls `startService(...)`, which promotes AssistantService to a
        // STARTED service that outlives both the ActivityScenario closing and the unbind above —
        // the very leak ServiceIsolationRule exists to catch (see
        // SpontaneousDeepLinkActivityInstrumentedTest, which stops it for the same reason).
        // Unbinding is not enough; stop it and wait until the platform agrees it is gone.
        runCatching {
            context.stopService(Intent(context, AssistantService::class.java))
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && runningService(am)) {
                Thread.sleep(25)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private fun runningService(am: ActivityManager): Boolean = runCatching {
        am.getRunningServices(500).any {
            it.service.className == AssistantService::class.java.name
        }
    }.getOrDefault(false)

    /**
     * A minimal Ollama stand-in. It answers EVERY request, so the app's startup `/api/tags` poll
     * completes, and records each request line — the assertion is that no CHAT request ever
     * arrives, which is precisely what an accidental auto-send would produce.
     */
    protected class Stub : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val requestLines = Collections.synchronizedList(mutableListOf<String>())
        val url: String get() = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true, name = "share-stub") {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { handle(it) }
                    } catch (_: Exception) {
                        // Closed, or one bad client: keep serving.
                    }
                }
            }
        }

        /** Request lines that are conversation turns — the thing a share must never produce. */
        fun chatRequests(): List<String> =
            requestLines.filter { it.contains("/api/chat") || it.contains("/api/generate") }

        private fun handle(client: Socket) {
            val input = BufferedInputStream(client.inputStream)
            val requestLine = readLine(input) ?: return
            requestLines += requestLine
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            val body = """{"models":[]}""".toByteArray()
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().write(head.toByteArray())
            client.getOutputStream().write(body)
            client.getOutputStream().flush()
        }

        private fun readLine(input: BufferedInputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return line.takeIf { it.isNotEmpty() }?.toString()
                if (b == '\n'.code) return line.toString()
                if (b == '\r'.code) continue
                line.append(b.toChar())
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}

