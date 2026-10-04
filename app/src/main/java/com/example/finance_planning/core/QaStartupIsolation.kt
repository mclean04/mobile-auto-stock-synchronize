package com.example.finance_planning.core

import com.example.finance_planning.BuildConfig
import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import org.json.JSONObject

/** Immutable, reviewed APK selection: effective before Application/providers, never inferred from FCM. */
object QaStartupIsolation {
    const val ADMISSION_FILE = "qa-startup-admission.json"
    @Volatile private var selectedOnDisk = false
    @Volatile private var admission: QaStartupAdmission? = null
    val active: Boolean get() = BuildConfig.QA_STARTUP_ISOLATED || selectedOnDisk
    val admissionValid: Boolean get() = admission != null
    val policy: QaStartupPolicy get() = QaStartupPolicy(active)

    /** Called from attachBaseContext before any target ContentProvider can initialize. Never writes. */
    fun attach(context: Context) {
        loadPrivateSelection(File(context.filesDir, ADMISSION_FILE))
    }

    internal fun loadPrivateSelection(file: File) {
        admission = null
        // An unreadable/malformed/partly restored selection is CLOSED, never ordinary mode.
        selectedOnDisk = true
        admission = if (selectedOnDisk) runCatching {
            val attributes = try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                selectedOnDisk = false
                return
            }
            require(attributes.isRegularFile && attributes.size() in 1..16_384)
            QaStartupAdmission.parse(JSONObject(file.readText()))
        }.getOrNull() else null
    }

    fun requireBusiness() = policy.requireBusiness()
    fun requireNotification(config: QaNotificationConfig?, uid: String?, device: String?) {
        if (!active) return
        policy.requireNotificationConfig(config, uid, device)
        requireNotNull(admission) { "QA_ADMISSION_CLOSED" }.requireConfig(requireNotNull(config))
    }
    fun requireTransportConfig(config: QaNotificationConfig?) {
        if (active) requireNotification(config, config?.targetUid, config?.targetDeviceId)
    }
    fun firebaseAllowed(project: String): Boolean = !active ||
        (project == "auto-stock-synchronization" && admission != null)
}

data class QaStartupAdmission(val targetUid: String, val deviceId: String,
                              val namespace: String, val campaign: QaCampaign) {
    fun requireConfig(config: QaNotificationConfig) {
        if (config.targetUid != targetUid || config.targetDeviceId != deviceId ||
            config.namespace != namespace || config.campaign != campaign) throw QaIsolationDenied()
    }
    companion object {
        fun parse(value: JSONObject): QaStartupAdmission {
            require(value.keys().asSequence().toSet() == setOf("schema_version", "mode", "firebase_project_id",
                "target_uid", "target_device_id", "notification_namespace", "campaign"))
            require(value.getString("schema_version") == "finance-qa-startup-admission.v1")
            require(value.getString("mode") == "QA_NOTIFICATION")
            require(value.getString("firebase_project_id") == "auto-stock-synchronization")
            val uid = value.getString("target_uid").also { require(it.length in 1..128) }
            val device = value.getString("target_device_id").also(java.util.UUID::fromString)
            val namespace = value.getString("notification_namespace").also {
                require(NotificationDeliveryPolicy.validNamespace(it)) }
            val campaign = QaCampaign.parse(value.getJSONObject("campaign"))
            require(campaign.sessionKind == "NOTIFICATION")
            return QaStartupAdmission(uid, device, namespace, campaign)
        }
    }
}

class QaIsolationDenied : IllegalStateException("QA_ISOLATION_DENIED")

/** Pure policy shared by startup callbacks, recovered work and repository/network boundaries. */
class QaStartupPolicy(val isolated: Boolean) {
    fun requireBusiness() { if (isolated) throw QaIsolationDenied() }

    fun tokenCallback(businessRefresh: () -> Unit, qaRegistration: () -> Unit) {
        if (isolated) qaRegistration() else businessRefresh()
    }

    fun deferSavedWork(endpoint: NotificationEndpoint?, campaignBound: Boolean): Boolean =
        isolated && (endpoint != NotificationEndpoint.QA || !campaignBound)

    fun requireNotificationConfig(config: QaNotificationConfig?, uid: String?, device: String?) {
        if (!isolated) return
        if (uid == null || device == null || config == null || config.campaign == null ||
            config.targetUid != uid || config.targetDeviceId != device) throw QaIsolationDenied()
    }

}
