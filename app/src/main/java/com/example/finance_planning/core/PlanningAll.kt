package com.example.finance_planning.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

enum class PlanningSection { ALL, UPCOMING, HISTORY }

/** One device instant partitions one durable snapshot; date-only values never imply midnight. */
object PlanningTimeline {
    fun scheduledAt(row: JSONObject): Instant? = runCatching {
        require(row.getString("time_status") == "EXACT")
        OffsetDateTime.parse(row.getString("scheduled_at")).toInstant()
    }.getOrNull()

    fun rows(snapshot: JSONObject?, section: PlanningSection, now: Instant,
             zone: ZoneId = ZoneId.systemDefault()): List<JSONObject> {
        val rows = snapshot?.objects("items").orEmpty().filter { row ->
            when (section) {
                PlanningSection.ALL -> true
                PlanningSection.UPCOMING -> scheduledAt(row)?.let { !it.isBefore(now) } == true
                PlanningSection.HISTORY -> scheduledAt(row)?.isBefore(now) == true
            }
        }

        // No priority field/rule exists in contract v2. Do not infer one from Sheet order.
        return if (section == PlanningSection.UPCOMING) rows.sortedWith(
            compareBy<JSONObject> { requireNotNull(scheduledAt(it)).atZone(zone).toLocalDate() }
                .thenBy { requireNotNull(scheduledAt(it)) }
                .thenBy { it.getString("intent_id") }
        ) else rows
    }

    fun mayOpenAction(section: PlanningSection, row: JSONObject, now: Instant): Boolean =
        section == PlanningSection.UPCOMING && row.optString("record_kind") == "CANONICAL" &&
            scheduledAt(row)?.let { !it.isBefore(now) } == true
}

/** Loading local data never calls the network. Only explicit refresh builds and commits All. */
class PlanningAllStore(
    private val read: suspend () -> JSONObject?,
    private val fetchPage: suspend (String?) -> JSONObject,
    private val replaceAtomically: suspend (JSONObject) -> Unit
) {
    suspend fun local(): JSONObject? = read()

    suspend fun refresh(): JSONObject {
        val items = JSONArray()
        val ids = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var source: PlanningSourceContext? = null
        var snapshotId: String? = null
        var total: Int? = null
        do {
            val page = fetchPage(cursor)
            require(page.getString("contract_version") == "2.0")
            val pageSource = PlanningSourceContext.parse(page.getJSONObject("source_context"))
            if (source == null) source = pageSource else require(source == pageSource)
            val pageSnapshot = page.getString("snapshot_id").also { require(it.isNotBlank()) }
            val count = page.get("total_count").also { require(it is Int && it >= 0) } as Int
            if (snapshotId == null) { snapshotId = pageSnapshot; total = count }
            else { require(snapshotId == pageSnapshot && total == count) }
            val rows = page.getJSONArray("items")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                require(PlanningSourceContext.parse(row.getJSONObject("source_context")) == source)
                require(ids.add(row.getString("intent_id").also { require(it.isNotBlank()) }))
                // Preserve all states and unknown timestamps. Eligibility parsing is an action gate only.
                items.put(JSONObject(row.toString()))
            }
            require(items.length() <= requireNotNull(total))
            require(page.has("next_cursor"))
            cursor = if (page.isNull("next_cursor")) null else (page.get("next_cursor") as String).also {
                require(it.isNotBlank() && cursors.add(it))
            }
        } while (cursor != null)
        require(items.length() == total)
        val snapshot = JSONObject().put("contract_version", "2.0")
            .put("snapshot_id", snapshotId).put("total_count", total)
            .put("source_context", requireNotNull(source).json()).put("items", items)
            .put("next_cursor", JSONObject.NULL)
        replaceAtomically(snapshot)
        return snapshot
    }
}
