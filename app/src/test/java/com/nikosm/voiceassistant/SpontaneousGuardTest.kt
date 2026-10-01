package com.nikosm.voiceassistant

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import java.security.GeneralSecurityException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spontaneous messages: the data model and — the part that matters — the CLOUD GUARD.
 *
 * The feature's hard constraint is that a local (Ollama) persona is the only kind that may
 * ever hold `spontaneousEnabled = true`, because a self-initiated turn on a cloud persona is
 * a paid turn nobody asked for. That guard is deliberately redundant (plan §6): a shared
 * predicate, a sanitize function, and three separate write paths. These tests pin each layer
 * AND prove the chokepoint end-to-end — including the backup-import path, which reaches
 * storage through `SettingsManager.savePersonas` and never touches `AssistantService`.
 *
 * Part 1 also pins the backward-compat contract: every new field is defaulted, so a
 * `personas_v2` entry written before this feature must decode unchanged.
 */
class SpontaneousGuardTest {

    private companion object {
        // Mirrors SettingsManager's Json exactly, so the compat test decodes the way
        // production does rather than against a more forgiving configuration.
        val PRODUCTION_JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        // The configured server/provider names, as the real router would resolve them.
        val OLLAMA = setOf("HomeBox")
        val CLOUD = setOf("OpenAI", "Anthropic", "Google")

        const val FALLBACK_PREFS = "voice_assistant_secure_prefs_fallback"
        // Must match SettingsManager.buildEncryptedPrefs().
        const val ENCRYPTED_PREFS = "voice_assistant_secure_prefs"
    }

