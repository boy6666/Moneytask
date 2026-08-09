package com.moneytask.ledger.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneytask.ledger.MoneytaskApplication
import com.moneytask.ledger.R
import com.moneytask.ledger.capture.Transaction
import com.moneytask.ledger.service.PaymentNotificationListenerService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }

    private fun container() = (application as MoneytaskApplication).container

    @Composable
    private fun HomeScreen() {
        val txns by container().pipeline.recentTransactions.collectAsStateWithLifecycle(initialValue = emptyList())
        var listenerGranted by remember { mutableStateOf(isListenerEnabled()) }
        val onResumeGranted = remember { { listenerGranted = isListenerEnabled() } }
        androidx.compose.runtime.LaunchedEffect(Unit) { onResumeGranted() }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // 本地徽标
            AssistChip(local = true)

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.recent_title), style = MaterialTheme.typography.titleLarge)

            Spacer(Modifier.height(8.dp))
            if (txns.isEmpty()) {
                Text(stringResource(R.string.empty_state), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(txns, key = { it.id }) { t -> TransactionRow(t) }
                }
            }

            Spacer(Modifier.weight(1f))

            // 通知使用权
            if (!listenerGranted) {
                Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.grant_access))
                }
            } else {
                Text(stringResource(R.string.listener_granted), color = Color(0xFF2E7D32))
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = { container().simulateMeituanPurchase() },
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.simulate_btn))
            }
        }
    }

    @Composable
    private fun AssistChip(local: Boolean) {
        Text(
            stringResource(R.string.local_badge),
            color = Color(0xFF2E7D32),
            style = MaterialTheme.typography.labelMedium,
        )
    }

    @Composable
    private fun TransactionRow(t: Transaction) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(t.merchant ?: "（未识别商户）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        buildString {
                            append(t.paymentMethod ?: "未知方式")
                            if (t.isPendingReview) append(" · 待复核")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${if (t.type.name == "EXPENSE") "-" else "+"} ¥ ${formatFen(t.amountFen)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (t.type.name == "EXPENSE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    private fun formatFen(fen: Long): String = "%.2f".format(fen / 100.0)

    private fun isListenerEnabled(): Boolean {
        // 读 Settings.Secure.ENABLED_NOTIFICATION_LISTENERS（冒号分隔的组件扁平串）。
        // 不依赖 NotificationManager（新版已移除 getEnabledNotificationListeners()）。
        val flat = ComponentName(this, PaymentNotificationListenerService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(":").any { it == flat }
    }
}
