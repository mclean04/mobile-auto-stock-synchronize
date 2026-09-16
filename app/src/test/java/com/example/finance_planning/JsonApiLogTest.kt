package com.example.finance_planning
import com.example.finance_planning.network.JsonApiLog
import com.example.finance_planning.core.Contracts
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class JsonApiLogTest {
    @Test fun getQueryIsJsonAndBodyIsNull() {
        val result = JSONObject(JsonApiLog.request(URI("https://openapi.dnse.com.vn/accounts/test/orders?pageIndex=0&pageSize=100&marketType=STOCK&secret=HIDDEN"), "GET", null))
        assertTrue(result.isNull("body"))
        assertEquals(100, result.getJSONObject("query").getInt("pageSize"))
        assertEquals("STOCK", result.getJSONObject("query").getString("marketType"))
        assertFalse(result.toString().contains("HIDDEN"))
    }
    @Test fun postBodyAndResponseAreSeparateRedactedJsonObjects() {
        val uri = URI(Contracts.BACKEND + "/v1/sync/batches")
        val request = JSONObject(JsonApiLog.request(uri, "POST", """{"batch_id":"PRIVATE","balances":[{"cash_vnd":"123456"}],"fcm_token":"SECRET"}"""))
        assertEquals("POST", request.getString("method"))
        assertTrue(request.getJSONObject("body").has("balances"))
        assertFalse(request.toString().contains("SECRET"))
        assertFalse(request.toString().contains("123456"))
        val response = JSONObject(JsonApiLog.response(uri, 200, """{"database":"committed"}"""))
        assertEquals(200, response.getInt("status"))
        assertTrue(response.getJSONObject("body").has("database"))
    }
}
