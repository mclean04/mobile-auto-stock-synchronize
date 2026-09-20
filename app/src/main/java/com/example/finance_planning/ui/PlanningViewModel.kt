package com.example.finance_planning.ui

import com.example.finance_planning.R
import com.example.finance_planning.core.AppText
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.finance_planning.PlanningApp
import com.example.finance_planning.core.*
import com.example.finance_planning.network.HttpFailure
import com.example.finance_planning.sync.SyncSchedule
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class DetailKind { NOTIFICATION, ORDER, BATCH }

data class ScreenState(
    val dnse: JSONObject? = null, val pushRegistered: Boolean = false, val email: String = "",
    val notificationNavigation: Long = 0,
    val busy: Boolean = false, val message: String = AppText.get(R.string.welcome_sign_in_to_connect_to_planning),
    val signedIn: Boolean = false, val approved: Boolean = false, val configured: Boolean = false,
    val admin: Boolean = false, val sources: List<JSONObject> = emptyList(),
    val sourceCursor: String? = null, val selectedSource: String? = null,
    val adminRecords: List<JSONObject> = emptyList(), val recordCursor: String? = null,
    val scheduleEnabled: Boolean = false,
    val planningExecutionFresh: Boolean = false,
    val planningSavedAt: String? = null,
    val planning: JSONObject? = null, val notifications: List<JSONObject> = emptyList(),
    val orders: List<JSONObject> = emptyList(), val batches: List<JSONObject> = emptyList(),
    val notificationCursor: String? = null, val orderCursor: String? = null, val batchCursor: String? = null,
    val status: JSONObject? = null, val detail: JSONObject? = null, val detailKind: DetailKind? = null,
    val lastSync: String = AppText.get(R.string.not_synced_yet), val hasDnse: Boolean = false, val dnseProduction: Boolean? = null,
    val hasProductionKeys: Boolean = false, val hasSandboxKeys: Boolean = false,
    val localQueue: List<String> = emptyList()
)

class PlanningViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as PlanningApp).repository
    private val mutable = MutableStateFlow(ScreenState(configured = repo.identity.configured))
    val state = mutable.asStateFlow()
    private var notificationObserver: Job? = null
    private var observedUid: String? = null
    init {
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkFlow("planning-periodic").collect { work ->
                    mutable.value = mutable.value.copy(scheduleEnabled = work.any { !it.state.isFinished })
                }
        }
        restore()
    }
    fun copyFcmToken() = run {
        if (!repo.approved()) throw AppFailure(AppText.get(R.string.backend_mobile_not_verified))
        val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
        val context = getApplication<Application>()
        val clip = android.content.ClipData.newPlainText(AppText.get(R.string.fcm_token_label), token)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
        AppText.get(R.string.fcm_token_copied)
    }
    private fun observeNotifications() {
        val uid = repo.identity.uid()?.takeIf { repo.approved() }
        if (uid == observedUid) return
        notificationObserver?.cancel()
        observedUid = uid
        mutable.value = mutable.value.copy(notifications = emptyList())
        if (uid != null) notificationObserver = viewModelScope.launch {
            repo.observeNotifications(uid).collect { events ->
                if (repo.identity.uid() == uid && repo.approved()) {
                    val current = mutable.value
                    val detail = if (current.detailKind == DetailKind.NOTIFICATION)
                        events.firstOrNull { it.optString("event_id") == current.detail?.optString("event_id") }
                            ?: current.detail else current.detail
                    mutable.value = current.copy(notifications = events, detail = detail)
                }
            }
        }
    }
    private fun flags() {
        observeNotifications()
        if (!repo.approved()) {
            repo.api.readSource = null
            mutable.value = mutable.value.copy(admin = false, sources = emptyList(),
                selectedSource = null, adminRecords = emptyList(), planning = null,
                planningSavedAt = null, planningExecutionFresh = false,
                notifications = emptyList(), orders = emptyList(), batches = emptyList())
        }
        mutable.value = mutable.value.copy(signedIn = repo.identity.uid() != null,
            pushRegistered = repo.pushRegistered(), email = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email.orEmpty(),
            approved = repo.approved(), configured = repo.identity.configured,
            lastSync = repo.lastSync() ?: AppText.get(R.string.not_synced_yet), hasDnse = repo.hasDnse(), dnseProduction = repo.dnseProduction(),
            hasProductionKeys = repo.hasDnse(true), hasSandboxKeys = repo.hasDnse(false))
    }
    private fun run(action: suspend () -> String) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true)
            try {
                // The action may update state while suspended. Copy its latest result, not the old state.
                val message = action()
                mutable.value = mutable.value.copy(message = message)
            }
            catch (e: CancellationException) { throw e }
            catch (e: HttpFailure) { if (e.status == 401 || e.status == 403) repo.invalidateSession(); mutable.value = mutable.value.copy(message = e.safe().safeMessage) }
            catch (e: AppFailure) { mutable.value = mutable.value.copy(message = e.safeMessage) }
            catch (_: Exception) { mutable.value = mutable.value.copy(message =
                AppText.get(R.string.operation_incomplete)) }
            finally { flags(); mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun restore() = run {
        flags()
        if (repo.identity.uid() != null) {
            mutable.value = mutable.value.copy(planning = null, notifications = emptyList())
            queue()
            restoreCachedPlanning()
        }
        if (repo.identity.uid() != null) { val status = repo.verifySession(); refreshAll(knownStatus = status) } else if (!repo.identity.configured)
            AppText.get(R.string.firebase_build_not_configured)
        else AppText.get(R.string.sign_in_backend_hint)
    }
    fun signIn(context: Context) = run {
        mutable.value = ScreenState(busy = true, configured = repo.identity.configured)
        repo.identity.signIn(context)
        val status = repo.verifySession()
        refreshAll(knownStatus = status)
    }
    fun verify() = run { val status = repo.verifySession(); refreshAll(knownStatus = status) }
    fun saveDnseEnvironment(production: Boolean) = run {
        repo.saveDnseEnvironment(production)
        queue()
        if (production) AppText.get(R.string.dnse_production_saved)
        else AppText.get(R.string.sandbox_environment_saved_with_existing_keys)
    }
    fun health() = run { AppText.get(R.string.server, repo.api.health().optString("status")) }
    fun refresh() = run { val status = repo.verifySession(); refreshAll(knownStatus = status) }
    fun resume() = run {
        if (repo.approved()) { queue(); restoreCachedPlanning() }
        mutable.value.message
    }
    private suspend fun restoreCachedPlanning() {
        if (!repo.approved()) return
        mutable.value = mutable.value.copy(planning = repo.localPlanningAll(),
            planningExecutionFresh = false,
            planningSavedAt = repo.cachedTime("planning_all"))
    }
    fun refreshPlanning() = run {
        if (!repo.approved()) throw AppFailure(AppText.get(R.string.backend_mobile_not_verified))
        mutable.value = mutable.value.copy(planningExecutionFresh = false)
        val all = repo.refreshPlanningAll()
        mutable.value = mutable.value.copy(planning = all, planningSavedAt = repo.cachedTime("planning_all"),
            planningExecutionFresh = true)
        AppText.get(R.string.planning_cache_refreshed)
    }
    fun deleteDnse(production: Boolean) = run { repo.deleteDnse(production); queue(); AppText.get(R.string.dnse_keys_deleted) }
    private suspend fun refreshAll(knownStatus: JSONObject? = null): String {
        restoreCachedPlanning()
        val status = knownStatus ?: repo.api.syncStatus()
        val admin = status.optString("role") == "admin"
        if (!admin) repo.api.readSource = null
        val sources = if (admin) repo.api.adminSources() else JSONObject()
        val records = if (admin && repo.api.readSource != null)
            repo.api.adminRecords(repo.api.readSource!!) else JSONObject()
        val events = repo.notifications()
        val orders = repo.api.orders()
        val batches = repo.api.batches()
        mutable.value = mutable.value.copy(status = status, admin = admin, sources = sources.objects("items"),
            sourceCursor = cursor(sources), selectedSource = repo.api.readSource,
            adminRecords = records.objects("items"), recordCursor = cursor(records),
            notificationCursor = cursor(events),
            orders = orders.objects("items"), orderCursor = cursor(orders),
            batches = batches.objects("items"), batchCursor = cursor(batches))
        queue()
        return if (admin) AppText.get(R.string.admin_access_verified)
        else AppText.get(R.string.data_updated_for_the_signed_in_account)
    }
    private fun cursor(json: JSONObject): String? = if (json.isNull("next_cursor")) null else json.optString("next_cursor").takeIf { it.isNotBlank() }
    fun more(kind: String) = run {
        val s = mutable.value
        when (kind) {
            "notifications" -> s.notificationCursor?.let { cursor ->
                val uid = repo.owner()
                val p = repo.api.notifications(cursor)
                repo.cacheNotifications(p, uid)
                mutable.value = mutable.value.copy(notificationCursor = cursor(p))
            }
            "orders" -> s.orderCursor?.let { cursor ->
                val p = repo.api.orders(cursor)
                mutable.value = s.copy(orders = (s.orders + p.objects("items")).distinctBy { it.optString("id") }, orderCursor = cursor(p))
            }
            "batches" -> s.batchCursor?.let { cursor ->
                val p = repo.api.batches(cursor)
                mutable.value = s.copy(batches = (s.batches + p.objects("items")).distinctBy { it.optString("id") }, batchCursor = cursor(p))
            }
        }
        AppText.get(R.string.more_items_loaded)
    }
    fun notificationInbox() {
        mutable.value = mutable.value.copy(notificationNavigation = mutable.value.notificationNavigation + 1)
    }
    fun notification(id: String) {
        if (runCatching { java.util.UUID.fromString(id) }.isFailure) return
        mutable.value = mutable.value.copy(notificationNavigation = mutable.value.notificationNavigation + 1)
        viewModelScope.launch {
            state.first { !it.busy }
            openNotification(id)
        }
    }
    private fun openNotification(id: String) = run {
        val event = repo.openNotification(id)
        getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancel(id, 1)
        mutable.value = mutable.value.copy(detail = event, detailKind = DetailKind.NOTIFICATION)
        if (!event.optBoolean("local_only"))
            SyncSchedule.receipt(getApplication(), id, "OPENED", repo.owner())
        AppText.get(R.string.notification_loaded)
    }
    fun source(id: String) = run {
        if (!mutable.value.admin) throw AppFailure(AppText.get(R.string.this_feature_requires_admin_access))
        repo.api.readSource = id
        refreshAll()
    }
    fun moreSources() = run {
        if (!mutable.value.admin) throw AppFailure(AppText.get(R.string.this_feature_requires_admin_access))
        val s = mutable.value
        val p = repo.api.adminSources(s.sourceCursor ?: return@run AppText.get(R.string.no_more_data_sources))
        mutable.value = s.copy(sources = (s.sources + p.objects("items")).distinctBy { it.getString("id") },
            sourceCursor = cursor(p))
        AppText.get(R.string.more_data_sources_loaded)
    }
    fun moreRecords() = run {
        val s = mutable.value
        if (!s.admin) throw AppFailure(AppText.get(R.string.this_feature_requires_admin_access))
        val p = repo.api.adminRecords(s.selectedSource ?: return@run AppText.get(R.string.choose_a_source),
            s.recordCursor ?: return@run AppText.get(R.string.no_more_records))
        mutable.value = s.copy(adminRecords = s.adminRecords + p.objects("items"), recordCursor = cursor(p))
        AppText.get(R.string.more_data_loaded)
    }
    fun order(row: JSONObject) = run {
        val p = row.optJSONObject("payload") ?: row
        mutable.value = mutable.value.copy(detail = repo.api.order(p.getString("account"), p.getString("order_id")),
            detailKind = DetailKind.ORDER)
        AppText.get(R.string.synced_order_notice)
    }
    fun batch(id: String) = run {
        mutable.value = mutable.value.copy(detail = repo.api.batch(id), detailKind = DetailKind.BATCH)
        AppText.get(R.string.details_loaded)
    }
    fun dismissDetail() { mutable.value = mutable.value.copy(detail = null) }
    fun saveDnse(key: String, secret: String, production: Boolean, unit: String) = run {
        repo.saveDnse(key, secret, production, unit)
        queue()
        AppText.get(R.string.keys_saved_encrypted_on_this_device)
    }
    fun sync() = run { val result = try { repo.sync() } finally { queue() }; refreshAll(); result }
    fun retry() = run { val result = repo.retryPending(); queue(); result }
    private suspend fun queue() {
        mutable.value = mutable.value.copy(dnse = repo.dnseSnapshot(), localQueue = repo.localQueueSummary())
    }
    fun importPlanning() = run { repo.api.importPlanning(); refreshAll() }
    fun reconcile() = run { AppText.get(R.string.sheet_status, repo.api.reconcile().optString("state")) }
    fun schedule(enabled: Boolean) = run {
        if (enabled) {
            if (!repo.approved()) throw AppFailure(AppText.get(R.string.schedule_backend_required))
            SyncSchedule.enable(getApplication())
        } else SyncSchedule.cancel(getApplication())
        if (enabled) AppText.get(R.string.sync_schedule_enabled) else AppText.get(R.string.scheduled_sync_disabled)
    }
    fun logout() = run {
        repo.logout()
        repo.api.readSource = null
        SyncSchedule.cancelAccount(getApplication())
        mutable.value = ScreenState(configured = repo.identity.configured)
        AppText.get(R.string.logout_complete)
    }
}
