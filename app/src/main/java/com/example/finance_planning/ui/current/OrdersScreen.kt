package com.example.finance_planning.ui.current

import com.example.finance_planning.R
import com.example.finance_planning.ui.*
import androidx.compose.ui.res.stringResource as text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import com.example.finance_planning.ui.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.finance_planning.core.NotificationContent
import com.example.finance_planning.core.PlanningTimeline
import com.example.finance_planning.core.PlanningSection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.Instant
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.finance_planning.ui.ScreenState
import org.json.JSONObject

@Composable
internal fun Orders(s: ScreenState, repo: com.example.finance_planning.data.PlanningRepository, refreshPlanning: () -> Unit) {
    OrdersContent(s, repo.identity.uid(), refreshPlanning) { plan, section, close ->
        com.example.finance_planning.ui.ManualTradeDialog(plan, repo, section, close)
    }
}

@Composable
internal fun OrdersContent(s: ScreenState, ownerUid: String?, refreshPlanning: () -> Unit,
    tradeDialog: @Composable (JSONObject, PlanningSection, () -> Unit) -> Unit) {
    var tradePlan by remember { mutableStateOf<JSONObject?>(null) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    val selected = PlanningSection.entries[section]
    val lifecycle = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(1000) }
        }
    }
    val snapshot = s.planning
    val zone = java.time.ZoneId.systemDefault()
    val rows = remember(snapshot, selected, now, zone) { PlanningTimeline.rows(snapshot, selected, now, zone) }
    val expandedCards = com.example.finance_planning.ui.rememberPlanningCardExpansion(
        ownerUid, snapshot?.optJSONObject("source_context"), selected)
    LaunchedEffect(snapshot, selected) { tradePlan = null }
    LaunchedEffect(now) {
        if (tradePlan?.let { !PlanningTimeline.mayOpenAction(selected, it, now) } == true) tradePlan = null
    }
    tradePlan?.takeIf { PlanningTimeline.mayOpenAction(selected, it, now) }?.let {
        tradeDialog(it, selected) { tradePlan = null }
    }
    Column {
        SecondaryTabRow(selectedTabIndex = section) {
            listOf(text(R.string.planning_all_tab), text(R.string.pending), text(R.string.history)).forEachIndexed { i, label ->
                Tab(selected = section == i, onClick = { tradePlan = null; section = i }, text = { Text(label) })
            }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(if (LocalAdaptiveLayout.current.tablet) 340.dp else 1000.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCard(text(R.string.orders)) {
                    s.planningSavedAt?.let { SyncNote(text(R.string.planning_cache_saved_at, NotificationContent.time(it))) }
                    FilledTonalButton(onClick = refreshPlanning, enabled = s.approved && !s.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.refresh_planning_orders)) }
                    SyncNote(text(R.string.planning_cache_display_note))
                    HelpDisclosure {
                        SyncNote(text(when(selected) {
                            PlanningSection.ALL -> R.string.planning_all_explanation
                            PlanningSection.UPCOMING -> R.string.pending_orders_explanation
                            PlanningSection.HISTORY -> R.string.order_history_explanation
                        }))
                        SyncNote(text(R.string.planning_cache_refresh_note))
                    }
                }
             } }
            items(rows, key = { com.example.finance_planning.ui.planningCardIdentity(it) }) { row ->
                val cardId = com.example.finance_planning.ui.planningCardIdentity(row)
                com.example.finance_planning.ui.PlanningOrderCard(row, s.dnse, selected == PlanningSection.UPCOMING,
                    s.approved && s.hasDnse && !s.busy, s.dnseProduction,
                    freshForAction = s.planningExecutionFresh && PlanningTimeline.mayOpenAction(selected, row, now),
                    expanded = expandedCards[cardId] == true,
                    toggleExpanded = { expandedCards[cardId] = expandedCards[cardId] != true }) {
                    if (PlanningTimeline.mayOpenAction(selected, row, Instant.now())) tradePlan = row
                }
            }
            if (!s.busy && snapshot == null) item(span = { GridItemSpan(maxLineSpan) }) { Text(text(R.string.planning_orders_unavailable)) }
            else if (!s.busy && rows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {  Text(text(R.string.no_orders_or_plans_in_this_group_yet))  } }
        }
    }
}

