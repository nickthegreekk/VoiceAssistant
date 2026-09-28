package com.nikosm.voiceassistant

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Log tag for the suggested-replies diagnostics below (matches the house tag); every
// message is prefixed with "suggestedReplies/…" so the whole path can be filtered with
//   adb logcat | grep suggestedReplies
internal const val SUGGESTED_REPLIES_TAG = "AssistantService"

internal suspend fun AssistantService.fetchWebSearchContext(query: String): String {
    var searxngUrl = settingsManager.getSearxngUrl()
    if (searxngUrl.isNullOrBlank()) return ""
    if (!searxngUrl.startsWith("http")) searxngUrl = "http://$searxngUrl"
    
    // S4: logs the search query (and the private SearXNG URL) — debug only.
    if (BuildConfig.DEBUG) android.util.Log.d("AssistantService", "Performing web search for: $query via $searxngUrl")
    return try {
        withContext(Dispatchers.IO) {
            val url = "${searxngUrl.trimEnd('/')}/search?q=${URLEncoder.encode(query, "UTF-8")}&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.e("AssistantService", "SearXNG search failed: ${response.code}")
                    return@withContext ""
                }
                val json = JSONObject(response.body.string())
                val results = json.optJSONArray("results") ?: return@withContext ""
                if (results.length() == 0) {
                    android.util.Log.d("AssistantService", "Web search returned 0 results")
                    return@withContext ""
                }
                
                val sb = StringBuilder("WEB SEARCH RESULTS:\n")
                for (i in 0 until minOf(5, results.length())) {
                    val r = results.getJSONObject(i)
                    val title = r.optString("title")
                    val content = r.optString("content").ifBlank { r.optString("snippet") }
                    val rUrl = r.optString("url")
                    if (title.isNotBlank() && content.isNotBlank()) {
                        sb.append("Title: $title\nContent: $content\nURL: $rUrl\n\n")
                    }
                }
                sb.append("Use these results to provide an up-to-date and accurate answer. If the results are irrelevant, ignore them.")
                val res = sb.toString()
                android.util.Log.d("AssistantService", "Web search successful, found results")
                res
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("AssistantService", "Web search failed", e)
        ""
    }
}

