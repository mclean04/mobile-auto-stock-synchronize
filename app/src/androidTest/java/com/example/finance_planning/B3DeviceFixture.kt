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
import java.util.concurrent.atomic.AtomicReference

/** Test-APK-only wiring. No Firebase credentials or real broker transport is used. */
internal class B3DeviceFixture(val context: Context, val config: JSONObject, val phase: String) {
    val run = config.getString("run_id").also { require(Regex("[A-Za-z0-9-]{1,60}").matches(it)) }
    val uid = config.getString("uid")
    val deviceId = config.getString("device_id").also { java.util.UUID.fromString(it) }
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
    private val campaign = if (config.has("campaign_id")) QaCampaign(
        config.getString("campaign_id"), config.getString("session_id"),
        config.getString("manifest_hash"), config.getString("session_kind")) else null
    private val concurrentRequestId = AtomicReference<String?>()
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
            if (method != "GET") requireCampaign(if (requested.path == "/v2/orders/placed")
                QaCampaignOperation.COMPLETION else QaCampaignOperation.BUSINESS)
            if (requested.path == "/v2/planning/intents") listCalls.incrementAndGet()
            if (requested.path == "/v2/planning/source" && switchBeforeSource) {
                switchBeforeSource = false
                awaitOperatorSourceSwitch()
            }
            if (requested.path == "/v2/orders/placed" && holdReports) {
                trace("report_transport_offline", JSONObject().put("payload", body))
                throw IOException("Test-only offline report transport")
            }
            val concurrentPreflight = phase == "concurrent" && method == "POST" &&
                requested.path.endsWith("/preflight")
            if (concurrentPreflight) awaitConcurrentRelease(requested.path, requireNotNull(body))
            val started = System.nanoTime()
            val result = try {
                http(requested.rawPath + (requested.rawQuery?.let { "?$it" } ?: ""), method, body)
            } catch (error: Throwable) {
                if (concurrentPreflight) coordinatorEvent(if (error is HttpFailure && error.status == 409)
                    "PREFLIGHT_CONFLICT" else "PREFLIGHT_FAILED", JSONObject()
                    .put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                    .put("http_status", (error as? HttpFailure)?.status ?: 0)
                    .put("code", (error as? HttpFailure)?.code ?: "UNKNOWN"))
                if (phase == "concurrent" && requested.path == "/v2/orders/placed")
                    coordinatorEvent("PLACED_REPORT_FAILED", JSONObject()
                        .put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                        .put("http_status", (error as? HttpFailure)?.status ?: 0)
                        .put("placed_request_id", body?.optString("request_id") ?: "UNKNOWN"))
                throw error
            }
            if (concurrentPreflight) {
                val eligible = (result.optJSONObject("eligibility") ?: result).optBoolean("eligible", false)
                coordinatorEvent(if (eligible) "PREFLIGHT_ACCEPTED" else "PREFLIGHT_FAILED", JSONObject()
                    .put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                    .put("http_status", 200).put("preflight_id", result.optString("preflight_id", "UNKNOWN"))
                    .put("duplicate", result.getBoolean("duplicate"))
                    .put("code", if (eligible) "NONE" else "NOT_ELIGIBLE"))
            }
            if (phase == "concurrent" && requested.path == "/v2/orders/placed")
                coordinatorEvent("PLACED_REPORT_ACCEPTED", JSONObject()
                    .put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                    .put("http_status", 200)
                    .put("placed_request_id", body?.optString("request_id") ?: "UNKNOWN"))
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
                runBlocking { requireCampaign(QaCampaignOperation.BUSINESS) }
                val calls = brokerCalls.incrementAndGet()
                val marker = runBlocking { journal() }
                check(marker?.getString("state") == "UNKNOWN")
                val fakeOrderId = "B3-$run-$scenario-${deviceId.take(8)}"
                if (phase == "concurrent") runBlocking {
                    coordinatorEvent("FAKE_BROKER_INVOKED", JSONObject()
                        .put("route", "ANDROID_INJECTED_FAKE_ONLY").put("order_id", fakeOrderId))
                }
                trace("fake_broker_called", JSONObject().put("calls", calls).put("journal", marker))
                if (killAfterMarker) {
                    trace("process_kill_after_unknown", JSONObject().put("journal", marker)
                        .put("broker_calls", calls).put("pid", android.os.Process.myPid()))
                    android.os.Process.killProcess(android.os.Process.myPid())
                    error("Process kill did not terminate test")
                }
                brokerRelease?.let { check(it.await(15, TimeUnit.SECONDS)) }
                if (brokerTimeout) throw IOException("Test-only ambiguous broker response")
                if (phase == "concurrent") runBlocking {
                    coordinatorEvent("FAKE_BROKER_ACCEPTED", JSONObject()
                        .put("route", "ANDROID_INJECTED_FAKE_ONLY").put("order_id", fakeOrderId))
                }
                JSONObject().put("id", fakeOrderId).put("orderStatus", "PENDING").put("fillQuantity", "0")
            }
            else -> error("No fake response for $path")
        }
        trace("fake_broker_transport", JSONObject().put("method", request.method).put("path", path))
        // This interceptor never calls chain.proceed: DNS/TLS/socket to DNSE cannot occur.
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Test only")
            .body(response.toString().toResponseBody("application/json".toMediaType())).build()
    }.build()
    val observationLog = ProductionObservationLog(File(directory, "observations"))
    val repo = PlanningRepository(object : MobileIdentity(isolated) { override fun uid() = this@B3DeviceFixture.uid },
        vault, db, BackendApi(transport, { emptyMap() }),
        manualBroker = { key, secret, production ->
            check(!production)
            DnseTradingApi(key, secret, false, client, qaInMemoryFakeOnly = true)
        }, observation = observationLog, qaInMemoryFakeBusiness = true)

    init {
        trace("process", JSONObject().put("pid", android.os.Process.myPid())
            .put("phase", phase).put("backend_boundary", config.optString("evidence_kind", "UNVERIFIED"))
            .put("wiring", "production Orders/dialog/repository + fake identity/broker, isolated Room/Vault"))
        vault.put("approved", uid)
        vault.put("mobile_scope", "uploader-v1:$uid")
        vault.put("device:$uid", deviceId)
        repo.saveDnse("FAKE-B3-KEY", "FAKE-B3-SECRET", false, "1")
    }

    @Synchronized fun trace(event: String, fields: JSONObject = JSONObject()) {
        val line = JSONObject().put("event", event).put("at", Instant.now().toString())
            .put("campaign_id", campaign?.campaignId ?: JSONObject.NULL)
            .put("session_id", campaign?.sessionId ?: JSONObject.NULL)
            .put("manifest_sha256", campaign?.manifestSha256 ?: JSONObject.NULL)
            .put("case_id", config.optString("case_id").ifBlank { null } ?: JSONObject.NULL)
            .put("data", sanitize(fields)).toString() + "\n"
        check(traceFile.length() + line.toByteArray().size <= 8 * 1024 * 1024) { "Bounded QA trace is full" }
        traceFile.appendText(line)
    }

    private fun sanitize(value: Any?, depth: Int = 0): Any? {
        if (depth > 16) return "TRUNCATED"
        return when (value) {
            is JSONObject -> JSONObject().also { clean ->
                value.keys().forEach { key ->
                    val sensitive = key.lowercase().let { name ->
                        listOf("token", "secret", "password", "bearer", "authorization", "otp", "api_key")
                            .any(name::contains) || name in setOf("headers", "message", "tree", "thesis", "conditions")
                    }
                    if (!sensitive) clean.put(key, sanitize(value.get(key), depth + 1))
                }
            }
            is JSONArray -> JSONArray().also { clean ->
                repeat(minOf(value.length(), 200)) { clean.put(sanitize(value.get(it), depth + 1)) }
                if (value.length() > 200) clean.put("TRUNCATED")
            }
            is String -> if (value.length > 512) "TRUNCATED" else value
            else -> value
        }
    }

    /** Loopback-only raw HTTP is confined to this test APK; product TLS policy stays untouched. */
    suspend fun http(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        require(path.substringBefore('?') != "/qa/source") { "Source changes belong to the QA operator" }
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
            val campaignHeaders = campaign?.headers(config.getString("case_id"))?.entries
                ?.joinToString("") { "${it.key}: ${it.value}\r\n" }.orEmpty()
            val head = "$method $path HTTP/1.1\r\nHost: 127.0.0.1:${base.port}\r\n$authHeader: Bearer $token\r\nX-QA-Correlation: b3-$run-$phase\r\n${campaignHeaders}Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
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
            if (status !in 200..299) throw HttpFailure(status, HttpFailure.safeCode(json.toString()))
            json
        }
    }

    private suspend fun awaitConcurrentRelease(path: String, request: JSONObject) {
        val source = request.getJSONObject("source_context")
        val requestId = request.getString("request_id")
        require(request.getString("device_id") == deviceId)
        concurrentRequestId.compareAndSet(null, requestId)
        require(concurrentRequestId.get() == requestId)
        val intentId = path.substringAfter("/v2/planning/intents/").substringBefore("/preflight")
        val ready = JSONObject().put("run_id", run)
            .put("participant_id", config.getString("participant_id"))
            .put("device_id", deviceId).put("request_id", requestId)
            .put("intent_id", intentId).put("version", request.getInt("expected_version"))
            .put("source_id", source.getString("source_id"))
            .put("source_generation", source.getLong("source_generation"))
            .put("client_ready_at_utc", Instant.now().toString())
            .put("client_ready_monotonic_ns", System.nanoTime())
        val released = coordinator("/v1/barrier/ready", ready)
        require(released.getBoolean("released"))
        trace("two_device_barrier_released", JSONObject().put("ready", ready).put("release", released))
    }

    suspend fun coordinatorEvent(event: String, fields: JSONObject = JSONObject()) {
        if (phase != "concurrent") return
        val requestId = requireNotNull(concurrentRequestId.get())
        val payload = JSONObject().put("run_id", run)
            .put("participant_id", config.getString("participant_id"))
            .put("device_id", deviceId).put("request_id", requestId)
            .put("event", event).put("at_utc", Instant.now().toString())
        fields.keys().forEach { payload.put(it, fields.get(it)) }
        coordinator("/v1/events", payload)
        trace("two_device_coordinator_event", payload)
    }

    suspend fun coordinatorAbort(reason: String) {
        if (phase != "concurrent") return
        runCatching { coordinator("/v1/barrier/abort", JSONObject()
            .put("participant_id", config.getString("participant_id")).put("reason", reason)) }
    }

    /** Separate localhost-only coordinator channel. It has no auth header or non-loopback route. */
    private suspend fun coordinator(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val base = URI(config.getString("coordinator_url"))
        require(base.scheme == "http" && base.host == "127.0.0.1" && base.port in 1024..65535)
        require(path in setOf("/v1/barrier/ready", "/v1/barrier/abort", "/v1/events"))
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        Socket(base.host, base.port).use { socket ->
            socket.soTimeout = 90000
            val head = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:${base.port}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val next = input.read()
                    if (next == -1 || next == 10) break
                    if (next != 13) output.write(next)
                    require(output.size() < 16384)
                }
                return output.toString("UTF-8")
            }
            val status = line().split(" ")[1].toInt()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val next = line()
                if (next.isEmpty()) break
                headers[next.substringBefore(":").lowercase()] = next.substringAfter(":").trim()
            }
            val length = headers.getValue("content-length").toInt().also { require(it in 0..65536) }
            val raw = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(raw, offset, length - offset)
                check(count > 0); offset += count
            }
            val response = JSONObject(String(raw, Charsets.UTF_8))
            if (status !in 200..299) throw IOException("Local coordinator rejected request: " +
                response.optString("error", "UNKNOWN"))
            response
        }
    }

    suspend fun requireCampaign(operation: QaCampaignOperation): JSONObject {
        val started = System.nanoTime()
        val status = http("/qa/status")
        if (campaign != null) {
            val deadline = campaign.validate(status, operation,
                config.optString("session_start_utc").takeIf { it.isNotBlank() }?.let(Instant::parse),
                config.optString("session_end_utc").takeIf { it.isNotBlank() }?.let(Instant::parse))
            if (operation != QaCampaignOperation.READ) {
                val case = http("/qa/cases/${config.getString("case_id")}")
                require(case.getString("campaign_id") == campaign.campaignId &&
                    case.getString("session_id") == campaign.sessionId &&
                    case.getString("manifest_sha256") == campaign.manifestSha256)
                val conservativeNow = Instant.parse(status.getString("server_now"))
                    .plusNanos(System.nanoTime() - started)
                require(conservativeNow < deadline)
                if (operation == QaCampaignOperation.BUSINESS) require(
                    conservativeNow >= Instant.parse(case.getString("case_opens_at")) &&
                        conservativeNow < Instant.parse(case.getString("case_ends_at")))
            }
        }
        else if (operation != QaCampaignOperation.READ) require(status.getString("session_state") == "ACTIVE")
        return status
    }

    /** Wait for a separately admitted operator action; never send /qa/source from mobile. */
    suspend fun awaitOperatorSourceSwitch(): JSONObject {
        val control = config.getJSONObject("operator_source_switch")
        val original = PlanningSourceContext.parse(control.getJSONObject("original_source"))
        val expected = PlanningSourceContext.parse(control.getJSONObject("switched_source"))
        require(original.sourceId != expected.sourceId && expected.sourceGeneration > original.sourceGeneration)
        trace("awaiting_qa_operator_source_switch", control)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline) {
            requireCampaign(QaCampaignOperation.BUSINESS)
            val current = http("/v2/planning/source")
            val source = PlanningContract.activeSource(current)
            if (source == expected) return current.also {
                lastSwitch = it
                trace("operator_source_switch_observed", JSONObject().put("source", it)
                    .put("mobile_source_mutation", false))
            }
            require(source == original) { "Unexpected source transition" }
            kotlinx.coroutines.delay(500)
        }
        error("QA operator source switch was not observed within the bounded wait")
    }
    suspend fun readback(): JSONObject = if (config.has("service_url")) {
        JSONObject().put("A", http("/qa/readback/A")).put("B", http("/qa/readback/B"))
            .put("trace", http("/qa/trace"))
    } else http(config.getString("readback_path"))
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
