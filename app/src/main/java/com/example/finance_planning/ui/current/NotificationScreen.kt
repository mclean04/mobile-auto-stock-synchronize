package com.example.finance_planning.ui.current

import com.example.finance_planning.R
import com.example.finance_planning.ui.*
import androidx.compose.ui.res.stringResource as text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import com.example.finance_planning.ui.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.finance_planning.core.NotificationContent
import com.example.finance_planning.ui.ScreenState
import org.json.JSONObject

@Composable
internal fun NotificationList(s: ScreenState, open: (String) -> Unit, more: () -> Unit) {
    LazyVerticalGrid(columns = GridCells.Adaptive(if (LocalAdaptiveLayout.current.tablet) 340.dp else 1000.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  Text(if (s.admin) text(R.string.all_notifications) else text(R.string.my_notifications),
            style = MaterialTheme.typography.titleLarge)  } }
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  Text(text(R.string.notifications_storage_hint),
            style = MaterialTheme.typography.bodySmall)  } }
        items(s.notifications, key = { it.optString("event_id", it.optString("id")) }) { row ->
            Card(onClick = { open(row.optString("event_id", row.optString("id"))) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !s.busy,
                shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(NotificationContent.title(row), style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (!row.optBoolean("_opened")) androidx.compose.ui.text.font.FontWeight.Bold
                                else androidx.compose.ui.text.font.FontWeight.Normal,
                            modifier = Modifier.weight(1f))
                        if (!row.optBoolean("_opened")) Badge(
                            containerColor = androidx.compose.ui.graphics.Color(0xFFB3261E),
                            contentColor = androidx.compose.ui.graphics.Color.White) { Text(text(R.string.notification_unread)) }
                    }
                    Text(NotificationContent.body(row), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(NotificationContent.status(row), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(NotificationContent.time(row.optString("created_at", row.optString("due_at"))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text(R.string.view_content), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (s.notifications.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  Text(text(R.string.no_notifications_yet))  } }
        if (s.notificationCursor != null) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = more, enabled = !s.busy) { Text(text(R.string.more_notifications)) }
         } }
    }
}

@Composable
internal fun NotificationDetails(event: JSONObject) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(NotificationContent.title(event), style = MaterialTheme.typography.titleLarge)
        Text(NotificationContent.body(event), style = MaterialTheme.typography.bodyLarge)
        Text(NotificationContent.status(event), color = MaterialTheme.colorScheme.primary)
        listOf("account" to text(R.string.account_2), "order_id" to text(R.string.order_to_review),
            "due_at" to text(R.string.reminder_time), "expires_at" to text(R.string.valid_until)).forEach { (key, label) ->
            val value = event.optString(key).takeIf { it.isNotBlank() && it != "null" }
            if (value != null) { val display = if (key.endsWith("_at")) NotificationContent.time(value) else value
                DetailValue(label, display) }
        }
        if (!event.optBoolean("local_only"))
            Text(text(R.string.notification_read_only_notice),
                style = MaterialTheme.typography.bodySmall)
    }
}

