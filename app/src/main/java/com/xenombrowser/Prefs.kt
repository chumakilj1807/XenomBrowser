package com.xenombrowser

import android.content.Context

/** Central settings store for the browser. */
object Prefs {
    private const val P = "xenom_settings"

    private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    var homePage: String
        get() = _ctx?.let { sp(it).getString("home", "about:home") ?: "about:home" } ?: "about:home"
        set(v) { _ctx?.let { sp(it).edit().putString("home", v).apply() } }

    // Search engines: name -> query template (%s = query)
    val engines = linkedMapOf(
        "Яндекс"   to "https://yandex.ru/search/?text=%s",
        "Google"   to "https://www.google.com/search?q=%s",
        "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
        "Bing"     to "https://www.bing.com/search?q=%s",
        "YouTube"  to "https://m.youtube.com/results?search_query=%s"
    )
    // Suggest endpoints per engine
    val suggestUrls = mapOf(
        "Яндекс"     to "https://suggest.yandex.ru/suggest-ff.cgi?part=%s",
        "Google"     to "https://suggestqueries.google.com/complete/search?client=firefox&q=%s",
        "DuckDuckGo" to "https://duckduckgo.com/ac/?q=%s&type=list",
        "Bing"       to "https://api.bing.com/osjson.aspx?query=%s",
        "YouTube"    to "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=%s"
    )

    private var _ctx: Context? = null
    fun init(c: Context) { _ctx = c.applicationContext }

    fun get(c: Context, k: String, def: String) = sp(c).getString(k, def) ?: def
    fun set(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun getBool(c: Context, k: String, def: Boolean) = sp(c).getBoolean(k, def)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    fun searchEngine(c: Context) = get(c, "engine", "Яндекс")
    fun searchUrl(c: Context, query: String): String {
        val tpl = engines[searchEngine(c)] ?: engines.values.first()
        return tpl.replace("%s", android.net.Uri.encode(query))
    }
    fun suggestUrl(c: Context, query: String): String? =
        suggestUrls[searchEngine(c)]?.replace("%s", android.net.Uri.encode(query))

    fun adBlock(c: Context) = getBool(c, "adblock", true)
    fun desktopMode(c: Context) = getBool(c, "desktop", true)
    fun darkMode(c: Context) = getBool(c, "dark", false)
    fun homeUrl(c: Context) = get(c, "home", "about:home")
}
