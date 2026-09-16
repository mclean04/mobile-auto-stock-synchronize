package com.example.finance_planning
import com.example.finance_planning.network.HttpLogFormat
import org.junit.Assert.*
import org.junit.Test
class HttpLogFormatTest {
    @Test fun debugHeadersPreserveExactValues() {
        val lines = mutableListOf<String>()
        HttpLogFormat.headers(listOf("Authorization" to "Bearer SECRET", "X-Api-Key" to "SECRET",
            "X-Signature" to "SECRET", "Set-Cookie" to "SECRET", "X-Firebase-AppCheck" to "SECRET",
            "Content-Type" to "application/json", "Content-Length" to "123"), lines::add)
        assertTrue(lines.contains("Authorization: Bearer SECRET"))
        assertTrue(lines.contains("Content-Type: application/json"))
        assertTrue(lines.contains("Content-Length: 123"))
    }
    @Test fun bodyHasNoEnvelopeOrCorrelationSuffix() {
        val lines = mutableListOf<String>()
        HttpLogFormat.body("""{"stock":{"availableCash":123}}""", lines::add)
        assertEquals("", lines.first())
        assertEquals("""{"stock":{"availableCash":123}}""", lines[1])
        assertFalse(lines.any { "REQUEST JSON" in it || "RESPONSE JSON" in it || "[id=" in it })
    }
}
