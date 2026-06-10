package com.xenombrowser

import android.content.Context

object HistoryManager {

    private const val PREFS = "history"
    private const val KEY = "list"
    private const val SEP = "|||"
    private const val MAX = 100

    data class HistoryEntry(val title: String, val url: String)

    fun add(ctx: Context, title: String, url: String) {
        val list = getAll(ctx).toMutableList()
        list.removeAll { it.url == url }
        list.add(0, HistoryEntry(title, url))
        if (list.size > MAX) list.subList(MAX, list.size).clear()
        val raw = list.joinToString(SEP) { "${it.title}:::${it.url}" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw).apply()
    }

    fun getAll(ctx: Context): List<HistoryEntry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEP).mapNotNull {
            val parts = it.split(":::", limit = 2)
            if (parts.size == 2) HistoryEntry(parts[0], parts[1]) else null
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
