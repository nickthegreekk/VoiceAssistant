package com.nikosm.voiceassistant

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
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

    // ContextWrapper(null) keeps the abstract-Context surface minimal: only the two
    // calls SettingsManager/EncryptedSharedPreferences actually reach need answers.
    // Every other delegation hits the null base and throws NPE, which the manager's
    // own init-recovery catches — that IS the fallback path under test.
    private class FakeAppContext : ContextWrapper(null) {
        private val stores = HashMap<String, FakeSharedPreferences>()
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            stores.getOrPut(name ?: "unnamed") { FakeSharedPreferences() }

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
}