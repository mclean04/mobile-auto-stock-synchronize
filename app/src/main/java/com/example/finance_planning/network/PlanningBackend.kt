package com.example.finance_planning.network

import org.json.JSONObject

/** Existing repository/domain seam. JSON is legacy domain representation, never a dynamic URL API. */
interface PlanningBackend {
    suspend fun health(): PlanningHealthDto
    suspend fun syncStatus(): PlanningSyncStatusDto
    suspend fun adminSources(cursor: String? = null): JSONObject
    suspend fun adminRecords(source: String, cursor: String? = null): JSONObject
    suspend fun allPlanning(cursor: String? = null): JSONObject
    suspend fun planningIntent(id: String): JSONObject
    suspend fun planningSource(): JSONObject
    suspend fun planningPreflight(id: String, payload: JSONObject): JSONObject
    suspend fun importPlanning(): JSONObject
    suspend fun retryProjection(): JSONObject
    suspend fun reconcile(): JSONObject
    suspend fun upload(batch: JSONObject): JSONObject
    suspend fun placedOrder(payload: JSONObject): JSONObject
    suspend fun placedOrderV2(payload: JSONObject): JSONObject
    suspend fun batches(cursor: String? = null, source: String? = null): JSONObject
    suspend fun batch(id: String, source: String? = null): JSONObject
    suspend fun orders(cursor: String? = null, source: String? = null): JSONObject
    suspend fun order(account: String, id: String, source: String? = null): JSONObject
    suspend fun notifications(cursor: String? = null): JSONObject
    suspend fun notification(id: String): JSONObject
    suspend fun notificationPlan(plan: String): JSONObject
    suspend fun registerDevice(device: String, token: String): PlanningDeviceDto
    suspend fun removeDevice(device: String): PlanningDeviceDto
    suspend fun receipt(event: String, device: String, state: String): JSONObject
    suspend fun publishInstruction(command: JSONObject): JSONObject
    suspend fun previewNotification(command: JSONObject): JSONObject
}
