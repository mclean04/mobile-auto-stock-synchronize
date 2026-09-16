package com.example.finance_planning.network

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
        if (uri.scheme != "https" || uri.userInfo != null) throw AppFailure("Địa chỉ kết nối không hợp lệ.")
        val connection = uri.toURL().openConnection() as HttpsURLConnection
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
            val status = connection.responseCode
            if (status !in 200..299) throw HttpFailure(status)
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
        } catch (e: java.io.IOException) {
            throw AppFailure("Không kết nối được máy chủ. Dữ liệu đang chờ sẽ được giữ lại.", true)
        } finally { connection.disconnect() }
    }
}
class HttpFailure(val status: Int) : Exception("HTTP $status") {
    fun safe(): AppFailure = when (status) {
        401, 403 -> AppFailure("Máy chủ chưa cấp quyền cho phiên đăng nhập này.")
        409 -> AppFailure("Dữ liệu xung đột phiên bản. Đợt gửi được giữ để kiểm tra.")
        422 -> AppFailure("Dữ liệu chưa đúng định dạng backend. Đợt gửi được giữ để kiểm tra.")
        429 -> AppFailure("Máy chủ đang giới hạn lượt gọi. Sẽ thử lại sau.", true)
        else -> AppFailure("Máy chủ trả lỗi HTTP $status.", status >= 500)
    }
}
