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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Phase 2 (multi-persona Rooms): the round turn loop, on-device, against a loopback stub.
 *
 * `runRoomRound` is the first thing in the app that spends a user's tokens without the user
 * asking for each message, so the properties under test are the ones that make that safe to
 * ship:
 *  1. every member answers the user's message exactly ONCE, in the room's own order, and each
 *     reply lands in the ROOM transcript attributed to its speaker (never the single-chat one);
 *  2. autonomous continuation stops EXACTLY at `RoomReplyPolicy.capFor(room)` — the cap counts
 *     total autonomous messages and is checked before every turn, so it cannot be overshot
 *     by one;
 *  3. only LOCAL members take autonomous turns (Q1: no unsupervised paid turns), rotating so
 *     nobody speaks twice in a row, while cloud members still answer the user in the initial
 *     round and never consume autonomous budget;
 *  4. Stop ends the round immediately: no further member turn starts, the in-flight turn adds
 *     nothing to the transcript (not even a blank bubble), and the round state settles to null;
 *  5. a newer round SUPERSEDES one in flight: the abandoned round's remaining turns never run,
 *     its late unwind cannot blank the successor's state, and the transcript the user ends up
 *     with is the new round's.
 *
 * How it proves it: one loopback server answers BOTH backend shapes the loop actually uses —
 * Direct-Ollama NDJSON (`/api/chat`, EOF-delimited, the shape the client streams) and a
 * cloud-provider JSON reply (`/chat/completions`, icon "C") — and every request body is
 * captured. Each probe persona's system prompt carries a unique tag, so a captured body says
 * WHICH member spoke; each reply echoes the request index, so the transcript says which
 * request's answer landed where. Rounds are driven through the production entry points
 * (`startRoomRound`/`stopRoomRound`) on a room opened through the production `switchRoom`, so
 * routing, budget selection, streaming, the H3 placeholder bookkeeping and the settle path are
 * all the real ones.
 */
