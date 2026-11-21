package com.sahil.chibi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Nana's own browser: one WebView that the agent drives (and that BrowserActivity can show). Logins persist in its cookies. */
object AgentBrowser {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wv: TabView? = null
    @Volatile private var loadLatch: CountDownLatch? = null
    @Volatile private var lastEls: List<String> = emptyList()
    @Volatile private var lastHash = 0
    @Volatile private var mark: Int? = null
    /** Set by the floating companion: shows the floating browser window when the agent uses the browser. */
    @Volatile var onUse: (() -> Unit)? = null

    class TabView(ctx: Context) : WebView(ctx) {
        override fun isShown(): Boolean = true
        override fun hasWindowFocus(): Boolean = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var out: Any? = null
        var err: Throwable? = null
        val l = CountDownLatch(1)
        main.post {
            try { out = block() } catch (t: Throwable) { err = t }
            l.countDown()
        }
        if (!l.await(20, TimeUnit.SECONDS)) throw RuntimeException("browser is busy")
        val e = err
        if (e != null) throw e
        return out as T
    }

    private fun relayout(ctx: Context, w: View) {
        val dm = ctx.resources.displayMetrics
        w.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        )
        w.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(ctx: Context): WebView = onMain {
        val cur = wv
        if (cur != null) cur else {
            val w = TabView(ctx.applicationContext)
            val s = w.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            s.mediaPlaybackRequiresUserGesture = false
            s.javaScriptCanOpenWindowsAutomatically = true
            s.setSupportMultipleWindows(true)
            // look like normal Chrome, not an embedded WebView (some sites refuse WebViews)
            s.userAgentString = s.userAgentString.replace("; wv)", ")").replace(" Version/4.0", "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    loadLatch?.countDown()
                }
            }
            w.webChromeClient = object : WebChromeClient() {
                // popups and target=_blank open in the same tab
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val t = WebView(view.context)
                    t.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                            view.loadUrl(r.url.toString())
                            return true
                        }
                    }
                    val tr = resultMsg.obj as WebView.WebViewTransport
                    tr.webView = t
                    resultMsg.sendToTarget()
                    return true
                }
            }
            relayout(ctx, w)
            wv = w
            w
        }
    }

    /** Takes the view back from a screen that showed it, so it keeps running headless. */
    fun release(ctx: Context) {
        onMain {
            val w = wv
            if (w != null) {
                (w.parent as? ViewGroup)?.removeView(w)
                relayout(ctx, w)
            }
        }
    }

    fun show(ctx: Context): String {
        val cb = onUse
        if (cb != null) {
            cb()
            return "Nana's browser is now floating on the user's screen (drag it by the title bar, resize with the arrow, close with the cross). If the user must act there (log in, captcha, password), tell them, then use browser_wait and browser_read."
        }
        val i = Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Nana's browser is now on the user's screen. Tell the user what they should do there (log in, solve a captcha, type a password), then use browser_wait and browser_read."
    }

    fun loadUrl(ctx: Context, u: String) {
        val w = view(ctx)
        onMain { w.loadUrl(u) }
    }

    fun canGoBack(): Boolean = wv?.canGoBack() ?: false
    fun goBackUi() { wv?.goBack() }

    private fun js(ctx: Context, code: String): String {
        val w = view(ctx)
        val l = CountDownLatch(1)
        var res = ""
        main.post {
            w.evaluateJavascript(code) { r ->
                res = r ?: ""
                l.countDown()
            }
        }
        l.await(15, TimeUnit.SECONDS)
        return res
    }

    private fun unquote(r: String): String = try {
        val v = JSONTokener(r).nextValue()
        if (v is String) v else r
    } catch (e: Exception) { r }

    fun open(ctx: Context, url: String): String {
        if (url.isBlank()) return "No url given."
        val w = view(ctx)
        val l = CountDownLatch(1)
        loadLatch = l
        val u = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        onMain { w.loadUrl(u) }
        l.await(25, TimeUnit.SECONDS)
        Thread.sleep(900)
        return snapshot(ctx)
    }

    fun snapshot(ctx: Context, offset: Int = 0): String {
        val raw = unquote(js(ctx, SNAP.replace("__OFF__", offset.coerceAtLeast(0).toString())))
        return try {
            val o = JSONObject(raw)
            val sb = StringBuilder()
            sb.append("Title: ").append(o.optString("title")).append("\nURL: ").append(o.optString("url"))
                .append("\nScroll: ").append(o.optInt("sy")).append(" of ").append(o.optInt("sh")).append(" px (screen height ").append(o.optInt("vh")).append(")")
                .append(if (o.optInt("sy") <= 2) " [at TOP]" else "").append(if (o.optInt("sy") + o.optInt("vh") >= o.optInt("sh") - 2) " [at BOTTOM]" else " [more below]")
                .append("\n\nPage text").append(if (o.optInt("off") > 0) " (from char " + o.optInt("off") + ")" else "").append(":\n").append(o.optString("text")).append("\n\nElements (* = new since your last read; \"below screen\" needs a scroll first):\n")
            val a = o.getJSONArray("els")
            val list = ArrayList<String>()
            for (i in 0 until a.length()) { sb.append(a.getString(i)).append('\n'); list.add(a.getString(i)) }
            lastEls = list
            val hash = (o.optString("url") + o.optString("text") + list.joinToString("|")).hashCode()
            val unchanged = mark != null && mark == hash
            mark = null
            lastHash = hash
            (if (unchanged) "WARNING: the page did not change after your last action. Try another element, scroll, or a different approach.\n" else "") + sb.toString()
        } catch (e: Exception) {
            "Could not read the page yet: " + raw.take(200)
        }
    }

    fun labelOf(id: Int): String {
        val s = lastEls.getOrNull(id) ?: return ""
        val k = s.indexOf(": ")
        if (k < 0) return ""
        return s.substring(k + 2).substringBefore(" -> ").substringBefore(" name=").substringBefore(" (checked)").substringBefore(" (unchecked)").trim()
    }

    private fun clickJs(id: Int): String = unquote(js(ctx0(), "(function(id){var e=document.querySelector('[data-nid=\"'+id+'\"]');if(!e)return 'missing';e.removeAttribute('target');e.scrollIntoView({block:'center'});var r=e.getBoundingClientRect();var x=r.left+r.width/2,y=r.top+r.height/2;var t=document.elementFromPoint(x,y)||e;var o={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};['pointerdown','mousedown','pointerup','mouseup'].forEach(function(n){var C=n.indexOf('pointer')===0&&window.PointerEvent?PointerEvent:MouseEvent;try{t.dispatchEvent(new C(n,o))}catch(err){}});if(e.focus)e.focus();e.click();return 'clicked';})($id)"))

    @Volatile private var appCtx: Context? = null
    private fun ctx0(): Context = appCtx ?: throw RuntimeException("browser not started")

    fun click(ctx: Context, id: Int): String {
        appCtx = ctx.applicationContext
        mark = lastHash
        var r = clickJs(id)
        if (r == "missing") {
            // the page re-rendered and dropped the numbers: find the same thing again by its label
            val lab = labelOf(id)
            if (lab.isEmpty()) return "That element is gone. Call browser_read and pick the number again."
            return clickText(ctx, lab)
        }
        settle(ctx)
        return r + "\n" + snapshot(ctx)
    }

    fun clickText(ctx: Context, text: String): String {
        appCtx = ctx.applicationContext
        if (text.isBlank()) return "No text given."
        snapshot(ctx)
        val found = unquote(js(ctx, "(function(q){q=q.toLowerCase();var all=document.querySelectorAll('[data-nid]');var best=null;for(var i=0;i<all.length;i++){var e=all[i];var t=((e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||e.title||'')+'').trim().toLowerCase();if(t===q){best=e;break;}if(!best&&t.indexOf(q)>=0)best=e;}return best?best.getAttribute('data-nid'):'';})(" + JSONObject.quote(text) + ")"))
        val id = found.toIntOrNull() ?: return "Nothing on the page contains '" + text + "'. Try browser_scroll, then read again."
        mark = lastHash
        val r = clickJs(id)
        settle(ctx)
        return "Clicked '" + text + "' (" + r + ")\n" + snapshot(ctx)
    }

    fun type(ctx: Context, id: Int, text: String, submit: Boolean): String {
        appCtx = ctx.applicationContext
        val code = "(function(id,text,submit){var e=document.querySelector('[data-nid=\"'+id+'\"]');if(!e)return 'missing';if(e.type==='password')return 'refused: password fields are off limits';e.scrollIntoView({block:'center'});e.focus();if(e.isContentEditable){document.execCommand('selectAll');document.execCommand('insertText',false,text);}else{var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,text);else e.value=text;}e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));if(submit){['keydown','keypress','keyup'].forEach(function(k){e.dispatchEvent(new KeyboardEvent(k,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}))});var f=e.form;if(f){try{f.requestSubmit()}catch(x){f.submit()}}}return 'typed';})($id," + JSONObject.quote(text) + "," + submit + ")"
        mark = lastHash
        val r = unquote(js(ctx, code))
        if (r == "missing") return "That element is gone. Call browser_read and pick the number again."
        Thread.sleep(if (submit) 1500 else 400)
        return r + "\n" + snapshot(ctx)
    }

    fun select(ctx: Context, id: Int, value: String): String {
        val code = "(function(id,v){var e=document.querySelector('[data-nid=\"'+id+'\"]');if(!e||e.tagName!=='SELECT')return 'not a dropdown';v=v.toLowerCase();var hit=-1;for(var i=0;i<e.options.length;i++){var o=e.options[i];if(o.value.toLowerCase()===v||o.text.trim().toLowerCase()===v){hit=i;break;}if(hit<0&&o.text.toLowerCase().indexOf(v)>=0)hit=i;}if(hit<0)return 'no such option';e.selectedIndex=hit;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'selected '+e.options[hit].text;})($id," + JSONObject.quote(value) + ")"
        mark = lastHash
        val r = unquote(js(ctx, code))
        Thread.sleep(400)
        return r + "\n" + snapshot(ctx)
    }

    fun scroll(ctx: Context, down: Boolean): String {
        val dir = if (down) "" else "-"
        val moved = unquote(js(ctx, "(function(){var b=scrollY;window.scrollBy(0," + dir + "Math.round(innerHeight*0.8));if(Math.abs(scrollY-b)>2)return 'page';var best=null,bh=0;document.querySelectorAll('*').forEach(function(e){var s=getComputedStyle(e);if((s.overflowY==='auto'||s.overflowY==='scroll')&&e.scrollHeight>e.clientHeight+20&&e.clientHeight>bh){best=e;bh=e.clientHeight}});if(best){var c=best.scrollTop;best.scrollBy(0," + dir + "Math.round(best.clientHeight*0.8));if(Math.abs(best.scrollTop-c)>2)return 'inner'}return 'none'})()"))
        Thread.sleep(400)
        val head = if (moved == "none") "Nothing moved: already at the " + (if (down) "bottom" else "top") + " of the page (or it does not scroll).\n" else "Scrolled " + (if (down) "down" else "up") + ".\n"
        return head + snapshot(ctx)
    }

    fun scrollTo(ctx: Context, text: String): String {
        if (text.isBlank()) return "No text given."
        val r = unquote(js(ctx, "(function(q){q=q.toLowerCase();var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);var n;while(n=w.nextNode()){if(n.nodeValue.toLowerCase().indexOf(q)>=0&&n.parentElement){var r0=n.parentElement.getBoundingClientRect();if(r0.width>1&&r0.height>1){n.parentElement.scrollIntoView({block:'center'});return 'found'}}}return 'none'})(" + JSONObject.quote(text) + ")"))
        Thread.sleep(400)
        return (if (r == "found") "Scrolled to '" + text + "'.\n" else "'" + text + "' is not on this page.\n") + snapshot(ctx)
    }

    fun clickXY(ctx: Context, x: Int, y: Int): String {
        appCtx = ctx.applicationContext
        if (x < 0 || y < 0) return "Give x and y (page pixels, from the top-left of the visible page)."
        mark = lastHash
        val r = unquote(js(ctx, "(function(x,y){var t=document.elementFromPoint(x,y);if(!t)return 'nothing there (outside the page)';var o={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};['pointerdown','mousedown','pointerup','mouseup'].forEach(function(n){var C=n.indexOf('pointer')===0&&window.PointerEvent?PointerEvent:MouseEvent;try{t.dispatchEvent(new C(n,o))}catch(err){}});if(t.focus)t.focus();t.click();return 'clicked '+t.tagName.toLowerCase()})($x,$y)"))
        Thread.sleep(900)
        return r + "\n" + snapshot(ctx)
    }

    fun back(ctx: Context): String {
        val w = view(ctx)
        val ok = onMain { if (w.canGoBack()) { w.goBack(); true } else false }
        if (!ok) return "No earlier page."
        Thread.sleep(1000)
        return snapshot(ctx)
    }

    fun waitAndRead(ctx: Context, seconds: Int, text: String = ""): String {
        val secs = seconds.coerceIn(1, 15)
        if (text.isBlank()) {
            Thread.sleep(secs * 1000L)
            return snapshot(ctx)
        }
        val end = System.currentTimeMillis() + secs * 1000L
        while (System.currentTimeMillis() < end) {
            val f = unquote(js(ctx, "(function(q){return (document.body&&document.body.innerText.toLowerCase().indexOf(q)>=0)?'y':'n'})(" + JSONObject.quote(text.lowercase()) + ")"))
            if (f == "y") return "'" + text + "' appeared.\n" + snapshot(ctx)
            Thread.sleep(400)
        }
        return "'" + text + "' did not appear in time.\n" + snapshot(ctx)
    }

    /** Waits until the page stops changing (url, text length, load state) instead of a fixed pause. */
    private fun settle(ctx: Context, maxMs: Long = 3500) {
        val end = System.currentTimeMillis() + maxMs
        Thread.sleep(350)
        var last = ""
        var same = 0
        while (System.currentTimeMillis() < end) {
            val cur = unquote(js(ctx, "(function(){return location.href+'|'+(document.body?document.body.innerText.length:0)+'|'+document.readyState})()"))
            if (cur == last) {
                same++
                if (same >= 2 && !cur.endsWith("loading")) return
            } else { same = 0; last = cur }
            Thread.sleep(300)
        }
    }

    fun scrollEdge(ctx: Context, top: Boolean): String {
        js(ctx, "(function(t){window.scrollTo(0,t?0:document.documentElement.scrollHeight);return 'ok'})(" + top + ")")
        Thread.sleep(500)
        return (if (top) "Jumped to the top.\n" else "Jumped to the bottom.\n") + snapshot(ctx)
    }

    /** Searches the page text and returns each match with its surrounding words. */
    fun find(ctx: Context, q: String): String {
        if (q.isBlank()) return "No text given."
        val raw = unquote(js(ctx, "(function(q){q=q.toLowerCase();var t=document.body?document.body.innerText:'';var low=t.toLowerCase();var out=[],i=-1;while(out.length<8&&(i=low.indexOf(q,i+1))>=0){out.push(t.slice(Math.max(0,i-60),i+q.length+60).split(String.fromCharCode(10)).join(' '))}return JSON.stringify({m:out})})(" + JSONObject.quote(q) + ")"))
        return try {
            val a = JSONObject(raw).getJSONArray("m")
            if (a.length() == 0) "'" + q + "' is not on this page."
            else {
                val sb = StringBuilder("Found " + a.length() + " match(es) of '" + q + "' in the page text:\n")
                for (i in 0 until a.length()) sb.append(i + 1).append(". ...").append(a.getString(i)).append("...\n")
                sb.append("Use browser_scroll_to to bring it on screen.")
                sb.toString()
            }
        } catch (e: Exception) { "Could not search the page: " + raw.take(100) }
    }

    private val KEYS = mapOf("Enter" to 13, "Escape" to 27, "Tab" to 9, "ArrowDown" to 40, "ArrowUp" to 38, "ArrowLeft" to 37, "ArrowRight" to 39, "Backspace" to 8, "PageDown" to 34, "PageUp" to 33, "Home" to 36, "End" to 35)

    /** Sends one special key to the focused element (never types characters). */
    fun keys(ctx: Context, key: String): String {
        val k = KEYS.keys.firstOrNull { it.equals(key.trim(), ignoreCase = true) } ?: return "Unknown key. Use one of: " + KEYS.keys.joinToString(", ")
        mark = lastHash
        val r = unquote(js(ctx, "(function(k,kc){var e=document.activeElement||document.body;if(e.type==='password'&&k==='Enter')return 'refused: not on a password field';['keydown','keypress','keyup'].forEach(function(n){e.dispatchEvent(new KeyboardEvent(n,{key:k,code:k,keyCode:kc,which:kc,bubbles:true,cancelable:true}))});if(k==='Enter'&&e.form){try{e.form.requestSubmit()}catch(x){e.form.submit()}}if(k==='PageDown')window.scrollBy(0,innerHeight*0.8);if(k==='PageUp')window.scrollBy(0,-innerHeight*0.8);if(k==='Home')window.scrollTo(0,0);if(k==='End')window.scrollTo(0,document.documentElement.scrollHeight);return 'sent '+k})(" + JSONObject.quote(k) + "," + KEYS[k] + ")"))
        settle(ctx, 2500)
        return r + "\n" + snapshot(ctx)
    }

    private const val SNAP = """(function(){var els=[],out=[],seen=new Set(),keys=[];document.querySelectorAll('[data-nid]').forEach(function(e){e.removeAttribute('data-nid')});function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return null;var cs=getComputedStyle(e);if(cs.visibility==='hidden'||cs.display==='none'||cs.opacity==='0')return null;if(r.bottom<-50||r.top>innerHeight*3)return null;return r;}function label(e){var t=(e.innerText||e.value||'').trim();if(!t)t=e.placeholder||e.getAttribute('aria-label')||e.title||e.alt||'';if(!t&&e.id){var l=document.querySelector('label[for='+JSON.stringify(e.id)+']');if(l)t=l.innerText;}if(!t&&e.labels&&e.labels[0])t=e.labels[0].innerText;return (t||'').trim().replace(/\s+/g,' ').slice(0,70);}function add(e,kind){if(seen.has(e)||els.length>=80)return;seen.add(e);e.setAttribute('data-nid',els.length);els.push(e);var x='';if(e.tagName==='A'&&e.href)x=' -> '+e.href.slice(0,80);if(e.name)x+=' name='+e.name;if(e.type==='checkbox'||e.type==='radio')x+=e.checked?' (checked)':' (unchecked)';var tg=e.tagName;if((tg==='INPUT'||tg==='TEXTAREA')&&e.type!=='password'&&e.type!=='checkbox'&&e.type!=='radio'&&e.value)x+=' value='+JSON.stringify(String(e.value).slice(0,40));if(tg==='SELECT'&&e.selectedIndex>=0&&e.options[e.selectedIndex])x+=' (selected: '+e.options[e.selectedIndex].text.trim().slice(0,30)+')';if(e.disabled)x+=' (disabled)';var ax=e.getAttribute('aria-expanded');if(ax==='true')x+=' (expanded)';else if(ax==='false')x+=' (collapsed)';var rr=e.getBoundingClientRect();if(rr.top>=innerHeight)x+=' (below screen)';else if(rr.bottom<=0)x+=' (above screen)';var key=kind+'|'+label(e)+'|'+(e.href||'');keys.push(key);var nw=(window.__nk&&!window.__nk.has(key))?'*':'';out.push(nw+'['+(els.length-1)+'] '+kind+': '+label(e)+x);}var q=document.querySelectorAll('a[href],button,input,textarea,select,label,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=option],[onclick],[tabindex],[contenteditable=true]');for(var i=0;i<q.length;i++){var e=q[i];if(!vis(e))continue;var tag=e.tagName.toLowerCase();if(tag==='input'&&e.type==='hidden')continue;if(tag==='label'&&e.control)continue;var kind=tag==='a'?'link':(tag==='input'||tag==='textarea')?('input:'+(e.type||'text')):tag==='select'?'select':e.isContentEditable?'input:richtext':'button';add(e,kind);}if(els.length<60){var g=document.querySelectorAll('div,span,li,img,svg');for(var j=0;j<g.length&&j<900;j++){var e2=g[j];if(seen.has(e2))continue;if(!vis(e2))continue;if(getComputedStyle(e2).cursor!=='pointer')continue;if(e2.closest('[data-nid]'))continue;add(e2,'clickable');}}window.__nk=new Set(keys);var body=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').slice(__OFF__,__OFF__+2500);return JSON.stringify({title:document.title,url:location.href,text:body,els:out,sy:Math.round(scrollY),sh:document.documentElement.scrollHeight,vh:innerHeight,off:__OFF__});})()"""
}
