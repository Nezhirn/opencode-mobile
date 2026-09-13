package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.SessionErrorInfo
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionErrorFormattingTest {

    @Test
    fun combinesNameAndServerMessage() {
        val info = SessionErrorInfo(
            name = "ProviderAuthError",
            data = buildJsonObject { put("message", "bad key") },
        )

        assertEquals("ProviderAuthError: bad key", formatSessionError(info, null))
    }

    @Test
    fun fallsBackToNameOnly() {
        assertEquals("UnknownError", formatSessionError(SessionErrorInfo(name = "UnknownError"), null))
    }

    @Test
    fun fallsBackToRawPayload() {
        assertEquals("\"oops\"", formatSessionError(null, JsonPrimitive("oops")))
    }

    @Test
    fun defaultMessageWhenNothingIsAvailable() {
        assertEquals("Session error", formatSessionError(null, null))
    }
}
