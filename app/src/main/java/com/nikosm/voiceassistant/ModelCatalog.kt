package com.nikosm.voiceassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Step 1 of the per-server models screen: the data shape and the pure parse/merge
 * functions. Everything here is a pure function over a response body, so it is unit
 * testable without a device. The cache that consumes it lives beside today's
 * `fetchModels` (see AssistantServiceChat.kt), not in this file.
 *
 * ## Why `/api/show` is enrichment, not a fallback
 *
 * `/api/tags` gives the list, byte sizes and basic details. `/api/show` is called for
 * EVERY model on a server and treated as the authority, field by field, wherever it
 * actually carries a value, for three measured reasons:
 *
 *  - **It is the only source of context length.** `/api/tags` has no context field. In
 *    the ziptie tags fixture four models (`translategemma:12b`, `gemma4:e4b`,
 *    `translategemma:4b`, `fluffy/magnum-v4-9b:q4_k_m`) carry none, while the
 *    `/api/show` fixture for `translategemma:12b` reports `gemma3.context_length =
 *    131072`.
 *  - **tags under-reports capabilities.** On the 5800x, `/api/tags` lists
 *    `gemma4:latest` as `[completion, tools, thinking]` while `/api/show` for the same
 *    model reports `[completion, vision, audio, tools, thinking]`. In the ziptie
 *    fixture the same pattern: `translategemma:12b` is `[completion]` in tags and
 *    `[completion, vision]` in show.
 *  - **The capability filters need every model answered.** A filter chip that silently
 *    excludes models whose capabilities were never fetched is worse than no filter.
 *
 * The disagreement runs BOTH ways and is not a superset relationship: on the same 5800x,
 * `/api/show` reports `tools`/`thinking` for `qwythos:latest`, `glm4:9b` and
 * `llama3.3:latest`, where `/api/tags` reports `[completion]` alone. Resolving that in
 * show's favour is deliberate and documented here so it is not mistaken for an accident:
 * show is the per-model endpoint, and it is the one that produced the vision/audio values
 * an earlier load log showed.
 *
 * A failed `/api/show` leaves that ONE model on its tags values. It never fails the list.
 *
 * ## "Not reported" is not "reported as none"
 *
 * [ModelEntry.capabilities] is a nullable set and the distinction is load-bearing:
 *  - `null`       -> the server said nothing; render no badges, and a capability filter
 *                    must not match (we do not know).
 *  - `emptySet()` -> the server answered with an empty list. That IS an answer.
 *
 * Collapsing the two would let a filter claim a model lacks vision when nobody said so.
 */

/** A capability Ollama can report for a model. */
internal enum class ModelCapability {
    COMPLETION, VISION, TOOLS, THINKING, EMBEDDING, AUDIO;

    companion object {
        /**
         * Maps one wire token to a capability. Unknown tokens are dropped rather than
         * throwing: a newer server may report a capability this build predates, and that
         * must not cost the user the whole model.
         */
        fun fromWire(value: String): ModelCapability? = when (value.lowercase()) {
            "completion" -> COMPLETION
            "vision" -> VISION
            "tools" -> TOOLS
            "thinking" -> THINKING
            "embedding" -> EMBEDDING
            "audio" -> AUDIO
            else -> null
        }
    }
}

/**
 * One model as this app knows it, from whatever responses have arrived.
 *
 * Every field except [name] is nullable because every one can be absent from a real
 * response: tags has no first-class context length, `/v1/models` has neither
 * capabilities nor size, and a failed `/api/show` contributes nothing at all.
 */
internal data class ModelEntry(
    val name: String,
    /** `null` = not reported. `emptySet()` = reported, and empty. Never conflated. */
    val capabilities: Set<ModelCapability>?,
    /** e.g. "8.0B" / "137M" — a display string from `details.parameter_size`. */
    val parameterSize: String?,
    /** e.g. "Q4_K_M" — `details.quantization_level`. */
    val quantization: String?,
    /** `model_info["<arch>.context_length"]`; in practice only ever from `/api/show`. */
    val contextLength: Long?,
    /** Bytes — `size`, only present in `/api/tags`. */
    val sizeBytes: Long?
) {
    /**
     * True when the server reported this model as embedding-capable and nothing else.
     *
     * Such models are labelled and sorted last in chat pickers, never removed. Returns
     * false for `capabilities == null`: "we were not told" is not "embedding only".
     */
    val isEmbeddingOnly: Boolean
        get() = capabilities != null &&
            ModelCapability.EMBEDDING in capabilities &&
            capabilities.none { it == ModelCapability.COMPLETION }
}

