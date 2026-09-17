package com.example.finance_planning.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.res.stringResource as text
import androidx.compose.ui.unit.dp
import com.example.finance_planning.R
import com.example.finance_planning.core.*
import org.json.JSONObject

@Composable
fun PlanningOrderCard(row: JSONObject, snapshot: JSONObject?, upcoming: Boolean, enabled: Boolean, place: () -> Unit) {
    val fields = PlanningFunds.fields(row)
    val cancelled = PlanningFunds.cancelled(row)
    // Explicit foreground/background pairs remain readable in either system theme.
    val background = when { !upcoming -> Color(0xFF30343B); cancelled -> Color(0xFF40242A); else -> Color(0xFF12382C) }
    val panel = when { !upcoming -> Color(0xFF3C424A); cancelled -> Color(0xFF503139); else -> Color(0xFF1B4939) }
    val foreground = Color(0xFFF4F7F5)
    val secondary = Color(0xFFCDD8D2)
    val accent = when { cancelled -> Color(0xFFFFCBD1); !upcoming -> foreground; else -> Color(0xFFB0E5C7) }
    val warning = Color(0xFFFFBAC3)
    val side = PlanningFunds.side(row)
    val required = PlanningFunds.required(row)
    val balances = PlanningFunds.balances(snapshot)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(fields.optString("Mã", text(R.string.plan)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = accent)
                    Text(row.optString("scheduled_date", fields.optString("Ngày dự kiến")), style = MaterialTheme.typography.titleSmall, color = secondary)
                }
                Surface(color = panel, contentColor = foreground, shape = RoundedCornerShape(50)) {
                    Text(text(when { cancelled -> R.string.plan_cancel_badge; side == "NB" -> R.string.plan_buy_badge; side == "NS" -> R.string.plan_sell_badge; else -> R.string.plan }),
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = accent, fontWeight = FontWeight.Bold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanMetric(text(R.string.trade_quantity), fields.optString("Số lượng", text(R.string.no_data_available)), Modifier.weight(1f), panel, foreground, secondary)
                PlanMetric(text(R.string.trade_price_vnd), OrderContent.money(PlanningFunds.price(row)), Modifier.weight(1f), panel, foreground, secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanMetric(text(R.string.plan_principal), OrderContent.money(PlanningFunds.principal(row)), Modifier.weight(1f), panel, foreground, secondary)
                PlanMetric(text(R.string.plan_required), OrderContent.money(required), Modifier.weight(1f), panel, foreground, secondary)
            }
            fields.optString("Trạng thái").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = accent)
            }
            if (upcoming && side == "NB" && !cancelled) {
                Surface(color = panel, contentColor = foreground, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text(R.string.plan_funds_heading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (balances.isEmpty()) Text(text(R.string.plan_funds_sync_required), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = secondary)
                        balances.forEach { (account, cash) ->
                            Text(text(R.string.plan_account_cash, account, OrderContent.money(cash)), fontWeight = FontWeight.SemiBold)
                            val difference = required?.let { cash - it }
                            Text(when {
                                difference == null -> text(R.string.plan_funds_unknown)
                                difference.signum() >= 0 -> text(R.string.plan_funds_enough, OrderContent.money(difference))
                                else -> text(R.string.plan_funds_short, OrderContent.money(difference.negate()))
                            }, color = if (difference != null && difference.signum() >= 0) accent else warning,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        snapshot?.optString("saved_at")?.takeIf { it.isNotBlank() }?.let {
                            Text(text(R.string.plan_funds_updated, NotificationContent.time(it)), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = secondary)
                        }
                        Text(text(R.string.plan_funds_comparison_note), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = secondary)
                        if (!PlanningFunds.affordable(snapshot, row)) Text(text(R.string.plan_funds_topup_note),
                            style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = secondary)
                    }
                }
            }
            if (upcoming && PlanningFunds.affordable(snapshot, row)) Button(onClick = place, enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB0E5C7), contentColor = Color(0xFF103426),
                    disabledContainerColor = Color(0xFF355947), disabledContentColor = Color(0xFFD1DDD7))) { Text(text(R.string.trade_title)) }
            val primary = setOf("Mã", "Ngày dự kiến", "Số lượng", "Giá LO tối đa", "Giá LO", "Giá LO (VND)", "Giá trị kế hoạch", "Tổng chi ngân sách", "Mua/Bán", "Trạng thái")
            val extra = fields.keys().asSequence().filter { it !in primary && !fields.isNull(it) && fields.optString(it).isNotBlank() }.toList()
            extra.filter { fields.optString(it).length <= 100 }.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { key -> PlanMetric(key, fields.optString(key), Modifier.weight(1f), panel, foreground, secondary) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            extra.filter { fields.optString(it).length > 100 }.forEach { key ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(key, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accent)
                    Text(fields.optString(key), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = secondary)
                }
            }
        }
    }
}

@Composable
private fun PlanMetric(label: String, value: String, modifier: Modifier, background: Color, foreground: Color, secondary: Color) {
    Surface(modifier, color = background, contentColor = foreground, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = secondary)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = foreground)
        }
    }
}
