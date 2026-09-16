package com.nikosm.voiceassistant

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore

class SettingsManager(context: Context) {

    // Retained so artwork import/restore can resolve filesDir without going through
    // the Activity. The application context is used deliberately: it outlives any
    // Activity, so a rotating/recreated UI can never leak here.
    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences = createEncryptedPrefsWithRecovery(context)

    private companion object {
        const val MAX_PERSONA_MESSAGES = 200

        // Persona artwork is COPIED into app-owned storage rather than persisted as
        // the raw photo-picker URI. A picked content:// URI is only readable while its
        // grant lasts, and that grant dies with the process; the user moving or
        // deleting the original photo would break it too. An app-owned copy survives
        // both, and needs no persistent URI-permission request at pick time.
        const val PERSONA_IMAGE_DIR = "persona_images"

        // Longest edge, in px, each slot is downsampled to before being written.
        // An icon is only ever drawn at <=40dp (ProviderLogo), so 512px stays crisp
        // on any density; a backdrop is drawn at screen size, so 1080px covers
        // 1080p-class panels. Keeping these bounded also keeps the backup's base64
        // payload (a 4/3 size inflation) from ballooning.
        const val ICON_MAX_DIM = 512
        const val BACKGROUND_MAX_DIM = 1080
    }

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
        if (data != null) {
            return runCatching { json.decodeFromString<List<ServerConfig>>(data) }
                .onFailure { e ->
                    Log.w(
                        "SettingsManager",
                        "Corrupted/unreadable JSON in server_bases_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

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
        if (data != null) {
            return runCatching { json.decodeFromString<List<ServerConfig>>(data) }
                .onFailure { e ->
                    Log.w(
                        "SettingsManager",
                        "Corrupted/unreadable JSON in ollama_bases_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

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
        if (data != null) {
            return runCatching { json.decodeFromString<List<CloudApiSetting>>(data) }
                .onFailure { e ->
                    Log.w(
                        "SettingsManager",
                        "Corrupted/unreadable JSON in cloud_apis_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

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
        if (data != null) {
            return runCatching { json.decodeFromString<List<CloudApiSetting>>(data) }
                .onFailure { e ->
                    Log.w(
                        "SettingsManager",
                        "Corrupted/unreadable JSON in custom_cloud_apis_v1 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }
        return null
    }

    fun savePersonas(personas: List<Persona>) {
        prefs.edit().putString("personas_v2", json.encodeToString(personas)).apply()
    }

    fun getPersonas(): List<Persona>? {
        val data = prefs.getString("personas_v2", null)
        if (data != null) {
            return runCatching { json.decodeFromString<List<Persona>>(data) }
                .onFailure { e ->
                    Log.w(
                        "SettingsManager",
                        "Corrupted/unreadable JSON in personas_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

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
        if (data != null) {
            return runCatching { json.decodeFromString<List<ChatMessage>>(data) }
                .onFailure { e ->
                    Log.w(
                        "SettingsManager",
                        "Corrupted/unreadable JSON in chat_history_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

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
        // C3: cap persisted history at 200 messages per persona. Trim from the oldest
        // end (keep most recent 200). Before discarding any evicted message, delete its
        // audio cache file if present — otherwise WAV files become orphaned on disk.
        val capped = if (messages.size > MAX_PERSONA_MESSAGES) {
            val evicted = messages.subList(0, messages.size - MAX_PERSONA_MESSAGES)
            evicted.forEach { msg ->
                msg.audioFilePath?.let { path ->
                    try { File(path).delete() } catch (_: Exception) { /* already gone */ }
                }
            }
            messages.subList(messages.size - MAX_PERSONA_MESSAGES, messages.size)
        } else messages
        prefs.edit().putString("persona_messages_$personaName", json.encodeToString(capped)).apply()
    }

    fun getPersonaMessages(personaName: String): List<ChatMessage>? {
        val data = prefs.getString("persona_messages_$personaName", null)
        return if (data != null) {
            runCatching { json.decodeFromString<List<ChatMessage>>(data) }
                .onFailure { e ->
                    Log.w(
                        "SettingsManager",
                        "Corrupted/unreadable JSON in persona history for '$personaName' — "
                            + "ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        } else null
    }

    // Rename support: history is persisted keyed by persona NAME, so a rename must
    // carry the old name-keyed entry over to the new one — otherwise the old entry
    // is orphaned on disk forever and the renamed persona starts empty. No-op when
    // the names match or the old persona never had saved history. (If the new name
    // collides with an existing persona's history, the renamed persona claims it —
    // persona identity is name-based throughout the app.)
    fun migratePersonaHistory(oldName: String, newName: String) {
        if (oldName == newName) return
        val data = prefs.getString("persona_messages_$oldName", null) ?: return
        prefs.edit().putString("persona_messages_$newName", data)
            .remove("persona_messages_$oldName").apply()
    }

    // ---------------------------------------------------------------------------
    // Persona artwork (custom icon + background image)
    //
    // Stored as app-owned JPEG copies under filesDir/persona_images and referenced
    // from Persona.iconImageUri / Persona.backgroundImageUri by absolute internal
    // path. See Models.kt for why the raw picker URI is never persisted.
    // ---------------------------------------------------------------------------

    private fun artworkDir(): File = File(appContext.filesDir, PERSONA_IMAGE_DIR)

    // Timestamp + random suffix so two picks in the same millisecond (and any file
    // left over from a previous install) can never collide.
    private fun newArtworkFile(slot: String): File {
        val dir = artworkDir().apply { mkdirs() }
        val unique = java.util.UUID.randomUUID().toString().take(8)
        return File(dir, "${slot}_${System.currentTimeMillis()}_$unique.jpg")
    }

    // Two-pass decode. The first pass reads only the image header to learn the
    // original dimensions, so inSampleSize can be chosen without ever decoding the
    // full-resolution bitmap: a 12MP phone photo would otherwise need ~48MB just for
    // its ARGB buffer, which is the classic picker-triggered OOM.
    private fun decodeDownsampled(source: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            appContext.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to read persona image bounds", e)
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Smallest power-of-two subsample that still keeps BOTH edges at or above
        // maxDim, so a landscape source isn't over-shrunk on its short edge.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }

        val decoded = try {
            appContext.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to decode persona image", e)
            null
        } ?: return null

        // inSampleSize only does powers of two, so the result can still overshoot
        // maxDim (a 3000px source sampled by 2 is still 1500px). Scale down exactly,
        // preserving aspect ratio — never upscale, since that would only add bytes.
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= maxDim) return decoded
        val scale = maxDim.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    // Copies a user-picked image into app-owned storage, downsampled per slot.
    // Returns the internal path to persist on the Persona, or null when the pick
    // could not be read/decoded — the caller keeps the previous artwork then.
    fun savePersonaImage(source: Uri, slot: String): String? {
        val maxDim = if (slot == "icon") ICON_MAX_DIM else BACKGROUND_MAX_DIM
        return try {
            val bitmap = decodeDownsampled(source, maxDim) ?: return null
            val file = newArtworkFile(slot)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            bitmap.recycle()
            file.absolutePath
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to save persona image", e)
            null
        }
    }

    // Deletes one app-owned artwork file. This is the single choke point every
    // artwork deletion goes through: persona removal, a save that replaced or cleared
    // a slot, and the backup-import orphan sweep. Missing files are ignored — a
    // half-deleted state is not an error.
    fun deleteArtworkFile(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (_: Exception) { /* already gone */ }
    }

    // Deletes both files a persona's artwork fields point at. Used by the deletion
    // path and by the backup-import orphan sweep, which has the stale Persona objects
    // in hand even though they are no longer in the stored list.
    fun deletePersonaArtworkFiles(persona: Persona) {
        deleteArtworkFile(persona.iconImageUri)
        deleteArtworkFile(persona.backgroundImageUri)
    }

    // Backup export: read the file and base64 its bytes. Null (rather than throwing)
    // when the file is missing — a persona can reference artwork whose file is
    // already gone, and a backup must still succeed.
    private fun readArtworkAsBase64(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val file = File(path)
            if (!file.exists()) null
            else Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to read persona image for backup", e)
            null
        }
    }

    // Backup restore: write the base64 payload back out as a fresh app-owned file and
    // return its internal path. The path is always regenerated here — the exported
    // paths point into the exporting device's filesDir and mean nothing elsewhere.
    private fun writeArtworkFromBase64(data: String, slot: String): String? {
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            val file = newArtworkFile(slot)
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to restore persona image", e)
            null
        }
    }

    // Removal support: a genuine persona deletion must also drop its name-keyed
    // history entry — otherwise it stays on disk forever, orphaned (nothing can ever
    // display it again, and it leaks forever). Rename is the only other history-entry
    // lifecycle event and it deliberately MIGRATES (migratePersonaHistory above)
    // rather than deletes, so this is never reached on a rename. Before removing the
    // entry, delete the audio cache files its messages reference — same as eviction
    // in savePersonaMessages — otherwise those WAVs would be orphaned in the entry's
    // place. A corrupted (unparseable) entry is still removed; its audio files are
    // unrecoverable anyway.
    fun deletePersonaHistory(personaName: String) {
        getPersonaMessages(personaName)?.forEach { msg ->
            msg.audioFilePath?.let { path ->
                try { File(path).delete() } catch (_: Exception) { /* already gone */ }
            }
        }
        prefs.edit().remove("persona_messages_$personaName").apply()
    }

    fun exportBackup(): String {
        val personas = getPersonas() ?: emptyList()
        val personaMessages = personas.associate { it.name to (getPersonaMessages(it.name) ?: emptyList()) }

        // Persona artwork travels as base64 BYTES, not as the paths stored on the
        // Persona: those paths point into this device's filesDir and are meaningless
        // after a restore on another device (or after a reinstall). Keyed by persona
        // name, matching the app's name-based persona identity everywhere else.
        // Personas with no artwork are omitted from the map entirely, so a backup of an
        // un-customised install carries an empty (and tiny) personaImages object. Two
        // Json settings matter here: encodeDefaults=true always emits the key, and
        // ignoreUnknownKeys=true lets an older build import a backup carrying it — just
        // without the artwork.
        val personaImages = personas.mapNotNull { persona ->
            val icon = readArtworkAsBase64(persona.iconImageUri)
            val background = readArtworkAsBase64(persona.backgroundImageUri)
            if (icon == null && background == null) null
            else persona.name to PersonaArtwork(icon = icon, background = background)
        }.toMap()

        val backup = BackupData(
            serverBases = getServerBases() ?: emptyList(),
            ollamaBases = getOllamaBases() ?: emptyList(),
            cloudApis = getCloudApis() ?: emptyList(),
            customCloudApis = getCustomCloudApis() ?: emptyList(),
            personas = personas,
            personaImages = personaImages,
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
            // Snapshot the pre-restore personas BEFORE the swap below: their artwork
            // files are what the post-restore sweep has to reconcile against, and once
            // savePersonas replaces the list those objects are unreachable.
            val previousPersonas = getPersonas() ?: emptyList()
            // Artwork restore: decode each base64 payload back into filesDir and rewrite
            // the persona's stored path to the freshly written file. A persona absent
            // from the map is left exactly as the backup serialized it — that covers
            // legacy backups (no artwork field at all), whose paths are device-local
            // but still valid for a same-device restore, and it degrades gracefully
            // elsewhere because a missing file just falls back to the letter logo.
            val restoredPersonas = backup.personas.map { persona ->
                val artwork = backup.personaImages[persona.name] ?: return@map persona
                persona.copy(
                    iconImageUri = artwork.icon?.let { writeArtworkFromBase64(it, "icon") },
                    backgroundImageUri = artwork.background?.let { writeArtworkFromBase64(it, "background") }
                )
            }
            saveServerBases(backup.serverBases)
            saveOllamaBases(backup.ollamaBases)
            saveCloudApis(backup.cloudApis)
            saveCustomCloudApis(backup.customCloudApis)
            savePersonas(restoredPersonas)
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
            // M2: the restore overwrites the persona LIST, but persona_messages_<name>
            // entries belonging to personas that existed before the restore and aren't
            // in this backup would survive as orphans — invisible forever and
            // accumulating across restores (the import-path sibling of the deletion-path
            // orphan deletePersonaHistory removes). Enumerate every stored history entry
            // via prefs.all (a decrypted snapshot; the filter materializes it before any
            // mutation, so removal during iteration is safe) and drop the ones whose
            // persona isn't in the restored list — reusing deletePersonaHistory so any
            // referenced audio cache files are cleaned up too.
            val restoredNames = restoredPersonas.map { it.name }.toSet()
            // Orphaned artwork sweep — the image-file sibling of the history cleanup
            // below. Any file a PRE-restore persona pointed at that no restored persona
            // points at is now unreachable forever: either the restore dropped that
            // persona, or it replaced it with a freshly written file above. Computed
            // from the live set (rather than deleted eagerly during the swap) so
            // artwork survives whenever the same path is legitimately still in use.
            val liveArtwork = restoredPersonas
                .flatMap { listOfNotNull(it.iconImageUri, it.backgroundImageUri) }
                .toSet()
            previousPersonas.forEach { persona ->
                listOfNotNull(persona.iconImageUri, persona.backgroundImageUri)
                    .filter { it !in liveArtwork }
                    .forEach { path ->
                        try { File(path).delete() } catch (_: Exception) { /* already gone */ }
                    }
            }
            prefs.all.keys
                .filter { it.startsWith("persona_messages_") }
                .map { it.removePrefix("persona_messages_") }
                .filter { it !in restoredNames }
                .forEach { deletePersonaHistory(it) }
            true
        } catch (e: Exception) {
            // Try legacy import if new format fails
            importLegacyBackup(jsonString)
        }
    }

    private fun importLegacyBackup(jsonString: String): Boolean {
        // Fix #14: report whether anything was ACTUALLY imported. This used to return
        // true for any valid JSON object (and even for real legacy backups it only
        // restores server_bases), so the UI showed "Import successful" for no-op and
        // partial imports alike. Legacy keys beyond server_bases have no known schema —
        // they are skipped rather than guessed at.
        return try {
            val backup = JSONObject(jsonString)
            var imported = false
            if (backup.has("server_bases")) {
                val array = backup.getJSONArray("server_bases")
                val bases = mutableListOf<ServerConfig>()
                for (i in 0 until array.length()) {
                    val item = array.get(i)
                    if (item is String) bases.add(ServerConfig("Server ${i + 1}", item))
                    else if (item is JSONObject) bases.add(ServerConfig(item.optString("name", "Server $i"), item.getString("url")))
                }
                // An explicitly empty list restores nothing — don't clobber the current
                // servers with it and don't claim success either.
                if (bases.isNotEmpty()) {
                    saveServerBases(bases)
                    imported = true
                }
            }
            imported
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
        if (data != null) {
            return runCatching { json.decodeFromString<List<String>>(data) }
                .onFailure { e ->
                    Log.w(
                        "SettingsManager",
                        "Corrupted/unreadable JSON in favorite_models_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }
        
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
        if (data != null) {
            return runCatching { json.decodeFromString<Map<String, String>>(data) }
                .onFailure { e ->
                    Log.w(
                        "SettingsManager",
                        "Corrupted/unreadable JSON in trusted_certs_v2 — ignoring entry, returning empty map",
                        e
                    )
                }
                .getOrElse { emptyMap() }
        }
        
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

    // Celestial UI (the HUD voice screen): persisted like is_first_run — a plain
    // boolean in the same prefs file. Default ON, because the HUD is now the layout a
    // fresh install should open in. getBoolean only falls back to this default when the
    // key is absent, so an explicitly saved value always wins: a device whose user
    // turned the HUD off (or on) keeps the classic layout (or the HUD) exactly as saved.
    fun getCelestialUi(): Boolean {
        return prefs.getBoolean("celestial_ui", true)
    }

    fun saveCelestialUi(enabled: Boolean) {
        prefs.edit().putBoolean("celestial_ui", enabled).apply()
    }

    // Adaptive Theme (Material You dynamic color): same precedent as celestial_ui —
    // a plain boolean in the same prefs file, default OFF so the existing static
    // green/amber theme is byte-identical until the user opts in. The wallpaper-
    // derived scheme itself is resolved in VoiceAssistantTheme.baseColorScheme(),
    // which also handles the API < 31 fallback (see isDynamicColorSupported).
    fun getAdaptiveTheme(): Boolean {
        return prefs.getBoolean("adaptive_theme", false)
    }

    fun saveAdaptiveTheme(enabled: Boolean) {
        prefs.edit().putBoolean("adaptive_theme", enabled).apply()
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
        return runCatching { json.decodeFromString<Map<String, ModelPricing>>(data) }
            .onFailure { e ->
                Log.w(
                    "SettingsManager",
                    "Corrupted/unreadable JSON in model_pricing_v1 — ignoring entry, returning empty map",
                    e
                )
            }
            .getOrElse { emptyMap() }
    }

    fun saveLastPriceSyncTimestamp(timestamp: Long) {
        prefs.edit().putLong("last_price_sync_ts", timestamp).apply()
    }

    fun getLastPriceSyncTimestamp(): Long {
        return prefs.getLong("last_price_sync_ts", 0L)
    }

    // ---- App-update check (Fix: update banner) — plain prefs, no JSON, no guards needed

    fun saveLastUpdateCheckTimestamp(timestamp: Long) {
        prefs.edit().putLong("last_update_check_ts", timestamp).apply()
    }

    fun getLastUpdateCheckTimestamp(): Long {
        return prefs.getLong("last_update_check_ts", 0L)
    }

    fun saveDismissedUpdateVersion(version: String) {
        prefs.edit().putString("dismissed_update_version", version).apply()
    }

    fun getDismissedUpdateVersion(): String? {
        return prefs.getString("dismissed_update_version", null)
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
    val ragPassword: String? = null,
    // Persona artwork, keyed by persona name. Base64-encoded JPEG bytes rather than
    // the on-device file paths carried in `personas`, which are meaningless off-device.
    // Defaulted, so backups written before artwork existed still decode: the Json
    // instance sets ignoreUnknownKeys/coerceInputValues, so an absent key (or a null
    // slot inside an entry) falls back to empty rather than failing the import.
    val personaImages: Map<String, PersonaArtwork> = emptyMap()
)

// One persona's embedded artwork. Both slots are optional: a persona may have only an
// icon, only a background, or neither — in which case it is absent from the map above.
@kotlinx.serialization.Serializable
data class PersonaArtwork(
    val icon: String? = null,        // base64 JPEG, written to an "icon" file on restore
    val background: String? = null   // base64 JPEG, written to a "background" file
)
