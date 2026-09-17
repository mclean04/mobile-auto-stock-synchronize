package com.example.finance_planning.data

import com.example.finance_planning.R
import com.example.finance_planning.core.AppText
import androidx.room.withTransaction
import com.example.finance_planning.auth.MobileIdentity
import com.example.finance_planning.core.*
import com.example.finance_planning.network.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asSharedFlow
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
    private val dnseCredentials = DnseCredentialStore(vault::get, vault::put, vault::remove)
    private val lock = Mutex()
    private val dao = db.dao()
    private val notificationLock = Mutex()
    private val planningCacheLock = Mutex()
    private val arrivals = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 16)
    val notificationArrivals = arrivals.asSharedFlow()
    fun announceNotification(uid: String, event: JSONObject) {
        if (identity.uid() == uid && approved()) arrivals.tryEmit(uid to event)
    }
    fun owner() = identity.uid() ?: throw AppFailure(AppText.get(R.string.sign_in_to_access_data_on_this_device))
    fun device(): String {
        val key = "device:" + owner()
        return vault.get(key) ?: UUID.randomUUID().toString().also { vault.put(key, it) }
    }
    fun invalidateSession() { vault.remove("approved") }
    fun approved(): Boolean = identity.uid()?.let { vault.get("approved") == it && vault.get("mobile_scope") == "uploader-v1:$it" } ?: false
    suspend fun verifySession(): JSONObject {
        val status = api.syncStatus() // Firebase sign-in alone is not backend authorization.
        if (vault.get("mobile_scope") != "uploader-v1:${owner()}") save("dnse", JSONObject())
        vault.put("mobile_scope", "uploader-v1:${owner()}")
        vault.put("approved", owner())
        registerPush()
        return status
    }
    suspend fun cached(key: String): JSONObject? =
        dao.cached(owner(), key)?.let { JSONObject(vault.open(it.ciphertext)) }
    private suspend fun save(key: String, json: JSONObject) {
        dao.cache(CacheRow(owner(), key, vault.seal(json.toString()), System.currentTimeMillis()))
    }
    suspend fun cachedTime(key: String): String? = dao.cached(owner(), key)?.let {
        java.time.Instant.ofEpochMilli(it.savedAt).toString()
    }
    private suspend fun planningRead(key: String, refresh: Boolean, fetch: suspend () -> JSONObject): JSONObject = planningCacheLock.withLock {
        val uid = owner()
        CacheFirstRead.load(refresh, { cached(key) }, {
            try { fetch() } catch (e: HttpFailure) {
                if (e.status != 404) throw e
                JSONObject().put("items", JSONArray()).put("_not_available", true)
            }
        }, { result ->
            if (identity.uid() != uid) throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
            dao.cache(CacheRow(uid, key, vault.seal(result.toString()), System.currentTimeMillis()))
        })
    }
    suspend fun planning(refresh: Boolean = true) = planningRead("planning", refresh) { api.latestPlanning() }
    suspend fun upcomingPlanning(refresh: Boolean = false) = planningRead("planning_upcoming", refresh) { api.upcomingPlanning() }
    suspend fun planningHistory(refresh: Boolean = false) = planningRead("planning_history", refresh) { api.planningHistory() }
    fun observeNotifications(uid: String) = dao.observeNotifications(uid).map { rows ->
        rows.map { JSONObject(vault.open(it.ciphertext)) }
            .sortedByDescending { it.optString("created_at") }
    }
    suspend fun cacheNotifications(page: JSONObject, uid: String) {
        if (identity.uid() != uid || !approved()) throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
        db.withTransaction {
            for (event in page.objects("items")) {
                val id = event.optString("event_id", event.optString("id"))
                UUID.fromString(id)
                dao.cache(CacheRow(uid, "notification:" + id, vault.seal(event.toString()), System.currentTimeMillis()))
            }
        }
    }
    suspend fun notifications(refresh: Boolean = true): JSONObject = notificationLock.withLock {
        val uid = owner()
        if (refresh) {
            var next: String? = null
            val seen = mutableSetOf<String>()
            do {
                val page = api.notifications(next)
                cacheNotifications(page, uid)
                next = page.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
                if (next != null && !seen.add(next)) throw AppFailure(AppText.get(R.string.backend_repeated_notification_page), true)
            } while (next != null)
            save("notifications", JSONObject().put("next_cursor", JSONObject.NULL))
        }
        cached("notifications") ?: JSONObject()
    }
    suspend fun openNotification(id: String): JSONObject {
        val uid = owner()
        val event = cached("notification:" + id) ?: receiveNotification(id)
        if (identity.uid() != uid) throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
        save("notification-opened:" + id, JSONObject().put("opened", true))
        return event
    }
    suspend fun notificationOpened(id: String): Boolean =
        cached("notification-opened:" + id)?.optBoolean("opened") == true
    suspend fun receiveNotification(id: String): JSONObject {
        val uid = owner()
        val event = api.notification(id)
        cacheNotifications(JSONObject().put("items", JSONArray().put(event)), uid)
        return event
    }
    private fun tradeHash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun dnseSnapshot(): JSONObject? {
        val config = dnseCredentials.config(owner()) ?: return null
        return cached("dnse")?.takeIf { it.optString("credential_scope") == tradeHash(config) }
    }

    /** One dialog owns one OTP session; tokens are never persisted or logged. */
    inner class ManualTradeSession internal constructor(private val uid: String, private val config: String,
                                                       private val plan: JSONObject) {
        private val settings = JSONObject(config)
        val production = settings.getBoolean("production")
        private val broker = DnseTradingApi(settings.getString("key"), settings.getString("secret"), production)
        private var tradingToken: String? = null
        private var verifiedAt = 0L
        private fun check() {
            if (identity.uid() != uid || !approved() || dnseCredentials.config(uid) != config)
                throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
        }
        private val journalKey = "manual_trade:" + production + ":" + tradeHash(
            plan.optString("scheduled_date") + ":" + plan.getInt("source_row"))
        suspend fun result(): JSONObject? { check(); return cached(journalKey)?.takeUnless { it.optString("state") == "REJECTED" } }
        suspend fun funds(): JSONObject? { check(); return dnseSnapshot() }
        suspend fun requireFunds(account: String, draft: TradeDraft) {
            check()
            if (PlanningFunds.cancelled(plan)) throw AppFailure(AppText.get(R.string.trade_plan_changed))
            if (draft.side != "NB") return
            val required = PlanningFunds.required(plan, java.math.BigDecimal(draft.price).multiply(java.math.BigDecimal(draft.quantity)))
                ?: throw AppFailure(AppText.get(R.string.plan_funds_unknown))
            val syncedCash = PlanningFunds.cash(dnseSnapshot(), account)
            if (syncedCash == null || syncedCash < required) throw AppFailure(AppText.get(R.string.plan_funds_topup_note))
        }
        suspend fun accounts(): List<JSONObject> {
            check()
            return broker.accounts().filter { it.optBoolean("dealAccount", false) }
        }
        suspend fun packages(account: String, symbol: String): List<JSONObject> {
            check()
            require(accounts().any { DnseApi.text(it, "id", "accountNo") == account })
            return broker.packages(account, symbol)
        }
        suspend fun emailOtp() { check(); broker.emailOtp(); check() }
        suspend fun verify(type: String, otp: String) {
            check()
            val token = broker.token(type, otp)
            check(); tradingToken = token; verifiedAt = System.currentTimeMillis()
        }
        fun close() { tradingToken = null; verifiedAt = 0 }
        suspend fun place(account: String, draft: TradeDraft): JSONObject = lock.withLock {
            check(); draft.body()
            require(draft.side == PlanningFunds.side(plan))
            requireFunds(account, draft)
            val token = tradingToken ?: throw AppFailure(AppText.get(R.string.trade_otp_required))
            if (System.currentTimeMillis() - verifiedAt !in 0..(5 * 60 * 1000L))
                throw AppFailure(AppText.get(R.string.trade_otp_required))
            if (result() != null) throw AppFailure(AppText.get(R.string.trade_already_attempted))
            val fresh = upcomingPlanning(refresh = true).objects("items").firstOrNull {
                it.optInt("source_row") == plan.getInt("source_row") &&
                    it.optString("scheduled_date") == plan.optString("scheduled_date")
            }
            if (fresh == null || fresh.getJSONObject("fields").toString() != plan.getJSONObject("fields").toString())
                throw AppFailure(AppText.get(R.string.trade_plan_changed))
            require(packages(account, draft.symbol).any { it.optLong("id", -1) == draft.packageId })
            check()
            if (draft.side == "NB") {
                val rawBalance = broker.balances(account)
                val balance = (rawBalance as? JSONObject)?.let { it.optJSONObject("data") ?: it.optJSONObject("balance") ?: it }
                    ?: throw AppFailure(AppText.get(R.string.plan_funds_unknown))
                val live = DnseApi.normalizeBalance(account, balance, java.math.BigDecimal.ONE, java.time.Instant.now())
                val cash = OrderContent.number(live, "cash_vnd")
                val required = PlanningFunds.required(plan, java.math.BigDecimal(draft.price).multiply(java.math.BigDecimal(draft.quantity)))!!
                if (cash == null || cash < required) throw AppFailure(AppText.get(R.string.plan_funds_topup_note))
                requireFunds(account, draft)
            }
            check()
            val journal = JSONObject().put("state", "UNKNOWN").put("account", account)
                .put("draft", draft.body()).put("created_at", java.time.Instant.now().toString())
            // Durable marker precedes the network write. Cancellation/timeouts never permit an automatic retry.
            save(journalKey, journal)
            try {
                check()
                val response = broker.place(account, draft, token)
                val orderId = DnseApi.text(response, "id", "orderId")
                require(orderId.isNotBlank() && orderId != "null")
                journal.put("state", "SUBMITTED").put("order_id", orderId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    dao.cache(CacheRow(uid, journalKey, vault.seal(journal.toString()), System.currentTimeMillis()))
                }
                journal
            } catch (e: Exception) {
                if (e is TradeHttpFailure && e.status in setOf(400, 401, 403, 404, 422)) {
                    journal.put("state", "REJECTED").put("http_status", e.status)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        dao.cache(CacheRow(uid, journalKey, vault.seal(journal.toString()), System.currentTimeMillis()))
                    }
                    throw AppFailure(AppText.get(R.string.trade_http_error, e.status))
                }
                throw AppFailure(AppText.get(R.string.trade_unknown_result))
            } finally { close() }
        }
    }
    fun manualTrade(plan: JSONObject): ManualTradeSession {
        if (!approved()) throw AppFailure(AppText.get(R.string.backend_mobile_not_verified))
        val uid = owner()
        val config = dnseCredentials.config(uid) ?: throw AppFailure(AppText.get(R.string.save_the_api_key_and_secret_first))
        return ManualTradeSession(uid, config, JSONObject(plan.toString()))
    }

    fun saveDnse(key: String, secret: String, production: Boolean, vndPerUnit: String) {
        dnseCredentials.save(owner(), key, secret, production, vndPerUnit)
    }
    fun saveDnseEnvironment(production: Boolean) { dnseCredentials.select(owner(), production) }
    fun dnseProduction(): Boolean? = identity.uid()?.let { dnseCredentials.production(it) }
    suspend fun deleteDnse(production: Boolean) = lock.withLock {
        val uid = owner()
        dnseCredentials.delete(uid, production)
        if (dnseCredentials.production(uid) == production) {
            save("dnse", JSONObject())
            vault.remove("last_sync:$uid")
        }
    }
    fun pushRegistered() = identity.uid()?.let { vault.get("push_registered:$it") == "true" } ?: false
    fun hasDnse() = identity.uid()?.let { dnseCredentials.config(it) != null } ?: false
    fun hasDnse(production: Boolean) = identity.uid()?.let { dnseCredentials.has(it, production) } ?: false
    suspend fun localQueueSummary(): List<String> = localBatches().filter { it.state != "COMMITTED" }.map { row ->
        val data = JSONObject(vault.open(row.ciphertext))
        val state = if (row.state == "REVIEW_REQUIRED") AppText.get(R.string.data_review_required) else AppText.get(R.string.awaiting_upload)
        AppText.get(R.string.n_orders_executions_positions_balances, state, NotificationContent.time(java.time.Instant.ofEpochMilli(row.createdAt).toString()), data.objects("orders").size, data.objects("executions").size, data.objects("positions").size, data.objects("balances").size)
    }
    suspend fun localBatches(): List<PendingBatch> = dao.batches(owner())
    suspend fun sync(): String = lock.withLock {
        if (!approved()) throw AppFailure(AppText.get(R.string.backend_mobile_not_verified))
        val uid = owner()
        flush(uid)
        val configRaw = dnseCredentials.config(uid) ?: throw AppFailure(AppText.get(R.string.save_your_dnse_keys_first))
        val config = JSONObject(configRaw)
        val production = config.getBoolean("production")
        val dnse = DnseApi(config.getString("key"), config.getString("secret"), production)
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
                    item.toString() != previous.toString()) throw AppFailure(AppText.get(R.string.dnse_timestamp_conflict))
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
                else -> throw AppFailure(AppText.get(R.string.unsupported_dnse_balance_format))
            }
            balances[id] = DnseApi.normalizeBalance(id, balanceSource, multiplier, observedAt)
            val rawPositions = dnse.positions(id)
            for (raw in DnseApi.rowsOrSingle(rawPositions, "data", "positions", "items")) {
                if (!raw.has("symbol") && !raw.has("instrument") && !raw.has("stockSymbol")) continue
                val item = DnseApi.normalizePosition(id, raw, multiplier, observedAt)
                positions[id + ":" + item.getString("position_id")] = item
            }
            // Preserve raw broker responses only inside the encrypted local cache.
            overview.put(JSONObject().put("account", id).put("profile", account).put("balances", rawBalance)
                .put("positions", rawPositions))
        }
        if (owner() != uid) throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
        val orderList = orders.values.toList()
        val executionList = executions.values.toList()
        val positionList = positions.values.toList()
        val balanceList = balances.values.toList()
        val snapshot = JSONObject().put("orders", JSONArray(orderList))
            .put("executions", JSONArray(executionList)).put("positions", JSONArray(positionList))
            .put("balances", JSONArray(balanceList)).put("accounts", overview)
            .put("saved_at", java.time.Instant.now().toString()).put("production", production)
            .put("credential_scope", tradeHash(configRaw))
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
        if (!production) return@withLock AppText.get(R.string.dnse_sandbox_sync_complete, orderList.size, executionList.size, positionList.size)
        flush(uid)
        val sheet = api.retryProjection()
        vault.put("last_sync:$uid", java.time.Instant.now().toString())
        AppText.get(R.string.dnse_sync_complete, orderList.size, executionList.size, positionList.size, balanceList.size, sheet.optString("state"))
    }
    suspend fun retryPending(): String = lock.withLock {
        if (!approved()) throw AppFailure(AppText.get(R.string.backend_mobile_access_has_not_been_granted))
        flush(owner())
        val result = api.retryProjection()
        AppText.get(R.string.pending_data_uploaded_again_sheet, result.optString("state"))
    }
    private suspend fun flush(uid: String) {
        if (dao.batches(uid).any { it.state == "REVIEW_REQUIRED" })
            throw AppFailure(AppText.get(R.string.pending_batch_requires_review))
        for (row in dao.pending(uid)) {
            if (owner() != uid || !approved()) throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
            try {
                val result = api.upload(JSONObject(vault.open(row.ciphertext)))
                if (result.optString("database") != "committed" || result.optString("batch_id") != row.id)
                    throw AppFailure(AppText.get(R.string.backend_commit_unconfirmed), true)
                dao.mark(uid, row.id, "COMMITTED", "")
            } catch (e: HttpFailure) {
                if (e.status == 409 || e.status == 422)
                    dao.mark(uid, row.id, "REVIEW_REQUIRED", "HTTP ${e.status}")
                throw e.safe()
            }
        }
    }
    fun lastSync() = identity.uid()?.let { vault.get("last_sync:$it") } ?: AppText.get(R.string.not_synced_yet)
    suspend fun registerPush() {
        if (approved()) {
            val uid = owner()
            vault.remove("push_registered:$uid")
            api.registerDevice(device(), FirebaseMessaging.getInstance().token.await())
            if (owner() == uid) vault.put("push_registered:$uid", "true")
        }
    }
    suspend fun logout() = lock.withLock {
        val uid = owner()
        // Revocation must succeed before claiming this device is disconnected.
        if (approved()) api.removeDevice(device())
        // Keep the installation token for repeated Firebase Console tests in debug builds.
        // Backend device revocation above still disconnects the signed-out account.
        if (!com.example.finance_planning.BuildConfig.DEBUG)
            FirebaseMessaging.getInstance().deleteToken().await()
        vault.remove("approved")
        dnseCredentials.clear(uid)
        vault.remove("push_registered:$uid")
        vault.remove("last_sync:$uid")
        db.withTransaction { dao.clearCache(uid); dao.clearBatches(uid) }
        identity.signOut()
    }
}
