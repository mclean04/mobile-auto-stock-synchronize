package com.example.finance_planning.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import com.example.finance_planning.ui.layout.LocalAdaptiveLayout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource as text
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.finance_planning.R
import com.example.finance_planning.core.*
import org.json.JSONObject

@Composable
fun AdminDashboard(s: ScreenState, model: PlanningViewModel, importPlanning: () -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var recordKind by rememberSaveable { mutableStateOf("all") }
    val plans = s.planning?.objects("items").orEmpty()
    val selected = s.sources.firstOrNull { it.optString("id") == s.selectedSource }
    val ownUid = model.repo.identity.uid()
    val sourceTitle = when {
        s.selectedSource == null -> text(R.string.admin_no_account_selected)
        s.selectedSource == "legacy" -> text(R.string.shared_historical_data)
        selected?.optString("uid") == ownUid -> s.email
        else -> text(R.string.admin_google_data_account)
    }
    val sheetEnabled = s.status?.opt("sheet_writes") as? Boolean
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.admin_overview_section, R.string.admin_accounts_section, R.string.admin_plans_section).forEachIndexed { index, label ->
                FilterChip(selected = section == index, onClick = { section = index }, label = { Text(text(label)) })
            }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(if (LocalAdaptiveLayout.current.tablet) 340.dp else 1000.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            if (section == 0) {
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = Color(0xFF244866), contentColor = Color.White, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text(R.string.admin_role_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(text(R.string.admin_role_note), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = Color(0xFFD5E4EE))
                        }
                    }
                 } }
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AdminMetric(text(R.string.admin_loaded_accounts), s.sources.count { it.optString("id") != "legacy" }.toString(), Modifier.weight(1f))
                        AdminMetric(text(R.string.admin_loaded_plans), plans.size.toString(), Modifier.weight(1f))
                    }
                 } }
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminSection(text(R.string.admin_sheet_status_title), text(R.string.admin_sheet_status_note))
                    Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(16.dp),
                        color = if (sheetEnabled == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text(R.string.google_sheet_write_state, text(when(sheetEnabled) { true -> R.string.enabled; false -> R.string.sheet_writes_off; null -> R.string.sheet_writes_unknown })),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(text(R.string.batches_awaiting_sheet_updates, s.status?.optInt("pending_sheet_batches") ?: 0), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                 } }
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminSection(text(R.string.admin_selected_account_title), text(R.string.admin_account_scope_note))
                    AdminMetric(text(R.string.admin_viewing_account), sourceTitle, Modifier.fillMaxWidth().padding(top = 8.dp))
                 } }
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton({ section = 1 }, Modifier.weight(1f)) { Text(text(R.string.admin_view_account_data)) }
                        FilledTonalButton({ section = 2 }, Modifier.weight(1f)) { Text(text(R.string.admin_manage_plans)) }
                    }
                    Button(model::refresh, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !s.busy) { Text(text(R.string.admin_refresh_dashboard)) }
                    AdminNote(text(R.string.admin_refresh_dashboard_note))
                 } }
            } else if (section == 1) {
                item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  AdminSection(text(R.string.admin_choose_account_title), text(R.string.admin_choose_account_note))  } }
                items(s.sources) { source ->
                    val id = source.getString("id")
                    val isOwn = source.optString("uid") == ownUid
                    val active = s.selectedSource == id
                    Card(onClick = { recordKind = "all"; model.source(id) }, enabled = !s.busy,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(when { id == "legacy" -> text(R.string.shared_historical_data); isOwn -> text(R.string.admin_your_account); else -> text(R.string.admin_google_data_account) },
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (isOwn) Text(s.email, style = MaterialTheme.typography.bodyLarge)
                            else if (id != "legacy") Text(text(R.string.admin_google_account_reference, source.optString("uid")), style = MaterialTheme.typography.bodySmall)
                            Text(text(if (active) R.string.admin_account_selected else R.string.admin_tap_to_view_account), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (s.sourceCursor != null) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  FilledTonalButton(model::moreSources, enabled = !s.busy) { Text(text(R.string.more_accounts)) }  } }
                item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminSection(text(R.string.admin_account_records_title), text(R.string.admin_account_records_note))
                    if (s.selectedSource == null) AdminNote(text(R.string.admin_no_account_selected))
                    else {
                        Text(sourceTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("all" to R.string.admin_all_records, "balance" to R.string.balance, "order" to R.string.orders,
                                "position" to R.string.admin_holdings, "execution" to R.string.execution).forEach { (kind, label) ->
                                FilterChip(recordKind == kind, { recordKind = kind }, label = { Text(text(label)) })
                            }
                        }
                        AdminNote(text(R.string.admin_loaded_records_count, s.adminRecords.size))
                    }
                 } }
                items(s.adminRecords.filter { recordKind == "all" || it.optString("kind") == recordKind }) { AdminRecord(it) }
                if (s.selectedSource != null && !s.busy && s.adminRecords.none { recordKind == "all" || it.optString("kind") == recordKind })
                    item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  AdminNote(text(R.string.admin_no_loaded_records))  } }
                if (s.recordCursor != null) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  FilledTonalButton(model::moreRecords, enabled = !s.busy) { Text(text(R.string.more_records)) }  } }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  AdminSection(text(R.string.admin_plan_tools_title), text(R.string.admin_plan_tools_note))  } }
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(text(R.string.admin_read_plans_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Button(importPlanning, Modifier.fillMaxWidth(), enabled = !s.busy) { Text(text(R.string.admin_read_plans_button)) }
                            AdminNote(text(R.string.admin_read_plans_note))
                        }
                    }
                 } }
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(text(R.string.admin_write_sheet_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            FilledTonalButton(model::reconcile, Modifier.fillMaxWidth(), enabled = !s.busy) { Text(text(R.string.admin_write_sheet_button)) }
                            AdminNote(text(R.string.admin_write_sheet_note))
                            if (sheetEnabled == false) Text(text(R.string.admin_sheet_disabled_notice), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                 } }
                item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  AdminSection(text(R.string.admin_plan_list_title, plans.size), text(R.string.admin_plan_list_note))  } }
                if (plans.isEmpty()) item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  AdminNote(text(R.string.admin_no_plans))  } }
                items(plans) { PlanningOrderCard(it, null, upcoming = false, enabled = false, place = {}) }
            }
        }
    }
}

