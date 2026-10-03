package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The room transcript's scroll behaviour, and the message identity it depends on.
 *
 * Two defects, fixed together because one caused the other:
 *
 *  1. [RoomChatArea] had no scroll handling at all, so opening a room that already had a
 *     conversation started at the top, and a live round grew downward off-screen.
 *  2. [RoomTurnSink] minted a FRESH `RoomMessage.id` on every streaming chunk. The
 *     transcript's LazyColumn keys on that id, so each token read as remove+insert: the
 *     bubble was destroyed and rebuilt mid-stream, discarding its scroll anchoring.
 *
 * The UI assertions are deliberately about POSITION (`firstVisibleItemIndex` /
 * `canScrollForward`), not pixels: "is the newest message on screen" is the user-visible
 * property, and a pixel assertion would be brittle across devices and font scales.
 */
@RunWith(AndroidJUnit4::class)
class RoomAutoScrollInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var roomMessagesBefore: List<RoomMessage> = emptyList()
    private var ownerBefore: String? = null

    @Before
    fun bindAssistantService() {
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
            context.bindService(
                Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE
            )
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        // Join the service's startup history load before this test touches _messages:
        // onCreate -> loadSettings() -> switchPersona() publishes _messages asynchronously,
        // and a test that assigns _messages while that is still in flight has it
        // overwritten underneath (a replay then finds no message to index and publishes
        // nothing). No-op when no history operation is in flight.
        runBlocking { service.awaitPersonaHistoryIo() }
        roomMessagesBefore = service._roomMessages.value
        ownerBefore = service.roomMessagesOwnerId
    }

    @After
    fun tearDown() {
        mainSync {
            service._roomMessages.value = roomMessagesBefore
            service.roomMessagesOwnerId = ownerBefore
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    // ---- message identity: the precondition for the scroll to hold still ---------------

    /**
     * A streaming update is an edit of an EXISTING message, so it must keep that message's
     * id and timestamp. It did not: each chunk minted a new UUID, which the transcript's
     * LazyColumn keys on — so every token destroyed and rebuilt the bubble.
     */
    @Test
    fun streamingUpdatesPreserveTheMessagesIdentity() {
        mainSync {
            service.roomMessagesOwnerId = ROOM_ID
            service._roomMessages.value = emptyList()
        }
        val sink = RoomTurnSink(service, ROOM_ID, "Bob")

        val appended = sink.append(ChatMessage("assistant", "Hel"))
        assertTrue("the sink refused the append", appended >= 0)
        val original = service._roomMessages.value.single()

        // More chunks, as a streaming reply would deliver.
        sink.updateAt(0) { it.copy(text = it.text + "lo, wor") }
        sink.updateAt(0) { it.copy(text = it.text + "ld!") }
        val afterChunks = service._roomMessages.value.single()

        assertEquals(
            "a streaming update re-minted the message id, so the LazyColumn treats every " +
                "chunk as remove+insert",
            original.id,
            afterChunks.id
        )
        assertEquals(
            "a streaming update re-stamped the message's creation time",
            original.timestamp,
            afterChunks.timestamp
        )
        // The text must of course have advanced — the identity is what must not.
        assertEquals("Hello, world!", afterChunks.text)
    }

    @Test
    fun anAppendedMessageStillGetsAFreshIdentity() {
        mainSync {
            service.roomMessagesOwnerId = ROOM_ID
            service._roomMessages.value = emptyList()
        }
        val sink = RoomTurnSink(service, ROOM_ID, "Bob")
        sink.append(ChatMessage("assistant", "one"))
        sink.append(ChatMessage("assistant", "two"))
        val ids = service._roomMessages.value.map { it.id }
        assertEquals(2, ids.size)
        assertNotEquals(
            "two distinct messages must not share an id, or the LazyColumn drops one",
            ids[0], ids[1]
        )
    }

    // ---- the scroll behaviour itself ------------------------------------------------------

    private val room = Room(id = ROOM_ID, name = "Scroll Room", memberNames = listOf("Bob"))

    /** Tall bubbles, so "at the top" and "at the bottom" are unambiguously different. */
    private fun transcript(count: Int) = (0 until count).map { i ->
        RoomMessage(
            speakerName = if (i % 2 == 0) RoomMessage.USER_SPEAKER else "Bob",
            text = "Message $i — " + "lorem ipsum dolor sit amet ".repeat(6)
        )
    }

    /**
     * Whether [listState] is showing the newest message.
     *
     * Deliberately NOT "firstVisibleItemIndex is near the end": the final bubble is tall, so
     * a correctly-bottomed list legitimately reports a small index (item 23 fully visible
     * with a tall item can mean firstVisibleItemIndex == 17). The last ITEM being visible is
     * the property the user actually sees.
     */
    private fun LazyListState.isShowingNewest(): Boolean {
        val total = layoutInfo.totalItemsCount
        return total > 0 && layoutInfo.visibleItemsInfo.any { it.index == total - 1 }
    }

    /**
     * Asserts the transcript is long enough that reaching the newest message requires an
     * actual scroll.
     *
     * Checked as "more items than fit on screen" rather than `canScrollForward`: once the
     * auto-scroll has done its job the list legitimately sits at the BOTTOM, where
     * `canScrollForward` is false. Using it as the precondition would be circular — it would
     * fail precisely when the fix is working.
     */
    private fun requireScrollable(listState: LazyListState, count: Int) {
        val info = listState.layoutInfo
        assertTrue(
            "precondition: $count messages must overflow the viewport, or scrolling is " +
                "meaningless here (total=${info.totalItemsCount}, visible=" +
                "${info.visibleItemsInfo.size})",
            info.totalItemsCount > info.visibleItemsInfo.size && info.visibleItemsInfo.size > 0
        )
    }

    @Composable
    private fun RoomTranscript(listState: LazyListState, messages: List<RoomMessage>) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                RoomChatArea(
                    room = room,
                    messages = messages,
                    personas = listOf(Persona("Bob", Color(0xFF4ADE80), "[S] m", "sys")),
                    roundState = null,
                    chatFontSize = 16f,
                    textInput = "",
                    onTextInputChange = {},
                    onSend = {},
                    onStop = {},
                    focusRequester = FocusRequester(),
                    listState = listState
                )
            }
        }
    }

    /**
     * Opening a room that already has a conversation lands on the NEWEST message, not the
     * top — the reported defect.
     */
    @Test
    fun openingARoomShowsTheNewestMessage() {
        val messages = transcript(24)
        val listState = LazyListState() // starts at the top, as an unscrolled list does
        composeRule.setContent { RoomTranscript(listState, messages) }
        composeRule.waitForIdle()

        requireScrollable(listState, messages.size)
        assertTrue(
            "a room with a history opened without reaching the newest message; visible=" +
                "${listState.layoutInfo.visibleItemsInfo.map { it.index }} of " +
                "${listState.layoutInfo.totalItemsCount}",
            listState.isShowingNewest()
        )
        assertTrue(
            "the transcript should be scrolled to the very end",
            !listState.canScrollForward
        )
    }

    /**
     * A message arriving DURING a round pulls the view down with it, so a live round can be
     * followed without touching the screen.
     */
    @Test
    fun aNewMessageDuringARoundPullsTheViewDown() {
        val messages = mutableStateOf(transcript(24))
        val listState = LazyListState()
        composeRule.setContent { RoomTranscript(listState, messages.value) }
        composeRule.waitForIdle()
        requireScrollable(listState, messages.value.size)
        val before = listState.firstVisibleItemIndex

        mainSync {
            messages.value = messages.value + RoomMessage(
                speakerName = "Bob",
                text = "Newest reply — " + "streaming content ".repeat(6)
            )
        }
        composeRule.waitForIdle()

        assertTrue(
            "a new message arrived but the view stayed at index $before",
            listState.firstVisibleItemIndex > before
        )
        assertTrue(
            "the view should be following the newest message",
            listState.isShowingNewest() && !listState.canScrollForward
        )
    }

    /**
     * The subtle half of following a live round: a streaming reply UPDATES the last bubble
     * in place, so `messages.size` never changes. Keyed on size alone the view would stop
     * following after the first chunk and the rest of the reply would sit below the fold.
     */
    @Test
    fun aStreamingReplyKeepsPullingTheViewDownAsItGrows() {
        val last = RoomMessage(speakerName = "Bob", text = "Short")
        val messages = mutableStateOf(transcript(24) + last)
        val countBeforeGrowth = messages.value.size
        val listState = LazyListState()
        composeRule.setContent { RoomTranscript(listState, messages.value) }
        composeRule.waitForIdle()
        requireScrollable(listState, messages.value.size)
        assertTrue(
            "precondition: the view must start at the newest message, so there is " +
                "somewhere BELOW the fold for the grown reply to hide in",
            listState.isShowingNewest()
        )

        // Same SIZE, much longer text — exactly what a streaming chunk looks like.
        mainSync {
            messages.value = messages.value.dropLast(1) + last.copy(
                text = "Short, and now considerably longer: " + "more output ".repeat(20)
            )
        }
        composeRule.waitForIdle()

        assertEquals(
            "this test only means something if the item count did NOT change — an append " +
                "would be followed by any size-keyed effect, an in-place edit by none",
            countBeforeGrowth,
            messages.value.size
        )
        assertTrue(
            "the reply grew in place but the view did not follow it " +
                "(canScrollForward=${listState.canScrollForward}, visible=" +
                "${listState.layoutInfo.visibleItemsInfo.map { it.index }})",
            listState.isShowingNewest() && !listState.canScrollForward
        )
    }

    private companion object {
        const val ROOM_ID = "room-autoscroll-probe"
    }

    // ---- visual proof, from the real composable -------------------------------------------

    /**
     * Screenshots of the REAL [RoomChatArea] with a long transcript, so the behaviour can be
     * checked by eye and not only by assertion.
     *
     *  - `07`: the transcript as it looks the MOMENT a room with history is opened. The
     *    newest message must be on screen — before the fix this frame showed the oldest.
     *  - `08`: the same view after a streaming reply has grown in place, still at the bottom.
     *
     * Nothing on the service is touched: the Room and the transcript are local values, so the
     * user's own rooms and messages are untouched by this class.
     */
    @Test
    fun captureTheOpenedAndFollowingTranscript() {
        val messages = mutableStateOf(transcript(20))
        val listState = LazyListState()
        composeRule.setContent { RoomTranscript(listState, messages.value) }
        composeRule.waitForIdle()
        requireScrollable(listState, messages.value.size)
        shot("07-room-opened-at-newest")

        // A reply streams in: appended, then grown in place, as a round produces it.
        val reply = RoomMessage(speakerName = "Bob", text = "Bob is replying")
        mainSync { messages.value = messages.value + reply }
        composeRule.waitForIdle()
        mainSync {
            messages.value = messages.value.dropLast(1) + reply.copy(
                text = "Bob is replying, and this part is streaming in: " + "more output ".repeat(12)
            )
        }
        composeRule.waitForIdle()
        shot("08-room-following-stream")
    }

    private fun shot(name: String) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        java.io.File(dir, "$name.png").outputStream().use { out ->
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}