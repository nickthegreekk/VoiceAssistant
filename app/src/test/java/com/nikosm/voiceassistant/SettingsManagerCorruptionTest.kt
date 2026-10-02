package com.nikosm.voiceassistant

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Guards against the one-bad-entry crash loop: every stored-format JSON decode in
// SettingsManager.kt is wrapped in runCatching and must degrade to that getter's
// null-case default instead of propagating. loadSettings()/switchPersona() run inside
// AssistantService.onCreate(), so a single unguarded decode throw turns one corrupt
// entry into a permanent startup crash loop.
//
// These tests exercise the REAL SettingsManager on the JVM: EncryptedSharedPreferences
// cannot initialize without AndroidKeyStore, so the constructor deterministically lands
// in its plaintext fallback branch, backed here by an in-memory fake. Corrupt JSON is
// injected directly into the same SharedPreferences surface the getters read from —
// the scenario adb could never produce against encrypted-at-rest values.
class SettingsManagerCorruptionTest {

    private companion object {
        const val GARBAGE = "{ this is ·definitely· not »valid« JSON ]"
        const val FALLBACK_PREFS = "voice_assistant_secure_prefs_fallback"

        // Must match the encrypted file name in SettingsManager.buildEncryptedPrefs().
        const val ENCRYPTED_PREFS = "voice_assistant_secure_prefs"
    }

