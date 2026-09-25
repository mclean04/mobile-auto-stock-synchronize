package com.example.finance_planning.network

import com.example.finance_planning.core.Contracts
import org.json.JSONObject
import java.net.URLEncoder

class BackendApi(private val transport: Transport, private val headers: suspend () -> Map<String, String>,
                 private val baseUrl: String = Contracts.BACKEND) {
    var readSource: String? = null
    private fun scoped(path: String): String = readSource?.let {
        path + (if ("?" in path) "&" else "?") + "source=" + URLEncoder.encode(it, "UTF-8")
    } ?: path
    suspend fun adminSources(cursor: String? = null) = call(page("/v1/admin/sources", cursor))
    suspend fun adminRecords(source: String, cursor: String? = null) =
        call(page("/v1/admin/sources/" + Contracts.id(source) + "/records", cursor))
    private suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        JSONObject(transport.request(baseUrl + path, method, headers(), body))
    suspend fun health() = JSONObject(transport.request(baseUrl + "/health"))
    suspend fun syncStatus() = call("/v1/sync/status")
    suspend fun allPlanning(cursor: String? = null): JSONObject {
        val path = "/v2/planning/intents?view=all&limit=100" +
            (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        return call(path)
    }
    suspend fun planningIntent(id: String) =
        call("/v2/planning/intents/" + java.util.UUID.fromString(id))
    suspend fun planningSource() = call("/v2/planning/source")
    suspend fun planningPreflight(id: String, payload: JSONObject) =
        call("/v2/planning/intents/" + java.util.UUID.fromString(id) + "/preflight", "POST", payload)
    suspend fun importPlanning() = call("/v1/planning/import", "POST")
    suspend fun retryProjection() = call("/v1/sync/retry", "POST")
    suspend fun reconcile() = call("/v1/sheets/reconcile", "POST")
    suspend fun upload(batch: JSONObject) = call("/v1/sync/batches", "POST", batch)
    suspend fun placedOrder(payload: JSONObject) = call("/v1/orders/placed", "POST", payload)
    suspend fun placedOrderV2(payload: JSONObject) = call("/v2/orders/placed", "POST", payload)
    private fun page(path: String, cursor: String?) =
        path + "?limit=20" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
    suspend fun batches(cursor: String? = null) = call(scoped(page("/v1/sync/batches", cursor)))
    suspend fun batch(id: String) = call(scoped("/v1/sync/batches/" + java.util.UUID.fromString(id)))
    suspend fun orders(cursor: String? = null) = call(scoped(page("/v1/orders", cursor)))
    suspend fun order(account: String, id: String) =
        call(scoped("/v1/orders/" + Contracts.id(account) + "/" + Contracts.id(id)))
    suspend fun notifications(cursor: String? = null) = call(page("/v1/notifications", cursor))
    suspend fun notification(id: String) = call("/v1/notifications/" + java.util.UUID.fromString(id))
    suspend fun notificationPlan(plan: String) = call("/v1/notification-plans/" + Contracts.id(plan))
    suspend fun registerDevice(device: String, token: String) =
        call("/v1/devices/" + java.util.UUID.fromString(device), "PUT", JSONObject().put("fcm_token", token))
    suspend fun removeDevice(device: String) = call("/v1/devices/" + java.util.UUID.fromString(device), "DELETE")
    suspend fun receipt(event: String, device: String, state: String): JSONObject {
        require(state in setOf("RECEIVED", "OPENED"))
        return call("/v1/notifications/" + java.util.UUID.fromString(event) + "/receipts", "POST",
            JSONObject().put("device_id", device).put("state", state))
    }
    // Publishing/preview belongs to the planning operator, never executed automatically on mobile.
    suspend fun publishInstruction(command: JSONObject) = call("/v1/notifications", "POST", command)
    suspend fun previewNotification(command: JSONObject) = call("/v1/notification-preview", "POST", command)

    companion object {
        fun qaNotifications(config: com.example.finance_planning.core.QaNotificationConfig) =
            BackendApi(QaNotificationTransport(config.bearer), { emptyMap() },
                com.example.finance_planning.core.NotificationDeliveryPolicy.QA_BASE_URL)
    }
}
