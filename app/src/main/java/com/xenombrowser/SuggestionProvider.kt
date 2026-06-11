package com.xenombrowser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Fetches search suggestions from the active engine's suggest endpoint. */
object SuggestionProvider {

    suspend fun fetch(c: Context, query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = Prefs.suggestUrl(c, query) ?: return@withContext emptyList()
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 4000; conn.readTimeout = 4000
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            conn.disconnect()
            parse(text)
        } catch (_: Exception) { emptyList() }
    }

    // Suggest APIs return: ["query", ["s1","s2",...], ...]
    private fun parse(text: String): List<String> {
        return try {
            val arr = JSONArray(text.trim())
            val list = arr.optJSONArray(1)
            if (list != null) {
                (0 until minOf(list.length(), 8)).map { list.getString(it) }
            } else {
                // DuckDuckGo list format: [{"phrase":"..."}]
                (0 until minOf(arr.length(), 8)).mapNotNull {
                    arr.optJSONObject(it)?.optString("phrase")
                }.filter { it.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
    }
}
