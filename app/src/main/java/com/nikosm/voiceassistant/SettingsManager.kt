package com.nikosm.voiceassistant

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefsWithRecovery(context)

    private fun createEncryptedPrefsWithRecovery(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: Throwable) {
            Log.e(
                "SettingsManager",
                "Encrypted prefs initialization failed — attempting recovery by clearing "
                    + "corrupted Keystore entry and preferences. Previous settings will be lost.",
                e
            )
            // Delete the corrupted AndroidKeyStore entry so a fresh key can be generated.
            // The alias is defined in androidx.security.crypto.MasterKey as
            // DEFAULT_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                val targetAlias = "_androidx_security_master_key_"
                if (keyStore.containsAlias(targetAlias)) {
                    keyStore.deleteEntry(targetAlias)
                    Log.w("SettingsManager", "Deleted corrupted Keystore entry: $targetAlias")
                }
            } catch (keystoreEx: Throwable) {
                Log.e("SettingsManager", "Keystore cleanup failed", keystoreEx)
            }
            // Also delete the (now-undecryptable) preferences file
            context.deleteSharedPreferences("voice_assistant_secure_prefs")
            // Retry — should now succeed with a genuinely fresh key.
            // If it STILL fails (known Android Keystore2 bug on Pixel/Android 13 — see
            // e.g. expo-secure-store issue #22804), fall back to unencrypted storage
            // rather than crashing. The app stays functional; credentials/settings
            // will not be encrypted at rest on this device.
            try {
                buildEncryptedPrefs(context)
            } catch (retryException: Throwable) {
                Log.w(
                    "SettingsManager",
                    "Encrypted storage unavailable on this device after retry "
                        + "(known Android Keystore bug), falling back to unencrypted storage. "
                        + "Credentials/settings will not be encrypted at rest.",
                    retryException
                )
                context.getSharedPreferences(
                    "voice_assistant_secure_prefs_fallback",
                    Context.MODE_PRIVATE
                )
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "voice_assistant_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun saveServerBases(bases: List<ServerConfig>) {
        prefs.edit().putString("server_bases_v2", json.encodeToString(bases)).apply()
    }

    fun getServerBases(): List<ServerConfig>? {
        val data = prefs.getString("server_bases_v2", null)
        if (data != null) return json.decodeFromString(data)

        // Legacy Migration
        val oldJson = prefs.getString("server_bases", null) ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i -> ServerConfig("Server ${i + 1}", array.getString(i)) }
        } catch (e: Exception) { null }
    }

    fun saveOllamaBases(bases: List<ServerConfig>) {
        prefs.edit().putString("ollama_bases_v2", json.encodeToString(bases)).apply()
    }

    fun getOllamaBases(): List<ServerConfig>? {
        val data = prefs.getString("ollama_bases_v2", null)
        if (data != null) return json.decodeFromString(data)

        // Legacy Migration
        val oldJson = prefs.getString("ollama_bases", null) ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i -> ServerConfig("Ollama ${i + 1}", array.getString(i)) }
        } catch (e: Exception) { null }
    }

    fun saveCloudApis(apis: List<CloudApiSetting>) {
        prefs.edit().putString("cloud_apis_v2", json.encodeToString(apis)).apply()
    }

    fun getCloudApis(): List<CloudApiSetting>? {
        val data = prefs.getString("cloud_apis_v2", null)
        if (data != null) return json.decodeFromString(data)

        // Legacy Migration
        val oldJson = prefs.getString("cloud_apis", null) ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                CloudApiSetting(
                    name = obj.getString("name"),
                    baseUrl = obj.getString("baseUrl"),
                    apiKey = obj.getString("apiKey"),
                    icon = obj.getString("icon"),
                    color = androidx.compose.ui.graphics.Color(obj.getInt("color")),
                    isEditableUrl = obj.optBoolean("isEditableUrl", false)
                )
            }
        } catch (e: Exception) { null }
    }

    fun saveCustomCloudApis(apis: List<CloudApiSetting>) {
        prefs.edit().putString("custom_cloud_apis_v1", json.encodeToString(apis)).apply()
    }

    fun getCustomCloudApis(): List<CloudApiSetting>? {
        val data = prefs.getString("custom_cloud_apis_v1", null)
        if (data != null) return json.decodeFromString(data)
        return null
    }

    fun savePersonas(personas: List<Persona>) {
        prefs.edit().putString("personas_v2", json.encodeToString(personas)).apply()
    }

    fun getPersonas(): List<Persona>? {
        val data = prefs.getString("personas_v2", null)
        if (data != null) return json.decodeFromString(data)

        // Legacy Migration
        val oldJson = prefs.getString("personas", null) ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                Persona(
                    name = obj.getString("name"),
                    themeColor = androidx.compose.ui.graphics.Color(obj.getInt("themeColor")),
                    model = obj.getString("model"),
                    systemPrompt = obj.getString("systemPrompt"),
                    isCloud = obj.getBoolean("isCloud"),
                    providerIcon = obj.getString("providerIcon"),
                    providerColor = androidx.compose.ui.graphics.Color(obj.getInt("providerColor")),
                    isTranslator = obj.getBoolean("isTranslator"),
                    targetLanguage = obj.optString("targetLanguage", "English"),
                    backendUrl = obj.optString("backendUrl", "")
                )
            }
        } catch (e: Exception) { null }
    }

    fun saveMessages(messages: List<ChatMessage>) {
        prefs.edit().putString("chat_history_v2", json.encodeToString(messages)).apply()
    }

    fun getMessages(): List<ChatMessage>? {
        val data = prefs.getString("chat_history_v2", null)
        if (data != null) return json.decodeFromString(data)

        // Legacy Migration
        val oldJson = prefs.getString("chat_history", null) ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                ChatMessage(role = obj.getString("role"), text = obj.getString("text"))
            }
        } catch (e: Exception) { null }
    }

    fun savePersonaMessages(personaName: String, messages: List<ChatMessage>) {
        prefs.edit().putString("persona_messages_$personaName", json.encodeToString(messages)).apply()
    }

    fun getPersonaMessages(personaName: String): List<ChatMessage>? {
        val data = prefs.getString("persona_messages_$personaName", null)
        return if (data != null) json.decodeFromString(data) else null
    }

    fun exportBackup(): String {
        val personas = getPersonas() ?: emptyList()
        val personaMessages = personas.associate { it.name to (getPersonaMessages(it.name) ?: emptyList()) }
        
        val backup = BackupData(
            serverBases = getServerBases() ?: emptyList(),
            ollamaBases = getOllamaBases() ?: emptyList(),
            cloudApis = getCloudApis() ?: emptyList(),
            customCloudApis = getCustomCloudApis() ?: emptyList(),
            personas = personas,
            messages = getMessages() ?: emptyList(),
            personaMessages = personaMessages,
            totalCost = getTotalCost(),
            favoriteModels = getFavoriteModels() ?: emptyList(),
            trustedCerts = getTrustedCertificates(),
            searxngUrl = getSearxngUrl(),
            userLocation = getUserLocation(),
            ragServerUrl = getRagServerUrl(),
            ragUsername = getRagUsername(),
            ragPassword = getRagPassword()
        )
        return json.encodeToString(backup)
    }

    fun importBackup(jsonString: String): Boolean {
        return try {
            val backup = json.decodeFromString<BackupData>(jsonString)
            saveServerBases(backup.serverBases)
            saveOllamaBases(backup.ollamaBases)
            saveCloudApis(backup.cloudApis)
            saveCustomCloudApis(backup.customCloudApis)
            savePersonas(backup.personas)
            saveMessages(backup.messages)
            backup.personaMessages.forEach { (name, msgs) ->
                savePersonaMessages(name, msgs)
            }
            saveTotalCost(backup.totalCost)
            saveFavoriteModels(backup.favoriteModels)
            saveTrustedCertificates(backup.trustedCerts)
            backup.searxngUrl?.let { saveSearxngUrl(it) }
            backup.userLocation?.let { saveUserLocation(it) }
            backup.ragServerUrl?.let { saveRagServerUrl(it) }
            backup.ragUsername?.let { saveRagUsername(it) }
            backup.ragPassword?.let { saveRagPassword(it) }
            true
        } catch (e: Exception) {
            // Try legacy import if new format fails
            importLegacyBackup(jsonString)
        }
    }

    private fun importLegacyBackup(jsonString: String): Boolean {
        return try {
            val backup = JSONObject(jsonString)
            if (backup.has("server_bases")) {
                val array = backup.getJSONArray("server_bases")
                val bases = mutableListOf<ServerConfig>()
                for (i in 0 until array.length()) {
                    val item = array.get(i)
                    if (item is String) bases.add(ServerConfig("Server ${i + 1}", item))
                    else if (item is JSONObject) bases.add(ServerConfig(item.optString("name", "Server $i"), item.getString("url")))
                }
                saveServerBases(bases)
            }
            // ... (rest of legacy import if needed, but above handles the main part)
            true
        } catch (e: Exception) { false }
    }

    fun saveTotalCost(cost: Double) {
        prefs.edit().putLong("total_cost_bits", java.lang.Double.doubleToRawLongBits(cost)).apply()
    }

    fun getTotalCost(): Double {
        val bits = prefs.getLong("total_cost_bits", 0L)
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun saveFavoriteModels(models: List<String>) {
        prefs.edit().putString("favorite_models_v2", json.encodeToString(models)).apply()
    }

    fun getFavoriteModels(): List<String>? {
        val data = prefs.getString("favorite_models_v2", null)
        if (data != null) return json.decodeFromString(data)
        
        val oldJson = prefs.getString("favorite_models", null) ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) { null }
    }

    fun saveTrustedCertificates(certs: Map<String, String>) {
        prefs.edit().putString("trusted_certs_v2", json.encodeToString(certs)).apply()
    }

    fun getTrustedCertificates(): Map<String, String> {
        val data = prefs.getString("trusted_certs_v2", null)
        if (data != null) return json.decodeFromString(data)
        
        val oldJson = prefs.getString("trusted_certs", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(oldJson)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { host -> map[host] = obj.getString(host) }
            map
        } catch (e: Exception) { emptyMap() }
    }

    fun isFirstRun(): Boolean {
        return prefs.getBoolean("is_first_run", true)
    }

    fun setFirstRunComplete() {
        prefs.edit().putBoolean("is_first_run", false).apply()
    }

    fun saveSearxngUrl(url: String) {
        prefs.edit().putString("searxng_url", url).apply()
    }

    fun getSearxngUrl(): String? {
        return prefs.getString("searxng_url", null)
    }

    fun saveUserLocation(location: String) {
        prefs.edit().putString("user_location", location).apply()
    }

    fun getUserLocation(): String? {
        return prefs.getString("user_location", null)
    }

    fun saveRagServerUrl(url: String) {
        prefs.edit().putString("rag_server_url", url).apply()
    }

    fun getRagServerUrl(): String? {
        return prefs.getString("rag_server_url", null)
    }

    fun saveRagUsername(username: String) {
        prefs.edit().putString("rag_username", username).apply()
    }

    fun getRagUsername(): String? {
        return prefs.getString("rag_username", null)
    }

    fun saveRagPassword(password: String) {
        prefs.edit().putString("rag_password", password).apply()
    }

    fun getRagPassword(): String? {
        return prefs.getString("rag_password", null)
    }

    fun saveModelPricing(pricing: Map<String, ModelPricing>) {
        prefs.edit().putString("model_pricing_v1", json.encodeToString(pricing)).apply()
    }

    fun getModelPricing(): Map<String, ModelPricing> {
        val data = prefs.getString("model_pricing_v1", null) ?: return emptyMap()
        return try { json.decodeFromString(data) } catch (e: Exception) { emptyMap() }
    }

    fun saveLastPriceSyncTimestamp(timestamp: Long) {
        prefs.edit().putLong("last_price_sync_ts", timestamp).apply()
    }

    fun getLastPriceSyncTimestamp(): Long {
        return prefs.getLong("last_price_sync_ts", 0L)
    }
}

@kotlinx.serialization.Serializable
data class BackupData(
    val serverBases: List<ServerConfig>,
    val ollamaBases: List<ServerConfig>,
    val cloudApis: List<CloudApiSetting>,
    val customCloudApis: List<CloudApiSetting> = emptyList(),
    val personas: List<Persona>,
    val messages: List<ChatMessage>,
    val personaMessages: Map<String, List<ChatMessage>> = emptyMap(),
    val totalCost: Double,
    val favoriteModels: List<String>,
    val trustedCerts: Map<String, String>,
    val searxngUrl: String? = null,
    val userLocation: String? = null,
    val ragServerUrl: String? = null,
    val ragUsername: String? = null,
    val ragPassword: String? = null
)
