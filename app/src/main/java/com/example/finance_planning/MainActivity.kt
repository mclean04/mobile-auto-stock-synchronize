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
import com.example.finance_planning.core.PlanningTimeline
import com.example.finance_planning.core.PlanningSection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.Instant
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.finance_planning.ui.current.*
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
    if (!s.signedIn) {
        SignInScreen(s, model::health) { model.signIn(it) }
        return // Dispose authenticated tabs, dialogs, saved scroll positions and their BackHandler.
    }
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
        val tablet = maxWidth >= 600.dp
        val landscape = tablet && maxWidth >= 900.dp && maxWidth > maxHeight
        CompositionLocalProvider(LocalAdaptiveLayout provides AdaptiveLayout(tablet, landscape)) {
            Scaffold(bottomBar = {
                if (!showNotifications && !tablet) Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 0.dp,
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
                    if (!showNotifications && tablet) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
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
                        Column(Modifier.widthIn(max = if (landscape) 1200.dp else 840.dp)
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
                            FeedbackMessage(s.message)
                            if (showNotifications) NotificationList(s, model::notification) { model.more("notifications") } else when (tab) {
                                0 -> Orders(s, model.repo, model::refreshPlanning)
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
private fun AdminPanel(s: ScreenState, model: PlanningViewModel, importPlanning: () -> Unit) {
    com.example.finance_planning.ui.AdminDashboard(s, model, importPlanning)
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
