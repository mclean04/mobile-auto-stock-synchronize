package com.example.finance_planning.ui.current

import com.example.finance_planning.R
import com.example.finance_planning.ui.*
import androidx.compose.ui.res.stringResource as text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.lazy.grid.*
import com.example.finance_planning.ui.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun SyncStatusLine(label: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun SyncNote(note: String) {
    Text(note, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SyncBlockHeader(title: String, symbol: String, accent: androidx.compose.ui.graphics.Color) {
    Text(title, style = MaterialTheme.typography.titleMedium,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = accent)
}

@Composable internal fun InfoCard(data: JSONObject, title: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text(data.optString("Mã", title), style = MaterialTheme.typography.titleMedium)
        JsonFields(data)
    }}
}
@Composable internal fun JsonFields(data: JSONObject) {
    data.keys().asSequence().toList().forEach { key ->
        val value = data.opt(key)
        if (value is JSONObject) { Text(key, style = MaterialTheme.typography.labelLarge); JsonFields(value) }
        else if (value is JSONArray) Text(text(R.string.records, key, value.length()))
        else if (value != null && value != JSONObject.NULL && value.toString().isNotBlank())
            Text(text(R.string.label_value, key, value), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
    }
}


