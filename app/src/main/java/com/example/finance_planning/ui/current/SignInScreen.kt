package com.example.finance_planning.ui.current

import com.example.finance_planning.R
import com.example.finance_planning.ui.*
import androidx.compose.ui.res.stringResource as text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.*
import com.example.finance_planning.ui.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.finance_planning.ui.ScreenState

@Composable
internal fun SignInScreen(s: ScreenState, checkServer: () -> Unit, signIn: (android.content.Context) -> Unit) {
    val context = LocalContext.current
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            Card(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(text(R.string.app_heading), style = MaterialTheme.typography.headlineSmall)
                    Text(text(R.string.google_account), style = MaterialTheme.typography.titleMedium)
                    FeedbackMessage(s.message)
                    if (!s.configured) Text(text(R.string.sign_in_configuration_required), color = MaterialTheme.colorScheme.error)
                    if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = { signIn(context) }, enabled = s.configured && !s.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text(R.string.sign_in_with_google))
                    }
                    if (com.example.finance_planning.core.LocalBackend.active) {
                        HelpDisclosure(text(R.string.current_ui_technical)) { LocalPlanningConnectionInfo() }
                        OutlinedButton(onClick = checkServer, enabled = !s.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text(R.string.check_server))
                        }
                    }
                }
            }
        }
    }
}
