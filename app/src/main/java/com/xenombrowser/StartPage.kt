package com.xenombrowser

import android.content.Context

/** Generates the speed-dial start page (about:home). Tiles use href="x:URL". */
object StartPage {

    data class Shortcut(val title: String, val url: String, val color: String)

    private val defaults = listOf(
        Shortcut("YouTube", "https://m.youtube.com", "#FF0000"),
        Shortcut("Яндекс", "https://ya.ru", "#FF3333"),
        Shortcut("Google", "https://www.google.com", "#4285F4"),
        Shortcut("Wikipedia", "https://ru.wikipedia.org", "#888888"),
        Shortcut("VK", "https://m.vk.com", "#0077FF"),
        Shortcut("Twitch", "https://m.twitch.tv", "#9146FF"),
        Shortcut("Reddit", "https://www.reddit.com", "#FF4500"),
        Shortcut("RuTube", "https://rutube.ru", "#0A5")
    )

    fun html(c: Context): String {
        val tiles = LinkedHashMap<String, Shortcut>()
        defaults.forEach { tiles[it.url] = it }
        BookmarkManager.getAll(c).take(8).forEach { bm ->
            if (!tiles.containsKey(bm.url)) tiles[bm.url] = Shortcut(bm.title.take(16), bm.url, "#00B4FF")
        }
        val tilesHtml = tiles.values.take(12).joinToString("") { s ->
            val letter = s.title.firstOrNull()?.uppercase() ?: "?"
            "<a class=\"tile\" href=\"x:${s.url}\"><div class=\"ico\" style=\"background:${s.color}\">$letter</div><div class=\"tt\">${esc(s.title)}</div></a>"
        }
        val recentItems = HistoryManager.getAll(c).take(6)
        val recent = recentItems.joinToString("") { h ->
            "<a class=\"rec\" href=\"x:${h.url}\"><span class=\"rd\">●</span>${esc(h.title.take(48))}</a>"
        }
        val recsBlock = if (recent.isNotBlank())
            "<div class=\"recs\"><div class=\"rh\">Недавнее</div>$recent</div>" else ""

        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0;font-family:-apple-system,Roboto,Arial,sans-serif}
body{background:linear-gradient(135deg,#0D1117 0%,#161B22 100%);color:#fff;min-height:100vh;padding:48px 32px}
.logo{text-align:center;font-size:54px;font-weight:800;letter-spacing:2px;background:linear-gradient(90deg,#00B4FF,#00FF88);-webkit-background-clip:text;-webkit-text-fill-color:transparent;margin-bottom:8px}
.sub{text-align:center;color:#8B949E;margin-bottom:32px;font-size:15px}
.sb{max-width:720px;margin:0 auto 40px;display:flex;background:#21262D;border:2px solid #30363D;border-radius:14px;overflow:hidden}
.sb:focus-within{border-color:#00B4FF}
#q{flex:1;background:transparent;border:0;outline:0;color:#fff;font-size:18px;padding:16px 20px}
.sb button{background:#00B4FF;border:0;color:#fff;font-size:17px;padding:0 28px;cursor:pointer}
.grid{max-width:920px;margin:0 auto;display:grid;grid-template-columns:repeat(4,1fr);gap:18px}
.tile{display:flex;flex-direction:column;align-items:center;text-decoration:none;color:#fff;background:#161B22;border:2px solid transparent;border-radius:16px;padding:18px 8px;transition:all .15s}
.tile:focus,.tile:hover{border-color:#00B4FF;background:#1C2230;transform:translateY(-2px);outline:none}
.ico{width:56px;height:56px;border-radius:14px;display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:700;margin-bottom:10px}
.tt{font-size:13px;color:#C9D1D9;text-align:center;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}
.recs{max-width:920px;margin:36px auto 0}
.rh{color:#8B949E;font-size:13px;margin-bottom:10px;text-transform:uppercase;letter-spacing:1px}
.rec{display:block;text-decoration:none;color:#C9D1D9;padding:10px 14px;border-radius:10px;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.rec:focus,.rec:hover{background:#21262D;outline:none;color:#fff}
.rd{color:#00B4FF;margin-right:10px;font-size:9px;vertical-align:middle}
@media(max-width:640px){.grid{grid-template-columns:repeat(3,1fr)}}
</style></head><body>
<div class="logo">XENOM</div>
<div class="sub">Введите запрос или адрес сайта</div>
<form class="sb" onsubmit="return go()"><input id="q" placeholder="Поиск или адрес..." autocomplete="off"><button type="submit">Найти</button></form>
<div class="grid">$tilesHtml</div>
$recsBlock
<script>
function go(){var v=document.getElementById('q').value.trim();if(v&&typeof XenomBridge!=='undefined')XenomBridge.onSearch(v);return false;}
document.querySelectorAll('a.tile,a.rec').forEach(function(a){a.addEventListener('click',function(e){e.preventDefault();var u=a.getAttribute('href').slice(2);if(typeof XenomBridge!=='undefined')XenomBridge.onOpen(u);});});
</script></body></html>"""
    }

    private fun esc(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
}