    // In-memory SharedPreferences so SettingsManager's plaintext fallback branch has a
    // real store to read from and tests can inject raw (corrupt) values into it.
    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) { /* not used by SettingsManager */ }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) { /* not used by SettingsManager */ }

        private inner class FakeEditor : SharedPreferences.Editor {
            private val ops = mutableListOf<() -> Unit>()
            private fun record(op: () -> Unit): SharedPreferences.Editor = apply { ops.add(op) }
            override fun putString(key: String, value: String?): SharedPreferences.Editor = record { map[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = record { map[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = record { map[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = record { map[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = record { map[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = record { map[key] = value }
            override fun remove(key: String?): SharedPreferences.Editor = record { map.remove(key) }
            override fun clear(): SharedPreferences.Editor = record { map.clear() }
            override fun commit(): Boolean { ops.forEach { it() }; return true }
            override fun apply() { commit() }
        }
    }

    // ContextWrapper(null) keeps the abstract-Context surface minimal: only the calls
    // SettingsManager actually reaches need answers. Delegation to the null base throws
    // NPE, which the manager's own init-recovery catches — that IS the fallback path
    // under test.
    //
    // Two overrides keep that premise true on the JVM:
    //  - getApplicationContext must be non-null. SettingsManager captures
    //    `context.applicationContext` in a field initializer (SettingsManager.kt:26)
    //    that runs BEFORE and OUTSIDE its recovery try/catch, so a null there aborts
    //    construction instead of falling back.
    //  - the encrypted prefs file must stay unavailable. Given a usable app context,
    //    EncryptedSharedPreferences can now construct on the JVM against stubbed crypto,
    //    which silently REPLACES the plaintext fallback store and breaks every test
    //    below (a null keyset then NPEs inside putEncryptedObject). Refusing that one
    //    filename reproduces the production condition these tests depend on:
    //    EncryptedSharedPreferences cannot initialize without AndroidKeyStore.
    private class FakeAppContext : ContextWrapper(null) {
        private val stores = HashMap<String, FakeSharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            if (name == ENCRYPTED_PREFS) throw GeneralSecurityException(
                "AndroidKeyStore is unavailable on the JVM — encrypted prefs cannot initialize"
            )
            return stores.getOrPut(name ?: "unnamed") { FakeSharedPreferences() }
        }

        override fun deleteSharedPreferences(name: String?): Boolean {
            stores.remove(name)
            return true
        }
    }

    private fun newManagerWithPlainPrefs(): Pair<SettingsManager, SharedPreferences> {
        val context = FakeAppContext()
        val manager = SettingsManager(context)
        val fallback = context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
        return manager to fallback
    }

    private fun SharedPreferences.Editor.put(key: String, value: String) {
        putString(key, value).commit()
    }

    // ---- Mechanism pin: getters really read the raw fallback store, so the corrupt-entry
    // ---- tests below inject into exactly the surface the decoder sees (not vacuous).

    @Test fun `getters round-trip through the plaintext fallback store`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        manager.saveServerBases(listOf(ServerConfig("S1", "http://10.0.0.2:11434")))
        val stored = prefs.getString("server_bases_v2", null)
        assertTrue("expected raw JSON in fallback store, got: $stored", stored!!.contains("\"url\":\"http://10.0.0.2:11434\""))
        assertEquals(listOf(ServerConfig("S1", "http://10.0.0.2:11434")), manager.getServerBases())
    }

    // ---- Each stored-format getter survives a corrupt entry by returning its null-case default

    @Test fun `corrupted server_bases_v2 returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("server_bases_v2", GARBAGE)
        assertNull(manager.getServerBases())
    }

    @Test fun `corrupted ollama_bases_v2 returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("ollama_bases_v2", GARBAGE)
        assertNull(manager.getOllamaBases())
    }

    @Test fun `corrupted cloud_apis_v2 returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("cloud_apis_v2", GARBAGE)
        assertNull(manager.getCloudApis())
    }

    @Test fun `corrupted custom_cloud_apis_v1 returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("custom_cloud_apis_v1", GARBAGE)
        assertNull(manager.getCustomCloudApis())
    }

    @Test fun `corrupted personas_v2 returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("personas_v2", GARBAGE)
        assertNull(manager.getPersonas())
    }

    @Test fun `corrupted chat_history_v2 returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("chat_history_v2", GARBAGE)
        assertNull(manager.getMessages())
    }

    @Test fun `corrupted persona history returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("persona_messages_default", GARBAGE)
        assertNull(manager.getPersonaMessages("default"))
    }

    @Test fun `corrupted room history returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("room_messages_r1", GARBAGE)
        assertNull(manager.getRoomMessages("r1"))
    }

    @Test fun `corrupted rooms_v1 returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("rooms_v1", GARBAGE)
        assertNull(manager.getRooms())
    }

    @Test fun `corrupted skills_v1 returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("skills_v1", GARBAGE)
        assertNull(manager.getSkills())
    }

    @Test fun `corrupted favorite_models_v2 returns null instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("favorite_models_v2", GARBAGE)
        assertNull(manager.getFavoriteModels())
    }

    @Test fun `corrupted trusted_certs_v2 returns empty map instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("trusted_certs_v2", GARBAGE)
        assertEquals(emptyMap<String, String>(), manager.getTrustedCertificates())
    }

    @Test fun `corrupted model_pricing_v1 returns empty map instead of crashing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("model_pricing_v1", GARBAGE)
        assertEquals(emptyMap<String, ModelPricing>(), manager.getModelPricing())
    }

    // ---- Future-format-change scenario: VALID JSON that no longer matches the expected type

    @Test fun `valid JSON of the wrong shape is treated as corrupt`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("server_bases_v2", "\"just a string, not a list\"")
        prefs.edit().put("personas_v2", "[1, 2, 3]")
        assertNull(manager.getServerBases())
        assertNull(manager.getPersonas())
    }

    // ---- One bad entry must not take down the others

    @Test fun `one corrupt entry does not affect other entries`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        manager.saveFavoriteModels(listOf("qwen2.5", "llama3"))
        manager.saveMessages(listOf(ChatMessage(role = "user", text = "hello")))
        prefs.edit().put("server_bases_v2", GARBAGE)
        assertNull(manager.getServerBases())
        assertEquals(listOf("qwen2.5", "llama3"), manager.getFavoriteModels())
        assertEquals(listOf(ChatMessage(role = "user", text = "hello")), manager.getMessages())
    }

    // ---- Happy paths are unchanged by the guards

    @Test fun `favorite models round-trip unchanged`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.saveFavoriteModels(listOf("a", "b"))
        assertEquals(listOf("a", "b"), manager.getFavoriteModels())
    }

    @Test fun `trusted certificates round-trip unchanged`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.saveTrustedCertificates(mapOf("host.local" to "CERTDATA"))
        assertEquals(mapOf("host.local" to "CERTDATA"), manager.getTrustedCertificates())
    }

    @Test fun `chat history round-trip unchanged`() {
        val (manager, _) = newManagerWithPlainPrefs()
        val messages = listOf(
            ChatMessage(role = "user", text = "hi"),
            ChatMessage(role = "assistant", text = "hello", responseTimeMs = 42L)
        )
        manager.saveMessages(messages)
        assertEquals(messages, manager.getMessages())
    }

    @Test fun `model pricing round-trip unchanged`() {
        val (manager, _) = newManagerWithPlainPrefs()
        val pricing = mapOf("llama3" to ModelPricing(prompt = 0.1, completion = 0.2))
        manager.saveModelPricing(pricing)
        assertEquals(pricing, manager.getModelPricing())
    }

    // ---- Fix #14: legacy/no-op imports must not be reported as successes

    @Test fun `legacy backup with server_bases imports and reports success`() {
        val (manager, _) = newManagerWithPlainPrefs()
        val legacy = "{\"server_bases\":[\"http://10.0.0.2:11434\",\"http://10.0.0.3:11434\"]}"
        assertTrue(manager.importBackup(legacy))
        assertEquals(
            listOf(ServerConfig("Server 1", "http://10.0.0.2:11434"), ServerConfig("Server 2", "http://10.0.0.3:11434")),
            manager.getServerBases()
        )
    }

    @Test fun `arbitrary json object is not reported as a successful import`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        assertFalse(manager.importBackup("{\"unrelated\":\"value\"}"))
        assertNull(manager.getServerBases())
        assertFalse(prefs.getAll().containsKey("server_bases_v2"))
    }

    @Test fun `garbage input fails import`() {
        val (manager, _) = newManagerWithPlainPrefs()
        assertFalse(manager.importBackup("not json at all"))
    }

    @Test fun `explicitly empty legacy server_bases imports nothing`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        assertFalse(manager.importBackup("{\"server_bases\":[]}"))
        assertFalse(prefs.getAll().containsKey("server_bases_v2"))
    }

    @Test fun `new-format backup round-trips`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.saveServerBases(listOf(ServerConfig("S1", "http://10.0.0.2:11434")))
        manager.saveTotalCost(1.25)
        val export = manager.exportBackup()

        val (manager2, _) = newManagerWithPlainPrefs()
        assertTrue(manager2.importBackup(export))
        assertEquals(listOf(ServerConfig("S1", "http://10.0.0.2:11434")), manager2.getServerBases())
        assertEquals(1.25, manager2.getTotalCost(), 0.0)
    }

    // ---- Rooms

    @Test fun `rooms and their transcripts round-trip through a backup`() {
        val (manager, _) = newManagerWithPlainPrefs()
        // The personas must travel in the backup: restore prunes any room member that the
        // restored persona list doesn't contain, so a room backed up without its members
        // would legitimately come back empty. That pruning is asserted separately below.
        manager.savePersonas(
            listOf(
                Persona("A", androidx.compose.ui.graphics.Color(0xFF4ADE80), "[S] m", "sys"),
                Persona("B", androidx.compose.ui.graphics.Color(0xFF60A5FA), "[S] m", "sys")
            )
        )
        val room = Room(id = "r1", name = "War Room", memberNames = listOf("A", "B"), autoReplyCap = 9)
        manager.saveRooms(listOf(room))
        val messages = listOf(RoomMessage(speakerName = "A", text = "hello"))
        manager.saveRoomMessages("r1", messages)
        val export = manager.exportBackup()

        val (manager2, _) = newManagerWithPlainPrefs()
        assertTrue(manager2.importBackup(export))
        assertEquals(listOf(room), manager2.getRooms())
        val restored = manager2.getRoomMessages("r1")!!
        assertEquals(1, restored.size)
        assertEquals("A", restored.single().speakerName)
        assertEquals("hello", restored.single().text)
        // The id and timestamp must survive too — a fresh id would mean a NEW message
        // rather than the persisted one.
        assertEquals(messages.single().id, restored.single().id)
        assertEquals(messages.single().timestamp, restored.single().timestamp)
    }

    @Test fun `a zero cap round-trips as zero, not as an absent cap`() {
        // The serialization hazard behind "0 means initial round only": a stored 0 must come
        // back as 0. If it decayed to null on the way, the room would silently inherit the
        // default and resume autonomous chatter the user had switched off — and a null here
        // is exactly what an absent key looks like, so this pins the two apart.
        val (manager, _) = newManagerWithPlainPrefs()
        manager.savePersonas(
            listOf(
                Persona("A", androidx.compose.ui.graphics.Color(0xFF4ADE80), "[S] m", "sys"),
                Persona("B", androidx.compose.ui.graphics.Color(0xFF60A5FA), "[S] m", "sys")
            )
        )
        manager.saveRooms(
            listOf(
                Room(id = "zero", name = "Zero", memberNames = listOf("A", "B"), autoReplyCap = 0),
                Room(id = "inherit", name = "Inherit", memberNames = listOf("A", "B"), autoReplyCap = null)
            )
        )
        val loaded = manager.getRooms()!!.associateBy { it.id }
        assertEquals(0, loaded.getValue("zero").autoReplyCap)
        assertNull("an unset cap must still read back as null", loaded.getValue("inherit").autoReplyCap)
        assertEquals(0, RoomReplyPolicy.capFor(loaded.getValue("zero")))
        assertEquals(
            "only the unset room inherits the default",
            RoomReplyPolicy.DEFAULT_AUTO_REPLY_CAP,
            RoomReplyPolicy.capFor(loaded.getValue("inherit"))
        )
    }

    @Test fun `restoring prunes room members the restore dropped but keeps the transcript`() {
        val (manager, _) = newManagerWithPlainPrefs()
        // The backup knows "A" and "B"; only "A" survives the restore.
        manager.savePersonas(
            listOf(
                Persona("A", androidx.compose.ui.graphics.Color(0xFF4ADE80), "[S] m", "sys")
            )
        )
        manager.saveRooms(listOf(Room(id = "r1", name = "R", memberNames = listOf("A", "B"))))
        val original = listOf(RoomMessage(speakerName = "B", text = "bye"))
        manager.saveRoomMessages("r1", original)
        val export = manager.exportBackup()

        // Now simulate a restore onto a device that has no "B" at all.
        val (manager2, _) = newManagerWithPlainPrefs()
        val trimmed = export.replace("\"B\"", "\"ZZZ\"")
        assertTrue(manager2.importBackup(trimmed))
        assertEquals(listOf("A"), manager2.getRooms()!!.single().memberNames)
        // B's words stay in the transcript: membership is corrected, history is not.
        // Compared field-wise, NOT as whole objects — a freshly constructed RoomMessage
        // carries a new id and timestamp and could never equal the persisted one.
        val restored = manager2.getRoomMessages("r1")!!
        assertEquals(1, restored.size)
        assertEquals("ZZZ", restored.single().speakerName)
        assertEquals("bye", restored.single().text)
        assertEquals(original.single().timestamp, restored.single().timestamp)
    }

    @Test fun `a backup written before rooms existed still imports`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.saveServerBases(listOf(ServerConfig("S1", "http://10.0.0.2:11434")))
        val export = manager.exportBackup()
        // Strip the room fields entirely, as an older build would have written it.
        val legacy = export.replace(Regex(",\"rooms\":.*?\"roomMessages\":\\{.*?\\}"), "")
        val (manager2, _) = newManagerWithPlainPrefs()
        assertTrue("an old backup must still import", manager2.importBackup(legacy))
        assertEquals(emptyList<Room>(), manager2.getRooms())
    }

    // ---- Skills: the shared library is persona-INDEPENDENT, so a persona holds only ids.
    // ---- These pin that relationship surviving a full backup/restore onto another device.

    private val skillLibrary = listOf(
        Skill(id = "s-poem", name = "Poet", triggerPhrases = listOf("write a poem"), instructions = "Answer in verse."),
        Skill(id = "s-joke", name = "Joker", triggerPhrases = listOf("tell me a joke"), instructions = "Be funny.")
    )

    @Test fun `skills round-trip unchanged through their own key`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        manager.saveSkills(skillLibrary)
        val stored = prefs.getString("skills_v1", null)
        assertTrue("expected raw JSON in fallback store, got: $stored", stored!!.contains("\"s-poem\""))
        assertEquals(skillLibrary, manager.getSkills())
    }

    @Test fun `an absent skills key reads as no skills rather than null`() {
        val (manager, _) = newManagerWithPlainPrefs()
        // A fresh install and every pre-Skills backup must mean "no skills", not "unknown".
        assertEquals(emptyList<Skill>(), manager.getSkills())
    }

    @Test fun `a persona's enabled skills survive a backup and restore`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.saveSkills(skillLibrary)
        manager.savePersonas(
            listOf(
                Persona(
                    name = "A",
                    themeColor = androidx.compose.ui.graphics.Color(0xFF4ADE80),
                    model = "[S] m",
                    systemPrompt = "sys",
                    enabledSkillIds = listOf("s-joke", "s-poem")
                )
            )
        )
        val export = manager.exportBackup()

        // Restore onto a different (empty) device, as a reinstall or a new phone would.
        val (manager2, _) = newManagerWithPlainPrefs()
        assertTrue(manager2.importBackup(export))
        assertEquals(skillLibrary, manager2.getSkills())
        assertEquals(listOf("s-joke", "s-poem"), manager2.getPersonas()!!.single().enabledSkillIds)
    }

    @Test fun `restoring prunes enabled skills the backup does not carry`() {
        val (manager, _) = newManagerWithPlainPrefs()
        // The persona's store references the joker, but the library does not contain it —
        // the state a restore faces when a backup was hand-edited, or written by a build
        // whose library had already dropped the skill. Written straight to the store so the
        // two lists genuinely disagree: going through removeSkill would (correctly) prune the
        // persona too, leaving nothing here to test.
        manager.saveSkills(listOf(skillLibrary[0]))
        manager.savePersonas(
            listOf(
                Persona(
                    name = "A",
                    themeColor = androidx.compose.ui.graphics.Color(0xFF4ADE80),
                    model = "[S] m",
                    systemPrompt = "sys",
                    enabledSkillIds = listOf("s-joke")
                )
            )
        )
        val export = manager.exportBackup()
        val (manager2, _) = newManagerWithPlainPrefs()
        assertTrue("backup must import", manager2.importBackup(export))
        assertEquals(
            "the dangling id must be pruned on restore",
            emptyList<String>(),
            manager2.getPersonas()!!.single().enabledSkillIds
        )
        // The persona that survives is otherwise untouched, and the real skill is kept.
        assertEquals(listOf("s-poem"), manager2.getSkills()!!.map { it.id })
        assertEquals("A", manager2.getPersonas()!!.single().name)
    }

    @Test fun `a backup written before skills existed still imports`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.saveServerBases(listOf(ServerConfig("S1", "http://10.0.0.2:11434")))
        val export = manager.exportBackup()
        // Strip the skills field entirely, as an older build would have written it.
        val legacy = export.replace(Regex(",\"skills\":\\[.*?\\]"), "")
        assertTrue("old backup must still import", !legacy.contains("\"skills\""))
        val (manager2, _) = newManagerWithPlainPrefs()
        assertTrue(manager2.importBackup(legacy))
        assertEquals(emptyList<Skill>(), manager2.getSkills())
    }

    // ---- Spontaneous messages: the six per-persona fields travel WITH the persona, the
    // ---- delivery state deliberately does NOT, and a pre-feature backup still imports.
    // ---- Same shape as the Rooms/Skills blocks above: export from one manager, import into
    // ---- a second one that has never seen the data, assert on what came out.

    private fun spontaneousPersona(
        name: String,
        enabled: Boolean = false,
        instruction: String = "",
        gapMinutes: Int = 0,
        windowStart: Int? = null,
        windowEnd: Int? = null,
        maxPerDay: Int = 0
    ) = Persona(
        name = name,
        themeColor = androidx.compose.ui.graphics.Color(0xFF4ADE80),
        model = "[S] m",
        systemPrompt = "sys",
        spontaneousEnabled = enabled,
        spontaneousInstruction = instruction,
        spontaneousGapMinutes = gapMinutes,
        spontaneousWindowStartMin = windowStart,
        spontaneousWindowEndMin = windowEnd,
        spontaneousMaxPerDay = maxPerDay
    )

    @Test fun `the spontaneous fields survive a backup and restore, set and defaulted`() {
        val (manager, _) = newManagerWithPlainPrefs()
        // The opt-in only survives a write for an ELIGIBLE persona (a local model on a
        // configured server), so the probe has to be one — otherwise savePersonas strips the
        // flag first and the test would be asserting a strip rather than a round-trip.
        manager.saveOllamaBases(listOf(ServerConfig("S", "http://10.0.0.2:11434")))
        manager.savePersonas(
            listOf(
                spontaneousPersona(
                    name = "Set",
                    enabled = true,
                    instruction = "only ask about the garden",
                    gapMinutes = 90,
                    windowStart = 9 * 60,
                    windowEnd = 21 * 60,
                    maxPerDay = 3
                ),
                // The other half of the contract, and the half a "set everything" test misses:
                // a persona the user never touched must come back DEFAULTED, not as some other
                // persona's values and not as an explicitly-off copy.
                spontaneousPersona(name = "Defaults")
            )
        )
        val export = manager.exportBackup()
        // Non-vacuity: the values really are in the payload, spelled the way a hand-written
        // backup would spell them (encodeDefaults=true is what puts the keys on the persona
        // that was left alone, too).
        assertTrue(export.contains("\"spontaneousEnabled\":true"))
        assertTrue(export.contains("\"spontaneousInstruction\":\"only ask about the garden\""))
        assertTrue(export.contains("\"spontaneousGapMinutes\":90"))
        assertTrue(export.contains("\"spontaneousWindowStartMin\":540"))
        assertTrue(export.contains("\"spontaneousWindowEndMin\":1260"))
        assertTrue(export.contains("\"spontaneousMaxPerDay\":3"))

        // Restore onto a different (empty) device, as a reinstall or a new phone would.
        val (manager2, _) = newManagerWithPlainPrefs()
        assertTrue(manager2.importBackup(export))

        val restored = manager2.getPersonas()!!.associateBy { it.name }
        val set = restored.getValue("Set")
        assertTrue("the opt-in must survive", set.spontaneousEnabled)
        assertEquals("only ask about the garden", set.spontaneousInstruction)
        assertEquals(90, set.spontaneousGapMinutes)
        assertEquals(9 * 60, set.spontaneousWindowStartMin)
        assertEquals(21 * 60, set.spontaneousWindowEndMin)
        assertEquals(3, set.spontaneousMaxPerDay)

        val defaults = restored.getValue("Defaults")
        assertFalse(defaults.spontaneousEnabled)
        assertEquals("", defaults.spontaneousInstruction)
        assertEquals(0, defaults.spontaneousGapMinutes)
        assertNull(defaults.spontaneousWindowStartMin)
        assertNull(defaults.spontaneousWindowEndMin)
        assertEquals(0, defaults.spontaneousMaxPerDay)
    }

    @Test fun `delivery state is not backed up and restores as never sent`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.savePersonas(listOf(spontaneousPersona("Set", enabled = true, gapMinutes = 60)))
        manager.recordSpontaneousSent("Set", at = 1_700_000_000_000L, todayDate = "2026-01-01")
        assertEquals(
            "the probe must really have state to lose",
            1,
            manager.spontaneousStateFor("Set").sentTodayCount
        )

        val export = manager.exportBackup()
        // Deliberately NOT in the backup. Asserted on the PAYLOAD, so the exclusion is a fact
        // about the format rather than a claim about the code: the delivery counters are
        // runtime bookkeeping (when this persona last spoke, how often today), not settings.
        // Carrying them across a restore would hand the new device another device's history —
        // "already sent 3 today" would silently suppress messages for the rest of the day.
        assertFalse("the state entry must not travel", export.contains("spontaneous_state_v1"))
        assertFalse("lastSentAt must not travel", export.contains("lastSentAt"))
        assertFalse("sentTodayCount must not travel", export.contains("sentTodayCount"))

        val (manager2, _) = newManagerWithPlainPrefs()
        assertTrue(manager2.importBackup(export))
        // Absent entry → the never-sent default. Not null, not a throw. Pacing after a restore
        // comes from the ARMING path, which measures the gap from arm time (now + gap), so a
        // restored persona does not fire the moment the app opens — see
        // SpontaneousSchedulerTest's "re-arming from a fresh attempt schedules one full gap
        // ahead". "Never sent" is therefore the safe fallback, not merely a harmless one.
        assertEquals(SpontaneousState(), manager2.spontaneousStateFor("Set"))
        assertEquals(0L, manager2.spontaneousStateFor("Set").lastSentAt)
        assertEquals(emptyMap<String, SpontaneousState>(), manager2.getSpontaneousStates())
    }

    @Test fun `a corrupt spontaneous state entry degrades to never sent`() {
        val (manager, prefs) = newManagerWithPlainPrefs()
        prefs.edit().put("spontaneous_state_v1", GARBAGE)
        // Same contract as every other stored-format read in SettingsManager: the getter
        // swallows the bad entry (these reads run inside AssistantService.onCreate, where a
        // throw is a permanent startup crash loop) and the scheduler sees "never sent" rather
        // than dying on the way to its first decision.
        assertEquals(emptyMap<String, SpontaneousState>(), manager.getSpontaneousStates())
        assertEquals(SpontaneousState(), manager.spontaneousStateFor("Anyone"))
    }

    @Test fun `a backup written before spontaneous messages existed still imports`() {
        val (manager, _) = newManagerWithPlainPrefs()
        // Eligible, so the export genuinely carries a live opt-in and non-default settings —
        // the strip below then removes something real rather than a block the guard had
        // already flattened.
        manager.saveOllamaBases(listOf(ServerConfig("S", "http://10.0.0.2:11434")))
        manager.savePersonas(
            listOf(
                spontaneousPersona(
                    name = "Set",
                    enabled = true,
                    instruction = "say something",
                    gapMinutes = 90,
                    windowStart = 9 * 60,
                    windowEnd = 21 * 60,
                    maxPerDay = 3
                )
            )
        )
        val export = manager.exportBackup()
        // Non-vacuity: the export really did carry the live opt-in, so the strip below removes
        // a real setting rather than a block the guard had already flattened.
        assertTrue(export.contains("\"spontaneousEnabled\":true"))
        // Strip the six fields from every persona, exactly as a pre-feature build's backup
        // would have written them. They serialize as one contiguous trailing run (Persona
        // declares them last), so the comma in front of the run goes with it and the persona
        // objects stay valid JSON.
        val legacy = export.replace(
            Regex(",\"spontaneousEnabled\":.*?,\"spontaneousMaxPerDay\":[0-9]+"),
            ""
        )
        assertTrue("the strip must actually have happened", !legacy.contains("spontaneous"))

        val (manager2, _) = newManagerWithPlainPrefs()
        assertTrue("an old backup must still import", manager2.importBackup(legacy))

        // Absent keys → the field defaults, through the same ignoreUnknownKeys /
        // defaulted-field contract every other post-hoc field relies on (Rooms, Skills,
        // artwork). No migration, and the persona itself is otherwise intact.
        val restored = manager2.getPersonas()!!.single()
        assertEquals("Set", restored.name)
        assertFalse(restored.spontaneousEnabled)
        assertEquals("", restored.spontaneousInstruction)
        assertEquals(0, restored.spontaneousGapMinutes)
        assertNull(restored.spontaneousWindowStartMin)
        assertNull(restored.spontaneousWindowEndMin)
        assertEquals(0, restored.spontaneousMaxPerDay)
    }

    // ---- F1: the delivery state's LIFECYCLE — it is keyed by persona NAME, so it must
    // ---- follow the persona through deletion, rename and restore exactly like the
    // ---- name-keyed history entry does. All three cases exercise the SHARED surfaces
    // ---- the real service paths route through (deletePersonaHistory / migratePersonaHistory
    // ---- / importBackup), not private reimplementations.

    /**
     * The O10 global daily cap is computed over EVERY stored state entry (the worker's
     * `allStates.values.filter { it.sentTodayDate == today }.sumOf { it.sentTodayCount }`),
     * mirrored here assertion-for-assertion against the same getter.
     */
    private fun globalSentToday(manager: SettingsManager, today: String): Int =
        manager.getSpontaneousStates().values
            .filter { it.sentTodayDate == today }
            .sumOf { it.sentTodayCount }

    @Test fun `the persona deletion path drops the spontaneous state so it stops counting toward the global daily cap`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.savePersonas(
            listOf(
                spontaneousPersona("Keep", enabled = true, gapMinutes = 60),
                spontaneousPersona("Doomed", enabled = true, gapMinutes = 60)
            )
        )
        manager.recordSpontaneousSent("Keep", at = 1_000L, todayDate = "2026-01-01")
        manager.recordSpontaneousSent("Doomed", at = 2_000L, todayDate = "2026-01-01")
        manager.recordSpontaneousSent("Doomed", at = 3_000L, todayDate = "2026-01-01")
        assertEquals(
            "precondition: the probe really counts toward the cap before its deletion",
            3, globalSentToday(manager, "2026-01-01")
        )

        // THE REAL removePersona path: this is the exact cleanup call its history-IO lane
        // runs (AssistantService.removePersona → deletePersonaHistory). Pre-F1 it dropped
        // only the history entry, and the persona's state entry went on counting toward
        // the cap above for the rest of the day.
        manager.deletePersonaHistory("Doomed")

        assertNull("the deleted persona's history entry is gone", manager.getPersonaMessages("Doomed"))
        assertEquals(
            "and its delivery state must die with it",
            SpontaneousState(), manager.spontaneousStateFor("Doomed")
        )
        assertFalse(
            "the state map must not carry the deleted persona",
            "Doomed" in manager.getSpontaneousStates()
        )
        assertEquals(
            "a dead persona must no longer count toward the global daily cap",
            1, globalSentToday(manager, "2026-01-01")
        )
        assertEquals(
            "a surviving persona's pacing is untouched",
            1, manager.spontaneousStateFor("Keep").sentTodayCount
        )
    }

    @Test fun `a rename carries the spontaneous state to the new name, pacing intact`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.savePersonas(listOf(spontaneousPersona("Old", enabled = true, gapMinutes = 60)))
        manager.recordSpontaneousSent("Old", at = 1_234_500L, todayDate = "2026-02-02")
        manager.recordSpontaneousSent("Old", at = 1_234_600L, todayDate = "2026-02-02")
        val before = manager.spontaneousStateFor("Old")
        assertEquals("precondition: the probe really has pacing to lose", 2, before.sentTodayCount)
        // A history entry too, so the rename exercises the whole shared surface at once.
        manager.savePersonaMessages("Old", emptyList())

        // THE REAL updatePersona rename path: the exact call its rename branch runs
        // (AssistantService.updatePersona → migratePersonaHistory). Pre-F1 the state
        // entry stayed stranded under the old key — orphaned AND lost to the renamed
        // persona, whose pacing silently reset to "never sent".
        manager.migratePersonaHistory("Old", "New")

        val moved = manager.spontaneousStateFor("New")
        assertEquals("lastSentAt must survive the rename", before.lastSentAt, moved.lastSentAt)
        assertEquals("and the daily counter", before.sentTodayCount, moved.sentTodayCount)
        assertEquals("and the date that counter belongs to", before.sentTodayDate, moved.sentTodayDate)
        assertEquals("the old key must be gone", SpontaneousState(), manager.spontaneousStateFor("Old"))
        assertFalse("Old" in manager.getSpontaneousStates())
        assertEquals("the history entry moved too", emptyList<ChatMessage>(), manager.getPersonaMessages("New"))
        assertNull("and left nothing behind", manager.getPersonaMessages("Old"))

        // A persona carrying state but NO history entry must still migrate: the state move
        // runs independently of the history move, not after its absent-entry early-return.
        manager.recordSpontaneousSent("StateOnly", at = 7_000L, todayDate = "2026-02-02")
        manager.migratePersonaHistory("StateOnly", "StateOnlyNew")
        assertEquals(7_000L, manager.spontaneousStateFor("StateOnlyNew").lastSentAt)
        assertEquals(SpontaneousState(), manager.spontaneousStateFor("StateOnly"))
    }

    @Test fun `an import drops spontaneous state entries for personas the restore did not bring back`() {
        val (manager, _) = newManagerWithPlainPrefs()
        manager.savePersonas(listOf(spontaneousPersona("Restored", enabled = true, gapMinutes = 60)))
        // A backup that restores ONLY "Restored".
        val export = manager.exportBackup()

        // The device being restored ONTO carries local delivery state (the device-local
        // bookkeeping that never travels in the backup) for two personas this restore does
        // not bring back — one WITH a history entry, one with state only, because the entry
        // sets are independent — plus local state for the persona that IS restored.
        val (manager2, _) = newManagerWithPlainPrefs()
        manager2.recordSpontaneousSent("GhostWithHistory", at = 1_000L, todayDate = "2026-01-01")
        manager2.savePersonaMessages("GhostWithHistory", emptyList())
        manager2.recordSpontaneousSent("GhostStateOnly", at = 2_000L, todayDate = "2026-01-01")
        manager2.recordSpontaneousSent("Restored", at = 3_000L, todayDate = "2026-01-01")

        assertTrue(manager2.importBackup(export))

        assertEquals(
            "an orphan that had a history entry must lose its state",
            SpontaneousState(), manager2.spontaneousStateFor("GhostWithHistory")
        )
        assertNull("and its history entry too", manager2.getPersonaMessages("GhostWithHistory"))
        assertEquals(
            "an orphan with state only must lose it as well — the history sweep cannot see it",
            SpontaneousState(), manager2.spontaneousStateFor("GhostStateOnly")
        )
        assertEquals(
            "the restored persona's LOCAL state survives the import",
            3_000L, manager2.spontaneousStateFor("Restored").lastSentAt
        )
    }
}