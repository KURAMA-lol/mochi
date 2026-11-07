package com.sahil.chibi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.settings.useWideViewPort = true
            w.settings.loadWithOverviewMode = true
            w.settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) { loadLatch?.countDown() }
            }
            w.webChromeClient = WebChromeClient()
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
        Thread.sleep(700)
        return snapshot(ctx)
    }

    fun snapshot(ctx: Context): String {
        val raw = unquote(js(ctx, SNAP))
        return try {
            val o = JSONObject(raw)
            val sb = StringBuilder()
            sb.append("Title: ").append(o.optString("title")).append("\nURL: ").append(o.optString("url"))
                .append("\n\nPage text:\n").append(o.optString("text")).append("\n\nElements:\n")
            val a = o.getJSONArray("els")
            for (i in 0 until a.length()) sb.append(a.getString(i)).append('\n')
            sb.toString()
        } catch (e: Exception) {
            "Could not read the page yet: " + raw.take(200)
        }
    }

    fun click(ctx: Context, id: Int): String {
        val r = unquote(js(ctx, "(function(id){var e=document.querySelector('[data-nid=\"'+id+'\"]');if(!e)return 'no such element, call browser_read again';e.removeAttribute('target');e.scrollIntoView({block:'center'});e.click();return 'clicked';})($id)"))
        Thread.sleep(900)
        return r + "\n" + snapshot(ctx)
    }

    fun type(ctx: Context, id: Int, text: String, submit: Boolean): String {
        val code = "(function(id,text,submit){var e=document.querySelector('[data-nid=\"'+id+'\"]');if(!e)return 'no such element, call browser_read again';if(e.type==='password')return 'refused: password fields are off limits';e.focus();var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,text);else e.value=text;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));if(submit){['keydown','keypress','keyup'].forEach(function(k){e.dispatchEvent(new KeyboardEvent(k,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}))});var f=e.form;if(f){try{f.requestSubmit()}catch(x){f.submit()}}}return 'typed';})($id," + JSONObject.quote(text) + "," + submit + ")"
        val r = unquote(js(ctx, code))
        if (submit) Thread.sleep(1200)
        return r + "\n" + snapshot(ctx)
    }

    fun scroll(ctx: Context, down: Boolean): String {
        js(ctx, "window.scrollBy(0," + (if (down) "" else "-") + "Math.round(innerHeight*0.8))")
        Thread.sleep(300)
        return snapshot(ctx)
    }

    fun back(ctx: Context): String {
        val w = view(ctx)
        val ok = onMain { if (w.canGoBack()) { w.goBack(); true } else false }
        if (!ok) return "No earlier page."
        Thread.sleep(900)
        return snapshot(ctx)
    }

    private const val SNAP = """(function(){var els=[],out=[];document.querySelectorAll('[data-nid]').forEach(function(e){e.removeAttribute('data-nid')});var all=document.querySelectorAll('a[href],button,input,textarea,select,[role=button],[role=link],[onclick],summary');for(var i=0;i<all.length&&els.length<70;i++){var e=all[i];var r=e.getBoundingClientRect();if(r.width<2||r.height<2)continue;var cs=getComputedStyle(e);if(cs.visibility==='hidden'||cs.display==='none')continue;if(r.bottom<0||r.top>innerHeight*3)continue;var tag=e.tagName.toLowerCase();if(tag==='input'&&e.type==='hidden')continue;var t=(e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||e.title||'').trim().replace(/\s+/g,' ').slice(0,70);var kind=tag==='a'?'link':(tag==='input'||tag==='textarea')?('input'+(e.type?':'+e.type:'')):tag==='select'?'select':'button';e.setAttribute('data-nid',els.length);els.push(e);out.push('['+(els.length-1)+'] '+kind+': '+t+((tag==='a'&&e.href)?' -> '+e.href.slice(0,80):''));}var body=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').slice(0,2500);return JSON.stringify({title:document.title,url:location.href,text:body,els:out});})()"""
}
