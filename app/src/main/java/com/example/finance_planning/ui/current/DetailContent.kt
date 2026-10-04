package com.example.finance_planning.ui.current

import com.example.finance_planning.R
import com.example.finance_planning.ui.*
import androidx.compose.ui.res.stringResource as text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import com.example.finance_planning.ui.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.example.finance_planning.core.objects
import com.example.finance_planning.core.NotificationContent
import com.example.finance_planning.core.OrderContent
import org.json.JSONObject

@Composable
internal fun OrderDetails(event: JSONObject, plan: Boolean) {
    val p = if (plan) event.optJSONObject("sheet") ?: event else event
    val side = when(p.optString("side")) { "BUY" -> text(R.string.buy); "SELL" -> text(R.string.sell); else -> if (plan) text(R.string.plan) else text(R.string.orders) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (plan) NotificationContent.title(event) else text(R.string.order_heading, side, p.optString("symbol")), style = MaterialTheme.typography.titleMedium)
        Text(if (plan) text(R.string.plan_execution_on_dnse_not_confirmed) else text(R.string.actual_order, OrderContent.status(p)),
            color = MaterialTheme.colorScheme.primary)
        DetailValue(text(R.string.current_ui_time), NotificationContent.time(event.optString(if (plan) "due_at" else "created_at")))
        if (plan) {
            Text(NotificationContent.body(event))
            Text(NotificationContent.status(event))
            event.optString("expires_at").takeIf { it.isNotBlank() }?.let { Text(text(R.string.until, NotificationContent.time(it))) }
        }
        val qty = OrderContent.number(p, "quantity")
        val price = OrderContent.number(p, "price_vnd")
        DetailGrid(listOf(
            text(R.string.trade_quantity) to OrderContent.quantity(p, "quantity"),
            text(R.string.trade_price_vnd) to OrderContent.money(price),
            text(R.string.current_ui_total) to OrderContent.money(if (qty != null && price != null) qty * price else null)))
        if (!plan) {
            val filled = OrderContent.number(p, "filled_quantity")
            val fillPrice = OrderContent.number(p, "average_fill_price_vnd")
            DetailValue(text(R.string.current_ui_filled), OrderContent.quantity(p, "filled_quantity"))
            if (qty != null && filled != null) DetailValue(text(R.string.current_ui_remaining), (qty - filled).max(java.math.BigDecimal.ZERO).stripTrailingZeros().toPlainString())
            DetailValue(text(R.string.current_ui_filled_value), OrderContent.money(if (filled != null && fillPrice != null) filled * fillPrice else null))
        }
        Text(text(R.string.dnse_account_2, event.optString("account")), style = MaterialTheme.typography.bodySmall)
        Text(if (plan) text(R.string.source_backend_plans_notifications) else text(R.string.broker_orders_source), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun BatchDetails(detail: JSONObject) {
    Text(text(R.string.received_at, NotificationContent.time(detail.optString("received_at"))))
    Text(text(R.string.record_count, detail.optInt("record_count", detail.objects("records").size)))
    detail.objects("records").forEach { row ->
        val p = OrderContent.payload(row)
        if (row.optString("kind") == "order") OrderDetails(p, false)
        else {
            Text(when (row.optString("kind")) { "execution" -> text(R.string.execution); "balance" -> text(R.string.balance); "position" -> text(R.string.position); else -> text(R.string.dnse_data) }, style = MaterialTheme.typography.titleSmall)
            Text(text(R.string.account, p.optString("account")))
            p.optString("symbol").takeIf { it.isNotBlank() }?.let { Text(text(R.string.symbol, it)) }
            OrderContent.number(p, "quantity")?.let { Text(text(R.string.quantity, it.stripTrailingZeros().toPlainString())) }
            OrderContent.number(p, "cash_vnd")?.let { Text(text(R.string.available_cash, OrderContent.money(it))) }
        }
    }
}