    private fun persona(
        name: String = "Bob",
        model: String = "[HomeBox] llama3",
        cloud: Boolean = false,
        image: Boolean = false,
        translator: Boolean = false
    ) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = model,
        systemPrompt = "You are $name.",
        isCloud = cloud,
        isImageGenerator = image,
        isTranslator = translator
    )

    // ------------------------------------------------------------- Part 1: data model

    @Test fun `spontaneous fields default to off so an existing persona never starts messaging`() {
        val p = persona()
        assertFalse(p.spontaneousEnabled)
        assertEquals("", p.spontaneousInstruction)
        assertEquals(0, p.spontaneousGapMinutes)
        assertEquals(null, p.spontaneousWindowStartMin)
        assertEquals(null, p.spontaneousWindowEndMin)
        assertEquals(0, p.spontaneousMaxPerDay)
    }

    @Test fun `a personas_v2 entry written before the feature still decodes to the defaults`() {
        // Exactly the four non-defaulted Persona fields; no spontaneous keys at all, which
        // is what every stored persona on every existing install looks like today.
        val legacy = """{"name":"Old","themeColor":-16744193,"model":"[HomeBox] llama3","systemPrompt":"hi"}"""
        val decoded = PRODUCTION_JSON.decodeFromString<Persona>(legacy)
        assertFalse(decoded.spontaneousEnabled)
        assertEquals(0, decoded.spontaneousGapMinutes)
        assertEquals(null, decoded.spontaneousWindowStartMin)
        assertEquals("Old", decoded.name)
    }

    @Test fun `spontaneous settings survive a round trip through kotlinx serialization`() {
        val original = persona().copy(
            spontaneousEnabled = true,
            spontaneousInstruction = "check in about the trip",
            spontaneousGapMinutes = 90,
            spontaneousWindowStartMin = 8 * 60,
            spontaneousWindowEndMin = 22 * 60,
            spontaneousMaxPerDay = 3
        )
        val encoded = PRODUCTION_JSON.encodeToString(Persona.serializer(), original)
        // encodeDefaults=true, so this genuinely asserts the keys are WRITTEN — not merely
        // that they fall back to defaults on read.
        assertTrue(encoded.contains("\"spontaneousEnabled\""))
        assertTrue(encoded.contains("\"spontaneousGapMinutes\""))
        assertEquals(original, PRODUCTION_JSON.decodeFromString<Persona>(encoded))
    }

    // -------------------------------------------------- Part 2: eligibility predicate

    @Test fun `a local direct-ollama persona is eligible`() {
        val e = persona().spontaneousEligibility(OLLAMA, CLOUD)
        assertTrue("expected eligible, got $e", e is RoomEligibility.Eligible)
        assertFalse("a local persona must never resolve as cloud", (e as RoomEligibility.Eligible).cloud)
    }

    @Test fun `a cloud persona is blocked even though Rooms would let it speak`() {
        // The whole point of the feature: roomEligibility says Eligible(cloud=true) — cloud
        // personas answer the user's message in a room — but must NOT be able to start a
        // turn of their own accord.
        val cloud = persona("C", model = "[OpenAI] gpt-4o", cloud = true)
        assertTrue(
            "Rooms must still accept it (the two rules differ deliberately)",
            cloud.roomEligibility(OLLAMA, CLOUD) is RoomEligibility.Eligible
        )
        val e = cloud.spontaneousEligibility(OLLAMA, CLOUD)
        assertTrue("expected blocked, got $e", e is RoomEligibility.Blocked)
        assertEquals(SpontaneousPolicy.CLOUD_BLOCK_REASON, (e as RoomEligibility.Blocked).reason)
    }

    @Test fun `eligibility does not depend on the isCloud flag being fresh`() {
        // PersonaEditor derives isCloud from the model string on every commit, so a stale
        // flag is normal while a model is being edited. A cloud-provider model must be
        // refused on the model string alone, never only when the flag happens to agree.
        val staleFlag = persona("C", model = "[Anthropic] claude", cloud = false)
        assertTrue("expected blocked with a stale isCloud=false", staleFlag.spontaneousEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
    }

    @Test fun `a gateway-only persona is blocked because its history never reaches the model`() {
        // A bare model name routes through the gateway, which is history-blind — a
        // spontaneous message has nothing to react to.
        assertTrue(persona("G", model = "llama3").spontaneousEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
        assertTrue(persona("G2", model = "[Unconfigured] llama3").spontaneousEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
    }

    @Test fun `translator and image-generator personas are blocked with chat-appropriate wording`() {
        assertTrue(persona("T", translator = true).spontaneousEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
        assertTrue(persona("I", image = true).spontaneousEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
        // The wording must not mention Rooms — this is the persona editor's own gate.
        val reason = (persona("I", image = true).spontaneousEligibility(OLLAMA, CLOUD) as RoomEligibility.Blocked).reason
        assertTrue("reason said 'room': $reason", !reason.contains("room"))
    }

    @Test fun `a blank model is blocked`() {
        assertTrue(persona("B", model = "").spontaneousEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
    }

    @Test fun `eligibility parses the model with the same trim rules as the router`() {
        assertTrue(persona("P", model = " [HomeBox] llama3 ").spontaneousEligibility(OLLAMA, CLOUD) is RoomEligibility.Eligible)
        assertTrue(persona("P2", model = " [OpenAI] gpt-4o ", cloud = true).spontaneousEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
    }

    // ------------------------------------------------------- Part 3: the sanitize guard

    @Test fun `sanitize forces a cloud persona off`() {
        val enabledCloud = persona("C", model = "[OpenAI] gpt-4o", cloud = true)
            .copy(spontaneousEnabled = true)
        val out = SpontaneousPolicy.sanitize(enabledCloud, OLLAMA, CLOUD)
        assertFalse("a cloud persona must never store spontaneousEnabled=true", out.spontaneousEnabled)
        // Only the flag may change — sanitize is a guard, not a rewrite.
        assertEquals(enabledCloud.copy(spontaneousEnabled = false), out)
    }

    @Test fun `sanitize leaves an eligible local persona enabled`() {
        val enabledLocal = persona().copy(spontaneousEnabled = true)
        assertEquals(enabledLocal, SpontaneousPolicy.sanitize(enabledLocal, OLLAMA, CLOUD))
    }

    @Test fun `sanitize leaves an already-disabled persona untouched`() {
        val off = persona("C", model = "[OpenAI] gpt-4o", cloud = true)
        assertEquals(off, SpontaneousPolicy.sanitize(off, OLLAMA, CLOUD))
    }

    @Test fun `sanitize is idempotent so it is safe to call from every write path`() {
        val cloud = persona("C", model = "[OpenAI] gpt-4o", cloud = true).copy(spontaneousEnabled = true)
        val once = SpontaneousPolicy.sanitize(cloud, OLLAMA, CLOUD)
        val twice = SpontaneousPolicy.sanitize(once, OLLAMA, CLOUD)
        assertEquals(once, twice)
        assertFalse(twice.spontaneousEnabled)
    }

    @Test fun `sanitize catches every ineligible shape, not just cloud`() {
        val ineligible = listOf(
            persona("G", model = "llama3").copy(spontaneousEnabled = true),      // gateway-only
            persona("T", translator = true).copy(spontaneousEnabled = true),
            persona("I", image = true).copy(spontaneousEnabled = true),
            persona("B", model = "").copy(spontaneousEnabled = true),            // blank model
            persona("U", model = "[Nope] llama3").copy(spontaneousEnabled = true) // unconfigured server
        )
        ineligible.forEach { p ->
            assertFalse("should have been stripped: ${p.name}/${p.model}", SpontaneousPolicy.sanitize(p, OLLAMA, CLOUD).spontaneousEnabled)
        }
    }

    // ------------------------------------------- Part 3b: gap / cap reading semantics

    @Test fun `a zero gap means the policy default, never no gap`() {
        // The c755070 trap: 0 and "unset" must not collide. 0 here means "use the default".
        assertEquals(SpontaneousPolicy.DEFAULT_GAP_MINUTES, SpontaneousPolicy.effectiveGapMinutes(persona()))
        assertEquals(SpontaneousPolicy.DEFAULT_GAP_MINUTES, SpontaneousPolicy.effectiveGapMinutes(persona().copy(spontaneousGapMinutes = 0)))
    }

    @Test fun `a gap below the WorkManager floor is clamped rather than silently stretched by Android`() {
        assertEquals(
            SpontaneousPolicy.MIN_GAP_MINUTES,
            SpontaneousPolicy.effectiveGapMinutes(persona().copy(spontaneousGapMinutes = 1))
        )
        assertEquals(90, SpontaneousPolicy.effectiveGapMinutes(persona().copy(spontaneousGapMinutes = 90)))
        assertTrue(SpontaneousPolicy.MIN_GAP_MINUTES >= 15)
    }

    @Test fun `a zero daily cap means the policy default`() {
        assertEquals(SpontaneousPolicy.DEFAULT_MAX_PER_DAY, SpontaneousPolicy.effectiveMaxPerDay(persona()))
        assertEquals(2, SpontaneousPolicy.effectiveMaxPerDay(persona().copy(spontaneousMaxPerDay = 2)))
    }

    @Test fun `isEligible agrees with spontaneousEligibility`() {
        assertTrue(SpontaneousPolicy.isEligible(persona(), OLLAMA, CLOUD))
        assertFalse(SpontaneousPolicy.isEligible(persona("C", model = "[OpenAI] gpt-4o", cloud = true), OLLAMA, CLOUD))
    }

    // ------------------------------------------------ Part 4: the persistence chokepoint
    //
    // `savePersonas` is the ONLY writer of personas_v2, so it — not AssistantService — is
    // where "no path can enable this on a cloud persona" is actually true. The harness
    // below reproduces SettingsManagerCorruptionTest's premise: EncryptedSharedPreferences
    // cannot initialize on the JVM without AndroidKeyStore, so the constructor falls back
    // to a plaintext store, which is the surface the guard reads its server lists from.

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
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

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

    // getApplicationContext must be non-null (captured in a field initializer before the
    // manager's recovery try/catch), and the encrypted file must stay unavailable so the
    // plaintext fallback is the store under test.
    private class FakeAppContext : ContextWrapper(null) {
        private val stores = HashMap<String, FakeSharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            if (name == ENCRYPTED_PREFS) throw GeneralSecurityException("AndroidKeyStore is unavailable on the JVM")
            return stores.getOrPut(name ?: "unnamed") { FakeSharedPreferences() }
        }
        override fun deleteSharedPreferences(name: String?): Boolean { stores.remove(name); return true }
    }

    private fun newManager(): SettingsManager = SettingsManager(FakeAppContext())

    private fun cloudApi(name: String) = CloudApiSetting(
        name = name, baseUrl = "https://api.$name.test", apiKey = "k", icon = "O",
        color = Color(0xFF4ADE80)
    )

    @Test fun `savePersonas strips spontaneousEnabled from a cloud persona`() {
        val manager = newManager()
        manager.saveOllamaBases(listOf(ServerConfig("HomeBox", "http://10.0.0.2:11434")))
        manager.saveCloudApis(listOf(cloudApi("OpenAI")))

        manager.savePersonas(listOf(persona("C", model = "[OpenAI] gpt-4o", cloud = true).copy(spontaneousEnabled = true)))

        val stored = manager.getPersonas()!!.single()
        assertFalse("the chokepoint must not store an enabled cloud persona", stored.spontaneousEnabled)
    }

    @Test fun `savePersonas keeps spontaneousEnabled on an eligible local persona`() {
        val manager = newManager()
        manager.saveOllamaBases(listOf(ServerConfig("HomeBox", "http://10.0.0.2:11434")))
        manager.saveCloudApis(listOf(cloudApi("OpenAI")))

        manager.savePersonas(listOf(persona().copy(spontaneousEnabled = true)))

        assertTrue("a genuinely local persona must keep its opt-in", manager.getPersonas()!!.single().spontaneousEnabled)
    }

    @Test fun `the guard judges against the persona's own server lists`() {
        // The persona points at a server that is NOT configured — gateway-only, history-blind
        // — so it is ineligible even though another local server exists.
        val manager = newManager()
        manager.saveOllamaBases(listOf(ServerConfig("HomeBox", "http://10.0.0.2:11434")))

        manager.savePersonas(listOf(persona("X", model = "[OtherBox] llama3").copy(spontaneousEnabled = true)))

        assertFalse(manager.getPersonas()!!.single().spontaneousEnabled)
    }

    @Test fun `a mixed list is filtered persona by persona, not all-or-nothing`() {
        val manager = newManager()
        manager.saveOllamaBases(listOf(ServerConfig("HomeBox", "http://10.0.0.2:11434")))
        manager.saveCloudApis(listOf(cloudApi("OpenAI")))

        manager.savePersonas(
            listOf(
                persona("L").copy(spontaneousEnabled = true),
                persona("C", model = "[OpenAI] gpt-4o", cloud = true).copy(spontaneousEnabled = true),
                persona("Off").copy(spontaneousEnabled = false)
            )
        )

        val stored = manager.getPersonas()!!.associateBy { it.name }
        assertTrue("local must survive", stored["L"]!!.spontaneousEnabled)
        assertFalse("cloud must be stripped", stored["C"]!!.spontaneousEnabled)
        assertFalse("already-off stays off", stored["Off"]!!.spontaneousEnabled)
    }

    @Test fun `importBackup cannot smuggle a cloud persona in as enabled`() {
        // THE PATH THE SERVICE CANNOT SEE: importBackup writes personas via savePersonas
        // directly and never reaches AssistantService.updatePersona. Had the guard lived
        // only in the service, a hand-made backup would restore an enabled cloud persona.
        // Note importBackup persists the backup's OWN server lists before savePersonas runs,
        // so the persona is judged against the servers it was configured with.
        val manager = newManager()
        val backup = BackupData(
            serverBases = emptyList(),
            ollamaBases = listOf(ServerConfig("HomeBox", "http://10.0.0.2:11434")),
            cloudApis = listOf(cloudApi("OpenAI")),
            personas = listOf(
                persona("C", model = "[OpenAI] gpt-4o", cloud = true).copy(spontaneousEnabled = true),
                persona("L").copy(spontaneousEnabled = true)
            ),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap()
        )

        assertTrue("import should succeed", manager.importBackup(Json.encodeToString(BackupData.serializer(), backup)))

        val stored = manager.getPersonas()!!.associateBy { it.name }
        assertFalse("imported cloud persona must be stripped", stored["C"]!!.spontaneousEnabled)
        assertTrue("imported local persona must keep its opt-in", stored["L"]!!.spontaneousEnabled)
    }

    @Test fun `a hand-crafted backup cannot smuggle an enabled cloud persona past import`() {
        // Same path as the test above, one step more literal: a backup carrying a cloud
        // persona whose flag is spelled out explicitly, alongside the other five spontaneous
        // fields. The guard must strip EXACTLY the opt-in and leave the rest of the persona's
        // configuration intact — clearing the whole block, or rejecting the import outright,
        // would be a different bug wearing the same green test.
        val manager = newManager()
        val smuggled = persona("C", model = "[OpenAI] gpt-4o", cloud = true).copy(
            spontaneousEnabled = true,
            spontaneousInstruction = "wake me up",
            spontaneousGapMinutes = 45,
            spontaneousWindowStartMin = 8 * 60,
            spontaneousWindowEndMin = 22 * 60,
            spontaneousMaxPerDay = 5
        )
        val json = Json.encodeToString(
            BackupData.serializer(),
            BackupData(
                serverBases = emptyList(),
                ollamaBases = listOf(ServerConfig("HomeBox", "http://10.0.0.2:11434")),
                cloudApis = listOf(cloudApi("OpenAI")),
                personas = listOf(smuggled),
                messages = emptyList(),
                totalCost = 0.0,
                favoriteModels = emptyList(),
                trustedCerts = emptyMap()
            )
        )
        // Non-vacuity: the payload really does try to smuggle the flag.
        assertTrue(
            "the crafted backup must carry the flag",
            json.contains("\"spontaneousEnabled\":true")
        )

        assertTrue("import should succeed", manager.importBackup(json))

        val restored = manager.getPersonas()!!.single()
        assertFalse("the opt-in must be stripped on the way in", restored.spontaneousEnabled)
        assertEquals("wake me up", restored.spontaneousInstruction)
        assertEquals(45, restored.spontaneousGapMinutes)
        assertEquals(8 * 60, restored.spontaneousWindowStartMin)
        assertEquals(22 * 60, restored.spontaneousWindowEndMin)
        assertEquals(5, restored.spontaneousMaxPerDay)
    }
}

