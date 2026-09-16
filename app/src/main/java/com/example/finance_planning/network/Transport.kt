package com.example.finance_planning.network

import com.example.finance_planning.core.AppFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

class Transport {
    companion object { private val sequence = java.util.concurrent.atomic.AtomicLong() }

    suspend fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
                        body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.userInfo != null) throw AppFailure("Địa chỉ kết nối không hợp lệ.")
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        val requestId = sequence.incrementAndGet()
        val started = System.nanoTime()
        val label = "id=$requestId service=${ApiDiagnostics.service(uri)} method=${method.takeIf { it in setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD") } ?: "other"} route=${ApiDiagnostics.route(uri)}"
        var status: Int? = null
        var code: String? = null
        var outcome = "ok"
        var skewSeconds: Long? = null
        fun log(text: String) {
            if (com.example.finance_planning.BuildConfig.DEBUG) android.util.Log.w("OkHttp", text)
        }
        log("--> $method https://${uri.host}${ApiDiagnostics.route(uri)} [$label]")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 40_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > 2 * 1024 * 1024) throw AppFailure("Đợt dữ liệu vượt giới hạn 2 MB.")
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(bytes) }
            }
            status = connection.responseCode
            if (ApiDiagnostics.service(uri).startsWith("dnse-")) {
                val serverDate = connection.getHeaderFieldDate("Date", 0)
                if (serverDate > 0) skewSeconds = (System.currentTimeMillis() - serverDate) / 1000
            }
            if (status !in 200..299) {
                code = if (ApiDiagnostics.service(uri) != "other") {
                    val raw = try {
                        connection.errorStream?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(1024)
                            while (output.size() < 4096) {
                                val n = input.read(buffer, 0, minOf(buffer.size, 4096 - output.size()))
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                            output.toString("UTF-8")
                        }
                    } catch (_: java.io.IOException) { null }
                    if (ApiDiagnostics.service(uri).startsWith("dnse-")) ApiDiagnostics.dnseCode(raw)
                    else HttpFailure.safeCode(raw)
                } else null
                throw HttpFailure(status, code)
            }
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return@withContext "{}"
            connection.inputStream.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 4 * 1024 * 1024)
                        throw AppFailure("Phản hồi quá lớn.")
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                if (bytes.size > 4 * 1024 * 1024) throw AppFailure("Phản hồi quá lớn.")
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            outcome = ApiDiagnostics.failure(e)
            if (e is java.io.IOException)
                throw AppFailure("Không kết nối được máy chủ. Dữ liệu đang chờ sẽ được giữ lại. [$outcome]", true)
            throw e
        } finally {
            log("<-- ${status ?: "HTTP FAILED"} https://${uri.host}${ApiDiagnostics.route(uri)} [$label] code=${code ?: "none"} outcome=$outcome elapsed_ms=${(System.nanoTime() - started) / 1_000_000} device_minus_server_seconds=${skewSeconds ?: "unknown"}")
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
            "owner_only" -> "Tài khoản Google này chưa được backend cho phép. Hãy dùng tài khoản chủ planning. [owner_only]"
            "identity_changed" -> "Định danh đăng nhập khác định danh đã lưu trong backend. Cần kiểm tra liên kết tài khoản. [identity_changed]"
            "invalid_mobile_app" -> "Firebase App ID của bản cài không khớp cấu hình backend. [invalid_mobile_app]"
            "invalid_firebase_token" -> "Backend không xác minh được phiên Firebase. [invalid_firebase_token]"
            "app_check_required", "invalid_app_check_token" -> "Backend chưa chấp nhận xác minh App Check. [$code]"
            else -> "Máy chủ chưa cấp quyền cho phiên đăng nhập này. [HTTP $status / ${code ?: "unclassified"}]"
        })
        409 -> AppFailure("Dữ liệu xung đột phiên bản. Đợt gửi được giữ để kiểm tra.")
        422 -> AppFailure("Dữ liệu chưa đúng định dạng backend. Đợt gửi được giữ để kiểm tra.")
        429 -> AppFailure("Máy chủ đang giới hạn lượt gọi. Sẽ thử lại sau.", true)
        else -> AppFailure("Máy chủ trả lỗi HTTP $status.", status >= 500)
    }
}
