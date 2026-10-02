package com.example.finance_planning.network

import com.example.finance_planning.R
import com.example.finance_planning.core.AppText
import com.example.finance_planning.core.AppFailure
import com.example.finance_planning.core.QaStartupIsolation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.net.URI

open class Transport(client: OkHttpClient? = null,
                     private val slotProvider: () -> BackendSlot = { NetworkClients.application.backend() }) {
    // Explicit client injection is the existing local component-test seam.
    private val testSlot by lazy { client?.let { BackendSlot(BackendConfiguration(), it, logger = {}) } }
    open suspend fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
                             body: JSONObject? = null): String = perform(url, method, headers, body, BackendCredentials(headers))
    suspend fun requestWithContext(url: String, method: String, headers: Map<String, String>,
                                   body: JSONObject?, credentials: BackendCredentials): String =
        perform(url, method, headers, body, credentials)
    private suspend fun perform(url: String, method: String, headers: Map<String, String>,
                                body: JSONObject?, credentials: BackendCredentials): String = withContext(Dispatchers.IO) {
        QaStartupIsolation.requireBusiness()
        val uri = URI(url)
        if ((com.example.finance_planning.core.LocalBackend.active &&
                !com.example.finance_planning.core.LocalBackend.accepts(url)) ||
            (uri.scheme != "https" && !com.example.finance_planning.core.LocalBackend.accepts(url)) || uri.userInfo != null)
            throw AppFailure(AppText.get(R.string.invalid_connection_address))
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (bytes != null && bytes.size > 2 * 1024 * 1024)
            throw AppFailure(AppText.get(R.string.data_batch_exceeds_the_2_mb_limit))
        try {
            val slot = testSlot ?: slotProvider()
            slot.checkActive()
            val response = slot.legacy.request(url, method, mapOf("Accept" to "application/json") + headers.filterKeys { !BackendCredentials.isCredential(it) },
                bytes?.toRequestBody("application/json".toMediaType()), credentials)
            val responseBody = response.body() ?: response.errorBody()
            responseBody.use {
                if (!response.isSuccessful) {
                    val raw = if (ApiDiagnostics.service(uri) != "other") {
                        try { it?.readLimited(4L * 1024 * 1024) } catch (_: java.io.IOException) { null }
                    } else null
                    throw HttpFailure(response.code(), if (ApiDiagnostics.service(uri).startsWith("dnse-"))
                        ApiDiagnostics.dnseCode(raw) else HttpFailure.safeCode(raw))
                }
                val result = if (response.code() == 204) "{}" else it?.readLimited(4L * 1024 * 1024).orEmpty()
                // An acknowledged mutation remains owned by its original journal.
                if (method == "GET") { slot.checkActive(); credentials.check() }
                result
            }
        } catch (_: HttpResponseTooLarge) {
            throw AppFailure(AppText.get(R.string.response_too_large))
        } catch (e: SupersededNetworkContext) {
            throw e
        } catch (e: java.io.IOException) {
            throw AppFailure(AppText.get(R.string.backend_connection_failed, ApiDiagnostics.failure(e)), true).apply { initCause(e) }
        }
    }
}
class HttpFailure(val status: Int, val code: String? = null) : Exception("HTTP $status") {
    companion object {
        private val allowedCodes = setOf("authentication_required", "invalid_access_token",
            "invalid_firebase_token", "app_check_required", "invalid_app_check_token",
            "invalid_mobile_app", "owner_only", "identity_changed", "invalid_identity_token",
            "intent_execution_claimed", "preflight_request_id_payload_conflict",
            "preflight_context_conflict")
        fun safeCode(body: String?): String? = try {
            body?.let { JSONObject(it).optString("detail").takeIf(allowedCodes::contains) }
        } catch (_: Exception) { null }
    }
    fun safe(): AppFailure = when (status) {
        401, 403 -> AppFailure(when (code) {
            "owner_only" -> AppText.get(R.string.backend_owner_only)
            "identity_changed" -> AppText.get(R.string.backend_identity_changed)
            "invalid_mobile_app" -> AppText.get(R.string.backend_app_id_mismatch)
            "invalid_firebase_token" -> AppText.get(R.string.backend_firebase_not_verified)
            "app_check_required", "invalid_app_check_token" -> AppText.get(R.string.backend_app_check_rejected, code)
            else -> AppText.get(R.string.backend_session_not_authorized, status, code ?: "unclassified")
        })
        409 -> AppFailure(AppText.get(R.string.backend_data_conflict))
        422 -> AppFailure(AppText.get(R.string.backend_data_format_invalid))
        429 -> AppFailure(AppText.get(R.string.backend_rate_limited), true)
        else -> AppFailure(AppText.get(R.string.the_server_returned_http_error, status), status >= 500)
    }
}
