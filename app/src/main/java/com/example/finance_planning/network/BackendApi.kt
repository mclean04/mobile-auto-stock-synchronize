package com.example.finance_planning.network

import com.example.finance_planning.core.Contracts
import org.json.JSONObject
import java.net.URLEncoder

class BackendApi(private val transport: Transport, private val headers: suspend () -> Map<String, String>,
                 private val baseUrl: String = Contracts.BACKEND) : PlanningBackend {
    private fun scoped(path: String, source: String?): String = source?.let {
        path + (if ("?" in path) "&" else "?") + "source=" + URLEncoder.encode(it, "UTF-8")
    } ?: path
    override suspend fun adminSources(cursor: String?) = call(page("/v1/admin/sources", cursor))
    override suspend fun adminRecords(source: String, cursor: String?) =
        call(page("/v1/admin/sources/" + Contracts.id(source) + "/records", cursor))
    private suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        JSONObject(transport.request(baseUrl + path, method, headers(), body))
    override suspend fun health() = JSONObject(transport.request(baseUrl + "/health"))
    override suspend fun syncStatus() = call("/v1/sync/status")
    override suspend fun allPlanning(cursor: String?): JSONObject {
        val path = "/v2/planning/intents?view=all&limit=100" +
            (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        return call(path)
    }
    override suspend fun planningIntent(id: String) =
        call("/v2/planning/intents/" + java.util.UUID.fromString(id))
    override suspend fun planningSource() = call("/v2/planning/source")
    override suspend fun planningPreflight(id: String, payload: JSONObject) =
        call("/v2/planning/intents/" + java.util.UUID.fromString(id) + "/preflight", "POST", payload)
    override suspend fun importPlanning() = call("/v1/planning/import", "POST")
    override suspend fun retryProjection() = call("/v1/sync/retry", "POST")
    override suspend fun reconcile() = call("/v1/sheets/reconcile", "POST")
    override suspend fun upload(batch: JSONObject) = call("/v1/sync/batches", "POST", batch)
    override suspend fun placedOrder(payload: JSONObject) = call("/v1/orders/placed", "POST", payload)
    override suspend fun placedOrderV2(payload: JSONObject) = call("/v2/orders/placed", "POST", payload)
    private fun page(path: String, cursor: String?) =
        path + "?limit=20" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
    override suspend fun batches(cursor: String?, source: String?) = call(scoped(page("/v1/sync/batches", cursor), source))
    override suspend fun batch(id: String, source: String?) = call(scoped("/v1/sync/batches/" + java.util.UUID.fromString(id), source))
    override suspend fun orders(cursor: String?, source: String?) = call(scoped(page("/v1/orders", cursor), source))
    override suspend fun order(account: String, id: String, source: String?) =
        call(scoped("/v1/orders/" + Contracts.id(account) + "/" + Contracts.id(id), source))
    override suspend fun notifications(cursor: String?) = call(page("/v1/notifications", cursor))
    override suspend fun notification(id: String) = call("/v1/notifications/" + java.util.UUID.fromString(id))
    override suspend fun notificationPlan(plan: String) = call("/v1/notification-plans/" + Contracts.id(plan))
    override suspend fun registerDevice(device: String, token: String) =
        call("/v1/devices/" + java.util.UUID.fromString(device), "PUT", JSONObject().put("fcm_token", token))
    override suspend fun removeDevice(device: String) = call("/v1/devices/" + java.util.UUID.fromString(device), "DELETE")
    override suspend fun receipt(event: String, device: String, state: String): JSONObject {
        require(state in setOf("RECEIVED", "OPENED"))
        return call("/v1/notifications/" + java.util.UUID.fromString(event) + "/receipts", "POST",
            JSONObject().put("device_id", device).put("state", state))
    }
    // Publishing/preview belongs to the planning operator, never executed automatically on mobile.
    override suspend fun publishInstruction(command: JSONObject) = call("/v1/notifications", "POST", command)
    override suspend fun previewNotification(command: JSONObject) = call("/v1/notification-preview", "POST", command)

    companion object {
        fun qaNotifications(config: com.example.finance_planning.core.QaNotificationConfig,
                            clients: NetworkClients = NetworkClients.application) =
            BackendApi(QaNotificationTransport(config.bearer, config, clients::qaNotifications), { emptyMap() },
                com.example.finance_planning.core.NotificationDeliveryPolicy.QA_BASE_URL)
    }
}
