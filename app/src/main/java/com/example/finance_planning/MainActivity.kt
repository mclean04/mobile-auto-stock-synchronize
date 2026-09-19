package com.example.finance_planning

import androidx.compose.ui.res.stringResource as text
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import com.example.finance_planning.ui.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.finance_planning.core.objects
import com.example.finance_planning.core.NotificationContent
import com.example.finance_planning.core.OrderContent
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.finance_planning.ui.PlanningViewModel
import com.example.finance_planning.ui.ScreenState
import com.example.finance_planning.ui.DetailKind
import com.example.finance_planning.ui.theme.Finance_planningTheme
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val model: PlanningViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { Finance_planningTheme { NotificationPermissionOnStart(); PlanningScreen(model) } }
        acceptIntent(intent)

    }
    override fun onResume() {
        super.onResume()
        if (model.repo.approved()) model.resume()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }
    private fun acceptIntent(intent: Intent) {
        val event = intent.getStringExtra("event_id")
        val targetUid = intent.getStringExtra("target_uid")
        if (event != null && model.repo.approved() &&
            (targetUid == null || targetUid == model.repo.identity.uid())) model.notification(event)
        if (event != null || intent.getBooleanExtra("notification_inbox", false) ||
            intent.hasExtra("google.message_id") || intent.hasExtra("gcm.message_id"))
            model.notificationInbox()
        intent.removeExtra("event_id")
        intent.removeExtra("notification_inbox")
        intent.removeExtra("google.message_id")
        intent.removeExtra("gcm.message_id")
    }
}

