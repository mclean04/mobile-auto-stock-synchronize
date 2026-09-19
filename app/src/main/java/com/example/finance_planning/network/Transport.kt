package com.example.finance_planning.network

import com.example.finance_planning.R
import com.example.finance_planning.core.AppText
import com.example.finance_planning.core.AppFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

class Transport {

    suspend fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
                        body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.userInfo != null) throw AppFailure(AppText.get(R.string.invalid_connection_address))
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        val started = System.nanoTime()
        var status: Int? = null
        var code: String? = null
        var outcome = "ok"
        fun log(text: String) {
            if (com.example.finance_planning.BuildConfig.DEBUG) android.util.Log.w("OkHttp", text)
        }
        val logUrl = uri.toString()
        fun responseBody(raw: String?) {
            if (com.example.finance_planning.BuildConfig.DEBUG) {
                HttpLogFormat.body(raw, ::log)
                log("<-- END HTTP (${raw?.toByteArray(Charsets.UTF_8)?.size ?: 0}-byte body)")
            }
        }
        log("--> $method $logUrl")
        if (com.example.finance_planning.BuildConfig.DEBUG) {
            HttpLogFormat.headers((headers + ("Accept" to "application/json")).toList(), ::log)
            body?.let {
                log("content-type: application/json")
                log("content-length: ${it.toString().toByteArray(Charsets.UTF_8).size}")
                HttpLogFormat.body(it.toString(), ::log)
            }
            log("--> END $method${if (body == null) "" else " (JSON body)"}")
        }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 40_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > 2 * 1024 * 1024) throw AppFailure(AppText.get(R.string.data_batch_exceeds_the_2_mb_limit))
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(bytes) }
            }
            status = connection.responseCode
            log("<-- $status $logUrl (${(System.nanoTime() - started) / 1_000_000}ms)")
            if (com.example.finance_planning.BuildConfig.DEBUG)
                HttpLogFormat.headers(connection.headerFields.filterKeys { it != null }.flatMap { (k, values) -> values.map { k to it } }, ::log)
            if (status !in 200..299) {
                code = if (ApiDiagnostics.service(uri) != "other") {
                    val raw = try {
                        connection.errorStream?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(1024)
                            while (output.size() < 4 * 1024 * 1024) {
                                val n = input.read(buffer, 0, minOf(buffer.size, 4 * 1024 * 1024 - output.size()))
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                            output.toString("UTF-8")
                        }
                    } catch (_: java.io.IOException) { null }
                    responseBody(raw)
                    if (ApiDiagnostics.service(uri).startsWith("dnse-")) ApiDiagnostics.dnseCode(raw)
                    else HttpFailure.safeCode(raw)
                } else null
                throw HttpFailure(status, code)
            }
            if (status == HttpURLConnection.HTTP_NO_CONTENT) {
                responseBody(null)
                return@withContext "{}"
            }
            connection.inputStream.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 4 * 1024 * 1024)
                        throw AppFailure(AppText.get(R.string.response_too_large))
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                if (bytes.size > 4 * 1024 * 1024) throw AppFailure(AppText.get(R.string.response_too_large))
                String(bytes, Charsets.UTF_8).also { raw ->
                    responseBody(raw)
                }
            }
        } catch (e: Exception) {
            outcome = ApiDiagnostics.failure(e)
            if (e is java.io.IOException) log("<-- HTTP FAILED: $outcome $logUrl")
            if (e is java.io.IOException)
                throw AppFailure(AppText.get(R.string.backend_connection_failed, outcome), true)
            throw e
        } finally {

            connection.disconnect()
        }
    }
}
class HttpFailure(val status: Int, val code: String? = null) : Exception("HTTP $status") {
    companion object {
        private val allowedCodes = setOf("authentication_required", "invalid_access_token",
            "invalid_firebase_token", "app_check_required", "invalid_app_check_token",
            "invalid_mobile_app", "owner_only", "identity_changed", "invalid_identity_token")
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
