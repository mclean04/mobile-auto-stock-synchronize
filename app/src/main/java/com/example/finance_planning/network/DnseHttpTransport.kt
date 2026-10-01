package com.example.finance_planning.network

import com.example.finance_planning.R
import com.example.finance_planning.core.AppText
import com.example.finance_planning.core.AppFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

object DnseHttpTransport {
    suspend fun request(url: String, headers: Map<String, String>, slot: DnseSlot? = null,
                        checkContext: () -> Unit = {}): String = withContext(Dispatchers.IO) {
        com.example.finance_planning.core.QaStartupIsolation.requireBusiness()
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
            uri.host in setOf("openapi.dnse.com.vn", "sb-openapi.dnse.com.vn"))
        try {
            val selected = slot ?: NetworkClients.application.dnse(DnseConfiguration(uri.host == "openapi.dnse.com.vn"))
            require(URI(selected.configuration.origin).host == uri.host)
            val query = uri.rawQuery?.split('&')?.associate {
                val fields = it.split('=', limit = 2)
                java.net.URLDecoder.decode(fields[0], "UTF-8") to java.net.URLDecoder.decode(fields.getOrElse(1) { "" }, "UTF-8")
            } ?: emptyMap()
            val response = selected.read(uri.rawPath, query, headers + ("Accept" to "application/json"), checkContext)
            val body = response.body() ?: response.errorBody()
            body?.use {
                val limit = if (response.isSuccessful) 4 * 1024 * 1024 else 4096
                val output = java.io.ByteArrayOutputStream()
                val input = it.byteStream()
                val buffer = ByteArray(8192)
                while (output.size() <= limit) {
                    val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                val bytes = output.toByteArray()
                if (!response.isSuccessful) {
                    val raw = if (bytes.size <= limit) String(bytes, Charsets.UTF_8) else null
                    throw HttpFailure(response.code(), ApiDiagnostics.dnseCode(raw))
                }
                if (bytes.size > limit) throw AppFailure(AppText.get(R.string.response_too_large))
                checkContext(); selected.checkActive()
                return@withContext if (response.code() == 204) "{}" else String(bytes, Charsets.UTF_8)
            }
            if (!response.isSuccessful) throw HttpFailure(response.code())
            checkContext(); selected.checkActive()
            "{}"
        } catch (e: SupersededNetworkContext) {
            throw e
        } catch (e: java.io.IOException) {
            throw AppFailure(AppText.get(R.string.dnse_connection_failed, ApiDiagnostics.failure(e)), true)
        }
    }
}