@Composable
private fun PlanningScreen(model: PlanningViewModel) {
    val s by model.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showNotifications) { showNotifications = false }
    LaunchedEffect(s.notificationNavigation) { if (s.notificationNavigation > 0) showNotifications = true }
    var confirm by remember { mutableStateOf("") }
    val labels = listOf(text(R.string.orders), text(R.string.settings)) +
        if (s.admin) listOf(text(R.string.admin)) else emptyList()
    val icons = listOf(R.drawable.ic_nav_clipboard_list, R.drawable.ic_nav_settings, R.drawable.ic_nav_shield_user)
    LaunchedEffect(s.admin) { if (tab !in labels.indices) tab = 0 }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tablet = maxWidth >= 600.dp && maxHeight >= 480.dp
        val landscape = tablet && maxWidth >= 900.dp && maxWidth > maxHeight
        CompositionLocalProvider(LocalAdaptiveLayout provides AdaptiveLayout(tablet, landscape)) {
            Scaffold(bottomBar = {
                if (!showNotifications && !landscape) Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))) {
                    NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent, tonalElevation = 0.dp) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                                labels.forEachIndexed { index, title ->
                                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                                        icon = { NavigationIcon(icons[index], tab == index) },
                                        label = { NavigationLabel(title, tab == index) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                                }
                            }
                        }
                    }
                }
            }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (!showNotifications && landscape) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Spacer(Modifier.height(24.dp))
                        labels.forEachIndexed { index, title ->
                            NavigationRailItem(selected = tab == index, onClick = { tab = index },
                                icon = { NavigationIcon(icons[index], tab == index) },
                                label = { NavigationLabel(title, tab == index) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = if (landscape) 1440.dp else 840.dp)
                            .fillMaxSize().padding(horizontal = if (tablet) 24.dp else 16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (showNotifications) IconButton(onClick = { showNotifications = false }) {
                                    Icon(painterResource(R.drawable.ic_back_arrow), contentDescription = text(R.string.back))
                                }
                                Text(text(if (showNotifications) R.string.notifications else R.string.app_heading),
                                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                                if (!showNotifications) {
                                    val unreadCount = s.notifications.count { !it.optBoolean("_opened") }
                                    val unreadDescription = text(R.string.unread_notifications_count, unreadCount)
                                    // The badge is a sibling of the button, so its round shape cannot clip it.
                                    Box(Modifier.size(56.dp)) {
                                        FilledTonalIconButton(onClick = { showNotifications = true },
                                            modifier = Modifier.size(48.dp).align(androidx.compose.ui.Alignment.BottomEnd)
                                                .semantics { stateDescription = unreadDescription }) {
                                            Icon(painterResource(R.drawable.ic_notifications_bell),
                                                contentDescription = text(R.string.open_notifications),
                                                modifier = Modifier.size(24.dp))
                                        }
                                        if (unreadCount > 0) Badge(
                                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopStart).zIndex(1f),
                                            containerColor = androidx.compose.ui.graphics.Color(0xFFB3261E),
                                            contentColor = androidx.compose.ui.graphics.Color.White) {
                                            Text(if (unreadCount > 99) text(R.string.notification_badge_overflow)
                                                else unreadCount.toString(), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(s.message, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 8.dp))
                            if (showNotifications) NotificationList(s, model) else when (tab) {
                                0 -> Orders(s, model)
                                1 -> Settings(s, model) { confirm = it }
                                2 -> if (s.admin) AdminPanel(s, model) { confirm = "import" }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm.isNotEmpty()) AlertDialog(onDismissRequest = { confirm = "" },
        title = { Text(if (confirm == "logout") text(R.string.sign_out_and_delete_data_on_this_device) else text(R.string.import_planning_into_the_database_again)) },
        text = { Text(if (confirm == "logout") text(R.string.logout_data_warning)
            else text(R.string.planning_import_explanation)) },
        confirmButton = { TextButton(onClick = { if (confirm == "logout") model.logout() else model.importPlanning(); confirm = "" }) { Text(text(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = "" }) { Text(text(R.string.back)) } })
    s.detail?.let { detail ->
        AlertDialog(onDismissRequest = model::dismissDetail,
            title = { Text(text(when (s.detailKind) {
                DetailKind.NOTIFICATION -> R.string.notification_content
                DetailKind.ORDER -> R.string.order_details
                else -> R.string.sync_batch_details
            })) },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (s.detailKind != DetailKind.NOTIFICATION)
                    Text(text(R.string.orders_read_only_notice))
                if (s.detailKind == DetailKind.NOTIFICATION) NotificationDetails(detail)
                else if (s.detailKind == DetailKind.ORDER) OrderDetails(OrderContent.payload(detail), false) else BatchDetails(detail)
            }},
            confirmButton = { TextButton(onClick = model::dismissDetail) { Text(text(R.string.close)) } })
    }
}
@Composable
private fun NavigationIcon(@androidx.annotation.DrawableRes icon: Int, selected: Boolean) {
    val size by androidx.compose.animation.core.animateDpAsState(if (selected) 26.dp else 24.dp,
        label = "navigationIconSize")
    // The label already names the tab for accessibility; avoid announcing it twice.
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(size))
}

@Composable
private fun NavigationLabel(title: String, selected: Boolean) {
    Text(title, style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold
            else androidx.compose.ui.text.font.FontWeight.Medium)
}

@Composable
private fun ConnectionStatus(title: String, verified: Boolean, ready: String, pending: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = androidx.compose.ui.graphics.Color(0xFFE0E8F4))
        Surface(shape = RoundedCornerShape(8.dp),
            color = androidx.compose.ui.graphics.Color(if (verified) 0xFF354C70 else 0xFF634350)) {
            Text(if (verified) ready else pending, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = androidx.compose.ui.graphics.Color(if (verified) 0xFFF1F5FF else 0xFFFFDBD7))
        }
    }
}

@Composable
private fun ConnectionSummary(s: ScreenState, notificationsAllowed: Boolean) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = androidx.compose.ui.graphics.Color(0xFF243249),
        contentColor = androidx.compose.ui.graphics.Color.White) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.ui.graphics.Color(0xFFCBDCFF))) {
                Text(text(if (showDetails) R.string.connection_hide_details else R.string.connection_show_details),
                    style = MaterialTheme.typography.labelSmall)
            }
            if (showDetails) {
                listOf(R.string.backend_access_status_note, R.string.fcm_registration_status_note,
                    R.string.android_notification_status_note).forEach { note ->
                    Text(text(note), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = androidx.compose.ui.graphics.Color(0xFFCED8E8))
                }
            }
        }
    }
}

