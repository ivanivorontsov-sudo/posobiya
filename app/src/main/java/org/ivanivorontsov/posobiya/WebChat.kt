package org.ivanivorontsov.posobiya

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

data class ChatSite(val id: String, val title: String, val url: String)

val chatSites = listOf(
    ChatSite("qwen", "Qwen", "https://chat.qwen.ai/?models=$DEFAULT_MODEL"),
    ChatSite("alice", "Алиса", "https://alice.yandex.ru/"),
    ChatSite("gigachat", "GigaChat", "https://giga.chat/"),
    ChatSite("deepseek", "DeepSeek", "https://chat.deepseek.com/"),
    ChatSite("chatgpt", "ChatGPT", "https://chatgpt.com/"),
    ChatSite("grok", "Grok", "https://grok.com/")
)

/** Попытка выбрать в chat.qwen.ai модель Omni Flash и включить «Размышление» и «Поиск». Безопасно ничего не делает, если интерфейс изменился. */
const val QWEN_SETUP_JS = """(function(){var log=[];var vis=function(e){return e&&e.getClientRects().length};
var txt=function(e){return ((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.title||'')).trim()};
var cur=[].slice.call(document.querySelectorAll('button,[role=button],div[class*=model]')).filter(vis).filter(function(b){return /qwen/i.test(txt(b))&&txt(b).length<60})[0];
if(cur&&!/3\.8.*omni.*flash/i.test(txt(cur))){cur.click();log.push('menu');setTimeout(function(){var it=[].slice.call(document.querySelectorAll('[role=option],[role=menuitem],li,div')).filter(vis).filter(function(e){return /qwen3\.8.?omni.?flash/i.test(txt(e))&&txt(e).length<120});
 if(it.length){it[it.length-1].click();}},700);}
var tog=function(re){var b=[].slice.call(document.querySelectorAll('button,[role=button],[role=switch]')).filter(vis).filter(function(b){return re.test(txt(b))&&txt(b).length<40})[0];
 if(!b)return 'none';var on=b.getAttribute('aria-pressed')==='true'||b.getAttribute('aria-checked')==='true'||/active|selected|checked|\bon\b/i.test(b.className||'');if(!on){b.click();return 'on'}return 'was'};
setTimeout(function(){log.push('think:'+tog(/thinking|думан|размышл|мышлен/i));log.push('search:'+tog(/search|поиск/i));},1600);
return JSON.stringify({model:cur?txt(cur):null});})()"""

const val SEND_JS = """(function(P){var vis=function(e){return e&&e.getClientRects().length};
var eds=[].slice.call(document.querySelectorAll('textarea,[contenteditable=true],div[role=textbox]')).filter(vis);var ed=eds[eds.length-1];
if(!ed)return 'noinput';ed.focus();
if(ed.tagName==='TEXTAREA'){var s=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(ed),'value').set;s.call(ed,P);ed.dispatchEvent(new Event('input',{bubbles:true}));}
else{document.execCommand('selectAll',false);document.execCommand('insertText',false,P);}
setTimeout(function(){var b=[].slice.call(document.querySelectorAll('button')).filter(function(b){return vis(b)&&!b.disabled&&/send|отправ|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.id||'')+' '+(b.className||'')+' '+(b.getAttribute('data-testid')||''))}).pop();
if(b)b.click();else ed.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));},500);return 'ok';})"""

fun webPrompt(question: String, first: Boolean): String =
    (if (first) "[Инструкция для ассистента] $SYSTEM_PROMPT\n\n[Вопрос] " else "") + question +
        "\n\n(Обязательно выполни поиск в интернете по актуальным законам на сегодня и укажи даты окончания мер.)"

@SuppressLint("SetJavaScriptEnabled")
fun makeChatWebView(ctx: android.content.Context, url: String, onReady: (WebView) -> Unit): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.databaseEnabled = true
    settings.loadWithOverviewMode = true; settings.useWideViewPort = true; settings.javaScriptCanOpenWindowsAutomatically = true
    settings.userAgentString = settings.userAgentString.replace("; wv", "")
    CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    val h = Handler(Looper.getMainLooper())
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, u: String?) {
            if (u?.contains("chat.qwen.ai") == true) { h.postDelayed({ view.evaluateJavascript(QWEN_SETUP_JS, null) }, 2500) }
        }
    }
    webChromeClient = WebChromeClient()
    loadUrl(url); onReady(this)
}

fun jsq(s: String) = JSONObject.quote(s)
