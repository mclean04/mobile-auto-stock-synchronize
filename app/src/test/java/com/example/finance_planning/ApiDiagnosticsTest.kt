package com.example.finance_planning

import com.example.finance_planning.network.ApiDiagnostics
import com.example.finance_planning.core.Contracts
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class ApiDiagnosticsTest {
    @Test fun sensitiveRouteAndQueryNeverAppear() {
        val uri = URI("https://openapi.dnse.com.vn/accounts/secret-account/orders/secret-order?api_key=secret-key")
        assertEquals("/accounts/{id}/orders/{id}", ApiDiagnostics.route(uri))
        assertEquals("dnse-production", ApiDiagnostics.service(uri))
        assertEquals("/v1/devices/{id}", ApiDiagnostics.route(URI(Contracts.BACKEND + "/v1/devices/secret-token")))
        assertEquals("/v1/admin/sources/{id}/records", ApiDiagnostics.route(URI(Contracts.BACKEND + "/v1/admin/sources/private-uid/records?cursor=secret")))
    }
    @Test fun unknownRoutesHostsAndEncodedSecretsAreHidden() {
        assertEquals("/[redacted]", ApiDiagnostics.route(URI("https://openapi.dnse.com.vn/secret-key")))
        assertEquals("/[redacted]", ApiDiagnostics.route(URI("https://unknown.example/accounts")))
        assertEquals("/accounts/{id}/balances", ApiDiagnostics.route(URI("https://sb-openapi.dnse.com.vn/accounts/secret%2Fkey/balances")))
    }
    @Test fun debugUrlIncludesEndpointAndSafeQueryButNoCredentials() {
        val url = ApiDiagnostics.dnseUrl(URI("https://openapi.dnse.com.vn/accounts/test-account/orders/history?pageIndex=0&from=2026-09-01&api_key=PRIVATE-KEY&marketType=PRIVATE-SECRET"))
        assertTrue(url.startsWith("https://openapi.dnse.com.vn/accounts/test-account/orders/history?"))
        assertTrue(url.contains("pageIndex=0&from=2026-09-01"))
        assertFalse(url.contains("PRIVATE"))
        assertEquals("<redacted-url>", ApiDiagnostics.dnseUrl(URI("https://unknown.example/secret")))
    }
    @Test fun onlyFixedErrorCodesEscapeParser() {
        assertEquals("invalid_api_key", ApiDiagnostics.dnseCode("""{"message":"invalid API key"}"""))
        assertEquals("OA-401", ApiDiagnostics.dnseCode("""{"code":"OA-401","message":"private secret"}"""))
        assertNull(ApiDiagnostics.dnseCode("""{"code":"secret-token","message":"invalid API key secret-token"}"""))
        assertNull(ApiDiagnostics.dnseCode("<html>secret</html>"))
        assertEquals("timeout", ApiDiagnostics.failure(java.net.SocketTimeoutException("secret-key")))
    }
}