internal fun AssistantService.isNewsRequest(text: String): Boolean {
    return Regex("\\bnews\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

internal suspend fun AssistantService.fetchNewsContext(): String {
    var searxngUrl = settingsManager.getSearxngUrl()
    if (searxngUrl.isNullOrBlank()) return ""
    if (!searxngUrl.startsWith("http")) searxngUrl = "http://$searxngUrl"

    val location = settingsManager.getUserLocation()
    val query = if (!location.isNullOrBlank()) "$location news" else "local news"

    return try {
        withContext(Dispatchers.IO) {
            val url = "${searxngUrl.trimEnd('/')}/search?q=${URLEncoder.encode(query, "UTF-8")}&categories=news&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext ""
                val json = JSONObject(response.body.string())
                val results = json.optJSONArray("results") ?: return@withContext ""
                if (results.length() == 0) return@withContext ""

                val sb = StringBuilder("TODAY'S NEWS HEADLINES:\n")
                for (i in 0 until minOf(6, results.length())) {
                    val r = results.getJSONObject(i)
                    val title = r.optString("title")
                    val content = r.optString("content").ifBlank { r.optString("snippet") }
                    if (title.isNotBlank()) {
                        sb.append("- $title: $content\n")
                    }
                }
                sb.append("\nRead these headlines to the user as a brief, natural spoken news update — like a friendly radio briefing, not a list. Do not read out URLs.")
                sb.toString()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("AssistantService", "News fetch failed", e)
        ""
    }
}

internal fun AssistantService.getCurrentDateTimeString(): String {
    return SimpleDateFormat("EEEE, MMMM d, yyyy, HH:mm", Locale.getDefault()).format(Date())
}

internal fun AssistantService.isImageGenerationRequest(text: String): Boolean {
    val trimmed = text.trim()
    val triggers = listOf("create an image of", "generate an image of", "draw me", "make an image of")
    return triggers.any { trimmed.startsWith(it, ignoreCase = true) }
}

internal fun AssistantService.extractImageGenerationPrompt(text: String): String {
    val trimmed = text.trim()
    val triggers = listOf("create an image of", "generate an image of", "draw me", "make an image of")
    for (trigger in triggers) {
        if (trimmed.startsWith(trigger, ignoreCase = true)) {
            return trimmed.substring(trigger.length).trim()
        }
    }
    return trimmed
}

internal fun parseRawSuggestedReplies(rawOutput: String): List<String> {
    if (rawOutput.isBlank()) return emptyList()
    val lines = rawOutput.lines()
    val results = mutableListOf<String>()
    for (line in lines) {
        var cleaned = line.trim()
        if (cleaned.isBlank()) continue
        cleaned = cleaned.replace(Regex("^(\\d+[.)]|[-•*])\\s*"), "").trim()
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            if (cleaned.length >= 2) {
                cleaned = cleaned.substring(1, cleaned.length - 1).trim()
            }
        }
        cleaned = cleaned.replace(Regex("^[-•*]\\s*"), "").trim()
        if (cleaned.isNotBlank() && !results.contains(cleaned)) {
            results.add(cleaned)
        }
    }
    if (results.isEmpty() && rawOutput.contains("[")) {
        val jsonClean = rawOutput.substringAfter("[").substringBeforeLast("]")
        return parseRawSuggestedReplies(jsonClean)
    }
    return results
}

internal fun parseSuggestedReplies(rawOutput: String): List<String> {
    return parseRawSuggestedReplies(rawOutput).take(3)
}

internal val assistantVoiceDenylist = listOf(
    "would you like", "do you want", "shall i", "should i", "let me know",
    "i can", "i could", "i'll", "i will", "i'd be happy", "happy to",
    "feel free", "can i help", "is there anything", "what would you like",
    "are you interested"
)

// ---------------------------------------------------------------------------
// Suggested-replies backend routing (AssistantServiceChat.kt:fetchSuggestedReplies).
//
// The normal chat path (sendTextMessageToServer / sendAudioToServer) resolves a
// `[Name] model` persona to a DIRECT Ollama call whenever `Name` matches an
// entry of the Ollama server list (AssistantServiceChat.kt:713-727): it posts to
// that entry's /api/chat and authenticates with the entry's own credentials
// (performDirectOllamaChat, :1409-1419, via effectiveAuthType).
//
// This is the one place suggested replies do the same lookup, so the routing
// can't drift again. Pure in / pure out (no Android surface): pinned by the
// SuggestedRepliesRoutingTest JVM tests.
// ---------------------------------------------------------------------------

/** Which backend a suggested-replies request must use, with its resolved config. */
internal sealed interface SuggestedRepliesRoute {
    /** Direct Ollama: POST `<config.url>/api/chat` with the entry's own auth. */
    data class DirectOllama(val config: ServerConfig) : SuggestedRepliesRoute
    /** Gateway: POST `<config.url>/text-chat` with the resolved entry's auth. */
    data class Gateway(val config: ServerConfig) : SuggestedRepliesRoute
}

/**
 * Mirrors the normal chat path: a `[Name] model` tag that matches an Ollama
 * server entry by name routes DIRECT to that entry (same lookup as
 * sendTextMessageToServer:713-714); otherwise the persona's configured gateway
 * — resolved through the shared [resolveGatewayConfig] (normalization plus
 * name match), never invented — is used.
 *
 * A null result means there is nothing to authenticate with: the caller must
 * fail visibly instead of sending a credential-less request.
 */
internal fun resolveSuggestedRepliesRoute(
    model: String,
    backendUrl: String,
    ollamaServers: List<ServerConfig>,
    gateways: List<ServerConfig>
): SuggestedRepliesRoute? {
    val raw = model.trim()
    val displayServer = if (raw.startsWith("[") && raw.contains("] ")) {
        raw.substring(1, raw.indexOf("]"))
    } else {
        null
    }
    // Same lookup the normal chat path uses: `[Name] model` -> Ollama entry by name.
    displayServer?.let { name ->
        ollamaServers.find { it.name == name }?.let { return SuggestedRepliesRoute.DirectOllama(it) }
    }
    // Same contract as the gateway flows (M1): null -> refuse loudly, never an
    // unauthenticated request.
    return resolveGatewayConfig(gateways, backendUrl, displayServer)
        ?.let { SuggestedRepliesRoute.Gateway(it) }
}

/** Auth header for a resolved server entry (BASIC or API key), or null for NONE. */
internal fun suggestedRepliesAuthHeader(config: ServerConfig): String? {
    return when (config.effectiveAuthType) {
        AuthType.NONE -> null
        AuthType.BASIC -> if (!config.username.isNullOrBlank()) {
            okhttp3.Credentials.basic(config.username, config.password ?: "")
        } else null
        AuthType.API_KEY -> if (!config.apiKey.isNullOrBlank()) {
            "Bearer ${config.apiKey}"
        } else null
    }
}

// The single source of truth for the assistant-voice filter: returns WHY a line
// reads as the assistant speaking, or null when the line is usable. isAssistantVoiced
// (used by parseAndFilterSuggestedReplies and its tests) is expressed in terms of this
// so a logged drop reason can never drift from the filter that actually runs.
internal fun assistantVoiceReason(line: String): String? {
    val trimmed = line.trim().lowercase()
    assistantVoiceDenylist.firstOrNull { trimmed.startsWith(it) }?.let {
        return "opens with assistant phrasing \"$it\""
    }
    val questionShaped = trimmed.endsWith("?") && (
        trimmed.contains("you ") || trimmed.contains(" your ") ||
            trimmed.startsWith("would ") || trimmed.startsWith("do ") ||
            trimmed.startsWith("can ") || trimmed.startsWith("is ") || trimmed.startsWith("are ")
        )
    if (questionShaped) {
        val followUp = listOf("like me", "want me", "shall i", "should i").firstOrNull { trimmed.contains(it) }
        if (followUp != null) return "assistant-voiced question (contains \"$followUp\")"
    }
    return null
}

internal fun isAssistantVoiced(line: String): Boolean = assistantVoiceReason(line) != null

/** One parse+filter pass, keeping everything the suggested-replies diagnostics log needs. */
internal data class SuggestionParseResult(
    val parsed: List<String>,
    val kept: List<String>,
    val dropped: List<Pair<String, String>>,
    val truncated: List<String>
)

internal fun parseAndFilterSuggestedRepliesDetailed(rawOutput: String): SuggestionParseResult {
    val parsed = parseRawSuggestedReplies(rawOutput)
    val kept = mutableListOf<String>()
    val dropped = mutableListOf<Pair<String, String>>()
    for (line in parsed) {
        val reason = assistantVoiceReason(line)
        if (reason != null) dropped += line to reason else if (kept.size < 3) kept += line
    }
    return SuggestionParseResult(
        parsed = parsed,
        kept = kept,
        dropped = dropped,
        // Usable lines the 3-suggestion cap cut off — not a filter rejection.
        truncated = parsed.filter { assistantVoiceReason(it) == null }.drop(3)
    )
}

internal fun parseAndFilterSuggestedReplies(rawOutput: String): List<String> =
    parseAndFilterSuggestedRepliesDetailed(rawOutput).kept

/**
 * Diagnostics for the suggested-replies path (fetchSuggestedReplies): the raw model
 * output, every line the parser produced, which lines the assistant-voice filter
 * dropped and the exact reason, lines cut by the 3-suggestion cap, and what survived.
 * This is the log that tells an exception apart from empty model output apart from an
 * over-rejecting filter.
 */
internal fun logSuggestedRepliesParse(stage: String, raw: String, result: SuggestionParseResult) {
    Log.d(SUGGESTED_REPLIES_TAG, "[$stage] raw model output (${raw.length} chars):\n$raw")
    Log.d(SUGGESTED_REPLIES_TAG, "[$stage] parser produced ${result.parsed.size} line(s): ${result.parsed.map { it.take(90) }}")
    if (result.dropped.isEmpty()) {
        Log.d(SUGGESTED_REPLIES_TAG, "[$stage] filter dropped 0 lines")
    } else {
        result.dropped.forEach { (line, reason) ->
            Log.d(SUGGESTED_REPLIES_TAG, "[$stage] filter DROPPED \"${line.take(90)}\" — $reason")
        }
    }
    result.truncated.forEach {
        Log.d(SUGGESTED_REPLIES_TAG, "[$stage] cut by the 3-suggestion cap: \"${it.take(90)}\"")
    }
    Log.d(SUGGESTED_REPLIES_TAG, "[$stage] KEPT ${result.kept.size}: ${result.kept}")
}
