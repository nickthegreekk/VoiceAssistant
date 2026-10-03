package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Room EDIT PERSISTENCE, on-device.
 *
 * The bug this pins down is a *silent* one: the room editor updated what the user saw, and
 * the list row even reflected it in memory, but nothing reached `rooms_v1`. Every assertion
 * here therefore reads back OUT OF [SettingsManager.getRooms] — what a relaunch would
 * actually load — rather than the in-memory `service.rooms` flow, which is exactly what made
 * the broken build look correct while it was open.
 *
 * Persistence is debounced by SAVE_DEBOUNCE_MS and re-reads the flows at write time, so each
 * assertion waits for the write to land. Asserting against the flow instead would pass on a
 * build that never saves at all.
 */
@RunWith(AndroidJUnit4::class)
class RoomPersistenceInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null
    private var roomsBefore: List<Room> = emptyList()

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
                Intent(context, AssistantService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
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
        roomsBefore = service.rooms.value
    }

    @After
    fun tearDown() {
        // Restore the user's real rooms and force the write, so the probe leaves no trace in
        // the app's own encrypted prefs.
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                service._rooms.value = roomsBefore
                service.saveSettings()
            }
        }
        runCatching {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                // Compare the WHOLE list, not just the IDs. An ID-only check would happily
                // pass while the probe left a renamed/reordered/recapped room behind, so the
                // probe could silently rewrite the user's real data and this test would still
                // call itself clean.
                if (service.settingsManager.getRooms() == roomsBefore) break
                Thread.sleep(50)
            }
        }
        // Asserted OUTSIDE the runCatching above, which would otherwise swallow the
        // AssertionError and let a corrupted teardown pass as a green test.
        assertEquals(
            "the probe left the app's real rooms altered on disk",
            roomsBefore,
            service.settingsManager.getRooms()
        )
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /** Poll the PERSISTED list until [matches], so we assert what a relaunch would read. */
    private fun awaitPersist(
        what: String,
        timeoutMs: Long = 10_000,
        matches: (List<Room>) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val onDisk = runCatching { service.settingsManager.getRooms() }.getOrNull()
            if (onDisk != null && matches(onDisk)) return
            Thread.sleep(50)
        }
        val actual = runCatching {
            service.settingsManager.getRooms()
                ?.joinToString { "${it.name}[${it.memberNames}](${it.autoReplyCap})" }
        }.getOrNull()
        assertTrue("timed out after ${timeoutMs}ms waiting until $what; disk holds: $actual", false)
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** Create a probe room through the production path and confirm it landed. */
    private fun seedRoom(): Room {
        val room = Room(name = "Persistence Probe", memberNames = listOf("PM1", "PM2", "PM3"))
        mainSync { service.addRoom(room) }
        awaitPersist("the probe room was never written to disk") { it.any { r -> r.id == room.id } }
        return room
    }

    /** The live copy of [room], so each edit starts from current state. */
    private fun live(room: Room): Room = service.rooms.value.first { it.id == room.id }

    private fun persisted(id: String): Room? =
        service.settingsManager.getRooms()?.firstOrNull { it.id == id }

    @Test
    fun renamingARoomSurvivesAWriteReadCycle() {
        val room = seedRoom()

        // Exactly what RoomEditor's Done button does for the name field.
        mainSync { service.updateRoom(live(room).copy(name = "Renamed Probe")) }

        awaitPersist("the renamed room never reached disk") {
            it.any { r -> r.id == room.id && r.name == "Renamed Probe" }
        }
        assertEquals("Renamed Probe", persisted(room.id)?.name)
    }

    @Test
    fun reorderingMembersSurvivesAWriteReadCycle() {
        val room = seedRoom()

        // Exactly what the editor's up/down arrows do.
        mainSync { service.moveRoomMember(room.id, 2, 0) }

        awaitPersist("the reordered members never reached disk") {
            it.any { r -> r.id == room.id && r.memberNames == listOf("PM3", "PM1", "PM2") }
        }
        assertEquals(listOf("PM3", "PM1", "PM2"), persisted(room.id)?.memberNames)
    }

    @Test
    fun addingAndRemovingAMemberSurvivesAWriteReadCycle() {
        val room = seedRoom()

        mainSync {
            val current = live(room)
            service.updateRoom(current.copy(memberNames = current.memberNames + "PM4"))
        }
        awaitPersist("the added member never reached disk") {
            it.any { r -> r.id == room.id && r.memberNames.contains("PM4") }
        }

        mainSync {
            val current = live(room)
            service.updateRoom(current.copy(memberNames = current.memberNames.filterNot { it == "PM1" }))
        }
        awaitPersist("the removed member never reached disk") {
            it.any { r -> r.id == room.id && !r.memberNames.contains("PM1") }
        }
        assertEquals(listOf("PM2", "PM3", "PM4"), persisted(room.id)?.memberNames)
    }

    @Test
    fun aPerRoomCapOverrideSurvivesAWriteReadCycle() {
        val room = seedRoom()

        // Driven through the editor's own parser, so a "0" cannot silently collapse back
        // to the inherit sentinel on its way to disk.
        val committed = autoReplyCapOverrideOrNull("0")
        assertEquals("the editor must accept a typed 0 as a real cap", 0, committed)
        mainSync { service.updateRoomCap(room.id, committed) }

        awaitPersist("the cap override never reached disk") {
            it.any { r -> r.id == room.id && r.autoReplyCap == 0 }
        }
        assertEquals(0, persisted(room.id)?.autoReplyCap)
    }

    @Test
    fun nameReorderAndCapFromOneEditingSessionAllReachDiskTogether() {
        // The user-visible bug was "none of it sticks". This asserts the whole editor's
        // output lands in one debounced write, so a partial future fix fails here too.
        val room = seedRoom()

        mainSync {
            service.moveRoomMember(room.id, 0, 1)
            service.updateRoomCap(room.id, 2)
            service.updateRoom(live(room).copy(name = "All Three"))
        }

        awaitPersist("not every edit reached disk together") {
            it.any { r ->
                r.id == room.id &&
                    r.name == "All Three" &&
                    r.memberNames == listOf("PM2", "PM1", "PM3") &&
                    r.autoReplyCap == 2
            }
        }
        val stored = persisted(room.id)
        assertNotNull("the probe room vanished from disk", stored)
        assertEquals("All Three", stored?.name)
        assertEquals(listOf("PM2", "PM1", "PM3"), stored?.memberNames)
        assertEquals(2, stored?.autoReplyCap)
    }
}