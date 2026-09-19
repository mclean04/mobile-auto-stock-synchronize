package com.example.finance_planning.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource as text
import androidx.compose.ui.unit.dp
import com.example.finance_planning.R
import com.example.finance_planning.core.AppFailure
import com.example.finance_planning.core.AppText
import com.example.finance_planning.core.PlanningFunds
import com.example.finance_planning.core.OrderContent
import com.example.finance_planning.data.PlanningRepository
import com.example.finance_planning.network.DnseApi
import com.example.finance_planning.network.TradeDraft
import com.example.finance_planning.network.TradeHttpFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigDecimal

@Composable
fun ManualTradeDialog(plan: JSONObject, repo: PlanningRepository, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<PlanningRepository.ManualTradeSession?>(null) }
    var funds by remember { mutableStateOf<JSONObject?>(null) }
    var accounts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var packages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var account by remember { mutableStateOf("") }
    var selectedPackage by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    val fields = remember(plan) { plan.optJSONObject("fields") ?: plan }
    val symbol = remember(plan) { fields.optString("Mã").trim().uppercase(java.util.Locale.US) }
    val side = remember(plan) { when(fields.optString("Mua/Bán").trim().uppercase(java.util.Locale.US)) {
        "MUA", "BUY", "NB" -> "NB"; "BÁN", "BAN", "SELL", "NS" -> "NS"; else -> ""
    } }
    var quantity by remember { mutableStateOf(runCatching { BigDecimal(fields.optString("Số lượng")).intValueExact().toString() }.getOrDefault("")) }
    // Planning Sheets do not define a reliable quote unit. Require an explicit VND limit price.
    var price by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<TradeDraft?>(null) }
    var otp by remember { mutableStateOf("") }
    var otpType by remember { mutableStateOf("smart_otp") }
    var verified by remember { mutableStateOf(false) }
    fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = when(e) {
                is AppFailure -> e.message.orEmpty()
                is TradeHttpFailure -> AppText.get(R.string.trade_http_error, e.status)
                else -> AppText.get(R.string.trade_prepare_failed)
            } }
            finally { busy = false }
        }
    }
    LaunchedEffect(plan) {
        busy = true
        try {
            val opened = repo.manualTrade(plan)
            session = opened; result = opened.result(); funds = opened.funds()
            if (result == null) accounts = opened.accounts()
        } catch (e: Exception) { message = if (e is AppFailure) e.message.orEmpty() else AppText.get(R.string.trade_prepare_failed) }
        finally { busy = false }
    }
    DisposableEffect(session) { onDispose { session?.close() } }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() },
        title = { Text(text(R.string.trade_title)) },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = if (session?.production == true) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium) {
                Text(text(if (session?.production == true) R.string.trade_live_notice else R.string.trade_sandbox_notice),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            if (result == null) {
                Text(text(R.string.trade_manual_notice), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                Text(text(R.string.planned_date, plan.optString("scheduled_date")), style = MaterialTheme.typography.titleSmall)
                fields.optString("Điều kiện thực thi").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                fields.optString("Ghi chú").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
            }
            if (result != null) {
                Text(text(if (result!!.optString("state") == "SUBMITTED") R.string.trade_submitted else R.string.trade_unknown_result))
                result!!.optString("order_id").takeIf { it.isNotBlank() }?.let { Text(text(R.string.trade_order_reference, it)) }
                if (result!!.has("backend_request_id")) Text(
                    text(if (result!!.optString("backend_state") == "REPORTED")
                        R.string.trade_backend_reported else R.string.trade_backend_pending),
                    style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    color = if (result!!.optString("backend_state") == "REPORTED")
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            } else if (draft == null) {
                Text(text(R.string.trade_plan_summary, symbol, text(when(side) { "NB" -> R.string.buy; "NS" -> R.string.sell; else -> R.string.plan })), style = MaterialTheme.typography.titleLarge)
                Text(text(R.string.trade_account), style = MaterialTheme.typography.titleMedium)
                accounts.forEach { row ->
                    val id = DnseApi.text(row, "id", "accountNo")
                    val cash = PlanningFunds.cash(funds, id)
                    val required = PlanningFunds.required(plan)
                    val enough = side != "NB" || (cash != null && required != null && cash >= required)
                    Text(text(R.string.plan_account_cash, id, OrderContent.money(cash)), style = MaterialTheme.typography.bodySmall)
                    FilterChip(selected = account == id, enabled = !busy && enough, onClick = {
                        account = id; packages = emptyList(); selectedPackage = null
                        task { packages = session!!.packages(id, symbol) }
                    }, label = { Text(row.optString("name", id) + " · " + id) })
                }
                if (!busy && accounts.isEmpty()) Text(text(R.string.trade_no_account))
                OutlinedTextField(quantity, { quantity = it }, label = { Text(text(R.string.trade_quantity)) }, singleLine = true, enabled = !busy)
                OutlinedTextField(price, { price = it }, label = { Text(text(R.string.trade_price_vnd)) }, singleLine = true, enabled = !busy)
                Text(text(R.string.trade_package), style = MaterialTheme.typography.titleMedium)
                packages.forEach { row ->
                    FilterChip(selected = selectedPackage == row, enabled = !busy, onClick = { selectedPackage = row }, label = {
                        Text(row.optString("name", row.optString("id")))
                    })
                    if (selectedPackage == row) Text(row.keys().asSequence().filter { it != "id" && it != "name" }
                        .joinToString("\n") { "$it: ${row.opt(it)}" }, style = MaterialTheme.typography.bodySmall)
                }
                Text(text(R.string.trade_package_note), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            } else {
                val d = draft!!
                Text(text(R.string.trade_review, account, d.symbol, text(if (d.side == "NB") R.string.buy else R.string.sell),
                    d.quantity, OrderContent.money(BigDecimal(d.price)),
                    OrderContent.money(BigDecimal(d.price).multiply(BigDecimal(d.quantity))), selectedPackage!!.optString("name", d.packageId.toString())),
                    style = MaterialTheme.typography.titleMedium)
                if (d.side == "NB") {
                    Text(text(R.string.plan_account_cash, account, OrderContent.money(PlanningFunds.cash(funds, account))), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(text(R.string.plan_required_value, OrderContent.money(PlanningFunds.required(plan, BigDecimal(d.price).multiply(BigDecimal(d.quantity))))))
                }
                Text(text(R.string.trade_otp_note), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                if (!verified) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(otpType == "smart_otp", { otpType = "smart_otp"; otp = "" }, enabled = !busy, label = { Text(text(R.string.trade_smart_otp)) })
                        FilterChip(otpType == "email_otp", { otpType = "email_otp"; otp = "" }, enabled = !busy, label = { Text(text(R.string.trade_email_otp)) })
                    }
                    if (otpType == "email_otp") TextButton(enabled = !busy, onClick = {
                        task { session!!.emailOtp(); message = AppText.get(R.string.trade_email_sent) }
                    }) { Text(text(R.string.trade_send_otp)) }
                    OutlinedTextField(otp, { otp = it }, label = { Text(text(R.string.trade_otp)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
                    Button(enabled = !busy && Regex("[0-9]{6}").matches(otp), onClick = {
                        task { val code = otp; otp = ""; session!!.verify(otpType, code); verified = true }
                    }) { Text(text(R.string.trade_verify_otp)) }
                } else Text(text(R.string.trade_otp_ready), color = MaterialTheme.colorScheme.primary)
            }
        } },
        confirmButton = {
            if (result == null) Button(enabled = !busy && session != null && (if (draft == null) account.isNotBlank() && selectedPackage != null else verified), onClick = {
                if (draft == null) {
                    try {
                        val next = TradeDraft(symbol, side, quantity.toInt(), price.toLong(), selectedPackage!!.getLong("id"))
                        next.body()
                        task { session!!.requireFunds(account, next); draft = next }

                    } catch (_: Exception) { message = AppText.get(R.string.trade_invalid_draft) }
                } else task {
                    try { result = session!!.place(account, draft!!) }
                    finally { verified = false; result = session!!.result() }
                }
            }) { Text(text(if (draft == null) R.string.trade_review_button else R.string.trade_confirm_place)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(text(R.string.close)) } })
}
