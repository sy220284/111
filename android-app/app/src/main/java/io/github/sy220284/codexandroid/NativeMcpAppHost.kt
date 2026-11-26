package io.github.sy220284.codexandroid

import android.content.Intent
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A trusted parent document owns a MessagePort. App HTML executes only in an opaque-origin
 * iframe with no network, navigation, file access, storage, Android JS object or device permissions. */
internal object McpAppSandbox {
    const val HOST = "https://111-mcp-host.invalid"
    const val VIEW = "https://111-mcp-app.invalid/view"
    const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    fun shell(source: String): String {
        val protected = "<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\">" + source
        val encoded = android.util.Base64.encodeToString(protected.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; frame-src 'self' about:; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
<style>html,body,iframe{margin:0;width:100%;height:100%;border:0;overflow:hidden}iframe{display:block}</style></head><body>
<script>
"use strict";
let port, frame;
window.addEventListener("message", e => {
 if (!port && e.data === "111-native-port" && e.ports.length === 1 && e.source === null) {
   port=e.ports[0];
   frame=document.createElement("iframe");
   frame.setAttribute("sandbox","allow-scripts");
   port.onmessage=msg=>{try {frame.contentWindow.postMessage(JSON.parse(msg.data),"*");}catch(_){}};
   frame.srcdoc=new TextDecoder().decode(Uint8Array.from(atob("$encoded"), c=>c.charCodeAt(0))); document.body.appendChild(frame);
   return;
 }
 if (port && frame && e.source===frame.contentWindow && e.origin==="null") {
   try {const value=JSON.stringify(e.data); if(value && value.length<=262144) port.postMessage(value);}catch(_){}
 }
});
</script></body></html>
""".trimIndent()
    }
    fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    /** A shared real WebView entry point for production and instrumented isolation tests. */
    fun attach(view: WebView, source: String, owns: () -> Boolean,
        connected: (WebMessagePort) -> Unit, receive: (String) -> Unit) {
        var attached = false
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true
            setGeolocationEnabled(false)
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = denied()
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame || request.url.toString() != "about:srcdoc"
            override fun onPageFinished(view: WebView, url: String) {
                if (!owns() || attached || url.trimEnd('/') != HOST) return
                attached = true
                val pair = view.createWebMessageChannel()
                pair[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                        if (owns()) receive(message.data.orEmpty())
                    }
                })
                connected(pair[0])
                view.postWebMessage(WebMessage("111-native-port", arrayOf(pair[1])), Uri.parse(HOST))
            }
        }
        view.loadDataWithBaseURL(HOST + "/", shell(source), "text/html", "UTF-8", null)
    }

}

@Composable
internal fun NativeMcpAppHost(vm: CodexViewModel, thread: String, call: JSONObject, source: String,
    epoch: Long, revision: Long, originCallId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contract = remember(call, source, originCallId) { McpAppHostContract(call, originCallId) }
    val currentSession by vm.bridge.connectedSession.collectAsState()
    val currentRevision by vm.catalogWarmup.accountRevision.collectAsState()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val theme = if (dark) "dark" else "light"
    var port by remember { mutableStateOf<WebMessagePort?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf(false) }
    val teardown = remember { CompletableDeferred<Unit>() }
    val teardownId = remember { "android-teardown-" + java.util.UUID.randomUUID() }
    var closed by remember { mutableStateOf(false) }
    fun owns() = !closed && vm.activeThread.value == thread && vm.bridge.connectedSession.value == epoch &&
        vm.catalogWarmup.accountRevision.value == revision && !vm.threadSwitching.value
    fun send(value: JSONObject) {
        if (owns()) port?.postMessage(WebMessage(value.toString()))
    }
    suspend fun rpc(method: String, params: JSONObject): JSONObject {
        check(owns()) { "会话或账号已变化" }
        val result = vm.bridge.request(method, params, expectedSession = epoch, sendPermit = ::owns)
        check(owns()) { "会话或账号已变化" }
        require(result.toString().length <= McpAppHostContract.MAX_MESSAGE) { "工具回执超过宿主容量" }
        return result
    }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            if (owns() && contract.ready) {
                send(JSONObject().put("jsonrpc", "2.0").put("id", teardownId)
                    .put("method", "ui/resource-teardown").put("params", JSONObject().put("reason", "user_closed")))
                withTimeoutOrNull(1000) { teardown.await() }
            }
            onDismiss()
        }
    }
    fun messageText(params: JSONObject): String {
        require(params.optString("role") == "user") { "仅支持用户角色消息" }
        val content = params.opt("content")
        val values = when (content) {
            is JSONObject -> listOf(content)
            is org.json.JSONArray -> (0 until content.length()).map { content.getJSONObject(it) }
            else -> error("无效消息内容")
        }
        require(values.size in 1..16 && values.all { it.optString("type") == "text" && it.opt("text") is String }) {
            "当前宿主仅接受文本消息"
        }
        return values.joinToString("\n") { it.getString("text") }.trim().also {
            require(it.length in 1..8192 && !it.startsWith("/") && !it.startsWith("!")) { "消息格式或长度无效" }
        }
    }
    fun handle(raw: String) {
        if (!owns() || raw.length > McpAppHostContract.MAX_MESSAGE) return
        val req = try { JSONObject(raw) } catch (_: org.json.JSONException) { return }
        if (req.optString("jsonrpc") != "2.0") return
        if (req.optString("id") == teardownId && (req.has("result") || req.has("error"))) { teardown.complete(Unit); return }
        if (closing) return
        val method = req.optString("method")
        val id = req.opt("id")
        val params = req.optJSONObject("params") ?: JSONObject()
        if (id == null || id == JSONObject.NULL) {
            if (method == "ui/notifications/initialized") {
                try { contract.initialized().forEach(::send) }
                catch (e: IllegalArgumentException) { failure = "应用初始化顺序无效" }
            }
            return
        }
        if (id !is String && id !is Number) return
        try {
            when {
                method == "ui/initialize" -> send(contract.response(id, contract.initialize(params, theme)))
                !contract.ready -> send(contract.error(id, -32000, "App 尚未初始化"))
                method == "ping" -> send(contract.response(id, JSONObject()))
                method == "ui/request-display-mode" -> send(contract.response(id, JSONObject().put("mode", "fullscreen")))
                method in setOf("tools/call", "resources/read", "ui/open-link", "ui/message") -> {
                    if (pending != null || busy) send(contract.error(id, -32000, "已有待确认操作"))
                    else if ((method == "tools/call" && !contract.scopedTools) ||
                        (method == "resources/read" && !contract.scopedTools && !contract.originResource))
                        send(contract.error(id, -32000, "当前核心未提供此托管应用的原调用工具授权通道"))
                    else { if (method == "ui/message") messageText(params); pending = req }
                }
                else -> send(contract.error(id, -32601, "宿主未提供该能力"))
            }
        } catch (e: Exception) { send(contract.error(id, -32602, "无效应用请求")) }
    }
    val receive by rememberUpdatedState<(String) -> Unit>(::handle)
    LaunchedEffect(currentSession, currentRevision) {
        if (currentSession != epoch || currentRevision != revision || vm.activeThread.value != thread) onDismiss()
    }
    DisposableEffect(Unit) {
        onDispose {
            closed = true
            port?.close()
            web?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        }
    }
    LaunchedEffect(theme) {
        if (contract.ready) send(contract.notification("ui/notifications/host-context-changed", JSONObject().put("theme", theme)))
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("mcp-app-host"), color = Ink.canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("应用 · " + contract.server, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    CodexIconAction(onClick = ::close) { CodexIcon(CodexGlyph.Close, "关闭应用") }
                }
                Text("应用在隔离页面运行，工具操作需逐次确认。", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                    WebView(ctx).apply {
                        web = this
                        McpAppSandbox.attach(this, source, ::owns, { port = it }, { receive(it) })
                    }
                })
                if (busy) Text("正在等待真实工具回执", color = Ink.muted)
                failure?.let { ErrorText(it) }
            }
        }
    }
    pending?.let { req ->
        val method = req.getString("method")
        val id = req.get("id")
        val params = req.optJSONObject("params") ?: JSONObject()
        val description = when (method) {
            "tools/call" -> "工具：" + params.optString("name") + "\n参数：" + (params.optJSONObject("arguments") ?: JSONObject()).toString(2)
            "resources/read" -> "读取：" + params.optString("uri")
            "ui/message" -> "将以下文本作为你的消息发送到当前会话：\n" + messageText(params)
            else -> "打开：" + params.optString("url")
        }
        CodexConfirmDialog(title = "确认应用操作", message = description, confirmLabel = "确认执行", destructive = false,
            onDismiss = { pending = null; send(contract.error(id, -32000, "用户取消")) },
            onConfirm = {
                pending = null
                busy = true
                scope.launch {
                    try {
                        val result = when (method) {
                            "ui/open-link" -> {
                                val url = params.getString("url")
                                require(owns() && McpAppHostContract.externalLink(url)) { "仅允许打开 HTTPS 链接" }
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                JSONObject()
                            }
                            "ui/message" -> {
                                require(owns() && !vm.running.value && vm.composerDrafts.value[vm.composerOwner(thread)]?.hasInput != true) {
                                    "当前会话忙碌或有未发送草稿，请先处理"
                                }
                                val accepted = CompletableDeferred<String>()
                                vm.sendPrompt(messageText(params), afterAccepted = { accepted.complete(it) })
                                val destination = withTimeoutOrNull(30000) { accepted.await() }
                                check(destination == thread) { "未取得当前会话的发送确认，请查看聊天记录后决定是否重试" }
                                JSONObject()
                            }
                            "tools/call" -> {
                                val name = params.getString("name")
                                val registration = contract.registration(rpc("mcpServerStatus/list", contract.catalogRequest(thread)))
                                require(contract.allowedTool(registration, name)) { "当前应用无此工具访问权限" }
                                rpc("mcpServer/tool/call", contract.scoped(JSONObject().put("tool", name)
                                    .put("arguments", params.optJSONObject("arguments") ?: JSONObject()), thread))
                            }
                            else -> {
                                val uri = params.getString("uri")
                                val registration = if (contract.originResource) null else
                                    contract.registration(rpc("mcpServerStatus/list", contract.catalogRequest(thread)))
                                val result = rpc("mcpServer/resource/read", contract.resourceRequest(thread, uri, registration))
                                if (contract.originResource) require(result.optString("originCallId") == originCallId) {
                                    "工具资源归属不一致"
                                }
                                // originCallId is a native authorization receipt, not MCP resource content.
                                JSONObject(result.toString()).apply { remove("originCallId") }
                            }
                        }
                        send(contract.response(id, result))
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { send(contract.error(id, -32000, e.message ?: "应用操作失败")) }
                    finally { busy = false }
                }
            })
    }
}