/**
 * Whether a model's `/api/show` enrichment has resolved, so a list can hold badges still
 * pending rather than letting them flip from tags values to show values under the user.
 */
internal enum class ModelShowState { PENDING, RESOLVED, FAILED }

/** Per-model show status, keyed by model name. */
internal typealias ModelShowStates = Map<String, ModelShowState>

/**
 * What one attempt to LIST a server's models actually produced.
 *
 * These are kept apart because they are different events and the user needs to be told
 * which one happened. They used to collapse into one empty list, so a server that was
 * refusing connections and a server that genuinely had nothing installed both reported
 * "server reported no models" — telling someone whose Ollama was down that their server
 * simply had no models on it.
 *
 * - [Listed] the server answered. [Listed.entries] may be EMPTY, and an empty [Listed] is
 *   a real answer, not a failure: 200 with `{"models":[]}` means this server has no
 *   models, so it replaces the list instead of being retried or marked stale.
 * - [HttpError] the server answered, with a non-2xx status. More specific than
 *   [Unreachable] whenever it applies, and it carries the status for display.
 * - [Malformed] a 2xx whose body is not a model list at all: a login portal serving HTML,
 *   a proxy returning half a document, a `{}` with no `models` key. A success status is
 *   not evidence of a good answer, so this must never be read as "this server has no
 *   models" — doing so replaced a good list with an empty one.
 * - [Unreachable] no HTTP response at all: connection refused, DNS failure, timeout.
 */
internal sealed interface ModelListOutcome {

    data class Listed(val entries: List<ModelEntry>) : ModelListOutcome

    data class HttpError(val status: Int) : ModelListOutcome

    data object Malformed : ModelListOutcome

    data object Unreachable : ModelListOutcome
}

/** The wording for [ModelListOutcome.Unreachable], shown in a stale note or a failure. */
internal const val UNREACHABLE_REASON: String = "Couldn't reach this server"

/** The wording for [ModelListOutcome.HttpError]; the status is always part of it. */
internal fun httpErrorReason(status: Int): String = "This server returned an error (HTTP $status)"

/**
 * The wording for [ModelListOutcome.Malformed].
 *
 * Deliberately does NOT say "no models": the server never said that, and the whole point
 * of this case is that a 200 here is not an answer about models.
 */
internal const val UNEXPECTED_RESPONSE_REASON: String = "Unexpected response from this server"

/**
 * One server's catalog state in the service-level cache, keyed by server URL.
 *
 * The states the models screen has to render, and nothing else:
 *  - [NotLoaded]  never fetched.
 *  - [Loading]    a fetch is in flight and there is nothing to show yet.
 *  - [Loaded]     entries, when they were fetched, and whether they are [stale].
 *  - [Failed]     the fetch failed and there is NO previous list to fall back on.
 *
 * ## Stale retention
 *
 * A REFRESH that fails keeps the previous good list, marked [Loaded.stale], rather than
 * replacing it with [Failed]. A server that is briefly unreachable while the user
 * refreshes should not empty the list they were reading; [Failed] is reserved for a
 * first fetch that never succeeded, where there is genuinely nothing to show.
 */
internal sealed interface ModelCatalogState {

    data object NotLoaded : ModelCatalogState

    data object Loading : ModelCatalogState

    /**
     * @param stale true when a later refresh failed and these entries are the last good
     *   ones rather than a fresh result.
     * @param degradedReason set alongside [stale] to say what went wrong, for display.
     */
    data class Loaded(
        val entries: List<ModelEntry>,
        val showStates: ModelShowStates,
        val fetchedAtMillis: Long,
        val stale: Boolean = false,
        val degradedReason: String? = null
    ) : ModelCatalogState {
        /** Entries whose `/api/show` has not answered yet. */
        val pending: List<ModelEntry>
            get() = entries.filter { showStates[it.name] == ModelShowState.PENDING }
    }

