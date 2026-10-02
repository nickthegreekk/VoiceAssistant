package com.nikosm.voiceassistant

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persona NAME uniqueness — the decision and the persistence chokepoint.
 *
 * The reported bug: the header button's translator lookup found nothing, added
 * [TRANSLATOR_PERSONA], and the picker ended up with TWO personas named "Translator".
 *
 * Why that is a data bug and not a cosmetic one: the name IS the identity. History lives in
 * `persona_messages_<name>`, room membership is `memberNames: List<String>`, the spontaneous
 * state map is name-keyed, and the scheduler's WorkManager chain and notification id are named
 * after it. Two personas sharing a name share one history file and one scheduler chain, and
 * make membership ambiguous — nothing anywhere in the app disambiguates them.
 *
 * Layering, mirroring the spontaneous cloud guard exactly:
 *   Part 1 — the pure decision the header button runs (`existingTranslatorPersona`).
 *   Part 2 — `SettingsManager.savePersonas`, the ONLY writer of `personas_v2`, which
 *            `importBackup` reaches directly without ever touching the service.
 * The in-memory layer (`AssistantService.addPersona` / `updatePersona`) needs a real service
 * and is proven on-device by [PersonaNameUniquenessInstrumentedTest].
 */
class PersonaNameGuardTest {

    private companion object {
        // Must match SettingsManager.buildEncryptedPrefs().
        const val ENCRYPTED_PREFS = "voice_assistant_secure_prefs"
    }

    private fun persona(
        name: String,
        translator: Boolean = false,
        prompt: String = "You are $name."
    ) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[HomeBox] llama3",
        systemPrompt = prompt,
        isTranslator = translator
    )

    // ------------------------------------------- Part 1: the header button's lookup

    @Test fun `the translator lookup finds a persona named Translator even without the flag`() {
        // EXACTLY the reported situation. The flag comes from a template; nothing sets it when
        // a user names a persona "Translator" (or renames one into that name). The old
        // flag-only lookup returned null here — which is precisely why a second "Translator"
        // was created beside the first.
        val namedOnly = persona("Translator", translator = false)
        val found = existingTranslatorPersona(listOf(persona("Bob"), namedOnly))
        assertSame("a name is identity here; the lookup must find it", namedOnly, found)
    }

    @Test fun `the translator lookup still finds a properly flagged translator`() {
        val flagged = persona("Übersetzer", translator = true)
        assertSame(flagged, existingTranslatorPersona(listOf(persona("Bob"), flagged)))
    }

    @Test fun `a flagged translator wins over a same-named unflagged one, whatever the order`() {
        // Order-independent on purpose: the button must not depend on which row the picker
        // happens to render first.
        val flagged = persona("Translator", translator = true, prompt = "flagged")
        val impostor = persona("Translator", translator = false, prompt = "impostor")
        assertSame(flagged, existingTranslatorPersona(listOf(flagged, impostor)))
        assertSame(flagged, existingTranslatorPersona(listOf(impostor, flagged)))
    }

    @Test fun `the lookup reports no translator when neither the flag nor the name matches`() {
        // Guards against a fix that over-matches and hijacks an unrelated persona.
        assertNull(existingTranslatorPersona(listOf(persona("Bob"), persona("Translations"))))
        assertNull(existingTranslatorPersona(emptyList()))
    }

    // ------------------------------------------- Part 2: the persistence chokepoint

    @Test fun `savePersonas drops the second persona carrying an existing name`() {
        val manager = newManager()
        manager.savePersonas(
            listOf(
                persona("Translator", prompt = "the real one"),
                persona("Translator", translator = true, prompt = "the duplicate")
            )
        )
        val stored = manager.getPersonas()!!
        assertEquals("only one persona may own a name", 1, stored.count { it.name == "Translator" })
        assertEquals("first occurrence wins, deterministically", "the real one", stored.single().systemPrompt)
    }

    @Test fun `savePersonas keeps distinct names untouched`() {
        // The inverse control: a dedupe that dropped more than duplicates would pass the
        // test above.
        val manager = newManager()
        manager.savePersonas(listOf(persona("A"), persona("B"), persona("C", translator = true)))
        assertEquals(listOf("A", "B", "C"), manager.getPersonas()!!.map { it.name })
    }

    @Test fun `importing a backup that carries two personas named Translator keeps one`() {
        // The import path never reaches AssistantService.addPersona — it calls savePersonas
        // directly — so a backup written by a build with the flag-only lookup (or hand-edited)
        // is exactly how a duplicate would otherwise become permanent on disk.
        val (manager, _) = newManagerWithPlainPrefs()
        manager.savePersonas(listOf(persona("A"), persona("B")))
        val export = manager.exportBackup()

        val (target, _) = newManagerWithPlainPrefs()
        assertTrue("the crafted backup must import", target.importBackup(export.replace("\"B\"", "\"A\"")))

        val restored = target.getPersonas()!!
        assertEquals("the restore must not persist a duplicate name", 1, restored.count { it.name == "A" })
    }


    // ------------------------------------------------------------------ fake prefs

    // Same premise as SettingsManagerCorruptionTest / SpontaneousGuardTest:
    // EncryptedSharedPreferences cannot initialize on the JVM without AndroidKeyStore, so the
    // constructor falls back to a plaintext store — the surface savePersonas writes to.

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

    private class FakeAppContext : ContextWrapper(null) {
        private val stores = HashMap<String, FakeSharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            if (name == ENCRYPTED_PREFS) throw GeneralSecurityException("AndroidKeyStore is unavailable on the JVM")
            return stores.getOrPut(name ?: "unnamed") { FakeSharedPreferences() }
        }
        override fun deleteSharedPreferences(name: String?): Boolean { stores.remove(name); return true }
    }

    private fun newManagerWithPlainPrefs(): Pair<SettingsManager, FakeSharedPreferences> {
        val prefs = FakeSharedPreferences()
        return SettingsManager(FakeAppContext()) to prefs
    }

    private fun newManager(): SettingsManager = newManagerWithPlainPrefs().first
}