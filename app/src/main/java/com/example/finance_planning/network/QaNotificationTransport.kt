package com.example.finance_planning.network

import com.example.finance_planning.BuildConfig
import com.example.finance_planning.core.NotificationDeliveryPolicy
import com.example.finance_planning.core.QaNotificationConfig
import com.example.finance_planning.core.QaCampaignOperation
import com.example.finance_planning.core.QaIsolationDenied
import com.example.finance_planning.core.QaStartupIsolation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Debug-only, loopback-only QA transport. It never logs or accepts a bearer/URL from FCM. */
class QaNotificationTransport(private val bearer: String, private val config: QaNotificationConfig? = null,
    private val slotProvider: () -> QaBackendSlot = NetworkClients.application::qaNotifications) : Transport() {
    init {
        require(BuildConfig.DEBUG)
        require(bearer.isNotBlank() && bearer.length <= 4096 && '\r' !in bearer && '\n' !in bearer)
    }

    // QA remains on its isolated bearer/config protocol; it never constructs an ordinary Backend slot.
    override suspend fun planningHealth(): PlanningHealthDto = throw QaIsolationDenied()
    override suspend fun planningStatus(credentials: BackendCredentials): PlanningSyncStatusDto = throw QaIsolationDenied()
    override suspend fun planningRegister(id: String, request: PlanningDeviceRequest, credentials: BackendCredentials): PlanningDeviceDto {
        credentials.check()
        val raw = request(NotificationDeliveryPolicy.QA_BASE_URL + "/v1/devices/$id", "PUT", emptyMap(),
            JSONObject().put("fcm_token", request.fcm_token))
        return com.example.finance_planning.network.session.SessionGson.gson.fromJson(raw, PlanningDeviceDto::class.java).validated(id, true)
    }
    override suspend fun planningRevoke(id: String, credentials: BackendCredentials): PlanningDeviceDto {
        credentials.check()
        val raw = request(NotificationDeliveryPolicy.QA_BASE_URL + "/v1/devices/$id", "DELETE", emptyMap(), null)
        return com.example.finance_planning.network.session.SessionGson.gson.fromJson(raw, PlanningDeviceDto::class.java).validated(id, false)
    }

    override suspend fun request(url: String, method: String, headers: Map<String, String>,
                                 body: JSONObject?): String = withContext(Dispatchers.IO) {
        require(headers.isEmpty())
        require(allowlisted(url, method))
        val uri = URI(url)
        requireIsolatedTarget(uri, method, body)
        val path = uri.rawPath
        var caseId: String? = null
        config?.campaign?.let { campaign ->
            val status = JSONObject(exchange(NotificationDeliveryPolicy.QA_BASE_URL + "/qa/status", "GET", null, emptyMap()))
            val receipt = method == "POST" && path.endsWith("/receipts")
            campaign.validate(status, when {
                method == "GET" -> QaCampaignOperation.READ
                receipt -> QaCampaignOperation.COMPLETION
                else -> QaCampaignOperation.PREPARE
            })
            if (receipt) {
                val eventId = path.removePrefix("/v1/notifications/").removeSuffix("/receipts")
                caseId = requireNotNull(config.eventCases[eventId]) { "Unbound QA receipt" }
                val binding = JSONObject(exchange(NotificationDeliveryPolicy.QA_BASE_URL + "/qa/cases/$caseId",
                    "GET", null, campaign.headers(caseId)))
                campaign.validateNotificationBinding(caseId!!, eventId, binding)
            }
        }
        exchange(url, method, body, config?.campaign?.headers(caseId) ?: emptyMap())
    }

    /** Only explicit config installation reads binding metadata; nothing is derived from push. */
    suspend fun verifyCampaignBindings() = withContext(Dispatchers.IO) {
        config?.campaign?.let { campaign ->
            val status = JSONObject(exchange(NotificationDeliveryPolicy.QA_BASE_URL + "/qa/status", "GET", null, emptyMap()))
            campaign.validate(status, QaCampaignOperation.READ)
            for ((event, case) in config.eventCases) {
                val readback = JSONObject(exchange(NotificationDeliveryPolicy.QA_BASE_URL + "/qa/cases/$case",
                    "GET", null, campaign.headers(case)))
                campaign.validateNotificationBinding(case, event, readback)
            }
        }
    }

    private fun requireIsolatedTarget(uri: URI, method: String, body: JSONObject?) {
        QaStartupIsolation.requireTransportConfig(config)
        if (QaStartupIsolation.active) {
            val trusted = requireNotNull(config)
            if (uri.path.startsWith("/v1/devices/"))
                require(uri.path == "/v1/devices/${trusted.targetDeviceId}")
            if (uri.path.startsWith("/v1/notifications/")) {
                val event = uri.path.removePrefix("/v1/notifications/").removeSuffix("/receipts")
                require(trusted.eventCases.containsKey(event))
                if (method == "POST") require(body?.getString("device_id") == trusted.targetDeviceId)
            }
        }
    }

    private suspend fun exchange(url: String, method: String, body: JSONObject?, campaignHeaders: Map<String, String>): String {
        require(allowlisted(url, method))
        val uri = URI(url)
        requireIsolatedTarget(uri, method, body)
        val payload = body?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        require(payload.size <= 2 * 1024 * 1024)
        val headers = mapOf("X-Planning-Authorization" to "Bearer $bearer", "Accept" to "application/json",
            "Content-Type" to "application/json", "Content-Length" to payload.size.toString(),
            "Connection" to "close", "Accept-Encoding" to "identity") + campaignHeaders
        try {
            val response = slotProvider().request(url, method, headers,
                if (method in setOf("POST", "PUT")) payload.toRequestBody("application/json".toMediaType()) else null,
                verify = { requireIsolatedTarget(uri, method, body) })
            (response.body() ?: response.errorBody()).use {
                val raw = it?.readLimited(4_000_000) ?: ""
                if (!response.isSuccessful) throw HttpFailure(response.code(), HttpFailure.safeCode(raw))
                return raw.ifBlank { "{}" }
            }
        } catch (_: HttpResponseTooLarge) {
            throw IllegalArgumentException("Response exceeds size limit")
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
                method == "GET" && path in setOf("/qa/status", "/qa/cases/N1", "/qa/cases/N2") -> Unit
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