    data class Failed(val reason: String, val failedAtMillis: Long) : ModelCatalogState
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Normalised Ollama base: trailing slash and `/v1`, `/api` suffixes removed. */
internal fun ollamaBaseUrl(raw: String): String {
    var b = raw.trim().removeSuffix("/")
    if (b.endsWith("/v1")) b = b.removeSuffix("/v1")
    if (b.endsWith("/api")) b = b.removeSuffix("/api")
    return b
}

/**
 * Parses `GET /api/tags`, or null when this body is not a model list at all.
 *
 * The null is the whole point. [parseTagsModels] answers null and empty list identically,
 * and the catalog fetch needs them apart: an Ollama with nothing installed really does answer
 * `{"models":[]}`, while a login portal answering `200 <html>`, a proxy returning half a
 * document, or a `{}` with no `models` key are all failures wearing a success status. Told
 * apart, the first empties the picker truthfully and the others keep the last good list.
 *
 * So: no parseable JSON object, no `models` ARRAY, or an array of entries none of which can
 * be read, are null. A present-but-empty `models` array is a real, empty answer.
 */
internal fun parseTagsModelsOrNull(body: String): List<ModelEntry>? {
    val root = runCatching { lenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return null
    val models = root["models"] as? JsonArray ?: return null
    val parsed = models.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = (obj["name"] ?: obj["model"])?.jsonPrimitive?.contentOrNull
            ?: return@mapNotNull null
        val details = obj["details"] as? JsonObject
        ModelEntry(
            name = name,
            capabilities = parseCapabilities(obj["capabilities"]),
            parameterSize = details?.string("parameter_size"),
            quantization = details?.string("quantization_level"),
            // `/api/tags` has no first-class context field. A few builds echo one inside
            // `details`; read it if present, since /api/show still wins in the merge and
            // this only helps when show fails.
            contextLength = details?.long("context_length"),
            sizeBytes = obj.long("size")
        )
    }
    // An array that exists but yields nothing readable is not an empty server; it is a list
    // we failed to understand, and treating it as "no models" would delete a good list.
    if (parsed.isEmpty() && models.isNotEmpty()) return null
    return parsed
}

/**
 * Parses `GET /api/tags`.
 *
 * A model with no `capabilities` key yields `capabilities = null`; a model whose key is
 * an empty array yields `emptySet()`. Malformed input yields an empty list rather than
 * throwing — a broken server must not take the screen down. Callers that need to tell
 * "empty server" from "not a model list" must use [parseTagsModelsOrNull].
 */
internal fun parseTagsModels(body: String): List<ModelEntry> =
    parseTagsModelsOrNull(body) ?: emptyList()

/**
 * Parses `GET /v1/models`, or null when this body is not a model list — see
 * [parseTagsModelsOrNull] for why that distinction is needed.
 */
internal fun parseOpenAiModelsOrNull(body: String): List<ModelEntry>? {
    val root = runCatching { lenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return null
    val data = root["data"] as? JsonArray ?: return null
    val parsed = data.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        ModelEntry(
            name = id, capabilities = null, parameterSize = null, quantization = null,
            contextLength = null, sizeBytes = null
        )
    }
    if (parsed.isEmpty() && data.isNotEmpty()) return null
    return parsed
}

/**
 * Parses `GET /v1/models`, the OpenAI-compatible endpoint `fetchModels` already tries
 * second. It carries an id and nothing else, so every optional field stays null — in
 * particular `capabilities` stays null (NOT reported), never empty.
 */
internal fun parseOpenAiModels(body: String): List<ModelEntry> =
    parseOpenAiModelsOrNull(body) ?: emptyList()

/**
 * Parses `POST /api/show` for one model.
 *
 * The response carries no name, so [name] comes from the request. Capabilities are the
 * top-level `capabilities`; context length is the single arch-prefixed
 * `model_info["<arch>.context_length"]`, which cannot be matched positionally.
 */
internal fun parseShowModel(body: String, name: String): ModelEntry? {
    val root = runCatching { lenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return null
    // `{}` is a well-formed body that resolved nothing. Treating it as a successful
    // enrichment would mark the model RESOLVED while every field fell back to tags,
    // which is how "pending" is supposed to be avoided but is really a silent failure.
    // A real /api/show always carries at least one of these.
    if (root.keys.none { it == "capabilities" || it == "details" || it == "model_info" }) {
        return null
    }
    val details = root["details"] as? JsonObject
    return ModelEntry(
        name = name,
        capabilities = parseCapabilities(root["capabilities"]),
        parameterSize = details?.string("parameter_size"),
        quantization = details?.string("quantization_level"),
        contextLength = contextLengthFromModelInfo(root["model_info"]),
        sizeBytes = null // `/api/show` has no size; tags owns it.
    )
}

/**
 * Merges a tags entry with its `/api/show` enrichment.
 *
 * `show` wins FIELD BY FIELD wherever it carries a value; `tags` fills the gaps — most
 * importantly `sizeBytes`, which only tags has, and `contextLength` when show failed or
 * omitted it. A null `show` (the call failed) returns `tags` unchanged, which is what
 * keeps one model's failure from costing the whole list.
 */
internal fun mergeModelEntry(tags: ModelEntry, show: ModelEntry?): ModelEntry =
    if (show == null) tags else tags.copy(
        capabilities = show.capabilities ?: tags.capabilities,
        parameterSize = show.parameterSize ?: tags.parameterSize,
        quantization = show.quantization ?: tags.quantization,
        contextLength = show.contextLength ?: tags.contextLength,
        sizeBytes = show.sizeBytes ?: tags.sizeBytes
    )

/** Human-readable byte size, binary units: 9608350718 -> "9.6 GB". */
internal fun formatModelSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val rounded = if (value >= 100) {
        String.format(java.util.Locale.US, "%.0f", value)
    } else {
        String.format(java.util.Locale.US, "%.1f", value)
    }
    return "$rounded ${units[unit]}"
}

/** Compact context length: 8192 -> "8K", 131072 -> "131K", 1048576 -> "1M". */
internal fun formatContextLength(contextLength: Long): String = when {
    contextLength <= 0 -> ""
    contextLength >= 1_048_576 -> trimUnit(contextLength.toDouble() / 1_048_576.0, "M")
    contextLength >= 1024 -> trimUnit(contextLength.toDouble() / 1024.0, "K")
    else -> contextLength.toString()
}

private fun trimUnit(value: Double, suffix: String): String {
    val rounded = if (value >= 10) {
        String.format(java.util.Locale.US, "%.0f", value)
    } else {
        String.format(java.util.Locale.US, "%.1f", value)
    }
    return if (rounded.endsWith(".0")) rounded.dropLast(2) + suffix else "$rounded$suffix"
}

/**
 * The spec line under a model name: only the fields the server actually reported, so a
 * missing context length never renders as "0" and never leaves a dangling separator.
 */
internal fun modelSpecLine(entry: ModelEntry): String = listOfNotNull(
    entry.parameterSize,
    entry.quantization,
    entry.contextLength
        ?.let(::formatContextLength)
        ?.takeIf(String::isNotBlank)
        ?.let { "$it ctx" },
    entry.sizeBytes?.let(::formatModelSize)
).joinToString(" · ")

/**
 * The badges a models list shows. `COMPLETION` is deliberately absent: every chat model
 * reports it, so a badge for it carries no information. An unreported model gets no
 * badges; a reported-but-empty model is labelled by the caller instead.
 */
internal fun deriveCapabilityBadges(entry: ModelEntry): List<ModelCapability> =
    entry.capabilities.orEmpty().filterNot { it == ModelCapability.COMPLETION }

/**
 * Chat-pick sort: embedding-only models last, then favorites, then name.
 *
 * Embedding-only sorts last but is never removed. Favorites outrank name so the order
 * matches what the persona picker shows.
 */
internal fun compareForChatPicker(
    a: ModelEntry,
    b: ModelEntry,
    favoriteNames: Set<String>
): Int {
    if (a.isEmbeddingOnly != b.isEmbeddingOnly) return if (a.isEmbeddingOnly) 1 else -1
    val aFav = a.name in favoriteNames
    val bFav = b.name in favoriteNames
    if (aFav != bFav) return if (aFav) -1 else 1
    return a.name.compareTo(b.name, ignoreCase = true)
}

private fun parseCapabilities(element: JsonElement?): Set<ModelCapability>? {
    val array = element as? JsonArray ?: return null
    // Distinct: a server listing a capability twice is not reporting two of them.
    return array.mapNotNull { item ->
        item.jsonPrimitive.contentOrNull?.let(ModelCapability::fromWire)
    }.toSet()
}

private fun contextLengthFromModelInfo(element: JsonElement?): Long? {
    val obj = element as? JsonObject ?: return null
    // Arch-prefixed ("gemma3.context_length", "nomic-bert.context_length"), so select by
    // suffix. The `general.*` and `tokenizer.*` blocks never carry one; should a future
    // build add more than one, the first in document order wins deterministically.
    return obj.entries
        .firstOrNull { it.key.endsWith(".context_length") }
        ?.value?.jsonPrimitive?.longOrNull
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

// ---------------------------------------------------------------------------------
// Models screen: the pure logic behind it
// ---------------------------------------------------------------------------------
//
// Kept out of the composable so it can be tested without a device, and so the screen
// itself stays a rendering of a decision rather than making the decision.

/** How the list is ordered. */
internal enum class ModelSort { NAME, SIZE }

/**
 * The full model name, which is what `/api/tags` returned and what every id is keyed by.
 *
 * Kept as its own function so the screen's split can never be mistaken for a new identity:
 * the row displays `displayModelName` + `modelTag`, but the star, the favorites key and the
 * `/api/show` lookup all use this value.
 */
internal fun shortModelName(fullName: String): String = fullName

/**
 * The prominent part of a name: after the last `/`, and with the trailing `:tag` split off.
 *
 * The tag is rendered separately and quieter (see `modelTag`), so it must not also sit in
 * the bold text: "deepseek-coder:6.7b" would otherwise read as "deepseek-coder:6.7b :6.7b".
 */
internal fun displayModelName(fullName: String): String {
    val short = fullName.substringAfterLast('/')
    val tag = modelTag(fullName)
    return if (tag.isEmpty()) short else short.removeSuffix(tag)
}

/** The quieter trailing tag, e.g. ":q4_k_m"; empty when the name carries no tag. */
internal fun modelTag(fullName: String): String {
    val short = shortModelName(fullName)
    val colon = short.lastIndexOf(':')
    // Only a trailing tag: a colon inside a repo path (hf.co/x/y:z) is part of the name.
    return if (colon > 0) short.substring(colon) else ""
}

/**
 * Does [entry] match every capability in [filters]?
 *
 * A model whose capabilities were NOT REPORTED (`capabilities == null`) matches NO
 * capability filter and never claims one. That is the whole point of the nullable set:
 * filtering on "vision" must not read as "this model has no vision", only "we were not
 * told". The same rule covers a reported-but-empty set, which also matches nothing.
 */
internal fun matchesCapabilityFilters(
    entry: ModelEntry,
    filters: Set<ModelCapability>
): Boolean {
    if (filters.isEmpty()) return true
    val reported = entry.capabilities ?: return false
    return filters.all { it in reported }
}

/**
 * Apply the screen's controls: capability filters (AND), the Favorites/Show all toggle,
 * then the sort.
 *
 * @param favoriteNames the full `"[ServerName] model"` strings, since that is the form the
 *   star toggles and the form stored in `favorite_models_v2`. [serverName] is the prefix
 *   being displayed, so favorites for OTHER servers never leak into this list.
 */
internal fun selectModels(
    entries: List<ModelEntry>,
    serverName: String,
    favoriteNames: Set<String>,
    filters: Set<ModelCapability>,
    favoritesOnly: Boolean,
    sort: ModelSort
): List<ModelEntry> {
    val mine = favoriteNamesFor(serverName, favoriteNames)

    return entries
        .filter { matchesCapabilityFilters(it, filters) }
        .filter { !favoritesOnly || it.name in mine }
        .sortedWith(Comparator { a, b ->
            when (sort) {
                // Embedding-only last and favorites first are carried by the shared
                // comparator; SIZE then orders the chat models by their own key, so the
                // "embedding last, favorites first" rule survives either sort.
                ModelSort.NAME -> compareForChatPicker(a, b, mine)
                ModelSort.SIZE -> {
                    val byCategory = compareForChatPicker(a, b, mine)
                    if (byCategory != 0) byCategory
                    else (b.sizeBytes ?: 0L).compareTo(a.sizeBytes ?: 0L)
                }
            }
        })
}

/** Does this server have any favorites at all? Decides the toggle's default. */
internal fun serverHasFavorites(serverName: String, favoriteNames: Set<String>): Boolean =
    favoriteNames.any { it.startsWith("[$serverName] ") }

/**
 * This server's favorites, as the BARE model names the catalog is keyed by.
 *
 * Favorites are stored as the qualified `"[ServerName] model"` string (that is the form
 * `toggleFavoriteModel` takes and the form in `favorite_models_v2`), while catalog entries
 * carry the bare name, so the prefix has to come off before the two can be compared.
 * Splitting it here keeps the models screen and the persona picker from disagreeing about
 * what "favorited" means.
 */
internal fun favoriteNamesFor(serverName: String, favoriteNames: Set<String>): Set<String> {
    val prefix = "[$serverName] "
    return favoriteNames.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
}

/** The `"[ServerName] model"` string a star toggles and a persona stores, for one entry. */
internal fun qualifiedModelName(serverName: String, modelName: String): String =
    "[$serverName] $modelName"

/**
 * The bare model name inside a stored `"[ServerName] model"` string.
 *
 * Returns the input unchanged when it carries no `[Name] ` prefix, so a cloud persona's
 * `"gpt-4o-mini"` or a hand-typed value is never mangled into something else.
 */
internal fun bareModelName(stored: String, serverName: String): String {
    val prefix = "[$serverName] "
    return if (stored.startsWith(prefix)) stored.removePrefix(prefix) else stored
}

/**
 * A stand-in entry for a model the catalog does not contain.
 *
 * Every field is null on purpose. The persona editor must still be able to SHOW and re-select
 * the model a persona already uses when the server has deleted it or cannot be reached, and
 * inventing capabilities, a size or a context length for it would be a claim nobody made.
 * `capabilities = null` renders "capabilities not reported by this server" and matches no
 * capability filter — which is the truth here.
 */
internal fun placeholderModelEntry(name: String): ModelEntry =
    ModelEntry(name, null, null, null, null, null)

/**
 * The persona picker's rows for one connection.
 *
 * Unlike [selectModels] this has no capability filters and no size sort: a picker is a short
 * list the user picks a CHAT model from, and the requirement is that it keeps the same
 * "favorites first, embedding-only last" order as the models screen. So it is
 * `compareForChatPicker` and nothing else.
 *
 * ## The current model is always present
 *
 * [currentModelName] is the persona's stored model, already stripped of its `[Server] `
 * prefix. It is appended when the catalog does not list it — a model deleted on the server,
 * or a server that is unreachable and therefore has no entries at all — and it is never
 * dropped by `favoritesOnly`, because hiding the model a persona is currently using would
 * make the picker unable to show what is actually selected. When the server DOES list it,
 * the real entry is kept and no stand-in is added, so the row keeps its real chips and spec
 * line rather than being blanked out.
 *
 * A null or blank [currentModelName] adds nothing: a brand-new persona has no current model,
 * and the picker must not invent a row for it.
 */
internal fun selectPickerModels(
    entries: List<ModelEntry>,
    serverName: String,
    favoriteNames: Set<String>,
    favoritesOnly: Boolean,
    currentModelName: String?
): List<ModelEntry> {
    val mine = favoriteNamesFor(serverName, favoriteNames)
    val wants = currentModelName?.takeIf { it.isNotBlank() }

    val base = entries.filter { !favoritesOnly || it.name in mine }
    val rows = if (wants != null && base.none { it.name == wants }) {
        // Prefer the catalog's REAL entry over a stand-in. In favorites-only mode the current
        // model is filtered out of `base` precisely because it is not a favorite — but the
        // catalog still knows it, and a blank placeholder would throw away the very chips and
        // spec line the models screen shows for the model this persona is using right now.
        // The stand-in is only for a model the catalog genuinely does not have.
        base + (entries.firstOrNull { it.name == wants } ?: placeholderModelEntry(wants))
    } else {
        base
    }
    return rows.sortedWith { a, b -> compareForChatPicker(a, b, mine) }
}

/**
 * The line shown above a list that is being served from cache rather than the server.
 *
 * Shared by the models screen and the persona picker so the two cannot drift into telling
 * the user different things about the same staleness. Unreachable outranks a reason: if the
 * server cannot be reached, that is what the user needs to know, and the underlying HTTP
 * failure is noise.
 */
internal fun staleModelNote(reason: String?, unreachable: Boolean): String = when {
    unreachable -> "This server looks unreachable. Showing the last models loaded."
    reason != null -> "Showing the last models loaded ($reason)."
    else -> "Showing the last models loaded."
}

/** The unreachable marker, worded exactly as the models screen's header words it. */
internal const val UNREACHABLE_LABEL: String = "Unreachable — reported by this server"

/**
 * The row's spoken label: name, what the server reported, and whether it is a favorite.
 *
 * Reads capabilities through the same "reported by this server" wording the UI uses, and
 * says "capabilities not reported" rather than implying a model lacks them.
 */
internal fun modelContentDescription(
    entry: ModelEntry,
    serverName: String,
    isFavorite: Boolean
): String {
    val caps = entry.capabilities
    val capabilities = when {
        caps == null -> "capabilities not reported by this server"
        caps.isEmpty() -> "no capabilities reported by this server"
        else -> {
            val listed = caps.filterNot { it == ModelCapability.COMPLETION }
                .joinToString(", ") { it.name.lowercase() }
            if (listed.isEmpty()) "text only, as reported by this server" else "$listed, reported by this server"
        }
    }
    val favorite = if (isFavorite) "favorite" else "not a favorite"
    return "${entry.name} on $serverName, $capabilities, $favorite"
}

/** "Updated 14:32", or "Never loaded" before the first successful fetch. */
internal fun modelFreshnessLabel(fetchedAtMillis: Long?): String {
    if (fetchedAtMillis == null || fetchedAtMillis <= 0L) return "Never loaded"
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = fetchedAtMillis
    return String.format(
        java.util.Locale.US,
        "Updated %02d:%02d",
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE)
    )
}

/**
 * Is this server unreachable, per the existing health status?
 *
 * Reads `service.serverStatus[url]` and treats the vaguer "Could not connect to server"
 * (written by `fetchModels`) as unreachable alongside the health check's own wording.
 * Informational only: it labels the screen and never hides, blocks or disables anything.
 */
internal fun isServerUnreachable(status: String?): Boolean {
    if (status == null) return false
    if (status == "Online") return false
    return status.startsWith("failed") || status.contains("Could not connect")
        || status == "Unauthorized"
}

/** The badge label for a capability chip. `COMPLETION` is deliberately not offered. */
internal fun capabilityLabel(capability: ModelCapability): String = when (capability) {
    ModelCapability.COMPLETION -> "Text"
    ModelCapability.VISION -> "Vision"
    ModelCapability.TOOLS -> "Tools"
    ModelCapability.THINKING -> "Thinking"
    ModelCapability.EMBEDDING -> "Embedding"
    ModelCapability.AUDIO -> "Audio"
}

/** The capability filters offered in the UI: everything except the universal COMPLETION. */
internal val FILTERABLE_CAPABILITIES: List<ModelCapability> = listOf(
    ModelCapability.VISION,
    ModelCapability.TOOLS,
    ModelCapability.THINKING,
    ModelCapability.EMBEDDING,
    ModelCapability.AUDIO
)
