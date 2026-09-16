package com.nikosm.voiceassistant

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.security.GeneralSecurityException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pins the Celestial UI default AND the precedence that makes flipping it safe:
// SharedPreferences.getBoolean returns the fallback only when the key is ABSENT, so
// raising the default to true must change the layout a fresh install opens in without
// touching any device that already stored a value — i.e. a user who deliberately
// switched the HUD off must stay on the classic layout across this change.
//
// Tested on the JVM rather than on hardware deliberately: the interesting cases are
// "key absent" versus "key present", which no adb-driven device check can isolate
// (a device that has ever run the app has the key), and it keeps the check off the
// instrumented runner entirely.
//
// Like SettingsManagerCorruptionTest, this drives the REAL SettingsManager:
// EncryptedSharedPreferences cannot initialize without AndroidKeyStore, so the
// constructor deterministically lands in its plaintext fallback branch, backed here
// by an in-memory fake.
class CelestialUiDefaultTest {

    private companion object {
        const val KEY = "celestial_ui"

        // Must match the fallback file name used by SettingsManager.
        const val FALLBACK_PREFS = "voice_assistant_secure_prefs_fallback"

        // Must match the encrypted file name in SettingsManager.buildEncryptedPrefs().
        const val ENCRYPTED_PREFS = "voice_assistant_secure_prefs"
    }

    // In-memory SharedPreferences so SettingsManager's plaintext fallback branch has a
    // real store, and tests can choose whether the key is absent or explicitly set.
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

    // ContextWrapper(null) keeps the abstract-Context surface minimal — see
    // SettingsManagerCorruptionTest for why these two overrides are load-bearing:
    // getApplicationContext must be non-null (captured in a field initializer that runs
    // outside the recovery try/catch), and the encrypted prefs file must stay
    // unavailable so the plaintext fallback branch is the one under test.
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

    private fun newManager(): Pair<SettingsManager, SharedPreferences> {
        val context = FakeAppContext()
        val manager = SettingsManager(context)
        return manager to context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
    }

    // A fresh install: nothing has ever written the key, so getBoolean takes the
    // fallback. This is the behavior change — the app must now open in the HUD.
    @Test
    fun freshInstall_defaultsToCelestialUiOn() {
        val (manager, store) = newManager()
        assertFalse("precondition: nothing may be saved yet", store.contains(KEY))
        assertTrue("a fresh install must open in Celestial UI", manager.getCelestialUi())
    }

    // The regression this guards: a device that has the key explicitly saved OFF must
    // keep the classic layout. Raising the default must not drag such devices back to
    // the HUD (they have a saved value, so the fallback is never consulted).
    @Test
    fun savedOff_keepsClassicLayout() {
        val (manager, store) = newManager()
        store.edit().putBoolean(KEY, false).commit()
        assertFalse(manager.getCelestialUi())
    }

    // Converse case: an explicit ON stays ON regardless of the default.
    @Test
    fun savedOn_keepsCelestialUi() {
        val (manager, store) = newManager()
        store.edit().putBoolean(KEY, true).commit()
        assertTrue(manager.getCelestialUi())
    }

    // Reading the setting must not pin the default into storage: if it did, a fresh
    // install would acquire an implicit saved value, and every later default change
    // would stop applying to it.
    @Test
    fun readingDoesNotPersistTheDefault() {
        val (manager, store) = newManager()
        assertTrue(manager.getCelestialUi())
        assertFalse("getCelestialUi() must not write the key", store.contains(KEY))
    }

    // The Settings toggle must work in both directions from the new default, and each
    // flip must survive (i.e. be read back) — this is what the on-device check for the
    // HUD <-> classic swap actually depends on.
    @Test
    fun toggleWorksBothDirectionsFromTheDefault() {
        val (manager, store) = newManager()
        assertTrue(manager.getCelestialUi())

        manager.saveCelestialUi(false)
        assertFalse("toggling the HUD off must stick", manager.getCelestialUi())
        assertTrue("the explicit OFF must be persisted", store.contains(KEY))

        manager.saveCelestialUi(true)
        assertTrue("toggling the HUD back on must stick", manager.getCelestialUi())

        // And a later manager instance (app restart) still sees the saved value.
        val (reopened, _) = newManager()
        assertTrue(reopened.getCelestialUi())
    }
}