@Composable
private fun AdminRecord(row: JSONObject) {
    val p = OrderContent.payload(row)
    val kind = row.optString("kind")
    val title = text(when(kind) { "balance" -> R.string.balance; "order" -> R.string.orders; "position" -> R.string.admin_holdings; "execution" -> R.string.execution; else -> R.string.dnse_data })
    val data = when(kind) {
        "balance" -> listOf(text(R.string.cash_metric_label) to OrderContent.money(OrderContent.number(p, "cash_vnd")),
            text(R.string.buying_power_metric_label) to OrderContent.money(OrderContent.number(p, "buying_power_vnd")))
        "order" -> listOf(text(R.string.trade_quantity) to OrderContent.quantity(p, "quantity"),
            text(R.string.admin_filled_quantity) to OrderContent.quantity(p, "filled_quantity"),
            text(R.string.trade_price_vnd) to OrderContent.money(OrderContent.number(p, "price_vnd")),
            text(R.string.admin_order_status) to OrderContent.status(p))
        "position" -> listOf(text(R.string.trade_quantity) to OrderContent.quantity(p, "quantity"),
            text(R.string.admin_available_quantity) to OrderContent.quantity(p, "available_quantity"),
            text(R.string.admin_average_cost) to OrderContent.money(OrderContent.number(p, "average_price_vnd")))
        "execution" -> listOf(text(R.string.admin_filled_quantity) to OrderContent.quantity(p, "quantity"),
            text(R.string.admin_fill_price) to OrderContent.money(OrderContent.number(p, "price_vnd")),
            text(R.string.admin_trading_fee) to OrderContent.money(OrderContent.number(p, "fee_vnd")))
        else -> emptyList()
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title + p.optString("symbol").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (kind == "order") Text(text(if (p.optString("side") == "BUY") R.string.buy else R.string.sell),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(text(R.string.account, p.optString("account")), style = MaterialTheme.typography.bodyMedium)
            data.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (label, value) -> AdminMetric(label, value, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            val time = p.optString("executed_at").ifBlank { p.optString("updated_at") }
            if (time.isNotBlank()) AdminNote(text(R.string.admin_record_time, NotificationContent.time(time)))
            val reference = p.optString("order_id").takeIf { it.isNotBlank() }
            if (reference != null) AdminNote(text(R.string.trade_order_reference, reference))
        }
    }
}

@Composable
private fun AdminMetric(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
private fun AdminSection(title: String, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        AdminNote(note)
    }
}
@Composable
private fun AdminNote(note: String) {
    Text(note, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
