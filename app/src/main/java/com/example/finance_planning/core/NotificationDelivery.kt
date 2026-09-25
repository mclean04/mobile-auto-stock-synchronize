package com.example.finance_planning.core

import com.example.finance_planning.BuildConfig
import org.json.JSONObject
import java.util.UUID

enum class NotificationEndpoint { PRODUCTION, QA }

data class QaNotificationConfig(
    val targetUid: String,
    val targetDeviceId: String,
    val namespace: String,
    val bearer: String
) {
    init {
        require(BuildConfig.DEBUG)
        require(targetUid.length in 1..128)
        UUID.fromString(targetDeviceId)
        require(NotificationDeliveryPolicy.validNamespace(namespace))
        require(bearer.isNotBlank() && bearer.length <= 4096 && '\r' !in bearer && '\n' !in bearer)
    }

    companion object {
        fun parse(value: JSONObject) = QaNotificationConfig(
            value.getString("target_uid"), value.getString("target_device_id"),
            value.getString("notification_namespace"), value.getString("bearer")
        )
    }
}

data class NotificationDelivery(
    val endpoint: NotificationEndpoint,
    val eventId: String,
    val targetUid: String,
    val planId: String? = null,
    val version: String? = null,
    val namespace: String? = null
) {
    init {
        UUID.fromString(eventId)
        require(targetUid.isNotBlank())
        if (endpoint == NotificationEndpoint.QA) {
            require(NotificationDeliveryPolicy.validTestPlan(requireNotNull(planId)))
            require(requireNotNull(version).toIntOrNull()?.let { it > 0 } == true)
            require(NotificationDeliveryPolicy.validNamespace(requireNotNull(namespace)))
        }
    }

    fun json(): JSONObject = JSONObject().put("endpoint", endpoint.name)
        .put("event_id", eventId).put("target_uid", targetUid)
        .put("plan_id", planId ?: JSONObject.NULL).put("version", version ?: JSONObject.NULL)
        .put("notification_namespace", namespace ?: JSONObject.NULL)

    companion object {
        fun parse(value: JSONObject) = NotificationDelivery(
            NotificationEndpoint.valueOf(value.getString("endpoint")), value.getString("event_id"),
            value.getString("target_uid"), value.optString("plan_id").takeIf { it.isNotBlank() },
            value.optString("version").takeIf { it.isNotBlank() },
            value.optString("notification_namespace").takeIf { it.isNotBlank() }
        )

        fun production(eventId: String, uid: String) =
            NotificationDelivery(NotificationEndpoint.PRODUCTION, eventId, uid)
    }
}

/** Fail-closed parsing for data-only FCM. Push metadata never selects a URL. */
object NotificationDeliveryPolicy {
    const val QA_BASE_URL = "http://127.0.0.1:18766"
    private val source = Regex("[A-Za-z0-9_-]{10,200}")
    private val testPlan = Regex("test-dnse-(daily|monthly)-[0-9]{8}-[0-9]{4}")
    private val qaKeys = setOf("schema_version", "event_id", "plan_id", "version", "type",
        "target_uid", "test_marker", "notification_namespace")

    fun validNamespace(value: String): Boolean {
        val separator = value.lastIndexOf(':')
        return separator > 0 && source.matches(value.substring(0, separator)) &&
            value.substring(separator + 1).toLongOrNull()?.let { it > 0 } == true
    }

    fun validTestPlan(value: String) = testPlan.matches(value)

    fun push(data: Map<String, String>, currentUid: String, qa: QaNotificationConfig?): NotificationDelivery? =
        runCatching {
            require(data["schema_version"] == "1")
            require(data["target_uid"] == currentUid)
            val event = UUID.fromString(data.getValue("event_id")).toString()
            if (data.containsKey("test_marker") || data.containsKey("notification_namespace")) {
                val config = requireNotNull(qa)
                require(config.targetUid == currentUid)
                require(data.keys == qaKeys)
                require(data["test_marker"] == "DEMO_ONLY")
                require(data["type"] == "PLANNING_REVIEW_REQUIRED")
                require(data["notification_namespace"] == config.namespace)
                NotificationDelivery(NotificationEndpoint.QA, event, currentUid,
                    data.getValue("plan_id"), data.getValue("version"), config.namespace)
            } else {
                require(qa == null) // A configured QA build never falls through to Production.
                NotificationDelivery.production(event, currentUid)
            }
        }.getOrNull()

    fun validateCanonical(event: JSONObject, delivery: NotificationDelivery, requireVisible: Boolean = false) {
        require(event.getString("event_id") == delivery.eventId)
        if (delivery.endpoint == NotificationEndpoint.QA) {
            require(event.getString("recipient_uid") == delivery.targetUid)
            require(event.getString("plan_id") == delivery.planId)
            require(event.get("version").toString() == delivery.version)
            require(event.getJSONObject("sheet").getString("mode") == "TEST")
            require(validTestPlan(event.getString("plan_id")))
            require(event.optBoolean("is_current"))
            if (requireVisible) require(event.optBoolean("test_notification_visible"))
            event.optString("notification_namespace").takeIf { it.isNotBlank() }?.let {
                require(it == delivery.namespace)
            }
        }
    }
}

class QaNotificationConfigStore(private val vault: Vault) {
    private val key = "qa-notification-config-v1"

    fun current(uid: String, deviceId: String): QaNotificationConfig? {
        if (!BuildConfig.DEBUG) return null
        return runCatching { vault.get(key)?.let(::JSONObject)?.let(QaNotificationConfig::parse) }
            .getOrNull()?.takeIf { it.targetUid == uid && it.targetDeviceId == deviceId }
    }

    fun present(): Boolean = BuildConfig.DEBUG && runCatching { vault.get(key) != null }.getOrDefault(true)

    fun install(value: JSONObject, uid: String, deviceId: String) {
        val config = QaNotificationConfig.parse(value)
        require(config.targetUid == uid && config.targetDeviceId == deviceId)
        vault.put(key, value.toString())
    }

    fun clear() = vault.remove(key)
}
