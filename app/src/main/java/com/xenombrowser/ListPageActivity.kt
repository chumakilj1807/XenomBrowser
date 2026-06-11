package com.xenombrowser

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Shared full-screen list page for History / Bookmarks / Downloads. */
class ListPageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"      // "history" | "bookmarks" | "downloads"
        const val EXTRA_URL  = "url"       // result: chosen url
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
        const val MODE_DOWNLOADS = "downloads"
    }

    private lateinit var rv: RecyclerView
    private lateinit var empty: TextView
    private var mode = MODE_HISTORY
    private data class Row(val title: String, val sub: String, val url: String)
    private val rows = mutableListOf<Row>()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_HISTORY

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            fitsSystemWindows = true
        }
        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        val title = TextView(this).apply {
            text = when (mode) { MODE_BOOKMARKS -> "★ Закладки"; MODE_DOWNLOADS -> "⬇ Загрузки"; else -> "🕘 История" }
            setTextColor(Color.WHITE); textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearBtn = Button(this).apply {
            text = "Очистить"
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#B62B2B"))
            isAllCaps = false
            setOnClickListener { clearAll() }
        }
        header.addView(title); header.addView(clearBtn)
        root.addView(header)

        empty = TextView(this).apply {
            text = "Пусто"
            setTextColor(Color.parseColor("#8B949E")); textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, dp(60), 0, 0)
            visibility = View.GONE
        }
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ListPageActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(empty)
        root.addView(rv)
        setContentView(root)

        load()
    }

    private fun load() {
        rows.clear()
        when (mode) {
            MODE_BOOKMARKS -> BookmarkManager.getAll(this).forEach { rows.add(Row(it.title, it.url, it.url)) }
            MODE_DOWNLOADS -> DownloadStore.getAll(this).forEach { rows.add(Row(it.name, it.url, it.url)) }
            else -> HistoryManager.getAll(this).forEach { rows.add(Row(it.title, it.url, it.url)) }
        }
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        rv.adapter = Adapter()
        if (rows.isNotEmpty()) rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun clearAll() {
        when (mode) {
            MODE_BOOKMARKS -> BookmarkManager.getAll(this).forEach { BookmarkManager.remove(this, it.url) }
            MODE_DOWNLOADS -> DownloadStore.clear(this)
            else -> HistoryManager.clear(this)
        }
        load()
    }

    private fun openUrl(url: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URL, url))
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val v: LinearLayout) : RecyclerView.ViewHolder(v) {
            val t = TextView(v.context); val s = TextView(v.context)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true
                setPadding(dp(22), dp(14), dp(22), dp(14))
                setBackgroundResource(android.R.drawable.list_selector_background)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val vh = VH(row)
            vh.t.apply { setTextColor(Color.WHITE); textSize = 16f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            vh.s.apply { setTextColor(Color.parseColor("#8B949E")); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            row.addView(vh.t); row.addView(vh.s)
            row.setOnFocusChangeListener { _, f -> row.setBackgroundColor(if (f) Color.parseColor("#1C2230") else Color.TRANSPARENT) }
            return vh
        }
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = rows[pos]
            h.t.text = r.title.ifBlank { r.url }
            h.s.text = r.sub
            h.v.setOnClickListener { openUrl(r.url) }
            h.v.setOnLongClickListener {
                when (mode) {
                    MODE_BOOKMARKS -> BookmarkManager.remove(this@ListPageActivity, r.url)
                    else -> {}
                }
                load(); true
            }
        }
    }
}
