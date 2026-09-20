package com.example.finance_planning

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.example.finance_planning.auth.MobileIdentity
import com.example.finance_planning.core.*
import com.example.finance_planning.data.*
import com.example.finance_planning.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Test-APK-only wiring. No Firebase credentials or real broker transport is used. */
internal class B3DeviceFixture(val context: Context, val config: JSONObject, val phase: String) {
    val run = config.getString("run_id").also { require(Regex("[A-Za-z0-9-]{1,60}").matches(it)) }
    val uid = config.getString("uid")
    val directory = File(context.filesDir, "b3-$run").apply { mkdirs() }
    private val traceFile = File(directory, "$phase.jsonl")
    val brokerCalls = AtomicInteger()
    val listCalls = AtomicInteger()
    var dropNextAck = false
    var holdReports = false
    var brokerTimeout = false
    var killAfterMarker = false
    var brokerRelease: CountDownLatch? = null
    var switchBeforeSource = false
    var lastSwitch: JSONObject? = null
    var lastActiveSource: JSONObject? = null
    val reportAcks = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    var scenario = phase
    private val isolated = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) =
            context.getSharedPreferences("b3-$run-$name", mode)
    }
    val vault = Vault(isolated)
    val db = Room.databaseBuilder(context, LocalDb::class.java, "b3-$run.db").build()
    val transport = object : Transport() {
        override suspend fun request(url: String, method: String, headers: Map<String, String>, body: JSONObject?): String {
            val requested = URI(url)
            require(requested.scheme == "https" && requested.host == URI(Contracts.BACKEND).host)
            require(requested.path.startsWith("/v2/planning/") || requested.path == "/v2/orders/placed")
            if (requested.path == "/v2/planning/intents") listCalls.incrementAndGet()
            if (requested.path == "/v2/planning/source" && switchBeforeSource) {
                switchBeforeSource = false
                switchSource()
            }
            if (requested.path == "/v2/orders/placed" && holdReports) {
                trace("report_transport_offline", JSONObject().put("payload", body))
                throw IOException("Test-only offline report transport")
            }
            val result = http(requested.rawPath + (requested.rawQuery?.let { "?$it" } ?: ""), method, body)
            if (requested.path == "/v2/planning/source") lastActiveSource = result
            if (requested.path == "/v2/orders/placed") reportAcks.add(result)
            if (requested.path == "/v2/orders/placed" && dropNextAck) {
                dropNextAck = false
                trace("report_ack_dropped", JSONObject().put("ack", result))
                throw IOException("Test-only acknowledgement loss after HTTP success")
            }
            return result.toString()
        }
    }
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        require(request.url.host == "sb-openapi.dnse.com.vn") { "Production broker forbidden in B3" }
        val path = request.url.encodedPath
        val account = config.getString("account")
        val response: Any = when {
            request.method == "GET" && path == "/accounts" -> JSONArray().put(JSONObject()
                .put("id", account).put("name", "QA cash account").put("dealAccount", true))
            request.method == "GET" && path == "/accounts/$account/loan-packages" ->
                JSONArray().put(JSONObject().put("id", 1).put("type", "N").put("name", "QA cash"))
                    .put(JSONObject().put("id", 2).put("type", "M").put("name", "QA margin forbidden"))
            request.method == "GET" && path == "/accounts/$account/balances" ->
                JSONObject().put("stock", JSONObject().put("availableCash", 1000000000))
            request.method == "POST" && path == "/registration/trading-token" ->
                JSONObject().put("tradingToken", "FAKE-B3-OTP-TOKEN")
            request.method == "POST" && path == "/accounts/$account/orders" -> {
                val calls = brokerCalls.incrementAndGet()
                val marker = runBlocking { journal() }
                check(marker?.getString("state") == "UNKNOWN")
                trace("fake_broker_called", JSONObject().put("calls", calls).put("journal", marker))
                if (killAfterMarker) {
                    trace("process_kill_after_unknown", JSONObject().put("journal", marker)
                        .put("broker_calls", calls).put("pid", android.os.Process.myPid()))
                    android.os.Process.killProcess(android.os.Process.myPid())
                    error("Process kill did not terminate test")
                }
                brokerRelease?.let { check(it.await(15, TimeUnit.SECONDS)) }
                if (brokerTimeout) throw IOException("Test-only ambiguous broker response")
                JSONObject().put("id", "B3-$run-$scenario").put("orderStatus", "PENDING").put("fillQuantity", "0")
            }
            else -> error("No fake response for $path")
        }
        trace("fake_broker_transport", JSONObject().put("method", request.method).put("path", path))
        // This interceptor never calls chain.proceed: DNS/TLS/socket to DNSE cannot occur.
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Test only")
            .body(response.toString().toResponseBody("application/json".toMediaType())).build()
    }.build()
    val repo = PlanningRepository(object : MobileIdentity(isolated) { override fun uid() = this@B3DeviceFixture.uid },
        vault, db, BackendApi(transport) { emptyMap() },
        manualBroker = { key, secret, production ->
            check(!production)
            DnseTradingApi(key, secret, false, client)
        })

    init {
        trace("process", JSONObject().put("pid", android.os.Process.myPid())
            .put("phase", phase).put("backend_boundary", config.optString("evidence_kind", "UNVERIFIED"))
            .put("wiring", "production Orders/dialog/repository + fake identity/broker, isolated Room/Vault"))
        vault.put("approved", uid)
        vault.put("mobile_scope", "uploader-v1:$uid")
        repo.saveDnse("FAKE-B3-KEY", "FAKE-B3-SECRET", false, "1")
    }

    @Synchronized fun trace(event: String, fields: JSONObject = JSONObject()) {
        traceFile.appendText(JSONObject().put("event", event).put("at", Instant.now().toString())
            .put("data", fields).toString() + "\n")
    }

    /** Loopback-only raw HTTP is confined to this test APK; product TLS policy stays untouched. */
    suspend fun http(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val base = URI(config.getString("base_url"))
        require(base.scheme == "http" && base.host == "127.0.0.1" && base.port in 1024..65535)
        require(path.startsWith("/") && !path.contains("\r") && !path.contains("\n"))
        val token = config.getString("token").also { require(!it.contains("\r") && !it.contains("\n")) }
        val authHeader = config.optString("auth_header", "X-Planning-Authorization")
            .also { require(it in setOf("X-Planning-Authorization", "Authorization")) }
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        val start = System.nanoTime()
        Socket(base.host, base.port).use { socket ->
            socket.soTimeout = 45000
            val head = "$method $path HTTP/1.1\r\nHost: 127.0.0.1:${base.port}\r\n$authHeader: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b == -1 || b == 10) break
                    if (b != 13) out.write(b)
                    require(out.size() < 16384)
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
                require(length in 0..4000000)
                val value = ByteArray(length)
                var offset = 0
                while (offset < length) { val n = input.read(value, offset, length - offset); check(n > 0); offset += n }
                return value
            }
            val raw = if (responseHeaders["transfer-encoding"]?.lowercase() == "chunked") {
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val size = line().substringBefore(";").trim().toInt(16)
                    if (size == 0) break
                    require(size >= 0 && size <= 4000000 - out.size())
                    out.write(bytes(size))
                    check(line().isEmpty())
                }
                out.toByteArray()
            } else bytes(responseHeaders.getValue("content-length").toInt())
            val json = JSONObject(String(raw, Charsets.UTF_8))
            trace("http", JSONObject().put("method", method).put("path", path).put("status", status)
                .put("elapsed_ms", (System.nanoTime() - start) / 1000000).put("request", body ?: JSONObject.NULL)
                .put("response", json))
            if (status !in 200..299) throw HttpFailure(status)
            json
        }
    }

    suspend fun switchSource(): JSONObject {
        val control = config.getJSONObject("switch")
        return http(control.getString("path"), "POST", control.getJSONObject("body")).also { lastSwitch = it }
    }
    suspend fun readback(): JSONObject = http(config.getString("readback_path"))
    suspend fun reports() = db.dao().placedReports(uid).map { JSONObject(vault.open(it.ciphertext)) }
    suspend fun journal(): JSONObject? {
        val intentId = config.getJSONObject("intents").getString(scenario)
        // Query only this isolated database; identify the durable journal through its stable intent suffix.
        val cursor = db.openHelper.readableDatabase.query("SELECT ciphertext FROM cache WHERE owner = ? AND key LIKE ?",
            arrayOf(uid, "manual_trade:v2:%:$intentId"))
        return cursor.use { if (it.moveToFirst()) JSONObject(vault.open(it.getString(0))) else null }
    }
    suspend fun saveFunds() {
        val configRaw = DnseCredentialStore(vault::get, vault::put, vault::remove).config(uid)!!
        val hash = MessageDigest.getInstance("SHA-256").digest(configRaw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val account = config.getString("account")
        val funds = JSONObject().put("credential_scope", hash)
            .put("accounts", JSONArray().put(JSONObject().put("account", account)
                .put("profile", JSONObject().put("dealAccount", true))))
            .put("balances", JSONArray().put(JSONObject().put("account", account).put("cash_vnd", "1000000000")))
        db.dao().cache(CacheRow(uid, "dnse", vault.seal(funds.toString()), System.currentTimeMillis()))
    }
    fun close() { brokerRelease?.countDown(); db.close(); client.dispatcher.executorService.shutdown() }
}
