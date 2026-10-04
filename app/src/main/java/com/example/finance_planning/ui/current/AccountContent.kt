package com.example.finance_planning.ui.current

import com.example.finance_planning.R
import com.example.finance_planning.ui.*
import androidx.compose.ui.res.stringResource as text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.*
import com.example.finance_planning.ui.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.finance_planning.core.objects
import com.example.finance_planning.core.OrderContent
import com.example.finance_planning.ui.ScreenState
import org.json.JSONObject

@Composable
internal fun AccountBlock(title: String, source: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.width(4.dp).height(24.dp)) { }
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
            content()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SyncNote(text(R.string.source, source))
        }
    }
}

@Composable
internal fun AccountMetric(label: String, value: String, accent: androidx.compose.ui.graphics.Color) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = 0.08f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = accent)
        }
    }
}

@Composable
internal fun DnseAccount(s: ScreenState) {
    if (s.dnse?.objects("accounts").isNullOrEmpty()) SyncNote(text(R.string.dnse_account_empty))
    s.dnse?.objects("accounts")?.forEach { account ->
        val id = account.optString("account")
        AccountBlock(text(R.string.personal_information, id), text(R.string.get_accounts)) {
            val profile = account.optJSONObject("profile") ?: JSONObject()
            val fields = listOf("name" to text(R.string.full_name), "fullName" to text(R.string.full_name), "email" to text(R.string.email),
                "phone" to text(R.string.phone), "phoneNumber" to text(R.string.phone), "accountName" to text(R.string.account_name),
                "accountType" to text(R.string.account_type), "status" to text(R.string.status))
            val present = fields.filter { profile.optString(it.first).let { value -> value.isNotBlank() && value != "null" } }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(text(R.string.dnse_account_2, id), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            present.forEach { (key, label) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(profile.optString(key), modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                }
            }
            if (present.isEmpty()) SyncNote(text(R.string.dnse_profile_not_provided))
        }
        AccountBlock(text(R.string.cash_and_buying_power), text(R.string.get_accounts_balances, id)) {
            val balance = s.dnse.objects("balances").firstOrNull { it.optString("account") == id }
            AccountMetric(text(R.string.cash_metric_label),
                OrderContent.money(balance?.let { OrderContent.number(it, "cash_vnd") }), MaterialTheme.colorScheme.primary)
            AccountMetric(text(R.string.buying_power_metric_label),
                OrderContent.money(balance?.let { OrderContent.number(it, "buying_power_vnd") }), MaterialTheme.colorScheme.tertiary)
        }
        AccountBlock(text(R.string.stock_portfolio), text(R.string.get_accounts_positions_markettype_stock, id)) {
            val holdings = s.dnse.objects("positions").filter { it.optString("account") == id }
            if (holdings.isEmpty()) SyncNote(text(R.string.no_stock_positions_in_the_synced_data))
            holdings.forEach { position ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(position.optString("symbol"), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text(text(R.string.quantity_available_to_sell, OrderContent.quantity(position, "quantity"),
                            OrderContent.quantity(position, "available_quantity")), style = MaterialTheme.typography.bodyMedium)
                        Text(text(R.string.average_cost, OrderContent.money(OrderContent.number(position, "average_price_vnd"))),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    }
                }
            }
        }
    }
}

