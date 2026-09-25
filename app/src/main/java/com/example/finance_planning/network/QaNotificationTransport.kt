package com.example.finance_planning.network

import com.example.finance_planning.BuildConfig
import com.example.finance_planning.core.NotificationDeliveryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.util.UUID

/** Debug-only, loopback-only QA transport. It never logs or accepts a bearer/URL from FCM. */
class QaNotificationTransport(private val bearer: String) : Transport() {
    init {
        require(BuildConfig.DEBUG)
        require(bearer.isNotBlank() && bearer.length <= 4096 && '\r' !in bearer && '\n' !in bearer)
    }

    override suspend fun request(url: String, method: String, headers: Map<String, String>,
                                 body: JSONObject?): String = withContext(Dispatchers.IO) {
        require(headers.isEmpty())
        val uri = URI(url)
        require(allowlisted(url, method))
        val path = uri.rawPath
        val payload = body?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        require(payload.size <= 2 * 1024 * 1024)
        Socket("127.0.0.1", 18766).use { socket ->
            socket.soTimeout = 45_000
            val target = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            val head = "$method $target HTTP/1.1\r\nHost: 127.0.0.1:18766\r\n" +
                "X-Planning-Authorization: Bearer $bearer\r\nAccept: application/json\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.UTF_8)); write(payload); flush() }
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val out = ByteArrayOutputStream()
                while (true) {
                    val next = input.read()
                    if (next == -1 || next == 10) break
                    if (next != 13) out.write(next)
                    require(out.size() < 16_384)
                }
                return out.toString("UTF-8")
            }
            val status = line().split(" ")[1].toInt()
            val responseHeaders = mutableMapOf<String, String>()
            while (true) {
                val next = line()
                if (next.isEmpty()) break
                responseHeaders[next.substringBefore(":").lowercase()] = next.substringAfter(":").trim()
            }
            fun bytes(length: Int): ByteArray {
                require(length in 0..4_000_000)
                val value = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(value, offset, length - offset)
                    check(count > 0)
                    offset += count
                }
                return value
            }
            val raw = if (responseHeaders["transfer-encoding"]?.lowercase() == "chunked") {
                val out = ByteArrayOutputStream()
                while (true) {
                    val size = line().substringBefore(';').trim().toInt(16)
                    if (size == 0) break
                    require(size <= 4_000_000 - out.size())
                    out.write(bytes(size)); check(line().isEmpty())
                }
                out.toByteArray()
            } else if (status == 204) byteArrayOf()
            else bytes(responseHeaders.getValue("content-length").toInt())
            val response = String(raw, Charsets.UTF_8)
            if (status !in 200..299) throw HttpFailure(status, HttpFailure.safeCode(response))
            response.ifBlank { "{}" }
        }
    }

    companion object {
        internal fun allowlisted(url: String, method: String): Boolean = runCatching {
            val uri = URI(url)
            require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18766 &&
                uri.userInfo == null && uri.fragment == null &&
                "${uri.scheme}://${uri.host}:${uri.port}" == NotificationDeliveryPolicy.QA_BASE_URL)
            val path = uri.rawPath
            val event = Regex("/v1/notifications/([0-9a-fA-F-]{36})").matchEntire(path)
            val receipt = Regex("/v1/notifications/([0-9a-fA-F-]{36})/receipts").matchEntire(path)
            val device = Regex("/v1/devices/([0-9a-fA-F-]{36})").matchEntire(path)
            when {
                method == "GET" && path == "/v1/notifications" -> Unit
                method == "GET" && event != null -> UUID.fromString(event.groupValues[1])
                method == "POST" && receipt != null -> UUID.fromString(receipt.groupValues[1])
                method in setOf("PUT", "DELETE") && device != null -> UUID.fromString(device.groupValues[1])
                else -> error("QA notification route is not allowlisted")
            }
            require(uri.rawQuery == null || (method == "GET" && path == "/v1/notifications" &&
                Regex("limit=[0-9]{1,3}(&cursor=[A-Za-z0-9%_.~+-]{1,500})?").matches(uri.rawQuery)))
            true
        }.getOrDefault(false)
    }
}
