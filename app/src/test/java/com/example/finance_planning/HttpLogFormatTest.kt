package com.example.finance_planning
import com.example.finance_planning.network.HttpLogFormat
import org.junit.Assert.*
import org.junit.Test
class HttpLogFormatTest {
    @Test fun headersKeepSafeMetadataAndMaskAllCredentials() {
        val lines = mutableListOf<String>()
        HttpLogFormat.headers(listOf("Authorization" to "Bearer SECRET", "X-Api-Key" to "SECRET",
            "X-Signature" to "SECRET", "Set-Cookie" to "SECRET", "X-Firebase-AppCheck" to "SECRET",
            "Content-Type" to "application/json", "Content-Length" to "123"), lines::add)
        assertFalse(lines.any { it.contains("SECRET") })
        assertTrue(lines.contains("content-type: application/json"))
        assertTrue(lines.contains("content-length: 123"))
    }
    @Test fun bodyHasNoEnvelopeOrCorrelationSuffix() {
        val lines = mutableListOf<String>()
        HttpLogFormat.body("""{"stock":{"availableCash":123}}""", lines::add)
        assertEquals("", lines.first())
        assertTrue(lines[1].startsWith("{"))
        assertFalse(lines.any { "REQUEST JSON" in it || "RESPONSE JSON" in it || "[id=" in it })
    }
}
