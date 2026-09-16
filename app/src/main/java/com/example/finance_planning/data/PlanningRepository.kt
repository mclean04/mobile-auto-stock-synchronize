package com.example.finance_planning.data

import androidx.room.withTransaction
import com.example.finance_planning.auth.MobileIdentity
import com.example.finance_planning.core.*
import com.example.finance_planning.network.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class PlanningRepository(val identity: MobileIdentity, private val vault: Vault,
                         private val db: LocalDb, val api: BackendApi) {
    private val lock = Mutex()
    private val dao = db.dao()
    fun owner() = identity.uid() ?: throw AppFailure("Hãy đăng nhập để truy cập dữ liệu trên máy.")
    fun device(): String {
        val key = "device:" + owner()
        return vault.get(key) ?: UUID.randomUUID().toString().also { vault.put(key, it) }
    }
    fun invalidateSession() { vault.remove("approved") }
    fun approved(): Boolean = identity.uid()?.let { vault.get("approved") == it && vault.get("mobile_scope") == "uploader-v1:$it" } ?: false
    suspend fun verifySession() {
        api.syncStatus() // Firebase sign-in alone is not backend authorization.
        if (vault.get("mobile_scope") != "uploader-v1:${owner()}") save("dnse", JSONObject())
        vault.put("mobile_scope", "uploader-v1:${owner()}")
        vault.put("approved", owner())
        save("planning", JSONObject())
        save("notifications", JSONObject())
        registerPush()
    }
    suspend fun cached(key: String): JSONObject? =
        dao.cached(owner(), key)?.let { JSONObject(vault.open(it.ciphertext)) }
    private suspend fun save(key: String, json: JSONObject) {
        dao.cache(CacheRow(owner(), key, vault.seal(json.toString()), System.currentTimeMillis()))
    }
    suspend fun planning(refresh: Boolean = true): JSONObject {
        if (refresh) save("planning", api.latestPlanning())
        return cached("planning") ?: JSONObject()
    }
    suspend fun notifications(refresh: Boolean = true): JSONObject {
        if (refresh) save("notifications", api.notifications())
        return cached("notifications") ?: JSONObject()
    }
    suspend fun openNotification(id: String): JSONObject {
        val event = api.notification(id)
        api.receipt(id, device(), "OPENED")
        return event
    }
    suspend fun receiveNotification(id: String, state: String = "RECEIVED"): JSONObject {
        val event = api.notification(id)
        api.receipt(id, device(), state)
        save("notification:" + id, event)
        return event
    }
    fun saveDnse(key: String, secret: String, production: Boolean, vndPerUnit: String) {
        require(key.isNotBlank() && secret.isNotBlank())
        require(vndPerUnit in setOf("1", "1000"))
        val uid = owner()
        vault.put("dnse:$uid", JSONObject().put("key", key.trim()).put("secret", secret.trim())
            .put("production", production).put("vndPerUnit", vndPerUnit).toString())
    }
    fun hasDnse() = identity.uid()?.let { vault.get("dnse:$it") != null } ?: false
    suspend fun localBatches(): List<PendingBatch> = dao.batches(owner())
    suspend fun sync(): String = lock.withLock {
        if (!approved()) throw AppFailure("Backend chưa xác nhận quyền mobile. Hãy kiểm tra kết nối.")
        val uid = owner()
        flush(uid)
        val config = vault.get("dnse:$uid")?.let(::JSONObject) ?: throw AppFailure("Hãy lưu khóa DNSE trước.")
        val production = config.getBoolean("production")
        val dnse = DnseApi(Transport(), config.getString("key"), config.getString("secret"), production)
        val now = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        val orders = linkedMapOf<String, JSONObject>()
        val executions = linkedMapOf<String, JSONObject>()
        val positions = linkedMapOf<String, JSONObject>()
        val balances = linkedMapOf<String, JSONObject>()
        val accounts = dnse.accounts()
        val overview = JSONArray()
        val multiplier = BigDecimal(config.getString("vndPerUnit"))
        for (account in accounts) {
            val id = DnseApi.text(account, "id", "accountNo")
            val rawOrders = dnse.history(id, now.minusDays(29), now) +
                dnse.today(id, "NORMAL") + dnse.today(id, "STOP")
            for (raw in rawOrders) {
                val item = DnseApi.normalizeOrder(id, raw, multiplier)
                val key = id + ":" + item.getString("order_id")
                val previous = orders[key]
                if (previous == null || java.time.Instant.parse(item.getString("updated_at")) >
                    java.time.Instant.parse(previous.getString("updated_at"))) orders[key] = item
                else if (item.getString("updated_at") == previous.getString("updated_at") &&
                    item.toString() != previous.toString()) throw AppFailure("DNSE trả hai bản ghi khác nhau cùng thời điểm.")
            }

            for (order in orders.values.filter { it.getString("account") == id &&
                it.optString("category", "NORMAL") == "NORMAL" &&
                BigDecimal(it.getString("filled_quantity")).signum() > 0 }) {
                val orderId = order.getString("order_id")
                val rawExecutions = dnse.executions(id, orderId)
                for (raw in DnseApi.rowsOrSingle(rawExecutions, "data", "executions", "items")) {
                    val item = DnseApi.normalizeExecution(id, orderId, raw, multiplier)
                    executions[id + ":" + item.getString("execution_id")] = item
                }
            }

            val observedAt = java.time.Instant.now()
            val rawBalance = dnse.balances(id)
            val balanceSource = when (rawBalance) {
                is JSONObject -> rawBalance.optJSONObject("data")
                    ?: rawBalance.optJSONObject("balance") ?: rawBalance
                else -> throw AppFailure("Định dạng số dư DNSE chưa được hỗ trợ.")
            }
            balances[id] = DnseApi.normalizeBalance(id, balanceSource, multiplier, observedAt)
            val rawPositions = dnse.positions(id)
            for (raw in DnseApi.rowsOrSingle(rawPositions, "data", "positions", "items")) {
                if (!raw.has("symbol") && !raw.has("instrument") && !raw.has("stockSymbol")) continue
                val item = DnseApi.normalizePosition(id, raw, multiplier, observedAt)
                positions[id + ":" + item.getString("position_id")] = item
            }
            // Preserve raw broker responses only inside the encrypted local cache.
            overview.put(JSONObject().put("account", id).put("balances", rawBalance)
                .put("positions", rawPositions))
        }
        if (owner() != uid) throw AppFailure("Phiên đăng nhập đã thay đổi.")
        val orderList = orders.values.toList()
        val executionList = executions.values.toList()
        val positionList = positions.values.toList()
        val balanceList = balances.values.toList()
        val snapshot = JSONObject().put("orders", JSONArray(orderList))
            .put("executions", JSONArray(executionList)).put("positions", JSONArray(positionList))
            .put("balances", JSONArray(balanceList)).put("accounts", overview)
            .put("saved_at", java.time.Instant.now().toString()).put("production", production)
        val previous = cached("dnse")?.takeIf { it.optBoolean("production") == production }
        fun previousMap(kind: String, key: (JSONObject) -> String): Map<String, JSONObject> =
            previous?.objects(kind)?.associateBy(key) ?: emptyMap()
        fun comparable(item: JSONObject, observedSnapshot: Boolean): String =
            JSONObject(item.toString()).apply { if (observedSnapshot) remove("updated_at") }.toString()
        val oldOrders = previousMap("orders") { it.getString("account") + ":" + it.getString("order_id") }
        val oldExecutions = previousMap("executions") { it.getString("account") + ":" + it.getString("execution_id") }
        val oldPositions = previousMap("positions") { it.getString("account") + ":" + it.getString("position_id") }
        val oldBalances = previousMap("balances") { it.getString("account") }
        val changed = mutableListOf<Pair<String, JSONObject>>()
        orderList.filter { oldOrders[it.getString("account") + ":" + it.getString("order_id")]?.toString() != it.toString() }
            .forEach { changed += "order" to it }
        executionList.filter { oldExecutions[it.getString("account") + ":" + it.getString("execution_id")]?.toString() != it.toString() }
            .forEach { changed += "execution" to it }
        positionList.filter { item -> oldPositions[item.getString("account") + ":" + item.getString("position_id")]
            ?.let { comparable(it, true) } != comparable(item, true) }.forEach { changed += "position" to it }
        balanceList.filter { item -> oldBalances[item.getString("account")]
            ?.let { comparable(it, true) } != comparable(item, true) }.forEach { changed += "balance" to it }
        val pending = if (production) changed.chunked(100).map { records ->
            val batchId = UUID.randomUUID().toString()
            val payload = Contracts.batch(device(),
                records.filter { it.first == "order" }.map { it.second },
                records.filter { it.first == "execution" }.map { it.second },
                records.filter { it.first == "position" }.map { it.second },
                records.filter { it.first == "balance" }.map { it.second }, batchId)
            PendingBatch(uid, batchId, vault.seal(payload.toString()), System.currentTimeMillis())
        } else emptyList()
        db.withTransaction {
            save("dnse", snapshot)
            dao.enqueue(pending)
        }
        if (!production) return@withLock "Đã đọc ${orderList.size} lệnh, ${executionList.size} khớp lệnh, " +
            "${positionList.size} vị thế sandbox trên máy; không gửi vào planning thật."
        flush(uid)
        val sheet = api.retryProjection()
        vault.put("last_sync:$uid", java.time.Instant.now().toString())
        "Đã đồng bộ ${orderList.size} lệnh, ${executionList.size} khớp lệnh, " +
            "${positionList.size} vị thế và ${balanceList.size} số dư. Sheet: ${sheet.optString("state")}"
    }
    suspend fun retryPending(): String = lock.withLock {
        if (!approved()) throw AppFailure("Backend chưa cấp quyền mobile.")
        flush(owner())
        val result = api.retryProjection()
        "Đã gửi lại dữ liệu chờ. Sheet: ${result.optString("state")}"
    }
    private suspend fun flush(uid: String) {
        if (dao.batches(uid).any { it.state == "REVIEW_REQUIRED" })
            throw AppFailure("Có đợt dữ liệu cần đối chiếu. Không tạo đợt mới để vượt qua lỗi.")
        for (row in dao.pending(uid)) {
            if (owner() != uid || !approved()) throw AppFailure("Phiên đăng nhập đã thay đổi.")
            try {
                val result = api.upload(JSONObject(vault.open(row.ciphertext)))
                if (result.optString("database") != "committed" || result.optString("batch_id") != row.id)
                    throw AppFailure("Chưa nhận được xác nhận lưu dữ liệu từ backend.", true)
                dao.mark(uid, row.id, "COMMITTED", "")
            } catch (e: HttpFailure) {
                if (e.status == 409 || e.status == 422)
                    dao.mark(uid, row.id, "REVIEW_REQUIRED", "HTTP ${e.status}")
                throw e.safe()
            }
        }
    }
    fun lastSync() = identity.uid()?.let { vault.get("last_sync:$it") } ?: "Chưa đồng bộ"
    suspend fun registerPush() {
        if (approved()) api.registerDevice(device(), FirebaseMessaging.getInstance().token.await())
    }
    suspend fun logout() = lock.withLock {
        val uid = owner()
        // Revocation must succeed before claiming this device is disconnected.
        if (approved()) api.removeDevice(device())
        FirebaseMessaging.getInstance().deleteToken().await()
        vault.remove("approved")
        vault.remove("dnse:$uid")
        vault.remove("last_sync:$uid")
        db.withTransaction { dao.clearCache(uid); dao.clearBatches(uid) }
        identity.signOut()
    }
}
