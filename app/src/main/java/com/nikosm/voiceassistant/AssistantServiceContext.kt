package com.nikosm.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    } catch (e: Exception) {
        android.util.Log.e("AssistantService", "News fetch failed", e)
        ""
    }
}

internal fun AssistantService.getCurrentDateTimeString(): String {
    return SimpleDateFormat("EEEE, MMMM d, yyyy, HH:mm", Locale.getDefault()).format(Date())
}
