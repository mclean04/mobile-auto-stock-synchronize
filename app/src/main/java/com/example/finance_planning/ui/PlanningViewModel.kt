package com.example.finance_planning.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.finance_planning.PlanningApp
import com.example.finance_planning.core.*
import com.example.finance_planning.network.HttpFailure
import com.example.finance_planning.sync.SyncSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ScreenState(
    val busy: Boolean = false, val message: String = "Chào bạn. Đăng nhập để kết nối planning.",
    val signedIn: Boolean = false, val approved: Boolean = false, val configured: Boolean = false,
    val admin: Boolean = false, val sources: List<JSONObject> = emptyList(),
    val sourceCursor: String? = null, val selectedSource: String? = null,
    val adminRecords: List<JSONObject> = emptyList(), val recordCursor: String? = null,
    val planning: JSONObject? = null, val notifications: List<JSONObject> = emptyList(),
    val orders: List<JSONObject> = emptyList(), val batches: List<JSONObject> = emptyList(),
    val notificationCursor: String? = null, val orderCursor: String? = null, val batchCursor: String? = null,
    val status: JSONObject? = null, val detail: JSONObject? = null, val detailTitle: String = "",
    val lastSync: String = "Chưa đồng bộ", val hasDnse: Boolean = false, val dnseProduction: Boolean? = null,
    val localQueue: List<String> = emptyList()
)

class PlanningViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as PlanningApp).repository
    private val mutable = MutableStateFlow(ScreenState(configured = repo.identity.configured))
    val state = mutable.asStateFlow()
    init { restore() }
    private fun flags() {
        if (!repo.approved()) {
            repo.api.readSource = null
            mutable.value = mutable.value.copy(admin = false, sources = emptyList(),
                selectedSource = null, adminRecords = emptyList(), planning = null,
                notifications = emptyList(), orders = emptyList(), batches = emptyList())
        }
        mutable.value = mutable.value.copy(signedIn = repo.identity.uid() != null,
            approved = repo.approved(), configured = repo.identity.configured,
            lastSync = repo.lastSync() ?: "Chưa đồng bộ", hasDnse = repo.hasDnse(), dnseProduction = repo.dnseProduction())
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
                "Chưa hoàn tất. Kiểm tra cấu hình đăng nhập, kết nối và định dạng dữ liệu.") }
            finally { flags(); mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun restore() = run {
        flags()
        if (repo.identity.uid() != null) {
            mutable.value = mutable.value.copy(planning = null, notifications = emptyList())
            queue()
        }
        if (repo.approved()) refreshAll() else if (!repo.identity.configured)
            "Bản cài chưa có cấu hình Firebase. Có thể kiểm tra máy chủ; đăng nhập cần hoàn tất cấu hình."
        else "Đăng nhập Google, rồi kiểm tra quyền kết nối backend."
    }
    fun signIn(context: Context) = run {
        mutable.value = ScreenState(busy = true, configured = repo.identity.configured)
        repo.identity.signIn(context)
        repo.verifySession()
        refreshAll()
    }
    fun verify() = run { repo.verifySession(); refreshAll() }
    fun health() = run { "Máy chủ: " + repo.api.health().optString("status") }
    fun refresh() = run { refreshAll() }
    private suspend fun refreshAll(): String {
        val status = repo.api.syncStatus()
        val admin = status.optString("role") == "admin"
        if (!admin) repo.api.readSource = null
        val sources = if (admin) repo.api.adminSources() else JSONObject()
        val records = if (admin && repo.api.readSource != null)
            repo.api.adminRecords(repo.api.readSource!!) else JSONObject()
        val planning = if (admin) try { repo.planning() } catch (e: HttpFailure) {
            if (e.status == 404) null else throw e
        } else null
        val events = repo.notifications()
        val orders = repo.api.orders()
        val batches = repo.api.batches()
        mutable.value = mutable.value.copy(status = status, admin = admin, sources = sources.objects("items"),
            sourceCursor = cursor(sources), selectedSource = repo.api.readSource,
            adminRecords = records.objects("items"), recordCursor = cursor(records),
            planning = planning, notifications = events.objects("items"),
            notificationCursor = cursor(events),
            orders = orders.objects("items"), orderCursor = cursor(orders),
            batches = batches.objects("items"), batchCursor = cursor(batches))
        queue()
        return if (admin) "Đã xác minh quyền admin. Có thể truy cập tất cả nguồn dữ liệu."
        else "Đã cập nhật dữ liệu của tài khoản đang đăng nhập."
    }
    private fun cursor(json: JSONObject): String? = if (json.isNull("next_cursor")) null else json.optString("next_cursor").takeIf { it.isNotBlank() }
    fun more(kind: String) = run {
        val s = mutable.value
        when (kind) {
            "notifications" -> s.notificationCursor?.let { cursor ->
                val p = repo.api.notifications(cursor)
                mutable.value = s.copy(notifications = (s.notifications + p.objects("items")).distinctBy { it.optString("event_id", it.optString("id")) },
                    notificationCursor = cursor(p))
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
        "Đã tải thêm."
    }
    fun notification(id: String) {
        viewModelScope.launch {
            state.first { !it.busy }
            openNotification(id)
        }
    }
    private fun openNotification(id: String) = run {
        val event = repo.openNotification(id)
        mutable.value = mutable.value.copy(detail = event, detailTitle = "Thông báo planning")
        "Đã tải thông báo."
    }
    fun source(id: String) = run {
        if (!mutable.value.admin) throw AppFailure("Chức năng dành cho admin.")
        repo.api.readSource = id
        refreshAll()
    }
    fun moreSources() = run {
        if (!mutable.value.admin) throw AppFailure("Chức năng dành cho admin.")
        val s = mutable.value
        val p = repo.api.adminSources(s.sourceCursor ?: return@run "Đã hết nguồn dữ liệu.")
        mutable.value = s.copy(sources = (s.sources + p.objects("items")).distinctBy { it.getString("id") },
            sourceCursor = cursor(p))
        "Đã tải thêm nguồn dữ liệu."
    }
    fun moreRecords() = run {
        val s = mutable.value
        if (!s.admin) throw AppFailure("Chức năng dành cho admin.")
        val p = repo.api.adminRecords(s.selectedSource ?: return@run "Hãy chọn nguồn.",
            s.recordCursor ?: return@run "Đã hết bản ghi.")
        mutable.value = s.copy(adminRecords = s.adminRecords + p.objects("items"), recordCursor = cursor(p))
        "Đã tải thêm dữ liệu."
    }
    fun order(row: JSONObject) = run {
        val p = row.optJSONObject("payload") ?: row
        mutable.value = mutable.value.copy(detail = repo.api.order(p.getString("account"), p.getString("order_id")),
            detailTitle = "Chi tiết lệnh")
        "Đây là dữ liệu đã đồng bộ; không phải xác nhận giao dịch mới."
    }
    fun batch(id: String) = run {
        mutable.value = mutable.value.copy(detail = repo.api.batch(id), detailTitle = "Chi tiết đợt đồng bộ")
        "Đã tải chi tiết."
    }
    fun dismissDetail() { mutable.value = mutable.value.copy(detail = null) }
    fun saveDnse(key: String, secret: String, production: Boolean, unit: String) = run {
        repo.saveDnse(key, secret, production, unit)
        "Đã lưu khóa bằng mã hóa trên thiết bị."
    }
    fun sync() = run { val result = repo.sync(); queue(); result }
    fun retry() = run { val result = repo.retryPending(); queue(); result }
    private suspend fun queue() {
        mutable.value = mutable.value.copy(localQueue = repo.localBatches().map { "${it.id.take(8)} • ${it.state} ${it.error}" })
    }
    fun importPlanning() = run { repo.api.importPlanning(); repo.planning(); refreshAll() }
    fun reconcile() = run { "Trạng thái Sheet: " + repo.api.reconcile().optString("state") }
    fun schedule(enabled: Boolean) = run {
        if (enabled) {
            if (!repo.approved()) throw AppFailure("Cần backend cấp quyền trước khi bật lịch.")
            SyncSchedule.enable(getApplication())
        } else SyncSchedule.cancel(getApplication())
        if (enabled) "Đã bật lịch mỗi 6 giờ; Android có thể chạy trễ khi tiết kiệm pin." else "Đã tắt lịch đồng bộ."
    }
    fun logout() = run {
        repo.logout()
        repo.api.readSource = null
        SyncSchedule.cancel(getApplication())
        mutable.value = ScreenState(configured = repo.identity.configured)
        "Đã gỡ thiết bị khỏi backend và xóa khóa/dữ liệu local của phiên này."
    }
}
