package com.example.finance_planning

import androidx.compose.ui.res.stringResource as text
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        if (model.repo.approved()) model.refresh()
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
    LaunchedEffect(s.notificationNavigation) { if (s.notificationNavigation > 0) tab = 2 }
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = (LocalContext.current as ComponentActivity).lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val viewNotification = text(R.string.view_content)
    val bannerMessage = s.foregroundNotification?.let {
        text(R.string.notification_banner_content, NotificationContent.title(it), NotificationContent.body(it))
    }
    LaunchedEffect(s.foregroundNotification?.optString("event_id"), started) {
        val event = s.foregroundNotification
        if (started && event != null) {
            val result = snackbar.showSnackbar(bannerMessage.orEmpty(),
                actionLabel = viewNotification, withDismissAction = true, duration = SnackbarDuration.Indefinite)
            if (result == SnackbarResult.ActionPerformed) model.notification(event.getString("event_id"))
            model.dismissForegroundNotification(event.getString("event_id"))
        }
    }
    var confirm by remember { mutableStateOf("") }
    val labels = listOf(text(R.string.dnse_account), text(R.string.orders),
        text(R.string.notifications), text(R.string.settings)) +
        if (s.admin) listOf(text(R.string.admin)) else emptyList()
    val icons = listOf(R.string.nav_account_icon, R.string.nav_orders_icon,
        R.string.nav_notifications_icon, R.string.nav_settings_icon, R.string.nav_admin_icon)
    LaunchedEffect(s.admin) { if (tab !in labels.indices) tab = 0 }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        NavigationBar { labels.forEachIndexed { index, title ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                icon = { Text(text(icons[index])) }, label = { Text(title) })
        }}
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(text(R.string.app_heading), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(s.message, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 10.dp))
            when (tab) {
                0 -> Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text(R.string.dnse_account), style = MaterialTheme.typography.titleLarge)
                    DnseAccount(s, model)
                    Spacer(Modifier.height(24.dp))
                }
                1 -> Orders(s, model)
                2 -> NotificationList(s, model)
                3 -> Settings(s, model) { confirm = it }
                4 -> if (s.admin) AdminPanel(s, model) { confirm = "import" }
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
private fun Settings(s: ScreenState, model: PlanningViewModel, confirm: (String) -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var production by remember(s.dnseProduction) { mutableStateOf(s.dnseProduction ?: false) }
    var unit by remember { mutableStateOf("1") }
    LaunchedEffect(s.hasDnse, s.email) { if (s.hasDnse || !s.signedIn) { key = ""; secret = "" } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text(R.string.settings), style = MaterialTheme.typography.titleLarge)
        Text(text(R.string.google_account), style = MaterialTheme.typography.titleLarge)
        if (s.signedIn) Text(s.email)
        Text(if (s.approved) text(R.string.backend_account_access_verified) else text(R.string.backend_connection_not_verified))
        Text(if (s.pushRegistered) text(R.string.fcm_device_registered_with_the_backend) else text(R.string.fcm_device_registration_incomplete))
        Text(if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
            text(R.string.notification_permission_enabled) else text(R.string.notification_permission_required))
        TextButton(onClick = model::copyFcmToken, enabled = s.approved && !s.busy) {
            Text(text(R.string.copy_current_fcm_token))
        }
        Text(text(R.string.fcm_token_test_hint), style = MaterialTheme.typography.bodySmall)
        if (!s.configured) Text(text(R.string.sign_in_configuration_required))
        Button(onClick = { model.signIn(context) }, enabled = s.configured && !s.signedIn && !s.busy) { Text(text(R.string.sign_in_with_google)) }
        Row {
            TextButton(onClick = model::health, enabled = !s.busy) { Text(text(R.string.check_server)) }
            TextButton(onClick = model::verify, enabled = s.signedIn && !s.busy) { Text(text(R.string.check_access)) }
        }
        HorizontalDivider()
        Text(text(R.string.dnse_read_only), style = MaterialTheme.typography.titleLarge)
        Text(text(R.string.dnse_keys_storage_notice))
        Text(text(R.string.dnse_environment), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = production, onClick = { production = true },
                label = { Text(text(R.string.production_live)) })
            FilterChip(selected = !production, onClick = { production = false },
                label = { Text(text(R.string.sandbox_test)) })
        }
        if (s.hasDnse) Button(onClick = { model.saveDnseEnvironment(production) },
            enabled = s.signedIn && !s.busy) {
            Text(text(R.string.save_environment_using_stored_keys))
        }
        if (s.hasDnse) {
            Text(text(R.string.dnse_keys_saved_delete_them_to_enter_new_keys))
            OutlinedButton(onClick = model::deleteDnse, enabled = !s.busy) { Text(text(R.string.delete_keys)) }
        } else {
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
        Text(when (s.dnseProduction) {
            true -> text(R.string.saved_production_live_dnse_account)
            false -> text(R.string.saved_sandbox_separate_test_keys_required)
            null -> text(R.string.no_dnse_keys_saved)
        })
        HorizontalDivider()
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text(R.string.sync), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(text(R.string.scheduled_sync), modifier = Modifier.weight(1f))
                Switch(checked = s.scheduleEnabled, onCheckedChange = model::schedule,
                    enabled = !s.busy && (s.approved || s.scheduleEnabled))
            }
            Text(text(R.string.sync_schedule_explanation))
            Text(text(R.string.last_sync, s.lastSync))
            val sheetStatus = if (s.status?.optBoolean("sheet_writes") == true) text(R.string.enabled) else text(R.string.disabled_not_verified)
            Text(text(R.string.sheet_writes, sheetStatus))
            Text(text(R.string.batches_awaiting_sheet_updates, s.status?.optInt("pending_sheet_batches") ?: 0))
            OutlinedButton(onClick = model::sync, enabled = s.approved && s.hasDnse && !s.busy) {
                Text(text(R.string.sync_dnse_now))
            }
            OutlinedButton(onClick = model::retry, enabled = s.approved && !s.busy) {
                Text(text(R.string.retry_pending_uploads))
            }
            Text(text(R.string.pending_uploads_on_this_device), style = MaterialTheme.typography.titleMedium)
            s.localQueue.forEach { Text(it) }
            Text(text(R.string.batches_uploaded_to_the_backend), style = MaterialTheme.typography.titleMedium)
            s.batches.forEach { row ->
                OutlinedButton(onClick = { model.batch(row.getString("id")) }, enabled = !s.busy) {
                    Text(text(R.string.uploaded_records, row.optInt("record_count"), NotificationContent.time(row.optString("received_at"))))
                }
            }
            if (s.batchCursor != null) TextButton(onClick = { model.more("batches") }, enabled = !s.busy) { Text(text(R.string.load_more)) }
        } }
        TextButton(onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) { Text(text(R.string.allow_notifications)) }
        OutlinedButton(onClick = { confirm("logout") }, enabled = s.signedIn && !s.busy) { Text(text(R.string.sign_out_and_delete_local_data)) }
        Spacer(Modifier.height(24.dp))
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(text(R.string.system_administration), style = MaterialTheme.typography.titleLarge) }
        item { Text(text(R.string.admin_source_explanation)) }
        items(s.sources) { source ->
            val id = source.getString("id")
            OutlinedButton(onClick = { model.source(id) }, enabled = !s.busy) {
                Text(text(R.string.selected_source_format,
                    if (s.selectedSource == id) text(R.string.selected_marker) else "",
                    if (id == "legacy") text(R.string.shared_historical_data) else text(R.string.account, source.optString("uid"))))
            }
        }
        if (s.sourceCursor != null) item {
            TextButton(onClick = model::moreSources, enabled = !s.busy) { Text(text(R.string.more_accounts)) }
        }
        item { Text(text(R.string.selected_source_data), style = MaterialTheme.typography.titleMedium) }
        items(s.adminRecords) { row -> InfoCard(row.optJSONObject("payload") ?: row, row.optString("kind")) }
        if (s.recordCursor != null) item {
            TextButton(onClick = model::moreRecords, enabled = !s.busy) { Text(text(R.string.more_records)) }
        }
        item { HorizontalDivider() }
        item { Text(text(R.string.shared_planning), style = MaterialTheme.typography.titleMedium) }
        item { Row {
            TextButton(onClick = importPlanning, enabled = !s.busy) { Text(text(R.string.import_planning)) }
            TextButton(onClick = model::reconcile, enabled = !s.busy) { Text(text(R.string.update_sheet)) }
        } }
        items(s.planning?.objects("items") ?: emptyList()) { row ->
            InfoCard(row.optJSONObject("fields") ?: row, text(R.string.plan))
        }
        item { Text(text(R.string.planning_notifications), style = MaterialTheme.typography.titleMedium) }
        items(s.notifications) { row ->
            OutlinedButton(onClick = { model.notification(row.optString("event_id", row.optString("id"))) },
                enabled = !s.busy) { Text(text(R.string.symbol_reason_format, row.optString("symbol"), row.optString("reason"))) }
        }
        if (s.notificationCursor != null) item {
            TextButton(onClick = { model.more("notifications") }, enabled = !s.busy) { Text(text(R.string.more_notifications)) }
        }
    }
}


