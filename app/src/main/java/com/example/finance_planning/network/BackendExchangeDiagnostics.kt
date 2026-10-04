package com.example.finance_planning.network

import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

/** Per-call stage evidence. No tokens, payloads, addresses, exception messages or persistence. */
internal class BackendExchangeDiagnostics(private val log: (String) -> Unit) : EventListener() {
    private var stage = "created"
    private var connecting = false
    private var connection = "none"
    private var protocol = "unknown"
    private var headersSent = false
    private var requestBodySent = false
    private var responseBodyRead = false
    private fun failed(call: Call, error: IOException, at: String) {
        if (!com.example.finance_planning.BuildConfig.DEBUG) return
        val request = call.request()
        val route = ApiDiagnostics.route(request.url.toUri())
        try {
            log("NETWORK_FAILURE method=${request.method} route=$route stage=$at last=$stage " +
                "exception=${ApiDiagnostics.exception(error)} causes=${ApiDiagnostics.causes(error)} " +
                "category=${ApiDiagnostics.failure(error)} connection=$connection protocol=$protocol " +
                "headers_sent=$headersSent request_body_sent=$requestBodySent response_body_read=$responseBodyRead " +
                "canceled=${call.isCanceled()}")
        } catch (_: Exception) { }
    }
    override fun callStart(call: Call) { stage = "call_start" }
    override fun dnsStart(call: Call, domainName: String) { stage = "dns" }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connecting = true; stage = "connect"
    }
    override fun secureConnectStart(call: Call) { stage = "tls" }
    override fun connectionAcquired(call: Call, connection: Connection) {
        this.connection = if (connecting) "new" else "pooled"
        protocol = connection.protocol().toString()
        stage = "connection_acquired"
    }
    override fun requestHeadersStart(call: Call) { stage = "request_headers" }
    override fun requestHeadersEnd(call: Call, request: Request) { headersSent = true; stage = "request_headers_sent" }
    override fun requestBodyStart(call: Call) { stage = "request_body" }
    override fun requestBodyEnd(call: Call, byteCount: Long) { requestBodySent = true; stage = "request_body_sent" }
    override fun responseHeadersStart(call: Call) { stage = "response_headers" }
    override fun responseHeadersEnd(call: Call, response: Response) { stage = "response_headers_received" }
    override fun responseBodyStart(call: Call) { stage = "response_body" }
    override fun responseBodyEnd(call: Call, byteCount: Long) { responseBodyRead = true; stage = "response_body_end" }
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
        protocol: Protocol?, ioe: IOException) = failed(call, ioe, "connect_failed")
    override fun requestFailed(call: Call, ioe: IOException) = failed(call, ioe, "request_failed")
    override fun responseFailed(call: Call, ioe: IOException) = failed(call, ioe, "response_failed")
    override fun callFailed(call: Call, ioe: IOException) = failed(call, ioe, "call_failed")
}
