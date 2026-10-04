package com.example.finance_planning.ui.current

import com.example.finance_planning.R
import com.example.finance_planning.ui.*
import androidx.compose.ui.res.stringResource as text
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.lazy.grid.*
import com.example.finance_planning.ui.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.finance_planning.core.NotificationContent
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.finance_planning.ui.PlanningViewModel
import com.example.finance_planning.ui.ScreenState

@Composable
internal fun ConnectionStatus(title: String, verified: Boolean, ready: String, pending: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(8.dp),
            color = if (verified) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer) {
            Text(if (verified) ready else pending, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = if (verified) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
internal fun ConnectionSummary(s: ScreenState, notificationsAllowed: Boolean) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text(R.string.connection_summary_title), style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            ConnectionStatus(text(R.string.connection_backend_label), s.approved,
                text(R.string.connection_verified), text(R.string.connection_not_verified))
            ConnectionStatus(text(R.string.connection_fcm_label), s.pushRegistered,
                text(R.string.connection_registered), text(R.string.connection_not_registered))
            ConnectionStatus(text(R.string.connection_android_label), notificationsAllowed,
                text(R.string.connection_allowed), text(R.string.connection_not_allowed))
            TextButton(onClick = { showDetails = !showDetails },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Text(text(if (showDetails) R.string.connection_hide_details else R.string.connection_show_details),
                    style = MaterialTheme.typography.labelSmall)
            }
            if (showDetails) {
                listOf(R.string.backend_access_status_note, R.string.fcm_registration_status_note,
                    R.string.android_notification_status_note).forEach { note ->
                    Text(text(note), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun LocalPlanningConnectionInfo() {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text(R.string.local_planning_connection_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(com.example.finance_planning.core.Contracts.BACKEND, style = MaterialTheme.typography.bodyLarge)
            Text(text(R.string.local_planning_connection_note), style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic)
            Text(text(R.string.local_planning_session_uncertain), style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic)
        }
    }
}

/** UI callbacks retain the existing ViewModel operations; fixtures never need a repository. */
internal data class SettingsActions(
    val health: () -> Unit, val verify: () -> Unit, val copyFcmToken: () -> Unit,
    val saveDnseEnvironment: (Boolean) -> Unit, val deleteDnse: (Boolean) -> Unit,
    val saveDnse: (String, String, Boolean, String) -> Unit, val schedule: (Boolean) -> Unit,
    val sync: () -> Unit, val retry: () -> Unit, val batch: (String) -> Unit, val moreBatches: () -> Unit
)

@Composable
internal fun Settings(s: ScreenState, model: PlanningViewModel, confirm: (String) -> Unit) {
    SettingsContent(s, SettingsActions(model::health, model::verify, model::copyFcmToken,
        model::saveDnseEnvironment, model::deleteDnse, model::saveDnse, model::schedule,
        model::sync, model::retry, model::batch, { model.more("batches") }), confirm) { close ->
        com.example.finance_planning.ui.SandboxTradeDialog(model.repo, close)
    }
}

@Composable
internal fun SettingsContent(s: ScreenState, actions: SettingsActions, confirm: (String) -> Unit,
    sandboxDialog: @Composable (() -> Unit) -> Unit) {
    var showSandboxTest by remember { mutableStateOf(false) }
    if (showSandboxTest && s.hasSandboxKeys && s.approved)
        sandboxDialog { showSandboxTest = false }
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var production by remember(s.dnseProduction) { mutableStateOf(s.dnseProduction ?: false) }
    var unit by remember { mutableStateOf("1") }
    LaunchedEffect(production, s.hasProductionKeys, s.hasSandboxKeys, s.email) { key = ""; secret = ""; unit = "1" }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    SettingsPanes(left = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(text(R.string.google_account)) {
            if (s.signedIn) Text(s.email, style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            HelpDisclosure { SyncNote(text(R.string.google_account_status_note)) }
            if (!s.configured) Text(text(R.string.sign_in_configuration_required), color = MaterialTheme.colorScheme.error)
        }
        val notificationsAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        SectionCard(text(R.string.current_ui_connection)) {
            ConnectionSummary(s, notificationsAllowed)
            OutlinedButton(onClick = actions.health, enabled = !s.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.check_server)) }
            OutlinedButton(onClick = actions.verify, enabled = s.signedIn && !s.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.check_access)) }
            HelpDisclosure {
                SyncNote(text(R.string.check_server_note))
                SyncNote(text(R.string.check_access_note))
            }
        }
        SectionCard(text(R.string.dnse_read_only)) {
        HelpDisclosure { SyncNote(text(R.string.dnse_keys_storage_notice)) }
        Text(text(R.string.dnse_environment), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = production, enabled = s.signedIn && !s.busy, onClick = { production = true; actions.saveDnseEnvironment(true) },
                label = { Text(text(R.string.production_live)) })
            FilterChip(selected = !production, enabled = s.signedIn && !s.busy, onClick = { production = false; actions.saveDnseEnvironment(false) },
                label = { Text(text(R.string.sandbox_test)) })
        }
        val selectedHasKeys = if (production) s.hasProductionKeys else s.hasSandboxKeys
        val environmentName = text(if (production) R.string.dnse_production_name else R.string.dnse_sandbox_name)
        if (selectedHasKeys) {
            SyncNote(text(R.string.dnse_environment_keys_saved, environmentName))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (production) {
                    Surface(modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(16.dp)) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(text(R.string.dnse_active_keys_status, environmentName),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(onClick = { actions.saveDnseEnvironment(production) }, enabled = s.signedIn && !s.busy,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)) {
                        Text(text(R.string.dnse_use_environment_keys, environmentName))
                    }
                }
                Button(onClick = { actions.deleteDnse(production) }, enabled = !s.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)) {
                    Text(text(R.string.dnse_delete_environment_keys, environmentName))
                }
            }
        } else {
            SyncNote(text(R.string.dnse_environment_keys_missing, environmentName))
            OutlinedTextField(key, { key = it }, label = { Text(text(R.string.api_key)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text(text(R.string.api_secret)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text(text(R.string.api_price_unit))
            Row {
                FilterChip(selected = unit == "1000", onClick = { unit = "1000" }, label = { Text(text(R.string.thousand_vnd)) })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = unit == "1", onClick = { unit = "1" }, label = { Text(text(R.string.vnd)) })
            }
            Button(onClick = { actions.saveDnse(key, secret, production, unit) },
                enabled = s.signedIn && key.isNotBlank() && secret.isNotBlank() && !s.busy) { Text(text(R.string.save_keys)) }
        }
        Text(if (s.hasDnse) text(if (s.dnseProduction == true) R.string.saved_production_live_dnse_account
            else R.string.saved_sandbox_separate_test_keys_required) else text(R.string.dnse_environment_keys_missing, environmentName))
        FilledTonalButton(onClick = { showSandboxTest = true },
            enabled = s.approved && s.hasSandboxKeys && !s.busy, modifier = Modifier.fillMaxWidth()) {
            Text(text(R.string.sandbox_test_title))
        }
        SyncNote(text(if (s.hasSandboxKeys) R.string.sandbox_test_settings_note else R.string.sandbox_keys_required))
        }
        }
    }, right = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SyncBlockHeader(text(R.string.sync), text(R.string.sync_block_icon), MaterialTheme.colorScheme.primary)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(text(R.string.scheduled_sync), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Switch(checked = s.scheduleEnabled, onCheckedChange = actions.schedule,
                    enabled = !s.busy && (s.approved || s.scheduleEnabled))
            }
            HelpDisclosure { SyncNote(text(R.string.sync_schedule_explanation)) }
            } }
            SyncStatusLine(text(R.string.last_sync, s.lastSync))
            val sheetWrites = s.status?.sheetWrites
            SyncStatusLine(text(R.string.google_sheet_write_state, text(when (sheetWrites) {
                true -> R.string.enabled
                false -> R.string.sheet_writes_off
                null -> R.string.sheet_writes_unknown
            })))
            HelpDisclosure { SyncNote(text(R.string.sheet_write_state_note)) }
            SyncStatusLine(text(R.string.sheet_status_verification, text(if (sheetWrites == null)
                R.string.sheet_status_not_received else R.string.sheet_status_received)))
            HelpDisclosure { SyncNote(text(R.string.sheet_status_verification_note)) }
            SyncStatusLine(text(R.string.batches_awaiting_sheet_updates, s.status?.pendingSheetBatches ?: 0))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            HelpDisclosure { SyncNote(text(R.string.sync_now_explanation)) }
            Button(onClick = actions.sync, enabled = s.approved && s.hasDnse && !s.busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)) {
                Text(text(R.string.sync_dnse_now))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(text(R.string.dnse_account), style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            DnseAccount(s)
        } }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SyncBlockHeader(text(R.string.sync_upload_queue), text(R.string.upload_block_icon), MaterialTheme.colorScheme.tertiary)
            HelpDisclosure { SyncNote(text(R.string.retry_upload_explanation)) }
            FilledTonalButton(onClick = actions.retry, enabled = s.approved && !s.busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)) {
                Text(text(R.string.retry_pending_uploads_count, s.localQueue.size))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text(R.string.pending_uploads_on_this_device), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary)
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                        Text(text(R.string.pending_batch_count, s.localQueue.size), modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    HorizontalDivider()
                    if (s.localQueue.isEmpty()) Text(text(R.string.no_pending_uploads), style = MaterialTheme.typography.bodySmall)
                    s.localQueue.forEachIndexed { index, summary ->
                        Text(text(R.string.sync_batch_number, index + 1), style = MaterialTheme.typography.labelLarge)
                        Text(summary, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                } }
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text(R.string.batches_uploaded_to_the_backend), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                    HorizontalDivider()
                    if (s.batches.isEmpty()) Text(text(R.string.no_uploaded_batches), style = MaterialTheme.typography.bodySmall)
                    s.batches.forEachIndexed { index, row ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !s.busy,
                            role = androidx.compose.ui.semantics.Role.Button) { actions.batch(row.getString("id")) }
                            .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text(R.string.sync_batch_number, index + 1), style = MaterialTheme.typography.labelLarge)
                            Text(text(R.string.uploaded_records, row.optInt("record_count"),
                                NotificationContent.time(row.optString("received_at"))), style = MaterialTheme.typography.bodySmall)
                            Text(text(R.string.view_batch_details), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                    if (s.batchCursor != null) TextButton(onClick = { actions.moreBatches() }, enabled = !s.busy) {
                        Text(text(R.string.load_more))
                    }
                } }
            }
        } }
        SectionCard(text(R.string.notifications)) {
            OutlinedButton(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.allow_notifications)) }
            HelpDisclosure { SyncNote(text(R.string.allow_notifications_note)) }
        }
        SectionCard(text(R.string.current_ui_technical)) {
            HelpDisclosure(text(R.string.current_ui_technical)) {
                if (com.example.finance_planning.core.LocalBackend.active) LocalPlanningConnectionInfo()
                TextButton(onClick = actions.copyFcmToken, enabled = s.approved && !s.busy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(text(R.string.copy_current_fcm_token)) }
                SyncNote(text(R.string.fcm_token_test_hint))
            }
        }
        SectionCard(text(R.string.current_ui_logout)) {
            OutlinedButton(onClick = { confirm("logout") }, enabled = s.signedIn && !s.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.sign_out_and_delete_local_data)) }
        }
        Spacer(Modifier.height(24.dp))
        }
    })
}
