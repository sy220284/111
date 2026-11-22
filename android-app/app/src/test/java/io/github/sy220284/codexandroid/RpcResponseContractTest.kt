package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RpcResponseContractTest {
    private fun rejected(envelope: JSONObject, reason: String) {
        try {
            RpcResponseContract.requireResult("turn/start", envelope)
            fail("不符合协议的响应不能被视为成功：$reason")
        } catch (failure: IllegalStateException) {
            assertTrue("应说明错误原因：${failure.message}",
                failure.message.orEmpty().contains(reason))
        }
    }

    @Test fun acceptsTypedResultObjectIncludingAnEmptyUnitResponse() {
        val result = JSONObject().put("turn", JSONObject().put("id", "turn-1"))
        assertEquals("turn-1", RpcResponseContract.requireResult(
            "turn/start", JSONObject().put("id", 9).put("result", result))
            .getJSONObject("turn").getString("id"))
        assertEquals(0, RpcResponseContract.requireResult("config/value/write",
            JSONObject().put("id", 10).put("result", JSONObject())).length())
    }

    @Test fun serverErrorSurfacesActualCodeAndMessage() {
        val envelope = JSONObject().put("id", 11).put("error",
            JSONObject().put("code", -32601).put("message", "该能力尚未启用"))
        try {
            RpcResponseContract.requireResult("project/create", envelope)
            fail("服务端拒绝不应进入成功回调")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("该能力尚未启用"))
            assertTrue(failure.message.orEmpty().contains("-32601"))
        }
    }

    @Test fun missingResultAndErrorIsNeverTreatedAsSuccessfulEmptyObject() {
        rejected(JSONObject().put("id", 7), "必须包含有效结果或错误")
    }

    @Test fun nullAndWrongTypedResultsAreRejected() {
        rejected(JSONObject().put("result", JSONObject.NULL), "结果为空或类型错误")
        rejected(JSONObject().put("result", "unexpected"), "结果为空或类型错误")
        rejected(JSONObject().put("result", 42), "结果为空或类型错误")
        rejected(JSONObject().put("result", org.json.JSONArray()), "结果为空或类型错误")
    }

    @Test fun successAndFailureCannotCoexistInOneEnvelope() {
        rejected(JSONObject().put("result", JSONObject())
            .put("error", JSONObject().put("code", -32000)
                .put("message", "conflict")), "只能出现一种")
    }

    @Test fun invalidErrorPayloadNeverUsesGenericSuccessPath() {
        rejected(JSONObject().put("error", "failure"), "错误格式无效")
        rejected(JSONObject().put("error", JSONObject()
            .put("message", "missing code")), "错误缺少有效的代码或说明")
        rejected(JSONObject().put("error", JSONObject()
            .put("code", -32001)), "错误缺少有效的代码或说明")
    }

    @Test fun nullErrorWithoutResultIsMalformed() {
        rejected(JSONObject().put("error", JSONObject.NULL), "必须包含有效结果或错误")
    }
}
