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

/** A trusted parent document owns a MessagePort. App HTML executes only in an opaque-origin
 * iframe with no network, navigation, file access, storage, Android JS object or device permissions. */
internal object McpAppSandbox {
    const val HOST = "https://111-mcp-host.invalid"
    const val VIEW = "https://111-mcp-app.invalid/view"
    const val CSP = "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    val shell = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-src https://111-mcp-app.invalid">
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
   frame.src="https://111-mcp-app.invalid/view"; document.body.appendChild(frame);
   return;
 }
 if (port && frame && e.source===frame.contentWindow && e.origin==="null") {
   try {const value=JSON.stringify(e.data); if(value && value.length<=262144) port.postMessage(value);}catch(_){}
 }
});
</script></body></html>
""".trimIndent()
    fun resource(source: String) = WebResourceResponse("text/html", "UTF-8", 200, "OK",
        mapOf("Content-Security-Policy" to CSP, "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer"),
        ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)))
    fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}

@Composable
internal fun NativeMcpAppHost(vm: CodexViewModel, thread: String, call: JSONObject, source: String,
    epoch: Long, revision: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contract = remember(call, source) { McpAppHostContract(call) }
    val currentSession by vm.bridge.connectedSession.collectAsState()
    val currentRevision by vm.catalogWarmup.accountRevision.collectAsState()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val theme = if (dark) "dark" else "light"
    var port by remember { mutableStateOf<WebMessagePort?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
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
    fun handle(raw: String) {
        if (!owns() || raw.length > McpAppHostContract.MAX_MESSAGE) return
        val req = try { JSONObject(raw) } catch (_: org.json.JSONException) { return }
        if (req.optString("jsonrpc") != "2.0") return
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
                method in setOf("tools/call", "resources/read", "ui/open-link") -> {
                    if (pending != null || busy) send(contract.error(id, -32000, "已有待确认操作"))
                    else if (method != "ui/open-link" && !contract.scopedTools)
                        send(contract.error(id, -32000, "当前核心未提供此托管应用的原调用工具授权通道"))
                    else pending = req
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("mcp-app-host"), color = Ink.canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("应用 · " + contract.server, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    CodexIconAction(onClick = onDismiss) { CodexIcon(CodexGlyph.Close, "关闭应用") }
                }
                Text("应用在隔离页面运行，工具操作需逐次确认。", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                    WebView(ctx).apply {
                        web = this
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.blockNetworkLoads = true
                        settings.setGeolocationEnabled(false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                                if (!request.isForMainFrame && request.url.toString() == McpAppSandbox.VIEW &&
                                    request.method == "GET") McpAppSandbox.resource(source) else McpAppSandbox.denied()
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.isForMainFrame || request.url.toString() != McpAppSandbox.VIEW
                            override fun onPageFinished(view: WebView, url: String) {
                                if (!owns() || port != null || url.trimEnd('/') != McpAppSandbox.HOST) return
                                val pair = view.createWebMessageChannel()
                                pair[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                                        receive(message.data.orEmpty())
                                    }
                                })
                                port = pair[0]
                                view.postWebMessage(WebMessage("111-native-port", arrayOf(pair[1])), Uri.parse(McpAppSandbox.HOST))
                            }
                        }
                        loadDataWithBaseURL(McpAppSandbox.HOST + "/", McpAppSandbox.shell, "text/html", "UTF-8", null)
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
                            "tools/call" -> {
                                val name = params.getString("name")
                                val registration = contract.registration(rpc("mcpServerStatus/list", contract.catalogRequest(thread)))
                                require(contract.allowedTool(registration, name)) { "当前应用无此工具访问权限" }
                                rpc("mcpServer/tool/call", contract.scoped(JSONObject().put("tool", name)
                                    .put("arguments", params.optJSONObject("arguments") ?: JSONObject()), thread))
                            }
                            else -> {
                                val uri = params.getString("uri")
                                val registration = contract.registration(rpc("mcpServerStatus/list", contract.catalogRequest(thread)))
                                require(contract.allowedResource(registration, uri)) { "资源不在应用服务目录中" }
                                rpc("mcpServer/resource/read", contract.scoped(JSONObject().put("uri", uri), thread))
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
