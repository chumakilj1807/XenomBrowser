package com.xenombrowser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private enum class Mode { SCROLL, LINK, CURSOR }
    private var mode = Mode.SCROLL

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var ivModeIcon: ImageView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnBookmarkAdd: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var navBar: LinearLayout
    private lateinit var statusBar: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var webFrame: FrameLayout
    private lateinit var cursorView: View
    private lateinit var panelSide: LinearLayout
    private lateinit var rvBookmarks: RecyclerView

    private val handler = Handler(Looper.getMainLooper())
    private var skipChecker: Runnable? = null
    private val adDomains = mutableSetOf<String>()
    private var okDownTime = -1L
    private val LONG_PRESS_MS = 700L
    private var lastBackTime = 0L
    private var cursorX = 0f
    private var cursorY = 0f
    private val CURSOR_STEP = 50
    private var pendingVideoUrl: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var customView: View? = null
    private var customViewCb: WebChromeClient.CustomViewCallback? = null
    private val videoExts = listOf(".mp4",".mkv",".avi",".mov",".wmv",".flv",".webm",".m3u8",".m3u",".ts",".mpd")
    private val audioExts = listOf(".mp3",".aac",".ogg",".flac",".wav",".m4a",".opus")

    private val INJECT_JS = """
(function(){
  if(window._xb)return;
  window._xb={
    cur:null,
    vis:function(el){var s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity)<0.05)return false;var r=el.getBoundingClientRect();return r.width>1&&r.height>1&&r.right>0&&r.left<window.innerWidth&&r.bottom>0&&r.top<window.innerHeight;},
    all:function(){var sels=['a[href]:not([href^="javascript:void"])','button:not([disabled])','[role="button"]','[role="link"]','input:not([type="hidden"]):not([disabled])','textarea:not([disabled])','select:not([disabled])','video','audio','[onclick]','.OrganicTitle-Link','.organic__url','.yuRUbf a','#video-title','.ytd-thumbnail a','.ytp-skip-ad-button','[class*="skip-ad"]','[class*="SkipAd"]','[data-purpose="skip-button"]','.ytp-ad-skip-button-modern'].join(',');var seen=new WeakSet();return Array.from(document.querySelectorAll(sels)).filter(function(el){if(seen.has(el))return false;seen.add(el);return window._xb.vis(el);});},
    hl:function(el){this.clr();if(!el)return'';el._o=el.style.outline||'';el._oo=el.style.outlineOffset||'';el.style.outline='3px solid #00B4FF';el.style.outlineOffset='2px';el.setAttribute('data-xb','1');el.scrollIntoView({block:'nearest',behavior:'smooth',inline:'nearest'});var t=el.tagName.toLowerCase();if(t==='input'||t==='textarea')return el.placeholder||el.name||'Поле ввода';if(t==='video')return'▶ Видео';if(t==='audio')return'♪ Аудио';return(el.title||el.textContent||el.alt||el.value||el.getAttribute('href')||'').trim().replace(/\s+/g,' ').substring(0,80);},
    clr:function(){var el=document.querySelector('[data-xb]');if(el){el.style.outline=el._o||'';el.style.outlineOffset=el._oo||'';el.removeAttribute('data-xb');}},
    nav:function(dir){var els=this.all();if(!els.length)return'EMPTY';if(!this.cur||!document.body.contains(this.cur)){this.cur=els.reduce(function(a,b){var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return(ra.top*3+ra.left)<(rb.top*3+rb.left)?a:b;});return this.hl(this.cur);}var cr=this.cur.getBoundingClientRect(),cx=cr.left+cr.width/2,cy=cr.top+cr.height/2;var cands=els.filter(function(el){if(el===window._xb.cur)return false;var r=el.getBoundingClientRect(),ex=r.left+r.width/2,ey=r.top+r.height/2;if(dir==='right')return ex>cx+8;if(dir==='left')return ex<cx-8;if(dir==='down')return ey>cy+8;if(dir==='up')return ey<cy-8;return false;});if(!cands.length)return'SCROLL_'+dir;var best=cands.reduce(function(a,b){var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect(),ax=ra.left+ra.width/2,ay=ra.top+ra.height/2,bx=rb.left+rb.width/2,by=rb.top+rb.height/2,sa,sb;if(dir==='right'||dir==='left'){sa=Math.abs(ax-cx)+Math.abs(ay-cy)*2.5;sb=Math.abs(bx-cx)+Math.abs(by-cy)*2.5;}else{sa=Math.abs(ay-cy)+Math.abs(ax-cx)*2.5;sb=Math.abs(by-cy)+Math.abs(bx-cx)*2.5;}return sa<sb?a:b;});this.cur=best;return this.hl(best);},
    act:function(){if(!this.cur)return'NONE';var el=this.cur;this.clr();this.cur=null;var tag=el.tagName.toLowerCase();if(tag==='input'||tag==='textarea'||tag==='select'){el.focus();var r2=el.getBoundingClientRect();return'INPUT:'+Math.round(r2.left+r2.width/2)+':'+Math.round(r2.top+r2.height/2);}var inp=el.querySelector('input:not([type="hidden"]),textarea');if(inp&&window._xb.vis(inp)){inp.focus();var ri=inp.getBoundingClientRect();return'INPUT:'+Math.round(ri.left+ri.width/2)+':'+Math.round(ri.top+ri.height/2);}if(tag==='video'){var src=el.currentSrc||el.src||'';if(src&&!src.startsWith('blob:'))return'VIDEO:'+src;if(el.paused)el.play();else el.pause();return'VIDEO_TOGGLE';}if(tag==='audio'){var src2=el.currentSrc||el.src||'';if(src2&&!src2.startsWith('blob:'))return'AUDIO:'+src2;if(el.paused)el.play();else el.pause();return'AUDIO_TOGGLE';}var r=el.getBoundingClientRect(),mx=r.left+r.width/2,my=r.top+r.height/2;['mousedown','mouseup','click'].forEach(function(evt){el.dispatchEvent(new MouseEvent(evt,{bubbles:true,cancelable:true,clientX:mx,clientY:my,view:window}));});return el.href||el.getAttribute('href')||el.textContent.trim().substring(0,60)||'CLICKED';},
    skip:function(doClick){var playing=Array.from(document.querySelectorAll('video')).some(function(v){return!v.paused&&!v.ended&&v.readyState>2;});if(!playing)return 0;var found=[];['.ytp-skip-ad-button','[class*="skip-ad"]','[class*="SkipAd"]','[id*="skip-ad"]','.ad-skip-button','.videoAdUiSkipButton','[data-purpose="skip-button"]','.ytp-ad-skip-button-modern'].forEach(function(s){try{Array.from(document.querySelectorAll(s)).filter(function(el){return window._xb.vis(el);}).forEach(function(el){found.push(el);});}catch(e){}});if(!found.length){var texts=['пропустить','skip ad','skip ads','skip','close ad'];Array.from(document.querySelectorAll('button,[role="button"],div,span')).filter(function(el){var t=el.textContent.trim().toLowerCase();return texts.some(function(s){return t===s||t.startsWith(s+'.');})&&window._xb.vis(el);}).forEach(function(el){found.push(el);});}if(doClick&&found.length){found[0].click();return-1;}return found.length;},
    clickAt:function(x,y){var el=document.elementFromPoint(x,y);if(!el)return'MISS';['mousedown','mouseup','click'].forEach(function(evt){el.dispatchEvent(new MouseEvent(evt,{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window}));});var tag=el.tagName.toLowerCase();if(tag==='input'||tag==='textarea'||tag==='select'){el.focus();return'INPUT';}var inp=el.querySelector('input,textarea');if(inp){inp.focus();return'INPUT';}return'CLICKED';},
    linkAt:function(x,y){var el=document.elementFromPoint(x,y);if(!el)return false;var tag=el.tagName.toLowerCase();if(['a','button','input','textarea','select','video','audio'].indexOf(tag)>=0)return true;if(el.onclick||el.getAttribute('role')==='button')return true;return getComputedStyle(el).cursor==='pointer';}
  };
  if(window._xbObs){window._xbObs.disconnect();}
  window._xbObs=new MutationObserver(function(){document.querySelectorAll('video[src]:not([data-xb-seen])').forEach(function(v){v.setAttribute('data-xb-seen','1');var src=v.currentSrc||v.src||'';if(src&&!src.startsWith('blob:')&&typeof XenomBridge!=='undefined'){try{XenomBridge.onVideoFound(src);}catch(e){}}});});
  window._xbObs.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['src']});
})();
""".trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        @Suppress("DEPRECATION")
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "XenomBrowser:main")
        @Suppress("DEPRECATION") wakeLock?.acquire(8 * 60 * 60 * 1000L)
        bindViews(); loadAdDomains(); setupWebView(); setupUrlBar()
        setupButtons(); setupCursor(); startSkipChecker()
        webView.loadUrl("https://ya.ru"); setMode(Mode.SCROLL)
    }

    private fun bindViews() {
        webView=findViewById(R.id.web_view); etUrl=findViewById(R.id.et_url)
        tvStatus=findViewById(R.id.tv_status); ivModeIcon=findViewById(R.id.iv_mode_icon)
        btnBack=findViewById(R.id.btn_back); btnForward=findViewById(R.id.btn_forward)
        btnReload=findViewById(R.id.btn_reload); btnBookmarkAdd=findViewById(R.id.btn_bookmark_add)
        btnMenu=findViewById(R.id.btn_menu); navBar=findViewById(R.id.nav_bar)
        statusBar=findViewById(R.id.status_bar); progressBar=findViewById(R.id.progress_bar)
        webFrame=findViewById(R.id.web_frame); cursorView=findViewById(R.id.cursor_view)
        panelSide=findViewById(R.id.panel_side); rvBookmarks=findViewById(R.id.rv_bookmarks)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled=true; domStorageEnabled=true; databaseEnabled=true
            loadWithOverviewMode=true; useWideViewPort=true
            setSupportZoom(false); builtInZoomControls=false; mediaPlaybackRequiresUserGesture=false
            userAgentString="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onVideoFound(url: String) {
                handler.post {
                    if (url.isNotEmpty() && pendingVideoUrl == null) {
                        pendingVideoUrl = url
                        hint("▶ Видео найдено — нажмите ОК для воспроизведения")
                    }
                }
            }
        }, "XenomBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val host = req.url.host ?: return null
                if (adDomains.any { host == it || host.endsWith(".$it") })
                    return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (videoExts.any { url.contains(it, ignoreCase=true) }) { openInPlayer(url, false); return true }
                if (audioExts.any { url.contains(it, ignoreCase=true) }) { openInPlayer(url, true); return true }
                return false
            }
            override fun onPageStarted(view: WebView, url: String, fav: Bitmap?) {
                etUrl.setText(url); updateBookmarkBtn(url)
                if (mode == Mode.LINK) exitToScroll()
                pendingVideoUrl = null; progressBar.visibility = View.VISIBLE; progressBar.progress = 10
            }
            override fun onPageFinished(view: WebView, url: String) {
                injectJs(); updateNavBtns(); etUrl.setText(url)
                progressBar.visibility = View.GONE
                HistoryManager.add(this@MainActivity, view.title ?: url, url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                progressBar.progress = progress
                progressBar.visibility = if (progress >= 100) View.GONE else View.VISIBLE
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; customViewCb = callback
                navBar.visibility = View.GONE; statusBar.visibility = View.GONE
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                webFrame.addView(view, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                webView.visibility = View.GONE
            }
            override fun onHideCustomView() {
                customView?.let { webFrame.removeView(it) }; customView = null
                customViewCb?.onCustomViewHidden()
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                webView.visibility = View.VISIBLE; navBar.visibility = View.VISIBLE
                statusBar.visibility = View.VISIBLE
            }
            override fun onConsoleMessage(msg: ConsoleMessage?) = true
        }
    }

    private fun injectJs() = webView.evaluateJavascript(INJECT_JS, null)

    private fun setMode(m: Mode) {
        mode = m
        when (m) {
            Mode.SCROLL -> {
                ivModeIcon.setImageResource(R.drawable.ic_mode_scroll)
                hint("↕ Прокрутка  •  ОК — выбор  •  удержать ОК — курсор")
                cursorView.visibility = View.GONE
            }
            Mode.LINK   -> ivModeIcon.setImageResource(R.drawable.ic_mode_link)
            Mode.CURSOR -> {
                ivModeIcon.setImageResource(R.drawable.ic_mode_cursor)
                hint("⊚ Курсор  •  стрелки — движение  •  ОК — нажать  •  НАЗАД — выход")
                cursorView.visibility = View.VISIBLE
            }
        }
    }

    private fun enterLink() {
        setMode(Mode.LINK)
        webView.evaluateJavascript("window._xb?window._xb.nav('down'):'EMPTY'") { r ->
            val lbl = r?.trim()?.removeSurrounding("\"") ?: ""
            when {
                lbl == "EMPTY" -> { hint("Нет элементов"); handler.postDelayed({setMode(Mode.SCROLL)}, 1500) }
                lbl.startsWith("SCROLL_") -> setMode(Mode.SCROLL)
                else -> hint("🔗 $lbl  •  ОК — открыть  •  НАЗАД — выход")
            }
        }
    }

    private fun exitToScroll() {
        webView.evaluateJavascript("if(window._xb)window._xb.clr()", null)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        setMode(Mode.SCROLL)
    }

    private fun setupCursor() { webFrame.doOnLayout { cursorX=it.width/2f; cursorY=it.height/2f; updateCursorPos() } }
    private fun updateCursorPos() { cursorView.translationX=cursorX-cursorView.width/2f; cursorView.translationY=cursorY-cursorView.height/2f }

    private fun moveCursor(dx: Int, dy: Int) {
        cursorX = (cursorX+dx).coerceIn(0f, webFrame.width.toFloat())
        cursorY = (cursorY+dy).coerceIn(0f, webFrame.height.toFloat())
        updateCursorPos()
        webView.evaluateJavascript("window._xb?window._xb.linkAt($cursorX,$cursorY):false") { r ->
            cursorView.alpha = if (r?.trim() == "true") 1f else 0.65f
        }
    }

    private fun clickAtCursor() {
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        val now = SystemClock.uptimeMillis()
        val dn = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up = MotionEvent.obtain(now, now+80, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(dn)
        handler.postDelayed({ webView.dispatchTouchEvent(up); dn.recycle(); up.recycle() }, 80)
        webView.evaluateJavascript("window._xb?window._xb.clickAt($cursorX,$cursorY):'MISS'") { r ->
            if (r?.trim()?.removeSurrounding("\"") == "INPUT") {
                handler.postDelayed({ showKeyboard() }, 300)
            } else {
                webView.isFocusable = false
                webView.isFocusableInTouchMode = false
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && !etUrl.isFocused) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> { if (okDownTime < 0) okDownTime = SystemClock.elapsedRealtime(); return true }
                KeyEvent.ACTION_UP -> {
                    val held = SystemClock.elapsedRealtime() - okDownTime; okDownTime = -1L
                    return if (held >= LONG_PRESS_MS && mode == Mode.SCROLL) { setMode(Mode.CURSOR); true }
                    else { handleOk(); true }
                }
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (etUrl.isFocused) return when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK -> { dismissKeyboard(); webView.requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { navigate(etUrl.text.toString().trim()); dismissKeyboard(); true }
            else -> super.dispatchKeyEvent(event)
        }
        if (panelSide.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) { panelSide.visibility = View.GONE; return true }
            return super.dispatchKeyEvent(event)
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP    -> when (mode) { Mode.SCROLL->{ if (webView.scrollY<=0) focusUrlBar() else webView.scrollBy(0,-250); true }; Mode.LINK->linkNav("up"); Mode.CURSOR->{ moveCursor(0,-CURSOR_STEP); true } }
            KeyEvent.KEYCODE_DPAD_DOWN  -> when (mode) { Mode.SCROLL->{ webView.scrollBy(0,250); true }; Mode.LINK->linkNav("down"); Mode.CURSOR->{ moveCursor(0,CURSOR_STEP); true } }
            KeyEvent.KEYCODE_DPAD_LEFT  -> when (mode) { Mode.SCROLL->{ webView.scrollBy(-250,0); true }; Mode.LINK->linkNav("left"); Mode.CURSOR->{ moveCursor(-CURSOR_STEP,0); true } }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (mode) { Mode.SCROLL->{ webView.scrollBy(250,0); true }; Mode.LINK->linkNav("right"); Mode.CURSOR->{ moveCursor(CURSOR_STEP,0); true } }
            KeyEvent.KEYCODE_BACK -> when (mode) {
                Mode.LINK, Mode.CURSOR -> { exitToScroll(); true }
                Mode.SCROLL -> when {
                    webView.canGoBack() -> { webView.goBack(); true }
                    else -> { val now=System.currentTimeMillis(); if (now-lastBackTime<2000) { finish(); true } else { lastBackTime=now; Toast.makeText(this,"Нажмите ещё раз для выхода",Toast.LENGTH_SHORT).show(); true } }
                }
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun handleOk() {
        when (mode) {
            Mode.SCROLL -> {
                pendingVideoUrl?.let { url -> pendingVideoUrl=null; openInPlayer(url,false); return }
                webView.evaluateJavascript("window._xb?window._xb.skip(false):0") { r ->
                    if ((r?.trim()?.toIntOrNull() ?: 0) > 0) {
                        webView.evaluateJavascript("window._xb.skip(true)", null)
                        hint("Реклама пропущена ✓")
                    } else enterLink()
                }
            }
            Mode.LINK -> {
                webView.evaluateJavascript("window._xb?window._xb.act():'NONE'") { r ->
                    val res = r?.trim()?.removeSurrounding("\"") ?: "NONE"
                    setMode(Mode.SCROLL)
                    when {
                        res.startsWith("INPUT") -> {
                            val parts = res.split(":")
                            val cx = parts.getOrNull(1)?.toFloatOrNull()
                            val cy = parts.getOrNull(2)?.toFloatOrNull()
                            if (cx != null && cy != null) {
                                handler.postDelayed({ tapWebViewInput(cx, cy) }, 150)
                            } else {
                                handler.postDelayed({ showKeyboard() }, 300)
                            }
                            hint("⌨ Введите текст — нажмите Enter для поиска")
                        }
                        res.startsWith("VIDEO:") -> openInPlayer(res.removePrefix("VIDEO:"), false)
                        res.startsWith("AUDIO:") -> openInPlayer(res.removePrefix("AUDIO:"), true)
                    }
                }
            }
            Mode.CURSOR -> clickAtCursor()
        }
    }

    private fun linkNav(dir: String): Boolean {
        webView.evaluateJavascript("window._xb?window._xb.nav('$dir'):'EMPTY'") { r ->
            val lbl = r?.trim()?.removeSurrounding("\"") ?: "EMPTY"
            when {
                lbl == "EMPTY" -> { exitToScroll(); hint("Нет элементов") }
                lbl.startsWith("SCROLL_") -> when (lbl.removePrefix("SCROLL_")) {
                    "up"->webView.scrollBy(0,-250); "down"->webView.scrollBy(0,250)
                    "left"->webView.scrollBy(-250,0); "right"->webView.scrollBy(250,0)
                }
                else -> hint("🔗 $lbl  •  ОК — открыть  •  НАЗАД — выход")
            }
        }
        return true
    }

    private fun focusUrlBar() {
        etUrl.requestFocus(); etUrl.selectAll()
        etUrl.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etUrl, InputMethodManager.SHOW_FORCED) }
        hint("Введите адрес или поисковый запрос")
    }
    private fun setupUrlBar() {
        etUrl.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) etUrl.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etUrl, InputMethodManager.SHOW_FORCED) } }
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId in listOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE)) {
                navigate(etUrl.text.toString().trim()); dismissKeyboard(); true
            } else false
        }
    }
    private fun navigate(input: String) {
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") && input.length > 3 -> "https://$input"
            else -> "https://yandex.ru/search/?text=${Uri.encode(input)}&lr=213"
        }
        webView.loadUrl(url); setMode(Mode.SCROLL)
    }
    private fun dismissKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
    }

    private fun showKeyboard() {
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED)
    }

    @Suppress("DEPRECATION")
    private fun tapWebViewInput(jsX: Float, jsY: Float) {
        // jsX/jsY are CSS viewport coords from getBoundingClientRect()
        // Multiply by current scale to get WebView view-local pixel coords
        val scale = webView.scale.takeIf { it > 0f } ?: 1f
        val px = jsX * scale
        val py = jsY * scale

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        val now = SystemClock.uptimeMillis()
        val dn = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, px, py, 0)
        val up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, px, py, 0)
        webView.dispatchTouchEvent(dn)
        handler.postDelayed({
            webView.dispatchTouchEvent(up)
            dn.recycle(); up.recycle()
        }, 100)

        // Force keyboard after touch settles
        handler.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED)
        }, 350)
    }

    private fun setupButtons() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnReload.setOnClickListener { webView.reload() }
        btnBookmarkAdd.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            if (BookmarkManager.contains(this, url)) { BookmarkManager.remove(this, url); Toast.makeText(this, "Закладка удалена", Toast.LENGTH_SHORT).show() }
            else { BookmarkManager.add(this, webView.title ?: url, url); Toast.makeText(this, "Закладка добавлена ★", Toast.LENGTH_SHORT).show() }
            updateBookmarkBtn(url); refreshBookmarks()
        }
        btnMenu.setOnClickListener {
            if (panelSide.visibility == View.VISIBLE) panelSide.visibility = View.GONE
            else { refreshBookmarks(); panelSide.visibility = View.VISIBLE }
        }
    }
    private fun refreshBookmarks() {
        val items = BookmarkManager.getAll(this)
        if (rvBookmarks.layoutManager == null) rvBookmarks.layoutManager = LinearLayoutManager(this)
        rvBookmarks.adapter = BookmarkAdapter(items) { url -> webView.loadUrl(url); panelSide.visibility = View.GONE }
    }

    private fun startSkipChecker() {
        skipChecker = object : Runnable { override fun run() {
            if (mode == Mode.SCROLL) webView.evaluateJavascript("window._xb?window._xb.skip(false):0") { r ->
                if ((r?.trim()?.toIntOrNull() ?: 0) > 0) hint("📢 Реклама — нажмите ОК для пропуска")
            }
            handler.postDelayed(this, 1500)
        } }
        handler.postDelayed(skipChecker!!, 2000)
    }

    private fun hint(text: String) { tvStatus.text = text }
    private fun updateBookmarkBtn(url: String) { btnBookmarkAdd.setImageResource(if (BookmarkManager.contains(this, url)) R.drawable.ic_bookmark_on else R.drawable.ic_bookmark_off) }
    private fun updateNavBtns() { btnBack.alpha=if (webView.canGoBack()) 1f else 0.35f; btnForward.alpha=if (webView.canGoForward()) 1f else 0.35f }
    private fun openInPlayer(url: String, isAudio: Boolean) { startActivity(Intent(this, VideoActivity::class.java).apply { putExtra(VideoActivity.EXTRA_URL, url); putExtra(VideoActivity.EXTRA_AUDIO_ONLY, isAudio) }) }
    private fun loadAdDomains() { try { assets.open("adblock.txt").bufferedReader().forEachLine { t -> if (t.isNotEmpty() && !t.startsWith("#")) adDomains.add(t.trim()) } } catch (_: Exception) {} }

    override fun onPause()   { super.onPause();   webView.onPause() }
    override fun onResume()  { super.onResume();  webView.onResume() }
    override fun onDestroy() {
        super.onDestroy()
        skipChecker?.let { handler.removeCallbacks(it) }
        webView.destroy()
        @Suppress("DEPRECATION") wakeLock?.takeIf { it.isHeld }?.release()
    }

    inner class BookmarkAdapter(
        private val items: List<BookmarkManager.Bookmark>,
        private val onOpen: (String) -> Unit
    ) : RecyclerView.Adapter<BookmarkAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView  = v.findViewById(R.id.tv_bookmark_title)
            val del: ImageButton = v.findViewById(R.id.btn_delete_bookmark)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(layoutInflater.inflate(R.layout.item_bookmark, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val bm = items[pos]; h.title.text = bm.title
            h.itemView.setOnClickListener { onOpen(bm.url) }
            h.del.setOnClickListener { BookmarkManager.remove(this@MainActivity, bm.url); refreshBookmarks() }
        }
    }
}
