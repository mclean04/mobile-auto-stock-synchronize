package com.example.finance_planning.data

import com.example.finance_planning.R
import com.example.finance_planning.core.AppText
import androidx.room.withTransaction
import com.example.finance_planning.auth.MobileIdentity
import com.example.finance_planning.core.*
import com.example.finance_planning.network.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class PlanningRepository(val identity: MobileIdentity, private val vault: Vault,
                         private val db: LocalDb, val api: BackendApi) {
    private val dnseCredentials = DnseCredentialStore(vault::get, vault::put, vault::remove)
    private val lock = Mutex()
    private val dao = db.dao()
    private val notificationLock = Mutex()
    private val planningCacheLock = Mutex()
    private val placedReportLock = Mutex()
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
        try {
            retryPlacedOrderReports()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A pending report remains durable and will be retried on the next verified session.
        }
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
    private fun planningStore(uid: String) =
        PlanningAllStore(read = { dao.cached(uid, "planning_all")?.let { JSONObject(vault.open(it.ciphertext)) } },
            fetchPage = { cursor ->
                if (identity.uid() != uid || !approved())
                    throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
                api.allPlanning(cursor)
            },
            replaceAtomically = { result ->
                db.withTransaction {
                    if (identity.uid() != uid || !approved())
                        throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
                    dao.cache(CacheRow(uid, "planning_all", vault.seal(result.toString()), System.currentTimeMillis()))
                }
            })
    suspend fun localPlanningAll(): JSONObject? = planningStore(owner()).local()
    suspend fun refreshPlanningAll(): JSONObject = planningCacheLock.withLock {
        planningStore(owner()).refresh()
    }
    fun observeNotifications(uid: String) = dao.observeNotificationInbox(uid).map { rows ->
        rows.map { JSONObject(vault.open(it.event.ciphertext)).put("_opened", it.opened) }
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
        return event.put("_opened", true)
    }
    suspend fun notificationOpened(id: String): Boolean =
        cached("notification-opened:" + id)?.optBoolean("opened") == true
    suspend fun receiveNotification(id: String): JSONObject {
        val uid = owner()
        val event = api.notification(id)
        cacheNotifications(JSONObject().put("items", JSONArray().put(event)), uid)
        return event
    }
    private suspend fun reportPlacedOrder(uid: String, journalKey: String, journal: JSONObject,
                                          payload: JSONObject, apiVersion: Int = 1) {
        placedReportLock.withLock {
            val requestId = payload.getString("request_id")
            val reportKey = "placed-report:$requestId"
            val report = JSONObject().put("state", "PENDING").put("journal_key", journalKey)
                .put("api_version", apiVersion)
                .put("payload", JSONObject(payload.toString()))
            journal.put("backend_request_id", requestId).put("backend_state", "PENDING")
            withContext(NonCancellable) {
                dao.cache(CacheRow(uid, reportKey, vault.seal(report.toString()), System.currentTimeMillis()))
                dao.cache(CacheRow(uid, journalKey, vault.seal(journal.toString()), System.currentTimeMillis()))
            }
            sendPlacedOrderReport(uid, reportKey, report)
        }
    }
    private suspend fun sendPlacedOrderReport(uid: String, reportKey: String, report: JSONObject) {
        if (identity.uid() != uid || !approved()) return
        var cancellation: CancellationException? = null
        try {
            val payload = report.getJSONObject("payload")
            val response = if (report.optInt("api_version", 1) == 2) api.placedOrderV2(payload)
                else api.placedOrder(payload)
            if (report.optInt("api_version", 1) == 2) {
                require(response.optString("request_id") == payload.getString("request_id"))
                require(response.optString("intent_id") == payload.getString("intent_id"))
                require(response.optInt("reported_version", -1) == payload.getInt("expected_version"))
                require(response.optString("environment") == payload.getString("environment"))
                require(PlanningSourceContext.parse(response.getJSONObject("source_context")) ==
                    PlanningSourceContext.parse(payload.getJSONObject("source_context")))
                val destination = response.getJSONObject("report_destination")
                require(PlanningSourceContext.parse(destination.getJSONObject("source_context")) ==
                    PlanningSourceContext.parse(payload.getJSONObject("source_context")))
                require(destination.getString("state") in setOf("CURRENT_PRIMARY", "QUARANTINED_SOURCE_CHANGED"))
                require(response.optString("database") == "committed")
            }
            report.put("state", "REPORTED")
        } catch (e: CancellationException) {
            cancellation = e
        } catch (e: HttpFailure) {
            report.put("state", if (e.status == 409 || e.status == 422) "REVIEW_REQUIRED" else "PENDING")
        } catch (_: Exception) {
            report.put("state", "PENDING")
        }
        withContext(NonCancellable) {
            dao.cache(CacheRow(uid, reportKey, vault.seal(report.toString()), System.currentTimeMillis()))
            val journalKey = report.optString("journal_key")
            val row = dao.cached(uid, journalKey)
            val journal = row?.let { runCatching { JSONObject(vault.open(it.ciphertext)) }.getOrNull() }
            if (journal?.optString("backend_request_id") == report.getJSONObject("payload").getString("request_id")) {
                journal.put("backend_state", report.getString("state"))
                dao.cache(CacheRow(uid, journalKey, vault.seal(journal.toString()), System.currentTimeMillis()))
            }
        }
        cancellation?.let { throw it }
    }
    private suspend fun retryPlacedOrderReports() = placedReportLock.withLock {
        val uid = owner()
        for (row in dao.placedReports(uid)) {
            val report = runCatching { JSONObject(vault.open(row.ciphertext)) }.getOrNull() ?: continue
            if (report.optString("state") == "PENDING") sendPlacedOrderReport(uid, row.key, report)
        }
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
        val intent = PlanningIntent.parse(plan)
        private val broker = DnseTradingApi(settings.getString("key"), settings.getString("secret"), production)
        private var tradingToken: String? = null
        private var verifiedAt = 0L
        private fun check() {
            if (identity.uid() != uid || !approved() || dnseCredentials.config(uid) != config)
                throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
        }
        private fun requirePendingTime() {
            if (!PlanningTimeline.mayOpenAction(PlanningSection.UPCOMING, plan, java.time.Instant.now()))
                throw AppFailure(AppText.get(R.string.planning_action_pending_only))
        }
        init {
            require(production == (intent.environment == TradingEnvironment.PRODUCTION))
            require(intent.executable)
        }
        private val journalKey = "manual_trade:v2:" + intent.environment.name.lowercase() + ":" +
            intent.sourceContext.sourceGeneration + ":" + tradeHash(intent.sourceContext.sourceId).take(16) +
            ":" + intent.intentId
        suspend fun result(): JSONObject? { check(); return cached(journalKey)?.takeUnless { it.optString("state") == "REJECTED" } }
        suspend fun funds(): JSONObject? { check(); return dnseSnapshot() }
        suspend fun requireFunds(account: String, draft: TradeDraft) {
            check()
            require(account == intent.account)
            require(intent.matchesDraft(draft.symbol, if (draft.side == "NB") "BUY" else "SELL",
                draft.quantity, draft.price))
            if (draft.side != "NB") return
            val required = PlanningFunds.required(plan, java.math.BigDecimal(draft.price).multiply(java.math.BigDecimal(draft.quantity)))
                ?: throw AppFailure(AppText.get(R.string.plan_funds_unknown))
            val syncedCash = PlanningFunds.cash(dnseSnapshot(), account)
            if (syncedCash == null || syncedCash < required) throw AppFailure(AppText.get(R.string.plan_funds_topup_note))
        }
        suspend fun accounts(): List<JSONObject> {
            check()
            return broker.accounts().filter { it.optBoolean("dealAccount", false) &&
                DnseApi.text(it, "id", "accountNo") == intent.account }
        }
        suspend fun packages(account: String, symbol: String): List<JSONObject> {
            check()
            require(accounts().any { DnseApi.text(it, "id", "accountNo") == account })
            require(account == intent.account && symbol == intent.symbol)
            return broker.packages(account, symbol).filter(DnseCashPackage::isCash)
        }
        suspend fun emailOtp() { check(); broker.emailOtp(); check() }
        suspend fun verify(type: String, otp: String) {
            check()
            val token = broker.token(type, otp)
            check(); tradingToken = token; verifiedAt = System.currentTimeMillis()
        }
        fun close() { tradingToken = null; verifiedAt = 0 }
        suspend fun place(account: String, draft: TradeDraft): JSONObject = lock.withLock {
            requirePendingTime()
            check(); draft.body()
            require(intent.matchesDraft(draft.symbol, if (draft.side == "NB") "BUY" else "SELL",
                draft.quantity, draft.price))
            requireFunds(account, draft)
            val token = tradingToken ?: throw AppFailure(AppText.get(R.string.trade_otp_required))
            if (System.currentTimeMillis() - verifiedAt !in 0..(5 * 60 * 1000L))
                throw AppFailure(AppText.get(R.string.trade_otp_required))
            if (result() != null) throw AppFailure(AppText.get(R.string.trade_already_attempted))
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
            val reportingDevice = device()
            val journal = JSONObject().put("state", "UNKNOWN").put("account", account)
                .put("source_context", intent.sourceContext.json())
                .put("draft", draft.body()).put("created_at", java.time.Instant.now().toString())
            try {
                // Cache/list eligibility is display-only. The guard performs a fresh detail read and
                // preflight after final confirmation. Its persistence hook is the last step before DNSE.
                val guarded = TradeExecutionGuard.execute(intent, account, java.time.Instant.now(),
                    readCurrent = { api.planningIntent(intent.intentId.toString()) },
                    runPreflight = { request -> api.planningPreflight(intent.intentId.toString(), request) },
                    beforeBrokerWrite = { preflight ->
                        check()
                        journal.put("preflight_id", preflight.preflightId.toString())
                        // Durable UNKNOWN marker precedes the network write. A timeout never permits retry.
                        save(journalKey, journal)
                    },
                    readActiveSource = { api.planningSource() },
                    brokerWrite = { check(); requirePendingTime(); broker.place(account, draft, token) })
                val response = guarded.value
                val preflight = guarded.preflight
                val placed = response.optJSONObject("data") ?: response.optJSONObject("order") ?: response
                val orderId = DnseApi.text(placed, "id", "orderId")
                require(orderId.isNotBlank() && orderId != "null")
                journal.put("state", "SUBMITTED").put("order_id", orderId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    dao.cache(CacheRow(uid, journalKey, vault.seal(journal.toString()), System.currentTimeMillis()))
                    val payload = PlacedOrderReport.typedPayload(UUID.randomUUID().toString(), reportingDevice,
                        intent, preflight, account, draft, response, java.time.Instant.now())
                    reportPlacedOrder(uid, journalKey, journal, payload, apiVersion = 2)
                }
                journal
            } catch (e: Exception) {
                if (e is PlanningPreflightUnavailable)
                    throw AppFailure(AppText.get(R.string.planning_preflight_unavailable))
                if (e is PlanningVersionChanged)
                    throw AppFailure(AppText.get(R.string.planning_gate_stale_version))
                if (e is PlanningGateFailure)
                    throw AppFailure(PlanningGateText.message(e.reasons))
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
    fun manualTrade(plan: JSONObject, section: PlanningSection): ManualTradeSession {
        if (!PlanningTimeline.mayOpenAction(section, plan, java.time.Instant.now()))
            throw AppFailure(AppText.get(R.string.planning_action_pending_only))
        if (!approved()) throw AppFailure(AppText.get(R.string.backend_mobile_not_verified))
        val uid = owner()
        val config = dnseCredentials.config(uid) ?: throw AppFailure(AppText.get(R.string.save_the_api_key_and_secret_first))
        val intent = PlanningIntent.parseOrNull(plan)
            ?: throw AppFailure(AppText.get(R.string.planning_legacy_read_only))
        if (!intent.executable) throw AppFailure(PlanningGateText.message(intent.eligibility.reasons))
        val selectedProduction = JSONObject(config).getBoolean("production")
        if (selectedProduction != (intent.environment == TradingEnvironment.PRODUCTION))
            throw AppFailure(PlanningGateText.message(listOf(EligibilityReason.WRONG_ENVIRONMENT)))
        return ManualTradeSession(uid, config, JSONObject(plan.toString()))
    }

    /** Dedicated sandbox client, independent of the user's active Production environment. */
    inner class SandboxTradeSession internal constructor(private val uid: String, private val config: String) {
        private val settings = JSONObject(config).also { require(!it.getBoolean("production")) }
        private val journalKey = "sandbox-test:last:" + tradeHash(config)
        private val reports = kotlinx.coroutines.flow.MutableStateFlow<List<BrokerResponse>>(emptyList())
        val responses = reports.asStateFlow()
        private val broker = DnseTradingApi(settings.getString("key"), settings.getString("secret"), false,
            diagnostic = { report -> reports.value = (reports.value + report).takeLast(20) })
        private fun check() {
            if (identity.uid() != uid || !approved() || dnseCredentials.config(uid, false) != config)
                throw AppFailure(AppText.get(R.string.the_sign_in_session_has_changed))
        }
        suspend fun accounts(): List<JSONObject> { check(); return broker.accounts().filter { it.optBoolean("dealAccount") } }
        suspend fun packages(account: String, symbol: String): List<JSONObject> {
            check(); require(accounts().any { DnseApi.text(it, "id", "accountNo") == account })
            broker.balances(account)
            return broker.packages(account, symbol)
        }
        suspend fun previous(): JSONObject? { check(); return cached(journalKey) }
        private suspend fun writeJournal(journal: JSONObject) {
            dao.cache(CacheRow(uid, journalKey, vault.seal(journal.toString()), System.currentTimeMillis()))
        }
        private fun status(raw: Any): String {
            val root = raw as? JSONObject ?: return ""
            val value = root.optJSONObject("data") ?: root.optJSONObject("order") ?: root
            return DnseApi.text(value, "orderStatus", "status").lowercase(java.util.Locale.US)
        }
        private suspend fun refreshJournal(journal: JSONObject): Any {
            val raw = broker.order(journal.getString("account"), journal.getString("order_id"))
            val current = status(raw)
            journal.put("broker_status", current)
            if (current in setOf("canceled", "cancelled", "filled", "rejected", "expired", "doneforday"))
                journal.put("state", "TERMINAL")
            else if (current == "pendingcancel" && journal.optString("state") == "CANCEL_UNKNOWN")
                journal.put("state", "CANCEL_REQUESTED")
            writeJournal(journal)
            return raw
        }
        suspend fun place(account: String, draft: TradeDraft, cancelImmediately: Boolean = false): JSONObject = lock.withLock {
            check(); draft.body()
            require(previous() == null) { AppText.get(R.string.sandbox_previous_test) }
            require(packages(account, draft.symbol).any { it.optLong("id", -1) == draft.packageId })
            broker.ppse(account, draft)
            val token = broker.token("email_otp", "666666")
            check()
            val reportingDevice = device()
            val journal = JSONObject().put("state", "UNKNOWN").put("account", account).put("draft", draft.body())
            save(journalKey, journal)
            var placedPayload: JSONObject? = null
            try {
                val result = broker.place(account, draft, token)
                val placed = result.optJSONObject("data") ?: result
                val id = DnseApi.text(placed, "id", "orderId")
                require(id.isNotBlank() && id != "null")
                journal.put("state", "SUBMITTED").put("order_id", id)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { writeJournal(journal) }
                placedPayload = PlacedOrderReport.payload(UUID.randomUUID().toString(), reportingDevice, "sandbox",
                    account, draft, result, java.time.Instant.now())
                if (cancelImmediately) {
                    journal.put("state", "CANCEL_UNKNOWN")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { writeJournal(journal) }
                    broker.cancel(account, id, token)
                    journal.put("state", "CANCEL_REQUESTED")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { writeJournal(journal) }
                    refreshJournal(journal)
                }
                journal
            } catch (e: TradeHttpFailure) {
                if (journal.has("order_id")) {
                    runCatching { refreshJournal(journal) }
                } else if (e.status in setOf(400, 401, 403, 404, 422)) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        dao.cache(CacheRow(uid, journalKey, vault.seal(journal.put("state", "REJECTED").toString()), System.currentTimeMillis()))
                    }
                }
                throw e
            } finally {
                placedPayload?.let { payload ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        reportPlacedOrder(uid, journalKey, journal, payload)
                    }
                }
            }
        }
        suspend fun detail(): Any {
            check(); val last = previous() ?: throw AppFailure(AppText.get(R.string.sandbox_previous_test))
            return if (last.has("order_id")) refreshJournal(last)
                else broker.orders(last.getString("account"))
        }
        suspend fun cancel(): Any = lock.withLock {
            check(); val last = previous() ?: throw AppFailure(AppText.get(R.string.sandbox_previous_test))
            require(last.has("order_id")) { AppText.get(R.string.trade_unknown_result) }
            require(last.optString("state") == "SUBMITTED") { AppText.get(R.string.sandbox_cancel_unknown) }
            val token = broker.token("email_otp", "666666")
            check()
            last.put("state", "CANCEL_UNKNOWN")
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { writeJournal(last) }
            try {
                val result = broker.cancel(last.getString("account"), last.getString("order_id"), token)
                last.put("state", "CANCEL_REQUESTED")
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { writeJournal(last) }
                refreshJournal(last)
                result
            } catch (e: Exception) {
                runCatching { refreshJournal(last) }
                throw e
            }
        }
        suspend fun reset() = lock.withLock {
            check(); val last = previous()
            if (last != null && last.optString("state") != "REJECTED") {
                require(last.has("order_id")) { AppText.get(R.string.trade_unknown_result) }
                val raw = refreshJournal(last)
                require(status(raw) in setOf("canceled", "cancelled", "filled", "rejected", "expired", "doneforday")) {
                    AppText.get(R.string.sandbox_previous_test)
                }
            }
            check(); dao.deleteCache(uid, journalKey)
        }
    }
    fun sandboxTrade(): SandboxTradeSession {
        if (!approved()) throw AppFailure(AppText.get(R.string.backend_mobile_not_verified))
        val config = dnseCredentials.config(owner(), false) ?: throw AppFailure(AppText.get(R.string.sandbox_keys_required))
        val settings = JSONObject(config)
        if (!DnseCredentialFormat.valid(settings.optString("key"), settings.optString("secret")))
            throw AppFailure(AppText.get(R.string.dnse_keys_invalid_format))
        return SandboxTradeSession(owner(), config)
    }

    fun saveDnse(key: String, secret: String, production: Boolean, vndPerUnit: String) {
        if (!DnseCredentialFormat.valid(key.trim(), secret.trim()))
            throw AppFailure(AppText.get(R.string.dnse_keys_invalid_format))
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
        val sourceContext = if (production) PlanningContract.activeSource(api.planningSource()) else null
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
                records.filter { it.first == "balance" }.map { it.second }, batchId,
                requireNotNull(sourceContext))
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
                val payload = JSONObject(vault.open(row.ciphertext))
                val result = api.upload(payload)
                if (result.optString("database") != "committed" || result.optString("batch_id") != row.id)
                    throw AppFailure(AppText.get(R.string.backend_commit_unconfirmed), true)
                result.optJSONObject("report_destination")?.let { destination ->
                    require(PlanningSourceContext.parse(destination.getJSONObject("source_context")) ==
                        PlanningSourceContext.parse(payload.getJSONObject("source_context")))
                    require(destination.getString("state") in
                        setOf("CURRENT_PRIMARY", "QUARANTINED_SOURCE_CHANGED"))
                }
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
