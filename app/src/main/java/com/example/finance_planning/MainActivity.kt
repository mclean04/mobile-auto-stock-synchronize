package com.example.finance_planning

import android.Manifest
import android.content.Intent
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
import com.example.finance_planning.core.objects
import com.example.finance_planning.ui.PlanningViewModel
import com.example.finance_planning.ui.ScreenState
import com.example.finance_planning.ui.theme.Finance_planningTheme
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val model: PlanningViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { Finance_planningTheme { PlanningScreen(model) } }
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
        if (event != null && model.repo.approved()) model.notification(event)
        intent.removeExtra("event_id")
    }
}

@Composable
private fun PlanningScreen(model: PlanningViewModel) {
    val s by model.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf("") }
    val labels = listOf("Tổng quan", "Lệnh của tôi", "Cài đặt")
    Scaffold(bottomBar = {
        NavigationBar { labels.forEachIndexed { index, title ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                icon = { Text(listOf("◉", "↔", "⚙")[index]) }, label = { Text(title) })
        }}
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text("DNSE • Planning", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(s.message, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 10.dp))
            when (tab) {
                0 -> Overview(s, model) { confirm = it }
                1 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = model::refresh, enabled = s.approved && !s.busy) { Text("Lịch sử trên backend") } }
                    items(s.orders) { row ->
                        val p = row.optJSONObject("payload") ?: row
                        Card(onClick = { model.order(row) }, enabled = !s.busy) {
                            Column(Modifier.padding(16.dp)) {
                                Text(p.optString("symbol"), style = MaterialTheme.typography.titleMedium)
                                Text("${p.optString("side")} • ${p.optString("filled_quantity")}/${p.optString("quantity")} • ${p.optString("status")}")
                                Text("Order ID: ${p.optString("order_id")}")
                            }
                        }
                    }
                    if (s.orderCursor != null) item { TextButton(onClick = { model.more("orders") }, enabled = !s.busy) { Text("Tải thêm") } }
                }
                2 -> Settings(s, model) { confirm = it }
            }
        }
    }
    if (confirm.isNotEmpty()) AlertDialog(onDismissRequest = { confirm = "" },
        title = { Text(if (confirm == "logout") "Đăng xuất và xóa dữ liệu trên máy?" else "Nhập lại planning vào database?") },
        text = { Text(if (confirm == "logout") "Hàng đợi chưa gửi và khóa DNSE trên máy sẽ bị xóa."
            else "Backend sẽ đọc file planning hiện tại và lưu một phiên bản. Thao tác này không đặt lệnh.") },
        confirmButton = { TextButton(onClick = { if (confirm == "logout") model.logout() else model.importPlanning(); confirm = "" }) { Text("Xác nhận") } },
        dismissButton = { TextButton(onClick = { confirm = "" }) { Text("Quay lại") } })
    s.detail?.let { detail ->
        AlertDialog(onDismissRequest = model::dismissDetail,
            title = { Text(s.detailTitle) },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("App không đặt hoặc hủy lệnh. Luôn kiểm tra trạng thái thực tế trên DNSE.")
                JsonFields(detail)
            }},
            confirmButton = { TextButton(onClick = model::dismissDetail) { Text("Đóng") } })
    }
}
@Composable
private fun Overview(s: ScreenState, model: PlanningViewModel, confirm: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(if (s.approved) "Đã được backend cấp quyền" else "Chưa kết nối tài khoản",
                    style = MaterialTheme.typography.titleMedium)
                Text("Đồng bộ gần nhất: ${s.lastSync}")
                Text("Ghi Sheet: " + if (s.status?.optBoolean("sheet_writes") == true) "Đã bật" else "Chưa bật / chưa xác minh")
                Text("Đợt chờ ghi Sheet: ${s.status?.optInt("pending_sheet_batches") ?: 0}")
            }}
        }
        item { Button(onClick = model::sync, enabled = s.approved && s.hasDnse && !s.busy,
            modifier = Modifier.fillMaxWidth()) { Text("Lấy DNSE và đồng bộ ngay") } }
        item { OutlinedButton(onClick = model::retry, enabled = s.approved && !s.busy,
            modifier = Modifier.fillMaxWidth()) { Text("Gửi lại dữ liệu đang chờ") } }
        item { Text("Bạn chỉ xem dữ liệu do tài khoản này gửi. Backend tự cập nhật file planning đã cấu hình.") }
        item { Text("Hàng đợi trên máy", style = MaterialTheme.typography.titleMedium) }
        items(s.localQueue) { Text(it) }
        item { Text("Các đợt đã gửi lên backend", style = MaterialTheme.typography.titleMedium) }
        items(s.batches) { row ->
            OutlinedButton(onClick = { model.batch(row.getString("id")) }, enabled = !s.busy) {
                Text(row.getString("id").take(8) + " • " + row.optString("received_at"))
            }
        }
        if (s.batchCursor != null) item { TextButton(onClick = { model.more("batches") }, enabled = !s.busy) { Text("Tải thêm") } }
    }
}
@Composable
private fun Settings(s: ScreenState, model: PlanningViewModel, confirm: (String) -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var production by remember { mutableStateOf(false) }
    var unit by remember { mutableStateOf("1") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tài khoản Google", style = MaterialTheme.typography.titleLarge)
        if (!s.configured) Text("Cần cấu hình Firebase và cấp quyền mobile trên backend trước khi đăng nhập.")
        Button(onClick = { model.signIn(context) }, enabled = s.configured && !s.signedIn && !s.busy) { Text("Đăng nhập Google") }
        Row {
            TextButton(onClick = model::health, enabled = !s.busy) { Text("Kiểm tra máy chủ") }
            TextButton(onClick = model::verify, enabled = s.signedIn && !s.busy) { Text("Kiểm tra quyền") }
        }
        HorizontalDivider()
        Text("DNSE chỉ đọc", style = MaterialTheme.typography.titleLarge)
        Text("Khóa chỉ lưu mã hóa trên máy. Dữ liệu sandbox không được gửi vào planning thật.")
        OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it }, label = { Text("API Secret") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row { Checkbox(production, { production = it }); Text("Tôi dùng tài khoản Production (chỉ đọc)") }
        Text("Đơn vị giá API — phải khớp dữ liệu DNSE trước khi gửi:")
        Row {
            FilterChip(selected = unit == "1000", onClick = { unit = "1000" }, label = { Text("Nghìn VND") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = unit == "1", onClick = { unit = "1" }, label = { Text("VND") })
        }
        Button(onClick = { model.saveDnse(key, secret, production, unit); key = ""; secret = "" },
            enabled = s.signedIn && key.isNotBlank() && secret.isNotBlank() && !s.busy) { Text("Lưu khóa trên thiết bị") }
        Text(if (s.hasDnse) "Đã có khóa lưu trên máy." else "Chưa có khóa DNSE.")
        HorizontalDivider()
        Text("Đồng bộ định kỳ", style = MaterialTheme.typography.titleLarge)
        Text("Mỗi 6 giờ khi có mạng. Android có thể trì hoãn; buộc dừng app sẽ ngăn lịch chạy tới khi mở lại.")
        Row {
            TextButton(onClick = { model.schedule(true) }, enabled = s.approved && !s.busy) { Text("Bật lịch") }
            TextButton(onClick = { model.schedule(false) }, enabled = !s.busy) { Text("Tắt lịch") }
        }
        TextButton(onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) { Text("Cho phép thông báo") }
        OutlinedButton(onClick = { confirm("logout") }, enabled = s.signedIn && !s.busy) { Text("Đăng xuất và xóa dữ liệu local") }
        Spacer(Modifier.height(24.dp))
    }
}
private fun actionLabel(action: String) = when(action) {
    "PLACE_ORDER" -> "Nhắc xem xét lệnh mua/bán"
    "CANCEL_ORDER" -> "Nhắc xem xét hủy lệnh"
    "WITHDRAW_PLAN" -> "Kế hoạch đã được rút lại"
    else -> "Cập nhật planning"
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
        else if (value is JSONArray) Text("$key: ${value.length()} bản ghi")
        else if (value != null && value != JSONObject.NULL && value.toString().isNotBlank())
            Text("$key: $value", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
    }
}
