package io.github.sy220284.codexandroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in only: use a dedicated authenticated test device. No account
 * credentials, full responses or real project data are written to evidence. */
@RunWith(AndroidJUnit4::class)
class LiveServiceInstrumentedTest {
    @Test fun configuredModelProducesRealFinalAnswer() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("liveService") == "true") { "真实服务测试需要操作者明确启用" }
        val model = requireNotNull(args.getString("liveModel")?.takeIf { it.isNotBlank() }) { "请指定已授权的测试模型" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        val dir = File(WorkspaceStorage.root(app), "111-live-check-${UUID.randomUUID()}").apply {
            check(mkdir()) { "无法创建独立测试工作目录" }
        }
        try {
            withTimeout(180_000L) {
                bridge.connect()
                assertNotNull(bridge.request("account/read"))
                val start = bridge.request("thread/start", JSONObject().put("cwd", dir.absolutePath)
                    .put("ephemeral", true).put("approvalPolicy", "never").put("sandbox", "read-only")
                    .put("model", model))
                val thread = start.getJSONObject("thread").getString("id")
                val marker = "CODEX_RUNTIME_" + UUID.randomUUID().toString().replace("-", "")
                val completed = CompletableDeferred<JSONObject>()
                val output = StringBuilder()
                val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                    bridge.events.collect { event ->
                        val params = event.optJSONObject("params") ?: return@collect
                        if (params.optString("threadId") != thread) return@collect
                        when (event.optString("method")) {
                            "item/completed" -> {
                                val item = params.optJSONObject("item") ?: return@collect
                                if (item.optString("type") == "agentMessage") output.append(item.optString("text"))
                            }
                            "turn/completed" -> completed.complete(params.getJSONObject("turn"))
                        }
                    }
                }
                try {
                    val accepted = bridge.request("turn/start", JSONObject().put("threadId", thread)
                        .put("input", TurnRequestContract.input("只输出以下测试标记，不要调用工具或读取文件：$marker")))
                    val final = completed.await()
                    assertEquals(accepted.getJSONObject("turn").getString("id"), final.getString("id"))
                    assertEquals("completed", final.getString("status"))
                    assertTrue("真实模型未返回测试标记", output.contains(marker))
                    args.getString("liveMcp")?.takeIf { it.isNotBlank() }?.let { server ->
                        val data = bridge.request("mcpServerStatus/list", JSONObject()
                            .put("threadId", thread).put("serverName", server)).objects()
                        assertTrue("指定 MCP 未报告实际连接",
                            data.any { it.value("name") == server && it.value("runtimeStatus") == "connected" })
                    }
                } finally { listener.cancelAndJoin() }
            }
        } finally {
            bridge.close()
            // Only delete this newly created empty test directory.
            check(dir.delete()) { "独立测试目录非空，请人工检查" }
        }
    }
}
