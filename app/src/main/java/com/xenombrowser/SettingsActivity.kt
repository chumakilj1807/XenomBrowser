package com.xenombrowser

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebStorage
import android.webkit.CookieManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0D1117")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(title("⚙ Настройки", 26))

        // Search engine
        root.addView(section("Поисковая система"))
        val engineRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Prefs.engines.keys.forEach { name ->
            val b = Button(this).apply {
                text = name; isAllCaps = false; textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(if (Prefs.searchEngine(this@SettingsActivity) == name) Color.parseColor("#00B4FF") else Color.parseColor("#21262D"))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.rightMargin = dp(8); layoutParams = lp
                setOnClickListener {
                    Prefs.set(this@SettingsActivity, "engine", name)
                    recreate()
                }
            }
            engineRow.addView(b)
        }
        val esWrap = HorizontalScrollView(this); esWrap.addView(engineRow); root.addView(esWrap)

        // Toggles
        root.addView(section("Основное"))
        root.addView(toggle("Блокировка рекламы", "adblock", true))
        root.addView(toggle("Десктоп-версия сайтов", "desktop", true))
        root.addView(toggle("Тёмный режим страниц", "dark", false))
        root.addView(toggle("Блокировать всплывающие окна", "popups", true))

        // Home page
        root.addView(section("Стартовая страница"))
        val homeInput = EditText(this).apply {
            setText(Prefs.homeUrl(this@SettingsActivity))
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#4A5568"))
            hint = "about:home или URL"
            setBackgroundColor(Color.parseColor("#21262D")); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(homeInput)
        root.addView(button("Сохранить стартовую") { Prefs.set(this, "home", homeInput.text.toString().trim().ifBlank { "about:home" }); toast("Сохранено") })

        // Data
        root.addView(section("Данные"))
        root.addView(button("Очистить историю") { HistoryManager.clear(this); toast("История очищена") })
        root.addView(button("Очистить cookies") { CookieManager.getInstance().removeAllCookies(null); toast("Cookies очищены") })
        root.addView(button("Очистить кэш и данные сайтов") { WebStorage.getInstance().deleteAllData(); toast("Кэш очищен") })
        root.addView(button("Очистить загрузки") { DownloadStore.clear(this); toast("Список загрузок очищен") })

        root.addView(TextView(this).apply {
            text = "XenomBrowser • TV-браузер"
            setTextColor(Color.parseColor("#4A5568")); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })
    }

    private fun title(t: String, sz: Float) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = sz
        setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, dp(8))
    }
    private fun section(t: String) = TextView(this).apply {
        text = t.uppercase(); setTextColor(Color.parseColor("#00B4FF")); textSize = 13f
        setPadding(0, dp(22), 0, dp(10)); letterSpacing = 0.08f
    }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        setBackgroundColor(Color.parseColor("#21262D"))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8); layoutParams = lp; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }
    private fun toggle(label: String, key: String, def: Boolean): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val tv = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(this).apply {
            isChecked = Prefs.getBool(this@SettingsActivity, key, def)
            setOnCheckedChangeListener { _, v -> Prefs.setBool(this@SettingsActivity, key, v) }
        }
        row.addView(tv); row.addView(sw); return row
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
