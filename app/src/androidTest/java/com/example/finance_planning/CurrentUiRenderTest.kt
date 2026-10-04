package com.example.finance_planning

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finance_planning.ui.*
import com.example.finance_planning.ui.current.*
import com.example.finance_planning.ui.layout.*
import com.example.finance_planning.ui.theme.Finance_planningTheme
import com.example.finance_planning.network.TradeDraft
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Bounded current-screen renders on the device. Synthetic data + inert callbacks only.
 * Never launches MainActivity, instantiates a repository, removes FLAG_SECURE, or calls a provider.
 * These are component screenshots, not authenticated app or tablet acceptance.
 */
class CurrentUiRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var page by mutableIntStateOf(0)
    private var actions = 0
    private val settings = SettingsActions({}, {}, {}, {}, {}, { _, _, _, _ -> }, {}, {}, {}, {}, {})
    private val admin = AdminActions({}, {}, {}, {}, {})
    private val plan = JSONObject().put("intent_id", "synthetic-plan")
        .put("record_kind", "LEGACY").put("time_status", "EXACT")
        .put("scheduled_at", "2030-10-04T09:00:00+07:00")
        .put("legacy_status", "PLANNED").put("symbol", "FPT")
        .put("fields", JSONObject().put("Mã", "FPT").put("Mua/Bán", "MUA")
            .put("Số lượng", "100").put("Giá LO (VND)", "90000")
            .put("Ghi chú", "Synthetic component fixture"))
    private val notification = JSONObject().put("event_id", "synthetic-notification")
        .put("title", "FPT · Cập nhật kế hoạch").put("body", "Nội dung minh hoạ để kiểm tra bố cục. Không đặt lệnh và không gửi thông báo.")
        .put("created_at", "2026-10-04T09:00:00Z").put("_opened", false).put("local_only", true)
    private fun state() = ScreenState(signedIn = true, configured = true, approved = true, admin = true,
        email = "demo@example.invalid", message = "", planningSavedAt = "2026-10-04T09:00:00Z",
        planning = JSONObject().put("items", JSONArray().put(plan)), notifications = listOf(notification),
        notificationCursor = "synthetic-next", dnseProduction = false)

    @Test fun renderCurrentScreensAndKeepCallbacksInert() {
        val label = InstrumentationRegistry.getArguments().getString("render_label", "phone")!!
        require(Regex("[a-z0-9_-]+").matches(label))
        val configuration = compose.activity.resources.configuration
        if ("portrait" in label || "landscape" in label) {
            assertEquals("Actual orientation must match the evidence label",
                if ("portrait" in label) android.content.res.Configuration.ORIENTATION_PORTRAIT
                else android.content.res.Configuration.ORIENTATION_LANDSCAPE, configuration.orientation)
            assertEquals(if ("large" in label) 1.5f else 1.0f, configuration.fontScale, 0.05f)
            assertEquals(if ("dark" in label) android.content.res.Configuration.UI_MODE_NIGHT_YES
                else android.content.res.Configuration.UI_MODE_NIGHT_NO,
                configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        }
        val folder = File(compose.activity.getExternalFilesDir(null), "current-ui-renders").apply { mkdirs() }
        compose.setContent {
            Finance_planningTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val adaptive = AdaptiveLayout(maxWidth >= 600.dp, maxWidth >= 900.dp && maxWidth > maxHeight)
                    CompositionLocalProvider(LocalAdaptiveLayout provides adaptive) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize().padding(16.dp)) {
                                Text("SYNTHETIC UI FIXTURE", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    when (page) {
                                        0 -> SignInScreen(ScreenState(configured = true), {}, { actions++ })
                                        1 -> OrdersContent(state(), "synthetic-owner", { actions++ }) { _, _, _ -> error("No trade UI may open") }
                                        2 -> NotificationList(state(), { actions++ }, { actions++ })
                                        3 -> SettingsContent(state(), settings, { actions++ }) { error("No sandbox dialog may open") }
                                        4 -> AdminDashboardContent(state(), "synthetic-owner", admin, { actions++ })
                                        5 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                                            TradeReviewContent("DEMO", TradeDraft("FPT", "NB", 100, 90000, 1), "Demo • no loan")
                                        }
                                        6 -> OrdersContent(state().copy(planning = null, message = ""), "synthetic-owner", {}) { _, _, _ -> }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val names = listOf("signin", "orders", "notifications", "settings", "admin", "review", "orders-empty")
        names.forEachIndexed { index, name ->
            compose.runOnIdle { page = index }
            compose.waitForIdle()
            if (index == 0) compose.onNodeWithText(compose.activity.getString(R.string.sign_in_with_google))
                .assertIsEnabled().assertHeightIsAtLeast(48.dp)
            save(folder, "$label-$name")
            if (index == 1) {
                compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(1)
                compose.onNodeWithText("FPT").assertExists()
                compose.onNodeWithText(compose.activity.getString(R.string.trade_quantity)).assertExists()
                save(folder, "$label-order-card")
            }
            if (index == 3) {
                compose.onNodeWithText(compose.activity.getString(R.string.sign_out_and_delete_local_data))
                    .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                save(folder, "$label-settings-tools")
            }
        }
        compose.runOnIdle { assertEquals(0, actions) }
    }

    private fun save(folder: File, name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