@Composable
private fun NotificationList(s: ScreenState, model: PlanningViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(if (s.admin) text(R.string.all_notifications) else text(R.string.my_notifications),
            style = MaterialTheme.typography.titleLarge) }
        item { Text(text(R.string.notifications_storage_hint),
            style = MaterialTheme.typography.bodySmall) }
        items(s.notifications, key = { it.optString("event_id", it.optString("id")) }) { row ->
            Card(onClick = { model.notification(row.optString("event_id", row.optString("id"))) },
                modifier = Modifier.fillMaxWidth(), enabled = !s.busy) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(NotificationContent.title(row), style = MaterialTheme.typography.titleMedium)
                    Text(NotificationContent.body(row), style = MaterialTheme.typography.bodyLarge)
                    Text(NotificationContent.status(row), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(NotificationContent.time(row.optString("created_at", row.optString("due_at"))),
                        style = MaterialTheme.typography.bodySmall)
                    Text(text(R.string.view_content), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (s.notifications.isEmpty()) item { Text(text(R.string.no_notifications_yet)) }
        if (s.notificationCursor != null) item {
            TextButton(onClick = { model.more("notifications") }, enabled = !s.busy) { Text(text(R.string.more_notifications)) }
        }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
            Text(text(R.string.source, source), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DnseAccount(s: ScreenState, model: PlanningViewModel) {
    Text(text(R.string.last_sync, NotificationContent.time(s.lastSync)))
    Button(onClick = model::sync, enabled = s.approved && s.hasDnse && !s.busy,
        modifier = Modifier.fillMaxWidth()) { Text(text(R.string.sync_data_from_dnse)) }
    if (s.dnse?.objects("accounts").isNullOrEmpty()) Text(text(R.string.dnse_account_empty))
    s.dnse?.objects("accounts")?.forEach { account ->
        val id = account.optString("account")
        AccountBlock(text(R.string.personal_information, id), text(R.string.get_accounts)) {
            val profile = account.optJSONObject("profile") ?: JSONObject()
            val fields = listOf("name" to text(R.string.full_name), "fullName" to text(R.string.full_name), "email" to text(R.string.email),
                "phone" to text(R.string.phone), "phoneNumber" to text(R.string.phone), "accountName" to text(R.string.account_name),
                "accountType" to text(R.string.account_type), "status" to text(R.string.status))
            val present = fields.filter { profile.optString(it.first).let { value -> value.isNotBlank() && value != "null" } }
            Text(text(R.string.dnse_account_2, id))
            present.forEach { (key, label) -> Text(text(R.string.label_value, label, profile.optString(key))) }
            if (present.isEmpty()) Text(text(R.string.dnse_profile_not_provided))
        }
        AccountBlock(text(R.string.cash_and_buying_power), text(R.string.get_accounts_balances, id)) {
            val balance = s.dnse.objects("balances").firstOrNull { it.optString("account") == id }
            Text(text(R.string.available_cash, OrderContent.money(balance?.let { OrderContent.number(it, "cash_vnd") })))
            Text(text(R.string.buying_power, OrderContent.money(balance?.let { OrderContent.number(it, "buying_power_vnd") })))
        }
        AccountBlock(text(R.string.stock_portfolio), text(R.string.get_accounts_positions_markettype_stock, id)) {
            val holdings = s.dnse.objects("positions").filter { it.optString("account") == id }
            if (holdings.isEmpty()) Text(text(R.string.no_stock_positions_in_the_synced_data))
            holdings.forEach { position ->
                Text(position.optString("symbol"), style = MaterialTheme.typography.titleSmall)
                Text(text(R.string.quantity_available_to_sell, OrderContent.quantity(position, "quantity"), OrderContent.quantity(position, "available_quantity")))
                Text(text(R.string.average_cost, OrderContent.money(OrderContent.number(position, "average_price_vnd"))))
            }
        }
    }
}

@Composable
private fun Orders(s: ScreenState, model: PlanningViewModel) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val response = if (section == 0) s.upcomingPlanning else s.planningHistory
    val rows = response?.objects("items").orEmpty()
    Column {
        SecondaryTabRow(selectedTabIndex = section) {
            listOf(text(R.string.pending), text(R.string.history)).forEachIndexed { i, label ->
                Tab(selected = section == i, onClick = { section = i }, text = { Text(label) })
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (section == 0) text(R.string.pending_orders_explanation)
                    else text(R.string.order_history_explanation), modifier = Modifier.padding(vertical = 12.dp))
                TextButton(onClick = model::refresh, enabled = s.approved && !s.busy) { Text(text(R.string.refresh_planning_orders)) }
            }
            items(rows) { row ->
                val fields = row.optJSONObject("fields") ?: row
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fields.optString("Mã", text(R.string.plan)), style = MaterialTheme.typography.titleMedium)
                    row.optString("scheduled_date").takeIf { it.isNotBlank() }?.let {
                        Text(text(R.string.planned_date, it), color = MaterialTheme.colorScheme.primary)
                    }
                    JsonFields(fields)
                } }
            }
            if (!s.busy && response == null) item { Text(text(if (s.approved && !s.admin)
                R.string.this_feature_requires_admin_access else R.string.planning_orders_unavailable)) }
            else if (!s.busy && rows.isEmpty()) item { Text(text(R.string.no_orders_or_plans_in_this_group_yet)) }
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