@Composable
private fun Settings(s: ScreenState, model: PlanningViewModel, confirm: (String) -> Unit) {
    var showSandboxTest by remember { mutableStateOf(false) }
    if (showSandboxTest && s.hasSandboxKeys && s.approved)
        com.example.finance_planning.ui.SandboxTradeDialog(model.repo) { showSandboxTest = false }
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var production by remember(s.dnseProduction) { mutableStateOf(s.dnseProduction ?: false) }
    var unit by remember { mutableStateOf("1") }
    LaunchedEffect(production, s.hasProductionKeys, s.hasSandboxKeys, s.email) { key = ""; secret = ""; unit = "1" }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    SettingsPanes(left = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text(R.string.settings), style = MaterialTheme.typography.titleLarge)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text(R.string.google_account), style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                if (s.signedIn) Text(s.email, style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                SyncNote(text(R.string.google_account_status_note))
            }
        }
        val notificationsAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        ConnectionSummary(s, notificationsAllowed)
        TextButton(onClick = model::copyFcmToken, enabled = s.approved && !s.busy) {
            Text(text(R.string.copy_current_fcm_token))
        }
        SyncNote(text(R.string.fcm_token_test_hint))
        if (!s.configured) Text(text(R.string.sign_in_configuration_required))
        Button(onClick = { model.signIn(context) }, enabled = s.configured && !s.signedIn && !s.busy) { Text(text(R.string.sign_in_with_google)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = model::health, enabled = !s.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF244866),
                            contentColor = androidx.compose.ui.graphics.Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
                        Text(text(R.string.check_server), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                    SyncNote(text(R.string.check_server_note))
                }
            }
            Card(Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = model::verify, enabled = s.signedIn && !s.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF174D3C),
                            contentColor = androidx.compose.ui.graphics.Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
                        Text(text(R.string.check_access), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                    SyncNote(text(R.string.check_access_note))
                }
            }
        }
        HorizontalDivider()
        Text(text(R.string.dnse_read_only), style = MaterialTheme.typography.titleLarge)
        SyncNote(text(R.string.dnse_keys_storage_notice))
        Text(text(R.string.dnse_environment), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = production, enabled = s.signedIn && !s.busy, onClick = { production = true; model.saveDnseEnvironment(true) },
                label = { Text(text(R.string.production_live)) })
            FilterChip(selected = !production, enabled = s.signedIn && !s.busy, onClick = { production = false; model.saveDnseEnvironment(false) },
                label = { Text(text(R.string.sandbox_test)) })
        }
        val selectedHasKeys = if (production) s.hasProductionKeys else s.hasSandboxKeys
        val environmentName = text(if (production) R.string.dnse_production_name else R.string.dnse_sandbox_name)
        if (selectedHasKeys) {
            SyncNote(text(R.string.dnse_environment_keys_saved, environmentName))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (production) {
                    Surface(modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        color = androidx.compose.ui.graphics.Color(0xFF174D3C),
                        contentColor = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(14.dp)) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(text(R.string.dnse_active_keys_status, environmentName),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(onClick = { model.saveDnseEnvironment(production) }, enabled = s.signedIn && !s.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF174D3C),
                            contentColor = androidx.compose.ui.graphics.Color.White,
                            disabledContainerColor = androidx.compose.ui.graphics.Color(0xFF36594C),
                            disabledContentColor = androidx.compose.ui.graphics.Color(0xFFD4DED9)),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Text(text(R.string.dnse_use_environment_keys, environmentName))
                    }
                }
                Button(onClick = { model.deleteDnse(production) }, enabled = !s.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF8D2838),
                        contentColor = androidx.compose.ui.graphics.Color.White,
                        disabledContainerColor = androidx.compose.ui.graphics.Color(0xFF68414A),
                        disabledContentColor = androidx.compose.ui.graphics.Color(0xFFE6D5D9)),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
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
            Button(onClick = { model.saveDnse(key, secret, production, unit) },
                enabled = s.signedIn && key.isNotBlank() && secret.isNotBlank() && !s.busy) { Text(text(R.string.save_keys)) }
        }
        Text(if (s.hasDnse) text(if (s.dnseProduction == true) R.string.saved_production_live_dnse_account
            else R.string.saved_sandbox_separate_test_keys_required) else text(R.string.dnse_environment_keys_missing, environmentName))
        FilledTonalButton(onClick = { showSandboxTest = true },
            enabled = s.approved && s.hasSandboxKeys && !s.busy, modifier = Modifier.fillMaxWidth()) {
            Text(text(R.string.sandbox_test_title))
        }
        SyncNote(text(if (s.hasSandboxKeys) R.string.sandbox_test_settings_note else R.string.sandbox_keys_required))
        HorizontalDivider()
        }
    }, right = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SyncBlockHeader(text(R.string.sync), text(R.string.sync_block_icon), MaterialTheme.colorScheme.primary)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(text(R.string.scheduled_sync), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Switch(checked = s.scheduleEnabled, onCheckedChange = model::schedule,
                    enabled = !s.busy && (s.approved || s.scheduleEnabled))
            }
            SyncNote(text(R.string.sync_schedule_explanation))
            } }
            SyncStatusLine(text(R.string.last_sync, s.lastSync))
            val sheetWrites = s.status?.opt("sheet_writes") as? Boolean
            SyncStatusLine(text(R.string.google_sheet_write_state, text(when (sheetWrites) {
                true -> R.string.enabled
                false -> R.string.sheet_writes_off
                null -> R.string.sheet_writes_unknown
            })))
            SyncNote(text(R.string.sheet_write_state_note))
            SyncStatusLine(text(R.string.sheet_status_verification, text(if (sheetWrites == null)
                R.string.sheet_status_not_received else R.string.sheet_status_received)))
            SyncNote(text(R.string.sheet_status_verification_note))
            SyncStatusLine(text(R.string.batches_awaiting_sheet_updates, s.status?.optInt("pending_sheet_batches") ?: 0))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SyncNote(text(R.string.sync_now_explanation))
            Button(onClick = model::sync, enabled = s.approved && s.hasDnse && !s.busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp)) {
                Text(text(R.string.sync_dnse_now))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(text(R.string.dnse_account), style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            DnseAccount(s)
        } }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SyncBlockHeader(text(R.string.sync_upload_queue), text(R.string.upload_block_icon), MaterialTheme.colorScheme.tertiary)
            SyncNote(text(R.string.retry_upload_explanation))
            FilledTonalButton(onClick = model::retry, enabled = s.approved && !s.busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp)) {
                Text(text(R.string.retry_pending_uploads_count, s.localQueue.size))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text(R.string.batches_uploaded_to_the_backend), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                    HorizontalDivider()
                    if (s.batches.isEmpty()) Text(text(R.string.no_uploaded_batches), style = MaterialTheme.typography.bodySmall)
                    s.batches.forEachIndexed { index, row ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !s.busy,
                            role = androidx.compose.ui.semantics.Role.Button) { model.batch(row.getString("id")) }
                            .padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text(R.string.sync_batch_number, index + 1), style = MaterialTheme.typography.labelLarge)
                            Text(text(R.string.uploaded_records, row.optInt("record_count"),
                                NotificationContent.time(row.optString("received_at"))), style = MaterialTheme.typography.bodySmall)
                            Text(text(R.string.view_batch_details), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                    if (s.batchCursor != null) TextButton(onClick = { model.more("batches") }, enabled = !s.busy) {
                        Text(text(R.string.load_more))
                    }
                } }
            }
        } }
        TextButton(onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) { Text(text(R.string.allow_notifications)) }
        SyncNote(text(R.string.allow_notifications_note))
        OutlinedButton(onClick = { confirm("logout") }, enabled = s.signedIn && !s.busy) { Text(text(R.string.sign_out_and_delete_local_data)) }
        Spacer(Modifier.height(24.dp))
        }
    })
}
@Composable
private fun SyncStatusLine(label: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SyncNote(note: String) {
    Text(note, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SyncBlockHeader(title: String, symbol: String, accent: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = accent.copy(alpha = 0.12f), modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(symbol, style = MaterialTheme.typography.headlineSmall, color = accent)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

@Composable private fun InfoCard(data: JSONObject, title: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text(data.optString("Mã", title), style = MaterialTheme.typography.titleMedium)
        JsonFields(data)
    }}
}
@Composable private fun JsonFields(data: JSONObject) {
    data.keys().asSequence().toList().forEach { key ->
        val value = data.opt(key)
        if (value is JSONObject) { Text(key, style = MaterialTheme.typography.labelLarge); JsonFields(value) }
        else if (value is JSONArray) Text(text(R.string.records, key, value.length()))
        else if (value != null && value != JSONObject.NULL && value.toString().isNotBlank())
            Text(text(R.string.label_value, key, value), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
    }
}


@Composable
private fun AdminPanel(s: ScreenState, model: PlanningViewModel, importPlanning: () -> Unit) {
    com.example.finance_planning.ui.AdminDashboard(s, model, importPlanning)
}


@Composable
private fun NotificationList(s: ScreenState, model: PlanningViewModel) {
    LazyVerticalGrid(columns = GridCells.Adaptive(if (LocalAdaptiveLayout.current.tablet) 340.dp else 1000.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  Text(if (s.admin) text(R.string.all_notifications) else text(R.string.my_notifications),
            style = MaterialTheme.typography.titleLarge)  } }
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  Text(text(R.string.notifications_storage_hint),
            style = MaterialTheme.typography.bodySmall)  } }
        items(s.notifications, key = { it.optString("event_id", it.optString("id")) }) { row ->
            Card(onClick = { model.notification(row.optString("event_id", row.optString("id"))) },
                modifier = Modifier.fillMaxWidth(), enabled = !s.busy) {
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
                    Text(NotificationContent.body(row), style = MaterialTheme.typography.bodyLarge)
                    Text(NotificationContent.status(row), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(NotificationContent.time(row.optString("created_at", row.optString("due_at"))),
                        style = MaterialTheme.typography.bodySmall)
                    Text(text(R.string.view_content), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (s.notifications.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  Text(text(R.string.no_notifications_yet))  } }
        if (s.notificationCursor != null) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model.more("notifications") }, enabled = !s.busy) { Text(text(R.string.more_notifications)) }
         } }
    }
}

