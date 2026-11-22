package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpToolArgumentsTest {
    private fun schema() = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("q", JSONObject().put("type", "string"))
            .put("limit", JSONObject().put("type", "integer"))
            .put("mode", JSONObject().put("type", "string")
                .put("enum", JSONArray().put("safe").put("fast"))))
        .put("required", JSONArray().put("q"))
        .put("additionalProperties", false)

    @Test fun validTypedInputPassesLocalContract() {
        assertNull(McpToolArguments.problem(schema(), JSONObject()
            .put("q", "代码审查").put("limit", 2).put("mode", "safe")))
    }

    @Test fun missingRequiredFieldIsRejected() {
        assertTrue(McpToolArguments.problem(schema(), JSONObject())!!
            .contains("q"))
    }

    @Test fun invalidNumberTypeCannotBeSent() {
        assertTrue(McpToolArguments.problem(schema(), JSONObject()
            .put("q", "hello").put("limit", "many"))!!.contains("limit"))
    }

    @Test fun enumAndUnexpectedFieldAreRejected() {
        assertTrue(McpToolArguments.problem(schema(), JSONObject()
            .put("q", "hello").put("mode", "unsafe"))!!.contains("mode"))
        assertTrue(McpToolArguments.problem(schema(), JSONObject()
            .put("q", "hello").put("unlisted", true))!!.contains("unlisted"))
    }

    @Test fun missingSchemaIsExplicitRatherThanGuessingArguments() {
        assertNotNull(McpToolArguments.problem(null, JSONObject()))
        assertNotNull(McpToolArguments.problem(schema(), null))
    }
}
