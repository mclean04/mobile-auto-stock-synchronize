package com.example.finance_planning.core

import org.json.JSONArray
import org.json.JSONObject

class AppFailure(val safeMessage: String, val retryable: Boolean = false) : Exception(safeMessage)

fun JSONObject.objects(key: String): List<JSONObject> =
    optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } } ?: emptyList()

object Contracts {
    const val BACKEND = "https://planning-backend-1026748304024.asia-southeast1.run.app"
    fun id(value: String): String {
        require(Regex("^[A-Za-z0-9_:.-]{1,100}$").matches(value)) { "Invalid identifier" }
        return value
    }
    fun batch(device: String, records: List<JSONObject>, batchId: String): JSONObject {
        require(records.size <= 100)
        return JSONObject().put("batch_id", batchId).put("device_id", device)
            .put("orders", JSONArray(records)).put("executions", JSONArray())
            .put("positions", JSONArray()).put("balances", JSONArray())
    }
    fun mayReview(event: JSONObject): Boolean =
        event.optBoolean("requires_review") && event.optBoolean("is_current") &&
            event.optBoolean("revision_is_current") && !event.optBoolean("broker_action_executed")
}
