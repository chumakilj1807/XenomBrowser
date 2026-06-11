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

    private enum class Mode { SCROLL, LINK, RESULTS, CURSOR }
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
    private val CURSOR_STEP = 14
    private var webInputMode = false
    private var webInputCx = 0f
    private var webInputCy = 0f
    private var customView: View? = null
    private var customViewCb: WebChromeClient.CustomViewCallback? = null
    private var desktopMode = true
    private var adBlockEnabled = true
    private var blockedCount = 0

    private val UA_DESKTOP = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val UA_MOBILE  = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // Direct media file URLs → open in the dedicated player. Streaming-page videos play in-page.
    private val videoExts = listOf(".mp4",".mkv",".avi",".mov",".wmv",".flv",".webm",".m3u8",".mpd")
    private val audioExts = listOf(".mp3",".aac",".ogg",".flac",".wav",".m4a",".opus")

    private val INJECT_JS = """
(function(){
  if(window._xb) { window._xb.clean(); return; }
  window._xb={
    cur:null,
    vis:function(el){var s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity)<0.05)return false;var r=el.getBoundingClientRect();return r.width>1&&r.height>1&&r.right>0&&r.left<window.innerWidth&&r.bottom>0&&r.top<window.innerHeight;},
    all:function(){var sels=['a[href]:not([href^="javascript:void"])','button:not([disabled])','[role="button"]','[role="link"]','input:not([type="hidden"]):not([disabled])','textarea:not([disabled])','select:not([disabled])','video','audio','[onclick]','[tabindex]:not([tabindex="-1"])','.ytp-skip-ad-button','[class*="skip-ad"]'].join(',');var seen=new WeakSet();return Array.from(document.querySelectorAll(sels)).filter(function(el){if(seen.has(el))return false;seen.add(el);return window._xb.vis(el);});},
    hl:function(el){this.clr();if(!el)return'';el._o=el.style.outline||'';el._oo=el.style.outlineOffset||'';el.style.outline='3px solid #00B4FF';el.style.outlineOffset='2px';el.setAttribute('data-xb','1');el.scrollIntoView({block:'center',behavior:'smooth',inline:'nearest'});var t=el.tagName.toLowerCase();if(t==='input'||t==='textarea')return el.placeholder||el.name||'Поле ввода';if(t==='video')return'▶ Видео — ОК для просмотра';if(t==='audio')return'♪ Аудио';return(el.title||el.textContent||el.alt||el.value||el.getAttribute('href')||'').trim().replace(/\s+/g,' ').substring(0,80);},
    clr:function(){var el=document.querySelector('[data-xb]');if(el){el.style.outline=el._o||'';el.style.outlineOffset=el._oo||'';el.removeAttribute('data-xb');}},
    nav:function(dir){var els=this.all();if(!els.length)return'EMPTY';if(!this.cur||!document.body.contains(this.cur)||!this.vis(this.cur)){this.cur=els.reduce(function(a,b){var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return(ra.top*3+ra.left)<(rb.top*3+rb.left)?a:b;});return this.hl(this.cur);}var cr=this.cur.getBoundingClientRect(),cx=cr.left+cr.width/2,cy=cr.top+cr.height/2;var cands=els.filter(function(el){if(el===window._xb.cur)return false;var r=el.getBoundingClientRect(),ex=r.left+r.width/2,ey=r.top+r.height/2;if(dir==='right')return ex>cx+8;if(dir==='left')return ex<cx-8;if(dir==='down')return ey>cy+8;if(dir==='up')return ey<cy-8;return false;});if(!cands.length)return'SCROLL_'+dir;var best=cands.reduce(function(a,b){var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect(),ax=ra.left+ra.width/2,ay=ra.top+ra.height/2,bx=rb.left+rb.width/2,by=rb.top+rb.height/2,sa,sb;if(dir==='right'||dir==='left'){sa=Math.abs(ax-cx)+Math.abs(ay-cy)*2.5;sb=Math.abs(bx-cx)+Math.abs(by-cy)*2.5;}else{sa=Math.abs(ay-cy)+Math.abs(ax-cx)*2.5;sb=Math.abs(by-cy)+Math.abs(bx-cx)*2.5;}return sa<sb?a:b;});this.cur=best;return this.hl(best);},
    playVideo:function(v){try{v.muted=false;v.removeAttribute('muted');}catch(e){}try{v.play();}catch(e){}try{var rf=v.requestFullscreen||v.webkitRequestFullscreen||v.webkitEnterFullscreen||v.mozRequestFullScreen;if(rf)rf.call(v);}catch(e){}return'VIDEO_PLAY';},
    act:function(){if(!this.cur)return'NONE';var el=this.cur;this.clr();this.cur=null;var tag=el.tagName.toLowerCase();if(tag==='input'||tag==='textarea'||tag==='select'){el.focus();var r2=el.getBoundingClientRect();var ph2=el.placeholder||el.getAttribute('aria-label')||el.name||'Поле ввода';return'INPUT:'+Math.round(r2.left+r2.width/2)+':'+Math.round(r2.top+r2.height/2)+':'+ph2.substring(0,40);}var inp=el.querySelector('input:not([type="hidden"]),textarea');if(inp&&window._xb.vis(inp)){inp.focus();var ri=inp.getBoundingClientRect();var phi=inp.placeholder||inp.getAttribute('aria-label')||inp.name||'Поле ввода';return'INPUT:'+Math.round(ri.left+ri.width/2)+':'+Math.round(ri.top+ri.height/2)+':'+phi.substring(0,40);}if(tag==='video'){var src=el.currentSrc||el.src||'';if(src&&/\.(mp4|m3u8|webm|mkv|mpd)/i.test(src)&&!src.startsWith('blob:'))return'VIDEO:'+src;return this.playVideo(el);}if(tag==='audio'){var src2=el.currentSrc||el.src||'';if(src2&&!src2.startsWith('blob:'))return'AUDIO:'+src2;if(el.paused)el.play();else el.pause();return'AUDIO_TOGGLE';}var r=el.getBoundingClientRect(),mx=r.left+r.width/2,my=r.top+r.height/2;['mousedown','mouseup','click'].forEach(function(evt){el.dispatchEvent(new MouseEvent(evt,{bubbles:true,cancelable:true,clientX:mx,clientY:my,view:window}));});return el.href||el.getAttribute('href')||el.textContent.trim().substring(0,60)||'CLICKED';},
    skip:function(doClick){var playing=Array.from(document.querySelectorAll('video')).some(function(v){return!v.paused&&!v.ended&&v.readyState>2;});if(!playing)return 0;var found=[];['.ytp-skip-ad-button','.ytp-ad-skip-button','.ytp-ad-skip-button-modern','[class*="skip-ad"]','[class*="SkipAd"]','[id*="skip-ad"]','.videoAdUiSkipButton'].forEach(function(s){try{Array.from(document.querySelectorAll(s)).filter(function(el){return window._xb.vis(el);}).forEach(function(el){found.push(el);});}catch(e){}});if(!found.length){var texts=['пропустить','skip ad','skip ads','skip','пропустить рекламу'];Array.from(document.querySelectorAll('button,[role="button"]')).filter(function(el){var t=el.textContent.trim().toLowerCase();return texts.some(function(s){return t===s||t.indexOf(s)===0;})&&window._xb.vis(el);}).forEach(function(el){found.push(el);});}if(doClick&&found.length){found[0].click();return-1;}return found.length;},
    clickAt:function(x,y){var el=document.elementFromPoint(x,y);if(!el)return'MISS';var v=el.closest&&el.closest('video');if(v||el.tagName==='VIDEO'){return this.playVideo(v||el);}['mousedown','mouseup','click'].forEach(function(evt){el.dispatchEvent(new MouseEvent(evt,{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window}));});var tag=el.tagName.toLowerCase();if(tag==='input'||tag==='textarea'||tag==='select'){el.focus();return'INPUT';}var inp=el.querySelector('input,textarea');if(inp){inp.focus();return'INPUT';}return'CLICKED';},
    linkAt:function(x,y){var el=document.elementFromPoint(x,y);if(!el)return false;var tag=el.tagName.toLowerCase();if(['a','button','input','textarea','select','video','audio'].indexOf(tag)>=0)return true;if(el.onclick||el.getAttribute('role')==='button')return true;return getComputedStyle(el).cursor==='pointer';},
    injectText:function(x,y,text){var el=document.elementFromPoint(x,y)||document.activeElement;if(!el||(el.tagName!=='INPUT'&&el.tagName!=='TEXTAREA')){el=document.querySelector('input:not([type="hidden"]):not([disabled]),textarea');}if(!el)return false;el.focus();try{var proto=el.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;var ns=Object.getOwnPropertyDescriptor(proto,'value');if(ns&&ns.set)ns.set.call(el,text);else el.value=text;}catch(e){el.value=text;}el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));['keydown','keypress','keyup'].forEach(function(t){el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));});setTimeout(function(){var form=el.closest('form');if(form){try{form.requestSubmit?form.requestSubmit():form.submit();}catch(e){}}var btn=document.querySelector('[type="submit"],.search__button,button[data-type="search"],input[type="submit"]');if(btn)btn.click();},80);return true;},
    results:{
      idx:-1,list:[],
      sels:['.organic','.serp-item','.OrganicResult','.MjjYud','.g:not(.g-blk)','[data-hveid]','.video-snippet','article'].join(','),
      collect:function(){try{return Array.from(document.querySelectorAll(this.sels)).filter(function(el){var r=el.getBoundingClientRect();return r.height>40&&r.width>80&&r.bottom>0&&r.top<window.innerHeight*3;});}catch(e){return[];}},
      hl:function(el){document.querySelectorAll('[data-xbr]').forEach(function(e){e.style.outline=e._xbRO||'';e.removeAttribute('data-xbr');});if(!el)return'';el._xbRO=el.style.outline||'';el.style.outline='3px solid #00FF88';el.setAttribute('data-xbr','1');el.scrollIntoView({block:'center',behavior:'smooth'});var t=el.querySelector('h2,h3,.title,a')||el;return(t.textContent||'').trim().replace(/\s+/g,' ').substring(0,80);},
      clear:function(){document.querySelectorAll('[data-xbr]').forEach(function(e){e.style.outline=e._xbRO||'';e.removeAttribute('data-xbr');});this.idx=-1;this.list=[];},
      enter:function(){this.list=this.collect();this.idx=-1;return this.list.length;},
      move:function(dir){if(!this.list.length)this.list=this.collect();if(!this.list.length)return'EMPTY';this.idx+=dir;if(this.idx<0)this.idx=0;if(this.idx>=this.list.length)this.idx=this.list.length-1;return this.hl(this.list[this.idx]);},
      click:function(){if(this.idx<0||!this.list[this.idx])return false;var el=this.list[this.idx];this.clear();var link=el.querySelector('a[href]')||el;['mousedown','mouseup','click'].forEach(function(t){link.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));});return true;}
    },
    // ── Page cleaner: cookie banners, GDPR/notify prompts, sticky overlays, ads ──
    AD:['[id*="banner-ad"]','[class*="banner-ad"]','[id^="ad-"]','[class^="ad-"]','[class*="-ads"]','[class*="adsbox"]','ins.adsbygoogle','[id*="google_ads"]','iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]','iframe[src*="/ads/"]','[class*="advert"]','[id*="advert"]','[aria-label="Advertisement"]','.ytp-ad-overlay-container','#player-ads','.popunder','[class*="popup-overlay"]','[class*="newsletter"]','[class*="subscribe-popup"]','[id*="onesignal"]','[class*="push-prompt"]'].join(','),
    COOKIE_WORDS:['accept all','accept cookies','i accept','agree','i agree','got it','allow all','принять все','принять','согласен','соглашаюсь','хорошо','разрешить','ok','понятно'],
    clean:function(){
      if(!window._xbClean)return;
      var n=0;
      try{Array.from(document.querySelectorAll(this.AD)).forEach(function(el){if(el&&el.offsetParent!==null){el.style.setProperty('display','none','important');n++;}});}catch(e){}
      // Auto-dismiss cookie/consent banners by clicking the accept button
      try{
        var btns=Array.from(document.querySelectorAll('button,a[role="button"],[class*="consent"] button,[id*="cookie"] button,[class*="cookie"] button'));
        for(var i=0;i<btns.length;i++){var b=btns[i];var t=(b.textContent||'').trim().toLowerCase();if(t.length<25&&window._xb.COOKIE_WORDS.indexOf(t)>=0&&window._xb.vis(b)){b.click();n++;break;}}
      }catch(e){}
      // Remove full-screen blocking overlays / modal popups (fixed, high z-index, covers page)
      try{
        Array.from(document.querySelectorAll('div,section,aside')).forEach(function(el){
          var s=getComputedStyle(el);if(s.position!=='fixed'&&s.position!=='sticky')return;
          var r=el.getBoundingClientRect();var big=r.width>=window.innerWidth*0.6&&r.height>=window.innerHeight*0.6;
          var z=parseInt(s.zIndex)||0;
          if(big&&z>=1000&&(s.backgroundColor.indexOf('rgba')>=0||el.className.toString().match(/modal|overlay|popup|backdrop|interstitial|paywall/i))){el.style.setProperty('display','none','important');n++;}
        });
      }catch(e){}
      // Unlock scroll that popups often disable
      try{if(getComputedStyle(document.body).overflow==='hidden'){document.body.style.setProperty('overflow','auto','important');document.documentElement.style.setProperty('overflow','auto','important');}}catch(e){}
      if(n&&typeof XenomBridge!=='undefined'){try{XenomBridge.onBlocked(n);}catch(e){}}
      return n;
    },
    bigVideo:function(){var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){var v=vs[i];if(v.paused||v.ended||v.readyState<2)continue;var r=v.getBoundingClientRect();if((r.width/window.innerWidth)*(r.height/window.innerHeight)>0.25)return true;}return false;}
  };
  // Block popups: neutralise window.open
  try{window.open=function(){return null;};}catch(e){}
  if(window._xbObs){window._xbObs.disconnect();}
  window._xbObs=new MutationObserver(function(){if(window._xbCleanT)return;window._xbCleanT=setTimeout(function(){window._xbCleanT=null;window._xb.clean();},400);});
  window._xbObs.observe(document.documentElement,{subtree:true,childList:true});
  window._xb.clean();
})();
""".trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
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
            setSupportZoom(true); builtInZoomControls=true; displayZoomControls=false
            mediaPlaybackRequiresUserGesture=false
            // Block popups opening new windows automatically
            javaScriptCanOpenWindowsAutomatically=false
            setSupportMultipleWindows(false)
            cacheMode=WebSettings.LOAD_DEFAULT
            userAgentString=if (desktopMode) UA_DESKTOP else UA_MOBILE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onBlocked(n: Int) { handler.post { blockedCount += n } }
        }, "XenomBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (!adBlockEnabled) return null
                val host = req.url.host ?: return null
                if (adDomains.any { host == it || host.endsWith(".$it") }) {
                    blockedCount++
                    return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                }
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                // Only direct media files open in the dedicated player; streaming pages play in-page
                val path = req.url.path?.lowercase() ?: ""
                if (videoExts.any { path.endsWith(it) }) { openInPlayer(url, false); return true }
                if (audioExts.any { path.endsWith(it) }) { openInPlayer(url, true); return true }
                // Block non-http(s) schemes (intent://, market://, app deep-links from ads)
                if (!url.startsWith("http")) return true
                return false
            }
            override fun onPageStarted(view: WebView, url: String, fav: Bitmap?) {
                etUrl.setText(shortUrl(url)); updateBookmarkBtn(url)
                if (mode != Mode.SCROLL) exitToScroll()
                progressBar.visibility = View.VISIBLE; progressBar.progress = 8
                // Enable cleaning for this page
                view.evaluateJavascript("window._xbClean=true;", null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                injectJs(); updateNavBtns(); etUrl.setText(shortUrl(url))
                progressBar.visibility = View.GONE
                HistoryManager.add(this@MainActivity, view.title ?: url, url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                progressBar.progress = progress
                progressBar.visibility = if (progress >= 100) View.GONE else View.VISIBLE
                if (progress > 30) injectJs()  // clean early
            }
            // Block popups that try to open new windows
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (isUserGesture) {
                    // A real click that wants a new tab: load in the same view via the href transport
                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    val tmp = WebView(this@MainActivity)
                    tmp.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                            webView.loadUrl(r.url.toString()); tmp.destroy(); return true
                        }
                    }
                    transport?.webView = tmp
                    resultMsg.sendToTarget()
                } else {
                    handler.post { hint("⛔ Всплывающее окно заблокировано") }
                }
                return true
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; customViewCb = callback
                navBar.visibility = View.GONE; statusBar.visibility = View.GONE
                cursorView.visibility = View.GONE
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
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }  // block camera/mic prompts
        }
        // Save/handle direct downloads
        webView.setDownloadListener { url, _, _, _, _ ->
            if (videoExts.any { url.lowercase().contains(it) }) openInPlayer(url, false)
            else if (audioExts.any { url.lowercase().contains(it) }) openInPlayer(url, true)
            else { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
        }
    }

    private fun injectJs() = webView.evaluateJavascript(INJECT_JS, null)
    private fun shortUrl(u: String) = u.removePrefix("https://").removePrefix("http://").removePrefix("www.")

    private fun setMode(m: Mode) {
        mode = m
        when (m) {
            Mode.SCROLL -> {
                ivModeIcon.setImageResource(R.drawable.ic_mode_scroll)
                hint("↑↓ листать  •  ← элементы  •  → результаты  •  ОК — выбор  •  удержать ОК — курсор")
                cursorView.visibility = View.GONE
            }
            Mode.LINK   -> ivModeIcon.setImageResource(R.drawable.ic_mode_link)
            Mode.RESULTS -> ivModeIcon.setImageResource(R.drawable.ic_mode_results)
            Mode.CURSOR -> {
                ivModeIcon.setImageResource(R.drawable.ic_mode_cursor)
                hint("⊚ Курсор  •  стрелки — движение  •  ОК — нажать  •  НАЗАД — выход")
                cursorView.visibility = View.VISIBLE
            }
        }
    }

    private fun enterElements() {
        setMode(Mode.LINK)
        webView.evaluateJavascript("window._xb?window._xb.nav('down'):'EMPTY'") { r ->
            val lbl = r?.trim()?.removeSurrounding("\"") ?: ""
            when {
                lbl == "EMPTY" -> { hint("Нет элементов на странице"); handler.postDelayed({setMode(Mode.SCROLL)}, 1200) }
                lbl.startsWith("SCROLL_") -> setMode(Mode.SCROLL)
                else -> hint("🔗 $lbl  •  стрелки — навигация  •  ОК — открыть  •  НАЗАД — выход")
            }
        }
    }

    private fun enterResults() {
        webView.evaluateJavascript("window._xb?window._xb.results.enter():0") { r ->
            val count = r?.trim()?.toIntOrNull() ?: 0
            if (count > 0) { setMode(Mode.RESULTS); moveResults(1) }
            else { hint("Результатов не найдено"); webView.scrollBy(0, 250) }
        }
    }

    private fun moveResults(dir: Int) {
        webView.evaluateJavascript("window._xb?window._xb.results.move($dir):'EMPTY'") { r ->
            val title = r?.trim()?.removeSurrounding("\"") ?: "EMPTY"
            if (title == "EMPTY") { exitToScroll(); hint("Конец результатов") }
            else hint("📋 $title  •  ОК — открыть  •  НАЗАД — выход")
        }
    }

    private fun exitToScroll() {
        webView.evaluateJavascript("if(window._xb){window._xb.clr();if(window._xb.results)window._xb.results.clear();}", null)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        setMode(Mode.SCROLL)
    }

    private fun setupCursor() { webFrame.doOnLayout { cursorX=it.width/2f; cursorY=it.height/2f; updateCursorPos() } }
    private fun updateCursorPos() { cursorView.translationX=cursorX-cursorView.width/2f; cursorView.translationY=cursorY-cursorView.height/2f }

    private fun moveCursor(dx: Int, dy: Int) {
        // Auto-scroll when cursor reaches the edges
        if (cursorY + dy > webFrame.height - 60) webView.scrollBy(0, 120)
        if (cursorY + dy < 60) webView.scrollBy(0, -120)
        cursorX = (cursorX+dx).coerceIn(0f, webFrame.width.toFloat())
        cursorY = (cursorY+dy).coerceIn(0f, webFrame.height.toFloat())
        updateCursorPos()
        webView.evaluateJavascript("window._xb?window._xb.linkAt($cursorX,$cursorY):false") { r ->
            cursorView.alpha = if (r?.trim() == "true") 1f else 0.6f
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
            val res = r?.trim()?.removeSurrounding("\"")
            if (res == "INPUT") handler.postDelayed({ showKeyboard() }, 300)
            else { webView.isFocusable = false; webView.isFocusableInTouchMode = false }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Media keys / fullscreen video handling: let WebView handle when in custom (fullscreen) view
        if (customView != null && code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            webView.evaluateJavascript("try{document.exitFullscreen&&document.exitFullscreen();}catch(e){}", null)
            return true
        }
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && !etUrl.isFocused) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> { if (okDownTime < 0) okDownTime = SystemClock.elapsedRealtime(); return true }
                KeyEvent.ACTION_UP -> {
                    val held = SystemClock.elapsedRealtime() - okDownTime; okDownTime = -1L
                    return if (held >= LONG_PRESS_MS && mode != Mode.CURSOR) { setMode(Mode.CURSOR); true }
                    else { handleOk(); true }
                }
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (etUrl.isFocused) return when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK -> {
                if (webInputMode) cancelWebInput()
                else { dismissKeyboard(); setMode(Mode.SCROLL) }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val text = etUrl.text.toString().trim()
                if (webInputMode) submitWebInput(text) else navigate(text)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
        if (panelSide.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) { panelSide.visibility = View.GONE; return true }
            return super.dispatchKeyEvent(event)
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP    -> when (mode) { Mode.SCROLL->{ if (webView.scrollY<=0) focusUrlBar() else webView.scrollBy(0,-280); true }; Mode.LINK->linkNav("up"); Mode.RESULTS->{ moveResults(-1); true }; Mode.CURSOR->{ moveCursor(0,-CURSOR_STEP); true } }
            KeyEvent.KEYCODE_DPAD_DOWN  -> when (mode) { Mode.SCROLL->{ webView.scrollBy(0,280); true }; Mode.LINK->linkNav("down"); Mode.RESULTS->{ moveResults(1); true }; Mode.CURSOR->{ moveCursor(0,CURSOR_STEP); true } }
            KeyEvent.KEYCODE_DPAD_LEFT  -> when (mode) { Mode.SCROLL->{ enterElements(); true }; Mode.LINK->linkNav("left"); Mode.RESULTS->{ exitToScroll(); true }; Mode.CURSOR->{ moveCursor(-CURSOR_STEP,0); true } }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (mode) { Mode.SCROLL->{ enterResults(); true }; Mode.LINK->linkNav("right"); Mode.RESULTS->{ webView.scrollBy(250,0); true }; Mode.CURSOR->{ moveCursor(CURSOR_STEP,0); true } }
            KeyEvent.KEYCODE_BACK -> when (mode) {
                Mode.LINK, Mode.RESULTS, Mode.CURSOR -> { exitToScroll(); true }
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
                // If an ad skip button is present, skip it; else enter element navigation
                webView.evaluateJavascript("window._xb?window._xb.skip(false):0") { r ->
                    if ((r?.trim()?.toIntOrNull() ?: 0) > 0) {
                        webView.evaluateJavascript("window._xb.skip(true)", null)
                        hint("Реклама пропущена ✓")
                    } else enterElements()
                }
            }
            Mode.LINK -> {
                webView.evaluateJavascript("window._xb?window._xb.act():'NONE'") { r ->
                    val res = r?.trim()?.removeSurrounding("\"") ?: "NONE"
                    when {
                        res.startsWith("INPUT") -> {
                            val parts = res.split(":", limit = 4)
                            val cx = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                            val cy = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                            activateWebInput(parts.getOrNull(3) ?: "Введите текст", cx, cy)
                        }
                        res.startsWith("VIDEO:") -> { setMode(Mode.SCROLL); openInPlayer(res.removePrefix("VIDEO:"), false) }
                        res.startsWith("AUDIO:") -> { setMode(Mode.SCROLL); openInPlayer(res.removePrefix("AUDIO:"), true) }
                        res == "VIDEO_PLAY" -> { setMode(Mode.SCROLL); hint("▶ Воспроизведение") }
                        else -> setMode(Mode.SCROLL)
                    }
                }
            }
            Mode.RESULTS -> webView.evaluateJavascript("window._xb?window._xb.results.click():false") { exitToScroll() }
            Mode.CURSOR -> clickAtCursor()
        }
    }

    private fun linkNav(dir: String): Boolean {
        webView.evaluateJavascript("window._xb?window._xb.nav('$dir'):'EMPTY'") { r ->
            val lbl = r?.trim()?.removeSurrounding("\"") ?: "EMPTY"
            when {
                lbl == "EMPTY" -> { exitToScroll(); hint("Нет элементов") }
                lbl.startsWith("SCROLL_") -> when (lbl.removePrefix("SCROLL_")) {
                    "up"->webView.scrollBy(0,-280); "down"->webView.scrollBy(0,280)
                    "left"->webView.scrollBy(-250,0); "right"->webView.scrollBy(250,0)
                }
                else -> hint("🔗 $lbl  •  ОК — открыть  •  НАЗАД — выход")
            }
        }
        return true
    }

    private fun focusUrlBar() {
        etUrl.requestFocus(); etUrl.setText(webView.url ?: ""); etUrl.selectAll()
        etUrl.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etUrl, InputMethodManager.SHOW_FORCED) }
        hint("Введите адрес или поисковый запрос")
    }
    private fun setupUrlBar() {
        etUrl.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) etUrl.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etUrl, InputMethodManager.SHOW_FORCED) } }
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId in listOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE)) {
                val text = etUrl.text.toString().trim()
                if (webInputMode) submitWebInput(text) else navigate(text)
                true
            } else false
        }
    }
    private fun navigate(input: String) {
        if (input.isEmpty()) return
        dismissKeyboard()
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.matches(Regex("^[\\w-]+(\\.[\\w-]+)+.*$")) && !input.contains(" ") -> "https://$input"
            else -> "https://yandex.ru/search/?text=${Uri.encode(input)}"
        }
        webView.loadUrl(url); setMode(Mode.SCROLL)
    }
    private fun dismissKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        webView.isFocusable = false; webView.isFocusableInTouchMode = false
    }
    private fun showKeyboard() {
        webView.isFocusable = true; webView.isFocusableInTouchMode = true; webView.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(webView, InputMethodManager.SHOW_FORCED)
    }

    private fun activateWebInput(fieldHint: String, cx: Float, cy: Float) {
        webInputMode = true; webInputCx = cx; webInputCy = cy
        etUrl.tag = webView.url ?: ""
        etUrl.setText(""); etUrl.hint = "Ввод: $fieldHint"
        focusUrlBar()
        hint("⌨ $fieldHint  •  введите и нажмите ОК  •  НАЗАД — отмена")
    }
    private fun submitWebInput(text: String) {
        webInputMode = false
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("window._xb?window._xb.injectText($webInputCx,$webInputCy,'$escaped'):false") {
            handler.postDelayed({ etUrl.setText(shortUrl(webView.url ?: "")); etUrl.hint = getString(R.string.search_hint); etUrl.tag = null }, 600)
        }
        dismissKeyboard(); webView.requestFocus()
    }
    private fun cancelWebInput() {
        webInputMode = false
        etUrl.setText(shortUrl(webView.url ?: "")); etUrl.hint = getString(R.string.search_hint); etUrl.tag = null
        dismissKeyboard(); webView.requestFocus()
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
        btnMenu.setOnClickListener { showMenu() }
    }

    private fun showMenu() {
        val items = arrayOf(
            "★ Закладки",
            if (desktopMode) "📱 Мобильная версия" else "🖥 Десктоп версия",
            if (adBlockEnabled) "🛡 Реклама: ВКЛ (откл.)" else "🛡 Реклама: ВЫКЛ (вкл.)",
            "🧹 Очистить рекламу/баннеры",
            "🏠 Домой (ya.ru)",
            "🗑 Очистить историю"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Меню")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { refreshBookmarks(); panelSide.visibility = View.VISIBLE }
                    1 -> { desktopMode = !desktopMode; webView.settings.userAgentString = if (desktopMode) UA_DESKTOP else UA_MOBILE; webView.reload(); Toast.makeText(this, if (desktopMode) "Десктоп режим" else "Мобильный режим", Toast.LENGTH_SHORT).show() }
                    2 -> { adBlockEnabled = !adBlockEnabled; Toast.makeText(this, if (adBlockEnabled) "Блокировка рекламы ВКЛ" else "ВЫКЛ", Toast.LENGTH_SHORT).show(); webView.reload() }
                    3 -> { webView.evaluateJavascript("window._xbClean=true;window._xb&&window._xb.clean();", null); Toast.makeText(this, "Очищено ✓", Toast.LENGTH_SHORT).show() }
                    4 -> webView.loadUrl("https://ya.ru")
                    5 -> { HistoryManager.clear(this); Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show() }
                }
            }
            .show()
    }

    private fun refreshBookmarks() {
        val items = BookmarkManager.getAll(this)
        if (rvBookmarks.layoutManager == null) rvBookmarks.layoutManager = LinearLayoutManager(this)
        rvBookmarks.adapter = BookmarkAdapter(items) { url -> webView.loadUrl(url); panelSide.visibility = View.GONE }
    }

    private fun startSkipChecker() {
        skipChecker = object : Runnable { override fun run() {
            if (mode == Mode.SCROLL && customView == null) {
                webView.evaluateJavascript("window._xb?window._xb.skip(false):0") { r ->
                    if ((r?.trim()?.toIntOrNull() ?: 0) > 0) {
                        webView.evaluateJavascript("window._xb.skip(true)", null)  // auto-skip ads
                    }
                }
                webView.evaluateJavascript("window._xb&&window._xb.clean();", null)  // keep page clean
            }
            handler.postDelayed(this, 2500)
        } }
        handler.postDelayed(skipChecker!!, 2500)
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