@Composable
private fun NotificationDetails(event: JSONObject) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(NotificationContent.title(event), style = MaterialTheme.typography.titleLarge)
        Text(NotificationContent.body(event), style = MaterialTheme.typography.bodyLarge)
        Text(NotificationContent.status(event), color = MaterialTheme.colorScheme.primary)
        listOf("account" to text(R.string.account_2), "order_id" to text(R.string.order_to_review),
            "due_at" to text(R.string.reminder_time), "expires_at" to text(R.string.valid_until)).forEach { (key, label) ->
            val value = event.optString(key).takeIf { it.isNotBlank() && it != "null" }
            if (value != null) { val display = if (key.endsWith("_at")) NotificationContent.time(value) else value
                Text(text(R.string.label_value, label, display)) }
        }
        if (!event.optBoolean("local_only"))
            Text(text(R.string.notification_read_only_notice),
                style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NotificationPermissionOnStart() {
    val context = LocalContext.current
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!requested && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            requested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AccountBlock(title: String, source: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun AccountMetric(label: String, value: String, accent: androidx.compose.ui.graphics.Color) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.08f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = accent)
        }
    }
}

@Composable
private fun DnseAccount(s: ScreenState) {
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
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(position.optString("symbol"), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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

@Composable
private fun Orders(s: ScreenState, model: PlanningViewModel) {
    var tradePlan by remember { mutableStateOf<JSONObject?>(null) }
    tradePlan?.let { com.example.finance_planning.ui.ManualTradeDialog(it, model.repo) { tradePlan = null } }
    var section by rememberSaveable { mutableIntStateOf(0) }
    val response = if (section == 0) s.upcomingPlanning else s.planningHistory
    val rows = response?.objects("items").orEmpty()
    Column {
        SecondaryTabRow(selectedTabIndex = section) {
            listOf(text(R.string.pending), text(R.string.history)).forEachIndexed { i, label ->
                Tab(selected = section == i, onClick = { section = i }, text = { Text(label) })
            }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(if (LocalAdaptiveLayout.current.tablet) 340.dp else 1000.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (section == 0) text(R.string.pending_orders_explanation)
                    else text(R.string.order_history_explanation), modifier = Modifier.padding(vertical = 12.dp))
                val savedAt = if (section == 0) s.upcomingSavedAt else s.historySavedAt
                savedAt?.let { SyncNote(text(R.string.planning_cache_saved_at, NotificationContent.time(it))) }
                SyncNote(text(R.string.planning_cache_display_note))
                FilledTonalButton(onClick = model::refreshPlanning, enabled = s.approved && !s.busy,
                    modifier = Modifier.padding(top = 8.dp)) { Text(text(R.string.refresh_planning_orders)) }
                SyncNote(text(R.string.planning_cache_refresh_note))
             } }
            items(rows) { row ->
                com.example.finance_planning.ui.PlanningOrderCard(row, s.dnse, section == 0,
                    s.approved && s.hasDnse && !s.busy, s.dnseProduction,
                    freshForAction = s.planningExecutionFresh) { tradePlan = row }
            }
            if (!s.busy && (response == null || response.optBoolean("_not_available"))) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  Text(text(if (s.approved && !s.admin)
                R.string.this_feature_requires_admin_access else R.string.planning_orders_unavailable))  } }
            else if (!s.busy && rows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  Text(text(R.string.no_orders_or_plans_in_this_group_yet))  } }
        }
    }
}