@RunWith(AndroidJUnit4::class)
class RoomRoundLoopInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var ollamaBasesBefore: List<ServerConfig> = emptyList()
    private var customCloudApisBefore: List<CloudApiSetting> = emptyList()
    private var messagesBefore: List<ChatMessage> = emptyList()
    private var personaNameBefore: String? = null

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
        ollamaBasesBefore = service._ollamaBaseUrls.value
        customCloudApisBefore = service._customCloudApis.value
        messagesBefore = service._messages.value
        personaNameBefore = service.currentPersonaName
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // stopEverything() also ends a running round (it bumps the shared chat-request sequence
        // and runs the room Stop hook), so a test that failed mid-round cannot leave a loop
        // running into the next test.
        runCatching { instrumentation.runOnMainSync { service.stopEverything() } }
        runCatching {
            awaitService(15_000, "the probe round never unwound after Stop") {
                service.roomRound.value == null
            }
        }
        runCatching {
            instrumentation.runOnMainSync {
                // Room-mode bookkeeping is service-global process state: leave none of it behind.
                service.roomRoundGeneration = null
                service.roomRoundJob = null
                service._roomRound.value = null
                service._roomMessages.value = emptyList()
                service._activeRoomId.value = null
                service.roomMessagesOwnerId = null
                service._streamingText.value = null
                service.streamingTextGeneration = null
                service.streamedPlaceholderIndex = null
                service.streamedPlaceholderGeneration = null
                service._messages.value = messagesBefore
                service.currentPersonaName = personaNameBefore
                service.messagesOwnerName = personaNameBefore
                // The probe server/provider entries are persisted by saveSettings() below, so
                // restoring the in-memory lists is not enough.
                service._ollamaBaseUrls.value = ollamaBasesBefore
                service._customCloudApis.value = customCloudApisBefore
                service.saveSettings()
            }
        }
        // Remove the probe room and its transcript, then the probe personas (both removeRoom
        // and removePersona persist, so this also cleans the on-disk state the probe wrote).
        runCatching { instrumentation.runOnMainSync { service.removeRoom(PROBE_ROOM_ID) } }
        runCatching {
            instrumentation.runOnMainSync {
                var idx = service.personas.value.indexOfFirst { it.name in PROBE_PERSONA_NAMES }
                while (idx >= 0) {
                    service.removePersona(idx)
                    idx = service.personas.value.indexOfFirst { it.name in PROBE_PERSONA_NAMES }
                }
            }
        }
        runCatching { service.settingsManager.deleteRoomHistory(PROBE_ROOM_ID) }
        PROBE_PERSONA_NAMES.forEach { runCatching { service.settingsManager.deletePersonaHistory(it) } }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    // ---- the five properties ---------------------------------------------------------------

    /**
     * Property 1: the initial round is exactly one turn per member, in the room's own order,
     * each reply attributed to its speaker in the ROOM transcript.
     */
    @Test
    fun initialRoundRunsEveryMemberOnceInRoomOrder() {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 0, members = listOf(LOCAL_A, LOCAL_B, CLOUD_ONE)))
            val room = service.activeRoom!!
            assertTrue("the probe round never started", startRound(room, USER_FIRST))
            awaitRoundSettled()

            assertEquals(
                "each member must answer the user exactly once, in room order",
                listOf(RoomMessage.USER_SPEAKER, LOCAL_A, LOCAL_B, CLOUD_ONE),
                speakers()
            )
            // The reply text echoes the request index, so this ties transcript order to the order
            // the turns were actually issued in AND to the member each request was built for.
            assertEquals(
                listOf(USER_FIRST, replyText(1, LOCAL_A), replyText(2, LOCAL_B), replyText(3, CLOUD_ONE)),
                texts()
            )
            assertEquals(
                "one request per member, in room order",
                listOf(LOCAL_A, LOCAL_B, CLOUD_ONE),
                server.requests.map { it.speakerTag }
            )
            assertNull(
                "a cap of 0 must leave the round state settled after the initial round",
                service.roomRound.value
            )
            assertEquals(
                "the finished round must still leave the room owning its transcript",
                room.id,
                service.roomMessagesOwnerId
            )
            assertNoFurtherRequests(server, expected = 3)
        }
    }

    /**
     * Property 2: with two local members and cap = 1 the round stops after exactly ONE
     * autonomous message — 4 messages total, not 5.
     */
    @Test
    fun autonomousContinuationStopsExactlyAtTheCap() {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 1, members = listOf(LOCAL_A, LOCAL_B)))
            val room = service.activeRoom!!
            assertEquals("precondition: the probe room's cap", 1, RoomReplyPolicy.capFor(room))
            assertTrue("the probe round never started", startRound(room, USER_FIRST))
            awaitRoundSettled()

            assertEquals(
                "the cap counts TOTAL autonomous messages: cap=1 means one of them, not one more",
                listOf(RoomMessage.USER_SPEAKER, LOCAL_A, LOCAL_B, LOCAL_A),
                speakers()
            )
            assertEquals(listOf(LOCAL_A, LOCAL_B, LOCAL_A), server.requests.map { it.speakerTag })
            assertEquals(
                "the loop must not have used the room's members as an unbounded relay",
                4,
                service.roomMessages.value.size
            )
            assertNull(service.roomRound.value)
            // Three requests total: one per member in the initial round, plus the single
            // autonomous turn the cap of 1 allows — and nothing after it.
            assertNoFurtherRequests(server, expected = 3)
        }
    }

    /**
     * Property 3: autonomous turns are local-only and rotate. `cap = 4` in a room with two
     * locals and one cloud member yields 4 autonomous messages, local members alternating, with
     * the cloud member participating exactly once (the initial round).
     */
    @Test
    fun autonomousTurnsRotateThroughLocalsAndNeverSpendOnCloud() {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 4, members = listOf(LOCAL_A, LOCAL_B, CLOUD_ONE)))
            val room = service.activeRoom!!
            assertTrue("the probe round never started", startRound(room, USER_FIRST))
            awaitRoundSettled()

            assertEquals(
                "cloud members answer the user but never take an autonomous turn (Q1), and " +
                    "local members must rotate rather than repeat",
                listOf(
                    RoomMessage.USER_SPEAKER,
                    LOCAL_A, LOCAL_B, CLOUD_ONE,
                    LOCAL_A, LOCAL_B, LOCAL_A, LOCAL_B
                ),
                speakers()
            )
            assertEquals(
                "the cloud member must be asked exactly once per user message",
                1,
                server.requests.count { it.speakerTag == CLOUD_ONE }
            )
            assertEquals(
                "each local member: one initial-round turn + two autonomous turns",
                3,
                server.requests.count { it.speakerTag == LOCAL_A }
            )
            assertNoFurtherRequests(server, expected = 7)
        }
    }

    /**
     * Property 4: Stop ends the round. The in-flight member's turn adds NOTHING to the
     * transcript (not even the blank bubble a cancelled stream would leave behind), no later
     * member turn runs, and the round state settles to null so the UI's Stop button clears.
     */
    @Test
    fun stopEndsTheRoundWithoutLeavingABlankBubble() {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 4, members = listOf(LOCAL_A, LOCAL_B)))
            val room = service.activeRoom!!
            // Park the FIRST member turn mid-stream, but only AFTER it streamed a whitespace
            // chunk: that chunk opens the turn's in-place placeholder, so the transcript really
            // does hold a row for this turn when Stop lands — Stop has to REMOVE that row, not
            // merely avoid appending one.
            server.preHoldChunk = " "
            server.holdConnections = true
            assertTrue("the probe round never started", startRound(room, USER_FIRST))
            server.awaitRequests(1)
            awaitTurnInFlight(0)
            awaitService(30_000, "the first member's streaming placeholder never landed") {
                service.roomMessages.value.size == 2
            }

            assertEquals(
                "precondition: the first member's turn is the one parked",
                LOCAL_A,
                server.requests.first().speakerTag
            )

            mainSync { service.stopRoomRound() }
            awaitRoundSettled()
            // Release the parked turn only now: it has to unwind, and its late output is exactly
            // what must never land. Asserting before this point would prove nothing.
            server.releaseHeld()
            assertNoFurtherRequests(server, expected = 1)

            assertEquals(
                "Stop must not append anything for the turn it cancelled",
                listOf(RoomMessage.USER_SPEAKER),
                speakers()
            )
            assertEquals(
                "Stop must leave exactly the user's own message in the transcript",
                1,
                service.roomMessages.value.size
            )
            assertNull(
                "Stop must clear the round state (the UI's Stop button keys off it)",
                service.roomRound.value
            )
            assertNull(
                "Stop must not leave a streaming overlay behind",
                service.streamingText.value
            )
        }
    }

    /**
     * Property 5: a new round SUPERSEDES one in flight. The abandoned round runs no further
     * turns, its late unwind cannot blank the successor's state, and the transcript ends up
     * being the new round's.
     */
    @Test
    fun aNewerRoundSupersedesTheRoundInFlight() {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 0, members = listOf(LOCAL_A, LOCAL_B, CLOUD_ONE)))
            val room = service.activeRoom!!
            // Round 1's first turn opens a placeholder (a whitespace chunk) and then parks, so
            // the abandoned round really has a row of its own to strand when it is superseded.
            server.preHoldChunk = " "
            server.holdConnections = true
            assertTrue("the first probe round never started", startRound(room, USER_FIRST))
            server.awaitRequests(1)
            awaitTurnInFlight(0)
            awaitService(30_000, "the first round's streaming placeholder never landed") {
                service.roomMessages.value.size == 2
            }
            assertEquals(LOCAL_A, server.requests.first().speakerTag)

            // Round 2 starts while round 1's first member is still parked mid-request. The hold
            // is lifted for round 2's own turns; round 1's parked connection stays parked, and
            // must be interrupted by the supersede (not merely released by the test).
            server.preHoldChunk = null
            server.holdConnections = false
            assertTrue(
                "a newer round must be accepted while an older one is still unwinding",
                startRound(room, USER_SECOND)
            )
            awaitRoundSettled()
            // Now release what is left of round 1 and give its output every chance to land.
            server.releaseHeld()
            assertNoFurtherRequests(server, expected = 4)

            assertEquals(
                "the superseded round must contribute nothing beyond the user message it " +
                    "already sent, and the new round must run every member once",
                listOf(
                    RoomMessage.USER_SPEAKER, RoomMessage.USER_SPEAKER,
                    LOCAL_A, LOCAL_B, CLOUD_ONE
                ),
                speakers()
            )
            assertEquals(
                listOf(USER_FIRST, USER_SECOND),
                texts().take(2)
            )
            // Request 1 is the abandoned round's turn; the surviving round is requests 2..4.
            assertEquals(
                listOf(LOCAL_A, LOCAL_A, LOCAL_B, CLOUD_ONE),
                server.requests.map { it.speakerTag }
            )
            assertEquals(
                "the superseded turn's reply must never reach the transcript",
                0,
                service.roomMessages.value.count { it.text == replyText(1, LOCAL_A) }
            )
            assertNull("the surviving round must settle too", service.roomRound.value)
        }
    }

    // ---- harness ----------------------------------------------------------------------------

    /**
     * Open [room] through the production `switchRoom`, with the stub registered as both the
     * probe personas' Ollama server and their cloud provider.
     *
     * Idempotent on purpose: an interrupted previous run may have left probe personas or a
     * probe room behind, and duplicate persona names would make `personas.value.find { ... }`
     * resolve the wrong one.
     */
    private fun enterRoom(server: RoomTurnServer, room: Room) {
        mainSync {
            var idx = service.personas.value.indexOfFirst { it.name in PROBE_PERSONA_NAMES }
            while (idx >= 0) {
                service.removePersona(idx)
                idx = service.personas.value.indexOfFirst { it.name in PROBE_PERSONA_NAMES }
            }
            service.removeRoom(room.id)
            service._ollamaBaseUrls.value = listOf(ServerConfig(PROBE_SERVER_NAME, server.url))
            service._customCloudApis.value = listOf(probeCloudApi(server))
            PROBE_PERSONAS.forEach { service.addPersona(it) }
            service.addRoom(room)
            service.switchRoom(room)
        }
        awaitService(30_000, "the probe room's transcript was never loaded") {
            service.activeRoomId.value == room.id && service.roomMessagesOwnerId == room.id
        }
        assertTrue(
            "the probe room must start from an empty transcript",
            service.roomMessages.value.isEmpty()
        )
    }

    /** Start a round through the production entry point the room composer's send button uses. */
    private fun startRound(room: Room, text: String): Boolean {
        var started = false
        mainSync { started = service.startRoomRound(room, text) }
        return started
    }

    /**
     * Wait until no round is running. [AssistantService.startRoomRound] publishes the round
     * state synchronously, so "state is null" can only mean the round finished (or was stopped)
     * — including the superseded round, whose late settle must NOT have blanked the survivor.
     */
    private fun awaitRoundSettled(timeoutMs: Long = 60_000) {
        awaitService(timeoutMs, "the room round never settled (round=${service.roomRound.value})") {
            service.roomRound.value == null
        }
    }

    /** Wait until the round has published a member turn — i.e. that turn is in flight. */
    private fun awaitTurnInFlight(memberIndex: Int) {
        awaitService(30_000, "member #$memberIndex's turn never started") {
            val state = service.roomRound.value
            state != null && state.currentMemberIndex == memberIndex &&
                (state.phase == RoomRoundPhase.INITIAL_ROUND || state.phase == RoomRoundPhase.AUTONOMOUS)
        }
    }

    /**
     * Fail if the provider stub is asked for anything more than [expected] requests during a
     * short window. Asserting the count alone would let a stray extra turn through after the
     * assertions ran, which is exactly the failure mode (a loop that keeps going, or a
     * superseded round that resumes) this guards against.
     */
    private fun assertNoFurtherRequests(server: RoomTurnServer, expected: Int, windowMs: Long = 600) {
        val deadline = SystemClock.uptimeMillis() + windowMs
        while (SystemClock.uptimeMillis() < deadline) {
            assertEquals(
                "a member turn reached the provider stub after the round was over " +
                    "(captured turns: ${server.requests.map { it.speakerTag }})",
                expected,
                server.requests.size
            )
            Thread.sleep(20)
        }
    }

    private fun speakers(): List<String> = service.roomMessages.value.map { it.speakerName }

    private fun texts(): List<String> = service.roomMessages.value.map { it.text }

    private fun awaitService(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("timed out after ${timeoutMs}ms waiting until $what", condition())
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private companion object {

        /** The `[Name]` tag the probe members' models resolve against. */
        const val PROBE_SERVER_NAME = "RoomLoopProbeServer"

        /** The custom cloud provider the cloud probe member resolves against (icon "C"). */
        const val PROBE_CLOUD_PROVIDER = "RoomLoopProbeCloud"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "room-loop-probe-model"

        const val LOCAL_A = "RoomLoopLocalA"
        const val LOCAL_B = "RoomLoopLocalB"
        const val CLOUD_ONE = "RoomLoopCloud"

        const val PROBE_ROOM_ID = "room-loop-probe-room"

        /** Both avoid the word "news", so `isNewsRequest` cannot fire a search on the device. */
        const val USER_FIRST = "kick off the round"
        const val USER_SECOND = "one more thought for the room"

        val PROBE_PERSONA_NAMES = listOf(LOCAL_A, LOCAL_B, CLOUD_ONE)

        val PROBE_PERSONAS = listOf(
            localProbePersona(LOCAL_A),
            localProbePersona(LOCAL_B),
            cloudProbePersona(CLOUD_ONE)
        )

        fun probeRoom(cap: Int?, members: List<String>): Room =
            Room(
                id = PROBE_ROOM_ID,
                name = "Room Loop Probe",
                memberNames = members,
                autoReplyCap = cap
            )

        /**
         * A direct (non-cloud) probe member: `[PROBE_SERVER_NAME] model` routes it to the stub
         * through the same display-name lookup the real router performs. `voiceMode = NONE`
         * makes `playResponse` take its documented no-play bail, so no TTS engine is touched.
         */
        fun localProbePersona(name: String): Persona = Persona(
            name = name,
            themeColor = Color(0xFF4ADE80),
            model = "[$PROBE_SERVER_NAME] $PROBE_MODEL",
            systemPrompt = systemPromptTag(name),
            isCloud = false,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            maxTokens = 256,
            numCtx = 8192,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false
        )

        /** A cloud probe member, routed to the stub's `/chat/completions` endpoint. */
        fun cloudProbePersona(name: String): Persona = Persona(
            name = name,
            themeColor = Color(0xFF60A5FA),
            model = "[$PROBE_CLOUD_PROVIDER] $PROBE_MODEL",
            systemPrompt = systemPromptTag(name),
            isCloud = true,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            maxTokens = 256,
            numCtx = 8192,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false
        )

        /**
         * A custom cloud provider pointing at the stub. Icon "C" is the OpenAI-compatible
         * branch, and (unlike the other icons) it does not require an API key — which is what
         * lets a loopback server stand in for a paid provider here.
         */
        fun probeCloudApi(server: RoomTurnServer): CloudApiSetting = CloudApiSetting(
            name = PROBE_CLOUD_PROVIDER,
            baseUrl = server.url,
            apiKey = "",
            icon = "C",
            color = Color(0xFF6366F1),
            isEditableUrl = false
        )

        /**
         * The tag that identifies a member in a captured request body. The room preamble names
         * participants by persona NAME and the transcript labels lines with names too, so this
         * tag appears in exactly one place: the speaker's own system prompt.
         */
        fun systemPromptTag(name: String) = "ROOM-PROBE-$name"
    }
}

