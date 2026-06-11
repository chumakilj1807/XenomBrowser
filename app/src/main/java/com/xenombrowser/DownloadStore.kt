package com.xenombrowser

import android.content.Context

/** Lightweight record of completed/active downloads. */
object DownloadStore {
    private const val P = "downloads"
    private const val K = "list"
    private const val SEP = "|||"

    data class Item(val name: String, val url: String, val path: String, val time: Long)

    fun add(c: Context, item: Item) {
        val list = getAll(c).toMutableList()
        list.add(0, item)
        save(c, list)
    }

    fun getAll(c: Context): List<Item> {
        val raw = c.getSharedPreferences(P, Context.MODE_PRIVATE).getString(K, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEP).mapNotNull {
            val p = it.split(":::")
            if (p.size >= 4) Item(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0L) else null
        }
    }

    fun clear(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().remove(K).apply()

    private fun save(c: Context, list: List<Item>) {
        val raw = list.joinToString(SEP) { "${it.name}:::${it.url}:::${it.path}:::${it.time}" }
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(K, raw).apply()
    }
}
