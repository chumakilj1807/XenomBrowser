package com.xenombrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private lateinit var btnTabs: TextView
    private lateinit var btnMenu: ImageButton
    private lateinit var navBar: LinearLayout
    private lateinit var statusBar: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var webFrame: FrameLayout
    private lateinit var cursorView: View
    private lateinit var panelSide: LinearLayout
    private lateinit var rvBookmarks: RecyclerView

    private lateinit var suggestList: ListView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var skipChecker: Runnable? = null
    private val adDomains = mutableSetOf<String>()
    private var okDownTime = -1L
    private val LONG_PRESS_MS = 700L
    private var lastBackTime = 0L
    private var cursorX = 0f
    private var cursorY = 0f
    private val CURSOR_STEP = 14
    private var webTyping = false
    private var toolbarFocus = false
    private var toolbarIdx = 3
    private val focusRing by lazy {
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(8).toFloat()
            setStroke(dp(3), Color.parseColor("#00E5FF"))
        }
    }
    private var customView: View? = null
    private var customViewCb: WebChromeClient.CustomViewCallback? = null
    private var suggestJob: Job? = null
    private var currentUrl = "about:home"
    private var lastFailedUrl: String? = null

    private val UA_DESKTOP = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val UA_MOBILE  = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val videoExts = listOf(".mp4",".mkv",".avi",".mov",".wmv",".flv",".webm",".m3u8",".mpd")
    private val audioExts = listOf(".mp3",".aac",".ogg",".flac",".wav",".m4a",".opus")

    private val DARK_CSS = "javascript:(function(){var d='_xbdark';if(document.getElementById(d))return;var s=document.createElement('style');s.id=d;s.innerHTML='html{filter:invert(1) hue-rotate(180deg)!important;background:#111!important}img,video,picture,iframe,canvas,[style*=\"background-image\"]{filter:invert(1) hue-rotate(180deg)!important}';document.head.appendChild(s);})()"

    private val pageResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringExtra(ListPageActivity.EXTRA_URL)?.let { loadUrlOrHome(it) }
    }
    private val voiceResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) navigate(text)
    }

    private fun startVoiceSearch() {
        try {
            val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Говорите...")
            }
            voiceResult.launch(i)
        } catch (_: Exception) { Toast.makeText(this, "Голосовой ввод недоступен", Toast.LENGTH_SHORT).show() }
    }

    private val READER_JS = "javascript:(function(){try{var best=null,bs=0;document.querySelectorAll('article,main,[role=main],.article,.post,.content,#content').forEach(function(e){var t=(e.innerText||'').length;if(t>bs){bs=t;best=e;}});if(!best||bs<400){var ps=document.querySelectorAll('p');var p=null,pl=0;ps.forEach(function(e){if((e.innerText||'').length>pl){pl=(e.innerText||'').length;p=e;}});best=p?p.parentElement:document.body;}var title=document.title;var h=best.innerHTML;document.documentElement.innerHTML='<head><meta name=viewport content=\"width=device-width,initial-scale=1\"></head><body style=\"max-width:760px;margin:0 auto;padding:32px 24px;background:#15171a;color:#e6e6e6;font:19px/1.7 Georgia,serif\"><h1 style=\"font-family:-apple-system,Arial;color:#fff\">'+title+'</h1><div>'+h+'</div></body>';document.querySelectorAll('img').forEach(function(i){i.style.maxWidth='100%';i.style.height='auto';});}catch(e){}})()"

    private val INJECT_JS = """
(function(){
  if(window._xb) { window._xb.clean(); return; }
  window._xb={
    cur:null,
    vis:function(el){var s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity)<0.05)return false;var r=el.getBoundingClientRect();return r.width>1&&r.height>1&&r.right>0&&r.left<window.innerWidth&&r.bottom>0&&r.top<window.innerHeight;},
    all:function(){var sels=['a[href]:not([href^="javascript:void"])','button:not([disabled])','[role="button"]','[role="link"]','input:not([type="hidden"]):not([disabled])','textarea:not([disabled])','select:not([disabled])','video','audio','[onclick]','[tabindex]:not([tabindex="-1"])'].join(',');var seen=new WeakSet();return Array.from(document.querySelectorAll(sels)).filter(function(el){if(seen.has(el))return false;seen.add(el);return window._xb.vis(el);});},
    hl:function(el){this.clr();if(!el)return'';el._o=el.style.outline||'';el._oo=el.style.outlineOffset||'';el._bs=el.style.boxShadow||'';el._br=el.style.borderRadius||'';el.style.setProperty('outline','4px solid #00E5FF','important');el.style.setProperty('outline-offset','2px','important');el.style.setProperty('box-shadow','0 0 0 4px rgba(0,229,255,.30), 0 0 18px 5px rgba(0,229,255,.55)','important');el.style.setProperty('border-radius','6px','important');el.setAttribute('data-xb','1');el.scrollIntoView({block:'center',behavior:'smooth',inline:'nearest'});var t=el.tagName.toLowerCase();if(t==='input'||t==='textarea')return el.placeholder||el.name||'Поле ввода';if(t==='video')return'▶ Видео — ОК для просмотра';if(t==='audio')return'♪ Аудио';return(el.title||el.textContent||el.alt||el.value||el.getAttribute('href')||'').trim().replace(/\s+/g,' ').substring(0,80);},
    clr:function(){var el=document.querySelector('[data-xb]');if(el){el.style.outline=el._o||'';el.style.outlineOffset=el._oo||'';el.style.boxShadow=el._bs||'';el.style.borderRadius=el._br||'';el.removeAttribute('data-xb');}},
    nav:function(dir){var els=this.all();if(!els.length)return'EMPTY';if(!this.cur||!document.body.contains(this.cur)||!this.vis(this.cur)){this.cur=els.reduce(function(a,b){var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return(ra.top*3+ra.left)<(rb.top*3+rb.left)?a:b;});return this.hl(this.cur);}var cr=this.cur.getBoundingClientRect(),cx=cr.left+cr.width/2,cy=cr.top+cr.height/2;var cands=els.filter(function(el){if(el===window._xb.cur)return false;var r=el.getBoundingClientRect(),ex=r.left+r.width/2,ey=r.top+r.height/2;if(dir==='right')return ex>cx+8;if(dir==='left')return ex<cx-8;if(dir==='down')return ey>cy+8;if(dir==='up')return ey<cy-8;return false;});if(!cands.length)return'SCROLL_'+dir;var best=cands.reduce(function(a,b){var ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect(),ax=ra.left+ra.width/2,ay=ra.top+ra.height/2,bx=rb.left+rb.width/2,by=rb.top+rb.height/2,sa,sb;if(dir==='right'||dir==='left'){sa=Math.abs(ax-cx)+Math.abs(ay-cy)*2.5;sb=Math.abs(bx-cx)+Math.abs(by-cy)*2.5;}else{sa=Math.abs(ay-cy)+Math.abs(ax-cx)*2.5;sb=Math.abs(by-cy)+Math.abs(bx-cx)*2.5;}return sa<sb?a:b;});this.cur=best;return this.hl(best);},
    playVideo:function(v){try{v.muted=false;v.removeAttribute('muted');}catch(e){}try{v.play();}catch(e){}try{var rf=v.requestFullscreen||v.webkitRequestFullscreen||v.webkitEnterFullscreen||v.mozRequestFullScreen;if(rf)rf.call(v);}catch(e){}return'VIDEO_PLAY';},
    act:function(){if(!this.cur)return'NONE';var el=this.cur;this.clr();this.cur=null;var tag=el.tagName.toLowerCase();if(tag==='input'||tag==='textarea'||tag==='select'){el.focus();var r2=el.getBoundingClientRect();var ph2=el.placeholder||el.getAttribute('aria-label')||el.name||'Поле ввода';return'INPUT:'+Math.round(r2.left+r2.width/2)+':'+Math.round(r2.top+r2.height/2)+':'+ph2.substring(0,40);}var inp=el.querySelector('input:not([type="hidden"]),textarea');if(inp&&window._xb.vis(inp)){inp.focus();var ri=inp.getBoundingClientRect();var phi=inp.placeholder||inp.getAttribute('aria-label')||inp.name||'Поле ввода';return'INPUT:'+Math.round(ri.left+ri.width/2)+':'+Math.round(ri.top+ri.height/2)+':'+phi.substring(0,40);}if(tag==='video'){var src=el.currentSrc||el.src||'';if(src&&/\.(mp4|m3u8|webm|mkv|mpd)/i.test(src)&&!src.startsWith('blob:'))return'VIDEO:'+src;return this.playVideo(el);}if(tag==='audio'){var src2=el.currentSrc||el.src||'';if(src2&&!src2.startsWith('blob:'))return'AUDIO:'+src2;if(el.paused)el.play();else el.pause();return'AUDIO_TOGGLE';}var r=el.getBoundingClientRect(),mx=r.left+r.width/2,my=r.top+r.height/2;['mousedown','mouseup','click'].forEach(function(evt){el.dispatchEvent(new MouseEvent(evt,{bubbles:true,cancelable:true,clientX:mx,clientY:my,view:window}));});return el.href||el.getAttribute('href')||el.textContent.trim().substring(0,60)||'CLICKED';},
    skip:function(doClick){var playing=Array.from(document.querySelectorAll('video')).some(function(v){return!v.paused&&!v.ended&&v.readyState>2;});if(!playing)return 0;var found=[];['.ytp-skip-ad-button','.ytp-ad-skip-button','.ytp-ad-skip-button-modern','[class*="skip-ad"]','[class*="SkipAd"]','.videoAdUiSkipButton'].forEach(function(s){try{Array.from(document.querySelectorAll(s)).filter(function(el){return window._xb.vis(el);}).forEach(function(el){found.push(el);});}catch(e){}});if(doClick&&found.length){found[0].click();return-1;}return found.length;},
    clickAt:function(x,y){var el=document.elementFromPoint(x,y);if(!el)return'MISS';var v=el.closest&&el.closest('video');if(v||el.tagName==='VIDEO'){return this.playVideo(v||el);}['mousedown','mouseup','click'].forEach(function(evt){el.dispatchEvent(new MouseEvent(evt,{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window}));});var tag=el.tagName.toLowerCase();if(tag==='input'||tag==='textarea'||tag==='select'){el.focus();return'INPUT';}var inp=el.querySelector('input,textarea');if(inp){inp.focus();return'INPUT';}return'CLICKED';},
    linkAt:function(x,y){var el=document.elementFromPoint(x,y);if(!el)return false;var tag=el.tagName.toLowerCase();if(['a','button','input','textarea','select','video','audio'].indexOf(tag)>=0)return true;if(el.onclick||el.getAttribute('role')==='button')return true;return getComputedStyle(el).cursor==='pointer';},
    injectText:function(x,y,text){var el=document.elementFromPoint(x,y)||document.activeElement;if(!el||(el.tagName!=='INPUT'&&el.tagName!=='TEXTAREA')){el=document.querySelector('input:not([type="hidden"]):not([disabled]),textarea');}if(!el)return false;el.focus();try{var proto=el.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;var ns=Object.getOwnPropertyDescriptor(proto,'value');if(ns&&ns.set)ns.set.call(el,text);else el.value=text;}catch(e){el.value=text;}el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));['keydown','keypress','keyup'].forEach(function(t){el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));});setTimeout(function(){var form=el.closest('form');if(form){try{form.requestSubmit?form.requestSubmit():form.submit();}catch(e){}}var btn=document.querySelector('[type="submit"],.search__button,button[data-type="search"],input[type="submit"]');if(btn)btn.click();},80);return true;},
    results:{
      idx:-1,list:[],
      sels:['.organic','.serp-item','.OrganicResult','.MjjYud','.g:not(.g-blk)','[data-hveid]','.video-snippet','article'].join(','),
      collect:function(){try{return Array.from(document.querySelectorAll(this.sels)).filter(function(el){var r=el.getBoundingClientRect();return r.height>40&&r.width>80&&r.bottom>0&&r.top<window.innerHeight*3;});}catch(e){return[];}},
      hl:function(el){document.querySelectorAll('[data-xbr]').forEach(function(e){e.style.outline=e._xbRO||'';e.style.boxShadow=e._xbRS||'';e.removeAttribute('data-xbr');});if(!el)return'';el._xbRO=el.style.outline||'';el._xbRS=el.style.boxShadow||'';el.style.setProperty('outline','4px solid #00FF88','important');el.style.setProperty('outline-offset','2px','important');el.style.setProperty('box-shadow','0 0 0 4px rgba(0,255,136,.28), 0 0 18px 5px rgba(0,255,136,.5)','important');el.setAttribute('data-xbr','1');el.scrollIntoView({block:'center',behavior:'smooth'});var t=el.querySelector('h2,h3,.title,a')||el;return(t.textContent||'').trim().replace(/\s+/g,' ').substring(0,80);},
      clear:function(){document.querySelectorAll('[data-xbr]').forEach(function(e){e.style.outline=e._xbRO||'';e.style.boxShadow=e._xbRS||'';e.removeAttribute('data-xbr');});this.idx=-1;this.list=[];},
      enter:function(){this.list=this.collect();this.idx=-1;return this.list.length;},
      move:function(dir){if(!this.list.length)this.list=this.collect();if(!this.list.length)return'EMPTY';this.idx+=dir;if(this.idx<0)this.idx=0;if(this.idx>=this.list.length)this.idx=this.list.length-1;return this.hl(this.list[this.idx]);},
      click:function(){if(this.idx<0||!this.list[this.idx])return false;var el=this.list[this.idx];this.clear();var link=el.querySelector('a[href]')||el;['mousedown','mouseup','click'].forEach(function(t){link.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));});return true;}
    },
    AD:['[id*="banner-ad"]','[class*="banner-ad"]','[id^="ad-"]','[class^="ad-"]','[class*="-ads"]','[class*="adsbox"]','ins.adsbygoogle','[id*="google_ads"]','iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]','iframe[src*="/ads/"]','[class*="advert"]','[id*="advert"]','[aria-label="Advertisement"]','.ytp-ad-overlay-container','#player-ads','.popunder','[class*="popup-overlay"]','[class*="newsletter"]','[class*="subscribe-popup"]','[id*="onesignal"]','[class*="push-prompt"]'].join(','),
    COOKIE_WORDS:['accept all','accept cookies','i accept','agree','i agree','got it','allow all','принять все','принять','согласен','соглашаюсь','хорошо','разрешить','ok','понятно'],
    clean:function(){
      if(!window._xbClean)return 0;var n=0;
      try{Array.from(document.querySelectorAll(this.AD)).forEach(function(el){if(el&&el.offsetParent!==null){el.style.setProperty('display','none','important');n++;}});}catch(e){}
      try{var btns=Array.from(document.querySelectorAll('button,a[role="button"],[class*="consent"] button,[id*="cookie"] button,[class*="cookie"] button'));for(var i=0;i<btns.length;i++){var b=btns[i];var t=(b.textContent||'').trim().toLowerCase();if(t.length<25&&window._xb.COOKIE_WORDS.indexOf(t)>=0&&window._xb.vis(b)){b.click();n++;break;}}}catch(e){}
      try{Array.from(document.querySelectorAll('div,section,aside')).forEach(function(el){var s=getComputedStyle(el);if(s.position!=='fixed'&&s.position!=='sticky')return;var r=el.getBoundingClientRect();var big=r.width>=window.innerWidth*0.6&&r.height>=window.innerHeight*0.6;var z=parseInt(s.zIndex)||0;if(big&&z>=1000&&(s.backgroundColor.indexOf('rgba')>=0||el.className.toString().match(/modal|overlay|popup|backdrop|interstitial|paywall/i))){el.style.setProperty('display','none','important');n++;}});}catch(e){}
      try{if(getComputedStyle(document.body).overflow==='hidden'){document.body.style.setProperty('overflow','auto','important');document.documentElement.style.setProperty('overflow','auto','important');}}catch(e){}
      return n;
    },
    bigVideo:function(){var vs=document.querySelectorAll('video');var best=null,bc=0;for(var i=0;i<vs.length;i++){var v=vs[i];var r=v.getBoundingClientRect();if(r.width<2||r.height<2)continue;var c=(r.width*r.height)/(window.innerWidth*window.innerHeight);if(c>bc){bc=c;best=v;}}return (best&&bc>0.15)?best:null;},
    playBig:function(){var v=this.bigVideo();if(!v)return'NONE';var s=v.currentSrc||v.src||'';if(s&&!/^blob:/.test(s)&&/\.(mp4|m3u8|webm|mkv|mpd)(\?|#|$)/i.test(s))return'PLAYER:'+s;this.playVideo(v);return'VIDEO_PLAY';},
    vseek:function(n){var v=this.bigVideo()||document.querySelector('video');if(!v)return'NO';if(n==='pp'){if(v.paused)v.play();else v.pause();return'PP';}try{var d=v.duration||1e9;v.currentTime=Math.max(0,Math.min(d,v.currentTime+(n*1)));}catch(e){}return'SEEK';}
  };
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
        Prefs.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews(); buildOverlays(); loadAdDomains(); setupWebView(); setupUrlBar()
        setupButtons(); setupCursor(); startSkipChecker()
        tabs.add(webView); tabIndex = 0; updateTabButton()  // the XML WebView is tab 0
        // Restore last session if enabled, else open the configured home page
        val restore = Prefs.getBool(this, "restore", false)
        val last = Prefs.get(this, "last_session", "")
        loadUrlOrHome(if (restore && last.isNotBlank()) last else Prefs.homeUrl(this))
        setMode(Mode.SCROLL)
    }

    private fun bindViews() {
        webView=findViewById(R.id.web_view); etUrl=findViewById(R.id.et_url)
        tvStatus=findViewById(R.id.tv_status); ivModeIcon=findViewById(R.id.iv_mode_icon)
        btnBack=findViewById(R.id.btn_back); btnForward=findViewById(R.id.btn_forward)
        btnReload=findViewById(R.id.btn_reload); btnBookmarkAdd=findViewById(R.id.btn_bookmark_add)
        btnTabs=findViewById(R.id.btn_tabs); btnMenu=findViewById(R.id.btn_menu); navBar=findViewById(R.id.nav_bar)
        statusBar=findViewById(R.id.status_bar); progressBar=findViewById(R.id.progress_bar)
        webFrame=findViewById(R.id.web_frame); cursorView=findViewById(R.id.cursor_view)
        panelSide=findViewById(R.id.panel_side); rvBookmarks=findViewById(R.id.rv_bookmarks)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Build the search-suggestions dropdown and find-in-page bar programmatically. */
    private fun buildOverlays() {
        suggestList = ListView(this).apply {
            setBackgroundColor(Color.parseColor("#161B22"))
            divider = null; visibility = View.GONE; elevation = dp(12).toFloat()
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280), Gravity.TOP)
        }
        webFrame.addView(suggestList)

        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#21262D")); setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE; elevation = dp(12).toFloat()
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        findInput = EditText(this).apply {
            hint = "Поиск по странице"; setHintTextColor(Color.parseColor("#4A5568")); setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0D1117")); setPadding(dp(12), dp(8), dp(12), dp(8)); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine()
            setOnEditorActionListener { _, _, _ -> webView.findAllAsync(text.toString()); true }
        }
        findCount = TextView(this).apply { setTextColor(Color.parseColor("#8B949E")); textSize = 13f; setPadding(dp(10),0,dp(10),0) }
        val prev = findBtn("▲") { webView.findNext(false) }
        val next = findBtn("▼") { webView.findNext(true) }
        val close = findBtn("✕") { hideFindBar() }
        findBar.addView(findInput); findBar.addView(findCount); findBar.addView(prev); findBar.addView(next); findBar.addView(close)
        webFrame.addView(findBar)
    }
    private fun findBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#30363D"))
        minWidth = dp(44); val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.leftMargin = dp(4); layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun setupWebView() = configureWebView(webView)

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled=true; domStorageEnabled=true; databaseEnabled=true
            loadWithOverviewMode=true; useWideViewPort=true
            setSupportZoom(true); builtInZoomControls=true; displayZoomControls=false
            mediaPlaybackRequiresUserGesture=false
            javaScriptCanOpenWindowsAutomatically=false
            setSupportMultipleWindows(true)
            cacheMode=WebSettings.LOAD_DEFAULT
            userAgentString=if (Prefs.desktopMode(this@MainActivity)) UA_DESKTOP else UA_MOBILE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface fun onSearch(q: String) { handler.post { navigate(q) } }
            @android.webkit.JavascriptInterface fun onOpen(url: String) { handler.post { loadUrlOrHome(url) } }
            @android.webkit.JavascriptInterface fun onRetry() { handler.post { lastFailedUrl?.let { loadUrlOrHome(it) } } }
        }, "XenomBridge")

        wv.setFindListener { active, total, _ -> if (wv === webView) findCount.text = if (total == 0) "0/0" else "${active + 1}/$total" }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (!Prefs.adBlock(this@MainActivity)) return null
                val host = req.url.host ?: return null
                if (adDomains.any { host == it || host.endsWith(".$it") })
                    return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                val path = req.url.path?.lowercase() ?: ""
                if (videoExts.any { path.endsWith(it) }) { openInPlayer(url, false); return true }
                if (audioExts.any { path.endsWith(it) }) { openInPlayer(url, true); return true }
                if (!url.startsWith("http") && !url.startsWith("about")) return true
                return false
            }
            override fun onPageStarted(view: WebView, url: String, fav: Bitmap?) {
                if (view === webView) {
                    currentUrl = url; etUrl.setText(shortUrl(url)); updateBookmarkBtn(url)
                    if (mode != Mode.SCROLL) exitToScroll()
                    progressBar.visibility = View.VISIBLE; progressBar.progress = 8
                }
                view.evaluateJavascript("window._xbClean=${Prefs.getBool(this@MainActivity,"popups",true)};", null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (view === webView) {
                    currentUrl = if (isHome(url)) "about:home" else url
                    updateNavBtns(); etUrl.setText(shortUrl(url)); progressBar.visibility = View.GONE; lastFailedUrl = null
                }
                view.evaluateJavascript(INJECT_JS, null)
                if (!isHome(url) && !url.startsWith("data:text/html")) HistoryManager.add(this@MainActivity, view.title ?: url, url)
                if (Prefs.darkMode(this@MainActivity) && !isHome(url)) view.loadUrl(DARK_CSS)
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame && view === webView) {
                    lastFailedUrl = req.url.toString()
                    view.loadDataWithBaseURL(null, errorPage(req.url.toString(), err.description?.toString() ?: "Ошибка соединения"), "text/html", "UTF-8", null)
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val idx = tabs.indexOf(view)
                if (idx < 0) return true
                val dead = if (view === webView) currentUrl else "about:home"
                try { webFrame.removeView(view) } catch (_: Exception) {}
                try { view.destroy() } catch (_: Exception) {}
                val fresh = createWebView()
                tabs[idx] = fresh
                if (idx == tabIndex) { webView = fresh; fresh.visibility = View.VISIBLE; handler.postDelayed({ loadUrlOrHome(if (dead == "about:home") "about:home" else dead) }, 200) }
                else fresh.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Вкладка перезагружена после сбоя", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                if (view !== webView) return
                progressBar.progress = progress
                progressBar.visibility = if (progress >= 100) View.GONE else View.VISIBLE
                if (progress > 30) injectJs()
            }
            override fun onReceivedTitle(view: WebView, title: String?) { if (view === webView) updateTabButton() }
            // A user-clicked _blank link opens a genuine new tab
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (isUserGesture) {
                    val newWv = createWebView()
                    tabs.add(newWv); newWv.visibility = View.GONE
                    (resultMsg.obj as? WebView.WebViewTransport)?.webView = newWv
                    resultMsg.sendToTarget()
                    switchTab(tabs.size - 1)
                } else handler.post { hint("⛔ Всплывающее окно заблокировано") }
                return true
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; customViewCb = callback
                navBar.visibility = View.GONE; statusBar.visibility = View.GONE; cursorView.visibility = View.GONE
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                webFrame.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                webView.visibility = View.GONE
            }
            override fun onHideCustomView() {
                customView?.let { webFrame.removeView(it) }; customView = null
                customViewCb?.onCustomViewHidden()
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                webView.visibility = View.VISIBLE; navBar.visibility = View.VISIBLE; statusBar.visibility = View.VISIBLE
            }
            override fun onConsoleMessage(msg: ConsoleMessage?) = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        wv.setDownloadListener { url, _, contentDisp, mime, _ -> startDownload(url, contentDisp, mime) }
    }

    // ── Tabs ────────────────────────────────────────────────────────────────
    private val tabs = mutableListOf<WebView>()
    private var tabIndex = 0

    private fun createWebView(): WebView {
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFocusable = false; isFocusableInTouchMode = false
        }
        webFrame.addView(wv, 0)  // below cursor/suggest/find overlays
        configureWebView(wv)
        return wv
    }

    private fun newTab(url: String) {
        val wv = createWebView()
        tabs.add(wv)
        switchTab(tabs.size - 1)
        loadUrlOrHome(url)
    }

    private fun switchTab(i: Int) {
        if (i < 0 || i >= tabs.size) return
        webView.visibility = View.GONE
        tabIndex = i
        webView = tabs[i]
        webView.visibility = View.VISIBLE
        currentUrl = webView.url?.let { if (isHome(it)) "about:home" else it } ?: "about:home"
        etUrl.setText(shortUrl(webView.url ?: "")); updateNavBtns(); updateBookmarkBtn(webView.url ?: ""); updateTabButton()
        setMode(Mode.SCROLL)
    }

    private fun closeTab(i: Int) {
        if (i < 0 || i >= tabs.size) return
        if (tabs.size == 1) { loadUrlOrHome("about:home"); return }  // never close the last tab
        val wv = tabs.removeAt(i)
        try { webFrame.removeView(wv) } catch (_: Exception) {}
        try { wv.destroy() } catch (_: Exception) {}
        tabIndex = i.coerceAtMost(tabs.size - 1)
        webView = tabs[tabIndex]
        webView.visibility = View.VISIBLE
        etUrl.setText(shortUrl(webView.url ?: "")); updateNavBtns(); updateTabButton(); setMode(Mode.SCROLL)
    }

    private fun updateTabButton() { btnTabs.text = tabs.size.toString() }

    private fun showTabSwitcher() {
        val labels = tabs.mapIndexed { i, wv ->
            val t = (wv.title ?: wv.url ?: "Новая вкладка").let { if (it.contains("home.xenom")) "Стартовая" else it }
            (if (i == tabIndex) "▶ " else "   ") + t.take(40)
        }.toMutableList()
        labels.add("➕ Новая вкладка")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Вкладки (${tabs.size})")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == tabs.size) newTab("about:home") else switchTab(which)
            }
            .setNeutralButton("Закрыть текущую") { _, _ -> closeTab(tabIndex) }
            .show()
    }

    private fun startDownload(url: String, contentDisp: String?, mime: String?) {
        if (videoExts.any { url.lowercase().contains(it) }) { openInPlayer(url, false); return }
        if (audioExts.any { url.lowercase().contains(it) }) { openInPlayer(url, true); return }
        try {
            val name = URLUtil.guessFileName(url, contentDisp, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setTitle(name)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            DownloadStore.add(this, DownloadStore.Item(name, url, "Downloads/$name", System.currentTimeMillis()))
            Toast.makeText(this, "Загрузка: $name", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        }
    }

    private fun errorPage(url: String, desc: String): String {
        val host = try { Uri.parse(url).host ?: url } catch (_: Exception) { url }
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{background:#0D1117;color:#C9D1D9;font-family:-apple-system,Roboto,Arial,sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;padding:24px}
.ic{font-size:64px;margin-bottom:16px}.t{font-size:24px;font-weight:700;color:#fff;margin-bottom:8px}
.d{color:#8B949E;margin-bottom:6px}.h{color:#00B4FF;margin-bottom:28px;font-size:14px}
button{background:#00B4FF;color:#fff;border:0;border-radius:12px;font-size:17px;padding:14px 36px;cursor:pointer}</style></head>
<body><div class="ic">📡</div><div class="t">Не удалось открыть страницу</div>
<div class="d">${esc(desc)}</div><div class="h">${esc(host)}</div>
<button onclick="XenomBridge.onRetry()">Повторить</button></body></html>"""
    }
    private fun esc(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")

    private fun injectJs() = webView.evaluateJavascript(INJECT_JS, null)
    private fun isHome(u: String) = u.startsWith("about") || u.startsWith("data:") || u.contains("home.xenom")
    private fun shortUrl(u: String) = if (isHome(u)) "" else u.removePrefix("https://").removePrefix("http://").removePrefix("www.")

    private fun loadUrlOrHome(url: String) {
        suggestList.visibility = View.GONE
        if (url == "about:home" || url.isBlank()) {
            currentUrl = "about:home"
            webView.loadDataWithBaseURL("https://home.xenom/", StartPage.html(this), "text/html", "UTF-8", null)
            etUrl.setText("")
        } else webView.loadUrl(url)
        setMode(Mode.SCROLL)
    }

    private fun setMode(m: Mode) {
        mode = m
        when (m) {
            Mode.SCROLL -> {
                ivModeIcon.setImageResource(R.drawable.ic_mode_scroll)
                hint("Обзор: ↑↓ листать страницу • ← элементы/ссылки • → результаты поиска • ОК — открыть видео/выбрать • удержать ОК — курсор-мышь • ↑ вверху — адрес")
                cursorView.visibility = View.GONE
            }
            Mode.LINK   -> { ivModeIcon.setImageResource(R.drawable.ic_mode_link); hint("Элементы: стрелки — переход между ссылками/полями • ОК — открыть/ввод • НАЗАД — выход") }
            Mode.RESULTS -> { ivModeIcon.setImageResource(R.drawable.ic_mode_results); hint("Результаты: ↑↓ между результатами • ОК — открыть • НАЗАД — выход") }
            Mode.CURSOR -> {
                ivModeIcon.setImageResource(R.drawable.ic_mode_cursor)
                hint("Курсор-мышь: стрелки — двигать • ОК — клик (по видео — плеер, по полю — клавиатура) • НАЗАД — выход")
                cursorView.visibility = View.VISIBLE
            }
        }
    }

    private fun enterElements() {
        setMode(Mode.LINK)
        webView.evaluateJavascript("window._xb?window._xb.nav('down'):'EMPTY'") { r ->
            val lbl = r?.trim()?.removeSurrounding("\"") ?: ""
            when {
                lbl == "EMPTY" -> { hint("Нет элементов"); handler.postDelayed({setMode(Mode.SCROLL)}, 1200) }
                lbl.startsWith("SCROLL_") -> setMode(Mode.SCROLL)
                else -> hint("🔗 $lbl • стрелки — навигация • ОК — открыть • НАЗАД — выход")
            }
        }
    }
    private fun enterResults() {
        webView.evaluateJavascript("window._xb?window._xb.results.enter():0") { r ->
            val count = r?.trim()?.toIntOrNull() ?: 0
            if (count > 0) { setMode(Mode.RESULTS); moveResults(1) } else { hint("Результатов не найдено"); webView.scrollBy(0, 250) }
        }
    }
    private fun moveResults(dir: Int) {
        webView.evaluateJavascript("window._xb?window._xb.results.move($dir):'EMPTY'") { r ->
            val title = r?.trim()?.removeSurrounding("\"") ?: "EMPTY"
            if (title == "EMPTY") { exitToScroll(); hint("Конец результатов") } else hint("📋 $title • ОК — открыть • НАЗАД — выход")
        }
    }
    private fun exitToScroll() {
        webView.evaluateJavascript("if(window._xb){window._xb.clr();if(window._xb.results)window._xb.results.clear();}", null)
        webView.isFocusable = false; webView.isFocusableInTouchMode = false
        setMode(Mode.SCROLL)
    }

    private fun setupCursor() { webFrame.doOnLayout { cursorX=it.width/2f; cursorY=it.height/2f; updateCursorPos() } }
    private fun updateCursorPos() { cursorView.translationX=cursorX-cursorView.width/2f; cursorView.translationY=cursorY-cursorView.height/2f }
    private fun moveCursor(dx: Int, dy: Int) {
        if (cursorY + dy > webFrame.height - 60) webView.scrollBy(0, 120)
        if (cursorY + dy < 60) webView.scrollBy(0, -120)
        cursorX = (cursorX+dx).coerceIn(0f, webFrame.width.toFloat())
        cursorY = (cursorY+dy).coerceIn(0f, webFrame.height.toFloat())
        updateCursorPos()
        webView.evaluateJavascript("window._xb?window._xb.linkAt($cursorX,$cursorY):false") { r -> cursorView.alpha = if (r?.trim() == "true") 1f else 0.6f }
    }
    private fun clickAtCursor() {
        webView.isFocusable = true; webView.isFocusableInTouchMode = true
        val now = SystemClock.uptimeMillis()
        val dn = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up = MotionEvent.obtain(now, now+80, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(dn)
        handler.postDelayed({ webView.dispatchTouchEvent(up); dn.recycle(); up.recycle() }, 80)
        webView.evaluateJavascript("window._xb?window._xb.clickAt($cursorX,$cursorY):'MISS'") { r ->
            when (r?.trim()?.removeSurrounding("\"")) {
                "INPUT" -> handler.postDelayed({ startWebTyping() }, 250)  // type directly into the field
                "VIDEO_PLAY" -> { setMode(Mode.SCROLL); hint("▶ Видео • ←→ ±10с • ОК пауза • НАЗАД выход") }
                else -> { webView.isFocusable = false; webView.isFocusableInTouchMode = false }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) && event.action == KeyEvent.ACTION_UP) {
            startVoiceSearch(); return true
        }
        // While typing into a web page field: let the keyboard/WebView handle keys; BACK finishes typing
        if (webTyping) {
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { stopWebTyping(); return true }
            return super.dispatchKeyEvent(event)
        }
        // Browser toolbar focus: navigate the nav buttons with a clear focus ring
        if (toolbarFocus && !etUrl.isFocused) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT  -> { moveToolbar(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveToolbar(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK -> { exitToolbar(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { activateToolbar(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> return true
            }
            return true
        }
        if (findBar.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { hideFindBar(); return true }
        }
        if (suggestList.visibility == View.VISIBLE && etUrl.isFocused) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                suggestList.requestFocus(); suggestList.setSelection(0); return true
            }
        }
        // Fullscreen video: standard-player controls (seek + pause)
        if (customView != null && event.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { webView.evaluateJavascript("window._xb&&window._xb.vseek(-10)", null); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { webView.evaluateJavascript("window._xb&&window._xb.vseek(10)", null); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { webView.evaluateJavascript("window._xb&&window._xb.vseek(-60)", null); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { webView.evaluateJavascript("window._xb&&window._xb.vseek(60)", null); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { webView.evaluateJavascript("window._xb&&window._xb.vseek('pp')", null); return true }
            }
        }
        if (customView != null && code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            webView.evaluateJavascript("try{document.exitFullscreen&&document.exitFullscreen();}catch(e){}", null); return true
        }
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && !etUrl.isFocused && findBar.visibility != View.VISIBLE) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> { if (okDownTime < 0) okDownTime = SystemClock.elapsedRealtime(); return true }
                KeyEvent.ACTION_UP -> {
                    val held = SystemClock.elapsedRealtime() - okDownTime; okDownTime = -1L
                    return if (held >= LONG_PRESS_MS && mode != Mode.CURSOR) { setMode(Mode.CURSOR); true } else { handleOk(); true }
                }
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (etUrl.isFocused) return when (code) {
            KeyEvent.KEYCODE_BACK -> { suggestList.visibility = View.GONE; dismissKeyboard(); setMode(Mode.SCROLL); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { navigate(etUrl.text.toString().trim()); true }
            else -> super.dispatchKeyEvent(event)
        }
        if (panelSide.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) { panelSide.visibility = View.GONE; return true }
            return super.dispatchKeyEvent(event)
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP    -> when (mode) { Mode.SCROLL->{ if (webView.scrollY<=0) enterToolbar() else webView.scrollBy(0,-280); true }; Mode.LINK->linkNav("up"); Mode.RESULTS->{ moveResults(-1); true }; Mode.CURSOR->{ moveCursor(0,-CURSOR_STEP); true } }
            KeyEvent.KEYCODE_DPAD_DOWN  -> when (mode) { Mode.SCROLL->{ webView.scrollBy(0,280); true }; Mode.LINK->linkNav("down"); Mode.RESULTS->{ moveResults(1); true }; Mode.CURSOR->{ moveCursor(0,CURSOR_STEP); true } }
            KeyEvent.KEYCODE_DPAD_LEFT  -> when (mode) { Mode.SCROLL->{ enterElements(); true }; Mode.LINK->linkNav("left"); Mode.RESULTS->{ exitToScroll(); true }; Mode.CURSOR->{ moveCursor(-CURSOR_STEP,0); true } }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (mode) { Mode.SCROLL->{ enterResults(); true }; Mode.LINK->linkNav("right"); Mode.RESULTS->{ webView.scrollBy(250,0); true }; Mode.CURSOR->{ moveCursor(CURSOR_STEP,0); true } }
            KeyEvent.KEYCODE_BACK -> when (mode) {
                Mode.LINK, Mode.RESULTS, Mode.CURSOR -> { exitToScroll(); true }
                Mode.SCROLL -> when {
                    webView.canGoBack() -> { webView.goBack(); true }
                    tabs.size > 1 -> { closeTab(tabIndex); true }  // close tab instead of exiting
                    else -> { val now=System.currentTimeMillis(); if (now-lastBackTime<2000) { finish(); true } else { lastBackTime=now; Toast.makeText(this,"Нажмите ещё раз для выхода",Toast.LENGTH_SHORT).show(); true } }
                }
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun handleOk() {
        when (mode) {
            Mode.SCROLL -> {
                // 1) skip ad, 2) open a page video in the fullscreen player, 3) else enter element nav
                webView.evaluateJavascript("window._xb?window._xb.skip(false):0") { r ->
                    if ((r?.trim()?.toIntOrNull() ?: 0) > 0) { webView.evaluateJavascript("window._xb.skip(true)", null); hint("Реклама пропущена ✓") }
                    else webView.evaluateJavascript("window._xb?window._xb.playBig():'NONE'") { v -> handleVideoResult(v) }
                }
            }
            Mode.LINK -> webView.evaluateJavascript("window._xb?window._xb.act():'NONE'") { r ->
                val res = r?.trim()?.removeSurrounding("\"") ?: "NONE"
                when {
                    res.startsWith("INPUT") -> startWebTyping()  // field is now focused — type directly
                    res.startsWith("VIDEO:") -> { setMode(Mode.SCROLL); openInPlayer(res.removePrefix("VIDEO:"), false) }
                    res.startsWith("AUDIO:") -> { setMode(Mode.SCROLL); openInPlayer(res.removePrefix("AUDIO:"), true) }
                    res == "VIDEO_PLAY" -> { setMode(Mode.SCROLL); hint("▶ Видео • ←→ ±10с • ОК пауза • НАЗАД выход") }
                    else -> setMode(Mode.SCROLL)
                }
            }
            Mode.RESULTS -> webView.evaluateJavascript("window._xb?window._xb.results.click():false") { exitToScroll() }
            Mode.CURSOR -> clickAtCursor()
        }
    }

    private fun handleVideoResult(v: String?) {
        val r = v?.trim()?.removeSurrounding("\"") ?: "NONE"
        when {
            r.startsWith("PLAYER:") -> { setMode(Mode.SCROLL); openInPlayer(r.removePrefix("PLAYER:"), false) }  // direct media → ExoPlayer
            r == "VIDEO_PLAY" -> hint("▶ Видео • ←→ ±10с • ОК пауза • НАЗАД выход")  // streaming → in-page fullscreen w/ seek
            else -> enterElements()
        }
    }

    private fun linkNav(dir: String): Boolean {
        webView.evaluateJavascript("window._xb?window._xb.nav('$dir'):'EMPTY'") { r ->
            val lbl = r?.trim()?.removeSurrounding("\"") ?: "EMPTY"
            when {
                lbl == "EMPTY" -> { exitToScroll(); hint("Нет элементов") }
                lbl.startsWith("SCROLL_") -> when (lbl.removePrefix("SCROLL_")) { "up"->webView.scrollBy(0,-280); "down"->webView.scrollBy(0,280); "left"->webView.scrollBy(-250,0); "right"->webView.scrollBy(250,0) }
                else -> hint("🔗 $lbl • ОК — открыть • НАЗАД — выход")
            }
        }
        return true
    }

    private fun focusUrlBar() {
        etUrl.requestFocus(); etUrl.setText(if (currentUrl=="about:home") "" else currentUrl); etUrl.selectAll()
        etUrl.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etUrl, InputMethodManager.SHOW_FORCED) }
        hint("Введите адрес или поисковый запрос • НАЗАД — отмена")
    }

    // ── Browser toolbar navigation (D-pad reaches the nav buttons with a clear focus ring) ──
    private val toolbarItems: List<View> get() = listOf(btnBack, btnForward, btnReload, etUrl, btnBookmarkAdd, btnTabs, btnMenu)

    private fun enterToolbar() {
        toolbarFocus = true; toolbarIdx = 3; highlightToolbar()
        hint("Панель: ←→ между кнопками (назад/вперёд/обновить/адрес/закладка/вкладки/меню) • ОК — нажать • ↓ — к странице")
    }
    private fun moveToolbar(d: Int) { toolbarIdx = (toolbarIdx + d).coerceIn(0, toolbarItems.size - 1); highlightToolbar() }
    private fun highlightToolbar() {
        toolbarItems.forEachIndexed { i, v -> v.foreground = if (i == toolbarIdx) focusRing else null; v.scaleX = if (i == toolbarIdx) 1.12f else 1f; v.scaleY = v.scaleX }
        val names = listOf("Назад","Вперёд","Обновить","Адрес","Закладка","Вкладки","Меню")
        hint("▸ ${names[toolbarIdx]} • ←→ выбор • ОК — нажать • ↓ — к странице")
    }
    private fun clearToolbar() { toolbarItems.forEach { it.foreground = null; it.scaleX = 1f; it.scaleY = 1f } }
    private fun exitToolbar() { toolbarFocus = false; clearToolbar(); setMode(Mode.SCROLL) }
    private fun activateToolbar() {
        val v = toolbarItems[toolbarIdx]
        if (v === etUrl) { toolbarFocus = false; clearToolbar(); focusUrlBar() }
        else { v.performClick(); if (toolbarIdx == 0 || toolbarIdx == 1) exitToolbar() }  // back/forward → return to page
    }

    private fun setupUrlBar() {
        etUrl.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) etUrl.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etUrl, InputMethodManager.SHOW_FORCED) } else suggestList.visibility = View.GONE }
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId in listOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE)) {
                navigate(etUrl.text.toString().trim()); true
            } else false
        }
        etUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (!etUrl.isFocused) return
                fetchSuggestions(s?.toString()?.trim() ?: "")
            }
        })
        suggestList.setOnItemClickListener { _, _, pos, _ ->
            val item = suggestList.adapter.getItem(pos) as? String ?: return@setOnItemClickListener
            suggestList.visibility = View.GONE; navigate(item)
        }
    }

    private fun fetchSuggestions(q: String) {
        suggestJob?.cancel()
        if (q.length < 2 || q.startsWith("http") || q.contains("/")) { suggestList.visibility = View.GONE; return }
        suggestJob = lifecycleScope.launch {
            delay(180)
            val list = SuggestionProvider.fetch(this@MainActivity, q)
            if (list.isEmpty()) { suggestList.visibility = View.GONE; return@launch }
            val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, list) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.setTextColor(Color.WHITE); v.textSize = 16f; v.setPadding(dp(22), dp(13), dp(22), dp(13))
                    v.isFocusable = true
                    return v
                }
            }
            suggestList.adapter = adapter
            suggestList.visibility = View.VISIBLE
        }
    }

    private fun navigate(input: String) {
        if (input.isEmpty()) return
        suggestList.visibility = View.GONE; dismissKeyboard()
        if (input == "about:home") { loadUrlOrHome("about:home"); return }
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.matches(Regex("^[\\w-]+(\\.[\\w-]+)+.*$")) && !input.contains(" ") -> "https://$input"
            else -> Prefs.searchUrl(this, input)
        }
        webView.loadUrl(url); setMode(Mode.SCROLL)
    }

    private fun dismissKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        webView.isFocusable = false; webView.isFocusableInTouchMode = false
    }
    // Type DIRECTLY into the focused web page field (the field was focused by act()/clickAt).
    // The on-screen keyboard routes input to the page input — no proxy through the URL bar.
    private fun startWebTyping() {
        webTyping = true
        webView.isFocusable = true; webView.isFocusableInTouchMode = true; webView.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(webView, InputMethodManager.SHOW_FORCED)
        hint("⌨ Печатайте в поле страницы • Enter — искать • НАЗАД — готово")
    }
    private fun stopWebTyping() {
        webTyping = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        webView.isFocusable = false; webView.isFocusableInTouchMode = false
        setMode(Mode.SCROLL)
    }

    private fun setupButtons() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnReload.setOnClickListener { webView.reload() }
        btnBookmarkAdd.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            if (BookmarkManager.contains(this, url)) { BookmarkManager.remove(this, url); Toast.makeText(this, "Закладка удалена", Toast.LENGTH_SHORT).show() }
            else { BookmarkManager.add(this, webView.title ?: url, url); Toast.makeText(this, "Закладка добавлена ★", Toast.LENGTH_SHORT).show() }
            updateBookmarkBtn(url)
        }
        btnTabs.setOnClickListener { showTabSwitcher() }
        btnMenu.setOnClickListener { showMenu() }
    }

    private fun showMenu() {
        val items = arrayOf(
            "➕ Новая вкладка", "🗂 Вкладки (${tabs.size})",
            "🏠 Стартовая страница", "🎤 Голосовой поиск", "📖 Режим чтения",
            "★ Закладки", "🕘 История", "⬇ Загрузки", "🔍 Поиск по странице",
            if (Prefs.desktopMode(this)) "📱 Мобильная версия" else "🖥 Десктоп-версия",
            if (Prefs.darkMode(this)) "☀ Светлые страницы" else "🌙 Тёмные страницы",
            if (Prefs.adBlock(this)) "🛡 Реклама: ВКЛ" else "🛡 Реклама: ВЫКЛ",
            "🧹 Очистить рекламу/баннеры", "⚙ Настройки"
        )
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Меню").setItems(items) { _, which ->
            when (which) {
                0 -> newTab("about:home")
                1 -> showTabSwitcher()
                2 -> loadUrlOrHome("about:home")
                3 -> startVoiceSearch()
                4 -> { webView.loadUrl(READER_JS); hint("📖 Режим чтения") }
                5 -> pageResult.launch(Intent(this, ListPageActivity::class.java).putExtra(ListPageActivity.EXTRA_MODE, ListPageActivity.MODE_BOOKMARKS))
                6 -> pageResult.launch(Intent(this, ListPageActivity::class.java).putExtra(ListPageActivity.EXTRA_MODE, ListPageActivity.MODE_HISTORY))
                7 -> pageResult.launch(Intent(this, ListPageActivity::class.java).putExtra(ListPageActivity.EXTRA_MODE, ListPageActivity.MODE_DOWNLOADS))
                8 -> showFindBar()
                9 -> { Prefs.setBool(this, "desktop", !Prefs.desktopMode(this)); webView.settings.userAgentString = if (Prefs.desktopMode(this)) UA_DESKTOP else UA_MOBILE; webView.reload() }
                10 -> { Prefs.setBool(this, "dark", !Prefs.darkMode(this)); webView.reload() }
                11 -> { Prefs.setBool(this, "adblock", !Prefs.adBlock(this)); webView.reload() }
                12 -> { webView.evaluateJavascript("window._xbClean=true;window._xb&&window._xb.clean();", null); Toast.makeText(this, "Очищено ✓", Toast.LENGTH_SHORT).show() }
                13 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        }.show()
    }

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE; findInput.requestFocus()
        findInput.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, InputMethodManager.SHOW_FORCED) }
    }
    private fun hideFindBar() {
        webView.clearMatches(); findBar.visibility = View.GONE; findInput.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun startSkipChecker() {
        skipChecker = object : Runnable { override fun run() {
            if (mode == Mode.SCROLL && customView == null) {
                webView.evaluateJavascript("window._xb?window._xb.skip(false):0") { r -> if ((r?.trim()?.toIntOrNull() ?: 0) > 0) webView.evaluateJavascript("window._xb.skip(true)", null) }
                webView.evaluateJavascript("window._xb&&window._xb.clean();", null)
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

    override fun onPause()   { super.onPause(); webView.onPause(); if (!isHome(currentUrl)) Prefs.set(this, "last_session", currentUrl) }
    override fun onResume()  {
        super.onResume(); webView.onResume()
        // Pick up changes made in Settings (search engine is read live; sync UA here)
        val wantUa = if (Prefs.desktopMode(this)) UA_DESKTOP else UA_MOBILE
        if (webView.settings.userAgentString != wantUa) { webView.settings.userAgentString = wantUa; webView.reload() }
    }
    override fun onDestroy() { super.onDestroy(); skipChecker?.let { handler.removeCallbacks(it) }; tabs.forEach { try { it.destroy() } catch (_: Exception) {} } }
}
