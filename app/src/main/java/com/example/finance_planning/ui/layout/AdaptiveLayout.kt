package com.example.finance_planning.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Uses the available app window, including split-screen, rather than the device model. */
data class AdaptiveLayout(val tablet: Boolean = false, val landscape: Boolean = false)
val LocalAdaptiveLayout = staticCompositionLocalOf { AdaptiveLayout() }

@Composable
fun SettingsPanes(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    if (LocalAdaptiveLayout.current.landscape) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SettingsPane(Modifier.weight(0.85f), left)
            SettingsPane(Modifier.weight(1.15f), right)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            left()
            right()
        }
    }
}

@Composable
private fun SettingsPane(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
        Spacer(Modifier.height(24.dp))
    }
}