@Composable
private fun OrderDetails(event: JSONObject, plan: Boolean) {
    val p = if (plan) event.optJSONObject("sheet") ?: event else event
    val side = when(p.optString("side")) { "BUY" -> text(R.string.buy); "SELL" -> text(R.string.sell); else -> if (plan) text(R.string.plan) else text(R.string.orders) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (plan) NotificationContent.title(event) else text(R.string.order_heading, side, p.optString("symbol")), style = MaterialTheme.typography.titleMedium)
        Text(if (plan) text(R.string.plan_execution_on_dnse_not_confirmed) else text(R.string.actual_order, OrderContent.status(p)),
            color = MaterialTheme.colorScheme.primary)
        Text(text(R.string.time, NotificationContent.time(event.optString(if (plan) "due_at" else "created_at"))))
        if (plan) {
            Text(NotificationContent.body(event))
            Text(NotificationContent.status(event))
            event.optString("expires_at").takeIf { it.isNotBlank() }?.let { Text(text(R.string.until, NotificationContent.time(it))) }
        }
        Text(text(R.string.quantity, OrderContent.quantity(p, "quantity")))
        val qty = OrderContent.number(p, "quantity")
        val price = OrderContent.number(p, "price_vnd")
        Text(text(R.string.order_price, OrderContent.money(price)))
        Text(text(R.string.estimated_value, OrderContent.money(if (qty != null && price != null) qty * price else null)))
        if (!plan) {
            val filled = OrderContent.number(p, "filled_quantity")
            val fillPrice = OrderContent.number(p, "average_fill_price_vnd")
            Text(text(R.string.filled, OrderContent.quantity(p, "filled_quantity")))
            if (qty != null && filled != null) Text(text(R.string.remaining, (qty - filled).max(java.math.BigDecimal.ZERO).stripTrailingZeros().toPlainString()))
            Text(text(R.string.filled_value, OrderContent.money(if (filled != null && fillPrice != null) filled * fillPrice else null)))
        }
        Text(text(R.string.dnse_account_2, event.optString("account")), style = MaterialTheme.typography.bodySmall)
        Text(if (plan) text(R.string.source_backend_plans_notifications) else text(R.string.broker_orders_source), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BatchDetails(detail: JSONObject) {
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
