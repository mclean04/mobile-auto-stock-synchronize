package com.example.finance_planning.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource as text
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.example.finance_planning.R
import com.example.finance_planning.core.*
import com.example.finance_planning.data.PlanningRepository
import com.example.finance_planning.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SandboxTradeDialog(repo: PlanningRepository, dismiss: () -> Unit) {
    val opened = remember(repo) { runCatching { repo.sandboxTrade() } }
    val session = opened.getOrNull()
    if (session == null) {
        AlertDialog(onDismissRequest = dismiss, title = { Text(text(R.string.sandbox_test_title)) },
            text = { Text((opened.exceptionOrNull() as? AppFailure)?.safeMessage
                ?: text(R.string.sandbox_invalid_input)) },
            confirmButton = { TextButton(onClick = dismiss) { Text(text(R.string.close)) } })
        return
    }
    SandboxTradeContent(session, dismiss)
}

@Composable
private fun SandboxTradeContent(session: PlanningRepository.SandboxTradeSession, dismiss: () -> Unit) {
    val responses by session.responses.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var packages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var account by remember { mutableStateOf("") }
    var packageId by remember { mutableStateOf<Long?>(null) }
    var symbol by remember { mutableStateOf("HPG") }
    var quantity by remember { mutableStateOf("100") }
    var price by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<JSONObject?>(null) }
    var cancelImmediately by remember { mutableStateOf(true) }
    var confirming by remember { mutableStateOf<TradeDraft?>(null) }
    val draft = runCatching { TradeDraft(symbol.trim().uppercase(java.util.Locale.US), "NB",
        quantity.toInt(), price.toLong(), packageId ?: 0).also { it.body() } }.getOrNull()
    fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = when (e) {
                is TradeHttpFailure -> AppText.get(R.string.trade_http_error, e.status)
                is AppFailure -> e.safeMessage
                is IllegalArgumentException -> AppText.get(R.string.sandbox_invalid_input)
                else -> AppText.get(R.string.sandbox_connection_error)
            } }
            finally { last = runCatching { session.previous() }.getOrNull(); busy = false }
        }
    }
    LaunchedEffect(session) { task { last = session.previous(); accounts = session.accounts() } }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(text(R.string.sandbox_test_title)) },
        text = {
            Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(text(R.string.sandbox_test_note), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                if (last == null) {
                    Text(text(R.string.trade_account), style = MaterialTheme.typography.titleSmall)
                    accounts.forEach { row ->
                        val id = DnseApi.text(row, "id", "accountNo")
                        FilterChip(account == id, { account = id; packageId = null; packages = emptyList() },
                            enabled = !busy, label = { Text(id) })
                    }
                    OutlinedTextField(symbol, { symbol = it; packages = emptyList(); packageId = null },
                        label = { Text(text(R.string.sandbox_symbol)) }, enabled = !busy, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(quantity, { quantity = it }, label = { Text(text(R.string.trade_quantity)) },
                            enabled = !busy, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(price, { price = it }, label = { Text(text(R.string.trade_price_vnd)) },
                            enabled = !busy, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    FilledTonalButton(onClick = { task { packages = session.packages(account, symbol.trim().uppercase(java.util.Locale.US)) } },
                        enabled = !busy && account.isNotBlank() && Regex("[A-Z][A-Z0-9]{2,9}").matches(symbol.trim().uppercase(java.util.Locale.US))) {
                        Text(text(R.string.sandbox_load_account))
                    }
                    Text(text(R.string.trade_package), style = MaterialTheme.typography.titleSmall)
                    packages.forEach { row ->
                        val id = row.optLong("id", -1)
                        val selected = packageId == id
                        Surface(Modifier.fillMaxWidth().clickable(enabled = !busy && id > 0) { packageId = id },
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = androidx.compose.foundation.BorderStroke(1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                RadioButton(selected = selected, onClick = { packageId = id },
                                    enabled = !busy && id > 0)
                                Column {
                                    Text(row.optString("name", id.toString()),
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(text(if (selected) R.string.sandbox_package_selected
                                        else R.string.sandbox_tap_package),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    val readiness = when {
                        account.isBlank() -> R.string.sandbox_select_account_reason
                        packages.isEmpty() -> R.string.sandbox_load_packages_reason
                        packageId == null -> R.string.sandbox_select_package_reason
                        draft == null -> R.string.sandbox_check_order_reason
                        else -> R.string.sandbox_order_ready
                    }
                    Text(text(readiness), style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = if (draft != null && account.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error)
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(text(R.string.sandbox_cancel_immediately),
                                    style = MaterialTheme.typography.titleSmall)
                                Text(text(R.string.sandbox_cancel_immediately_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic)
                            }
                            Switch(checked = cancelImmediately,
                                onCheckedChange = { cancelImmediately = it }, enabled = !busy)
                        }
                    }
                    Button(onClick = { confirming = draft }, enabled = !busy && draft != null && account.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) { Text(text(R.string.sandbox_place)) }
                } else {
                    Text(text(R.string.sandbox_previous_state, last!!.optString("state")), style = MaterialTheme.typography.titleSmall)
                    last!!.optString("order_id").takeIf { it.isNotBlank() }?.let { Text(text(R.string.trade_order_reference, it)) }
                    if (last!!.has("backend_request_id")) Text(
                        text(if (last!!.optString("backend_state") == "REPORTED")
                            R.string.trade_backend_reported else R.string.trade_backend_pending),
                        style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = if (last!!.optString("backend_state") == "REPORTED")
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    FilledTonalButton(onClick = { task { session.detail() } }, enabled = !busy) { Text(text(R.string.sandbox_order_status)) }
                    Button(onClick = { task { session.cancel() } }, enabled = !busy && last!!.has("order_id") &&
                        last!!.optString("state") == "SUBMITTED",
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text(text(R.string.sandbox_cancel))
                    }
                    TextButton(onClick = { task { session.reset() } }, enabled = !busy) { Text(text(R.string.sandbox_new_test)) }
                    Text(text(R.string.sandbox_previous_test), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
                Text(text(R.string.sandbox_responses), style = MaterialTheme.typography.titleMedium)
                if (responses.isEmpty()) Text(text(R.string.sandbox_no_response), style = MaterialTheme.typography.bodySmall)
                responses.asReversed().forEach { result ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("HTTP ${result.status} • ${result.method}", style = MaterialTheme.typography.titleSmall,
                                color = if (result.status in 200..299) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text(result.path, style = MaterialTheme.typography.bodySmall)
                            SelectionContainer {
                                Text(result.body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()))
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(text(R.string.close)) } })
    confirming?.let { review ->
        AlertDialog(onDismissRequest = { confirming = null }, title = { Text(text(R.string.sandbox_place)) },
            text = { Text(text(R.string.sandbox_order_review, account, review.symbol, review.quantity,
                OrderContent.money(java.math.BigDecimal(review.price)),
                OrderContent.money(java.math.BigDecimal(review.price).multiply(java.math.BigDecimal(review.quantity))))) },
            confirmButton = { Button(onClick = { confirming = null; task {
                last = session.place(account, review, cancelImmediately)
            } }) { Text(text(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(text(R.string.back)) } })
    }
}
