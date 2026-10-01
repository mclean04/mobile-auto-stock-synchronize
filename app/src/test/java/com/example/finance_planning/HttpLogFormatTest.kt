package com.example.finance_planning
import com.example.finance_planning.network.HttpLogFormat
import org.junit.Assert.*
import org.junit.Test
class HttpLogFormatTest {
    @Test fun headersOmitAllNamesAndValues() {
        val lines = mutableListOf<String>()
        HttpLogFormat.headers(listOf("Authorization" to "Bearer SECRET", "X-Api-Key" to "SECRET",
            "X-Signature" to "SECRET", "Set-Cookie" to "SECRET", "X-Firebase-AppCheck" to "SECRET",
            "Content-Type" to "application/json", "Content-Length" to "123"), lines::add)
        assertEquals(listOf("(headers omitted)"), lines)
    }
    @Test fun bodyIsOmitted() {
        val lines = mutableListOf<String>()
        HttpLogFormat.body("""{"stock":{"availableCash":123}}""", lines::add)
        assertEquals(listOf("(body omitted)"), lines)
    }
}