/**
 * The reply text the stub answers the [index]-th captured request with. Embedding the request
 * index makes the transcript say which REQUEST's answer landed where — not just which speaker.
 */
private fun replyText(index: Int, tag: String): String = "reply-$index-for-$tag"

/** One captured provider request, as the stub saw it. */
private data class RecordedRoomRequest(
    val index: Int,
    val path: String,
    val body: String,
    /** The probe member this request was built for, read back out of its own system prompt. */
    val speakerTag: String
)

/**
 * Loopback stub that answers BOTH backend shapes the room turn loop uses, and captures every
 * request body.
 *
 *  - `POST /api/chat` → Direct-Ollama NDJSON, EOF-delimited (`Connection: close`, no
 *    Content-Length), exactly the shape the production client streams.
 *  - `POST /chat/completions` → OpenAI-compatible JSON, the shape an icon-"C" custom provider
 *    gets from `parseCloudResponse`.
 *
 * [holdConnections] parks a connection AFTER its response headers (so the client is provably
 * mid-request) and before any content, which is what turns "Stop/supersede while a turn is in
 * flight" from a race into a deterministic test. The hold is released by [close], i.e. once the
 * test is done with the parked turn.
 */
private class RoomTurnServer : AutoCloseable {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val requestIndex = AtomicInteger(0)
    private val heldConnections = CountDownLatch(1)
    private val clients = CopyOnWriteArrayList<Socket>()

