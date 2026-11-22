package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigInputParserTest {
    @Test fun textValueDoesNotRequireJsonQuotes() {
        assertEquals("on-request", ConfigInputParser.parse("text", "on-request", false))
    }

    @Test fun booleanInputIsTypedValue() {
        assertEquals(true, ConfigInputParser.parse("boolean", "", true))
        assertEquals(false, ConfigInputParser.parse("boolean", "", false))
    }

    @Test fun numericValuesAreValidated() {
        assertTrue(ConfigInputParser.parse("number", "12.5", false) is Number)
        assertThrows(IllegalArgumentException::class.java) {
            ConfigInputParser.parse("number", "not a number", false)
        }
    }

    @Test fun rejectsExtraTokensInsteadOfSilentlySavingPartialConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigInputParser.parse("json", "{\"enabled\":true} garbage", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigInputParser.parse("number", "12 34", false)
        }
    }

    @Test fun structuredJsonIsOptIn() {
        assertEquals(true,
            (ConfigInputParser.parse("json", "{\"enabled\":true}", false)
                as org.json.JSONObject).getBoolean("enabled"))
    }
}