    /** Captured requests, in arrival order. */
    val requests = CopyOnWriteArrayList<RecordedRoomRequest>()

    /** When true, a connection is parked mid-response until [close] releases it. */
    @Volatile
    var holdConnections = false

    /**
     * Content for a first chunk the stub streams BEFORE parking (only meaningful together with
     * [holdConnections]). It lets a test park a turn that has ALREADY opened its in-place
     * placeholder — the state in which Stop has to actively REMOVE that row rather than merely
     * avoid appending one. A whitespace chunk is what makes the row removable: the app treats a
     * blank partial as no utterance at all.
     */
    @Volatile
    var preHoldChunk: String? = null

    /** Base URL accepted by the Ollama client AND by the custom cloud provider. */
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "room-loop-stub-accept") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    // Socket closed by close(): the accept loop is done.
                    break
                }
                clients.add(client)
                thread(isDaemon = true, name = "room-loop-stub-conn") { serve(client) }
            }
        }
    }

    /** Wait until at least [count] requests have been captured, or fail the test. */
    fun awaitRequests(count: Int, timeoutMs: Long = 30_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (requests.size >= count) return
            Thread.sleep(20)
        }
        throw AssertionError("the stub only ever saw ${requests.size} request(s), expected $count")
    }

    override fun close() {
        releaseHeld()
        clients.forEach { runCatching { it.close() } }
        runCatching { socket.close() }
    }

    /**
     * Let every parked connection finish, and stop parking new ones. Called at the end of a
     * `use` block; a test that means to prove a parked turn's output is DISCARDED calls it
     * itself before asserting, so the late write actually gets a chance to arrive.
     */
    fun releaseHeld() {
        holdConnections = false
        heldConnections.countDown()
    }

    private fun serve(client: Socket) {
        try {
            val input = BufferedInputStream(client.getInputStream())
            val request = readRequest(input) ?: return
            requests.add(request)
            val out = client.getOutputStream()
            if (request.path.contains("/chat/completions")) {
                respondCloud(out, request)
            } else {
                respondOllama(out, request)
            }
        } catch (_: Exception) {
            // A closed/reset socket is the normal end of a cancelled turn.
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Headers FIRST, then the hold. Writing the headers is what lets the client's `execute()`
     * return, so a parked turn is one the app has already committed to reading — which is
     * exactly the state Stop and supersede have to interrupt.
     */
    private fun awaitHoldIfNeeded() {
        if (!holdConnections) return
        heldConnections.await(30, TimeUnit.SECONDS)
    }

    private fun respondOllama(out: OutputStream, request: RecordedRoomRequest) {
        writeHeaders(out, "application/x-ndjson")
        out.flush()
        preHoldChunk?.let { writeNdjsonChunk(out, it) }
        awaitHoldIfNeeded()
        writeNdjsonChunk(out, request.reply())
        writeLine(out, """{"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}""")
    }

    private fun respondCloud(out: OutputStream, request: RecordedRoomRequest) {
        val body = """{"choices":[{"message":{"role":"assistant","content":"${request.reply()}"}}],""" +
            """"usage":{"prompt_tokens":3,"completion_tokens":2}}"""
        writeHeaders(out, "application/json", contentLength = body.toByteArray().size)
        out.flush()
        awaitHoldIfNeeded()
        out.write(body.toByteArray())
        out.flush()
    }

    private fun RecordedRoomRequest.reply(): String = replyText(index, speakerTag)

    private fun writeHeaders(out: OutputStream, contentType: String, contentLength: Int? = null) {
        // No Content-Length on the NDJSON response: that body is delimited by the connection
        // closing, which is what the production streaming reader expects.
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            (contentLength?.let { "Content-Length: $it\r\n" } ?: "") +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
    }

    private fun writeLine(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray())
        out.flush()
    }

    /** One NDJSON delta line, the shape the production streaming reader parses. */
    private fun writeNdjsonChunk(out: OutputStream, text: String) {
        writeLine(out, """{"message":{"role":"assistant","content":"$text"},"done":false}""")
    }

    private fun readRequest(input: InputStream): RecordedRoomRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        val headers = StringBuilder()
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line.isEmpty()) break
            headers.append(line).append('\n')
        }
        val contentLength = headers.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        // Drain the request body: leaving it unread makes the client see a reset connection
        // instead of a response.
        val body = StringBuilder()
        var remaining = contentLength
        val buffer = ByteArray(8_192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            body.append(String(buffer, 0, read, Charsets.UTF_8))
            remaining -= read
        }
        return RecordedRoomRequest(
            index = requestIndex.incrementAndGet(),
            path = requestLine.split(' ').getOrElse(1) { "" },
            body = body.toString(),
            speakerTag = PROBE_TAG_REGEX.find(body)?.groupValues?.get(1) ?: "unknown"
        )
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (sb.isEmpty()) null else sb.toString()
            if (byte == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(byte.toChar())
        }
    }
}

/**
 * Reads a probe member's name back out of a request body. The probe personas carry
 * `ROOM-PROBE-<name>` as their system prompt, and nothing else in a room request mentions that
 * tag (the room preamble and the transcript labels use plain names), so a match identifies
 * exactly which member the request was built for.
 */
private val PROBE_TAG_REGEX = Regex("ROOM-PROBE-([A-Za-z0-9_]+)")
