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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import com.moneytask.ledger.AppContainer
import com.moneytask.ledger.MoneytaskApplication
import com.moneytask.ledger.R
import com.moneytask.ledger.capture.Account
import com.moneytask.ledger.capture.Category
import com.moneytask.ledger.capture.Transaction
import com.moneytask.ledger.capture.TxnType
import com.moneytask.ledger.service.CaptureForegroundService
import com.moneytask.ledger.service.PaymentNotificationListenerService
import java.math.BigDecimal

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
        val c = container()
        val txns by c.pipeline.recentTransactions.collectAsStateWithLifecycle(initialValue = emptyList())
        val pending by c.pendingReview.collectAsStateWithLifecycle(initialValue = emptyList())
        var listenerGranted by remember { mutableStateOf(isListenerEnabled()) }
        var showPending by remember { mutableStateOf(false) }
        var showAdd by remember { mutableStateOf(false) }
        val onResumeGranted = remember {
            {
                listenerGranted = isListenerEnabled()
                // 已授予通知使用权 → 拉起前台保活服务（幂等启动）。
                if (listenerGranted) CaptureForegroundService.start(this@MainActivity)
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { onResumeGranted() }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            AssistChip(local = true)

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.recent_title), style = MaterialTheme.typography.titleLarge)

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAdd = true }) { Text(stringResource(R.string.add_entry)) }
                OutlinedButton(onClick = { showPending = !showPending }) {
                    Text(
                        if (showPending) stringResource(R.string.view_recent)
                        else "${stringResource(R.string.pending_review)} (${pending.size})"
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            if (showPending) {
                PendingList(pending, onConfirm = c::confirmReview, onDelete = c::deleteTransaction)
            } else if (txns.isEmpty()) {
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
            Button(onClick = { c.simulateMeituanPurchase() },
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.simulate_btn))
            }
        }

        if (showAdd) {
            ManualEntryDialog(container = c, onDismiss = { showAdd = false })
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
                    "${if (t.type == TxnType.EXPENSE) "-" else "+"} ¥ ${formatFen(t.amountFen)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (t.type == TxnType.EXPENSE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    @Composable
    private fun PendingList(pending: List<Transaction>, onConfirm: (String) -> Unit, onDelete: (String) -> Unit) {
        if (pending.isEmpty()) {
            Text(stringResource(R.string.pending_empty), style = MaterialTheme.typography.bodyMedium)
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pending, key = { it.id }) { t ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(t.merchant ?: "（未识别商户）", style = MaterialTheme.typography.bodyLarge)
                                Text("账户信息/方向不足 · ${t.paymentMethod ?: "未知方式"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("¥ ${formatFen(t.amountFen)}", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onConfirm(t.id) }) { Text(stringResource(R.string.confirm)) }
                            TextButton(onClick = { onDelete(t.id) }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }

    /** 手动补录弹窗：金额/方向/商户 + 账户/分类下拉。 */
    @Composable
    private fun ManualEntryDialog(container: AppContainer, onDismiss: () -> Unit) {
        var amount by remember { mutableStateOf("") }
        var merchant by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(TxnType.EXPENSE) }
        val accounts = remember { container.accounts }
        val categories = remember(type) { container.categories(type) }
        var accountIdx by remember { mutableStateOf(0) }
        var catIdx by remember { mutableStateOf(0) }
        var error by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.add_entry)) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 方向
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == TxnType.EXPENSE,
                            onClick = { type = TxnType.EXPENSE }, label = { Text("支出") })
                        FilterChip(selected = type == TxnType.INCOME,
                            onClick = { type = TxnType.INCOME }, label = { Text("收入") })
                    }
                    TextField(
                        value = amount, onValueChange = { amount = it },
                        label = { Text(stringResource(R.string.amount_label)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = merchant, onValueChange = { merchant = it },
                        label = { Text(stringResource(R.string.merchant_label)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    LabeledDropdown(
                        label = stringResource(R.string.account_label),
                        items = accounts.map { it.name },
                        selectedIndex = accountIdx,
                        onSelect = { accountIdx = it },
                    )
                    LabeledDropdown(
                        label = stringResource(R.string.category_label),
                        items = categories.map { it.name },
                        selectedIndex = catIdx,
                        onSelect = { catIdx = it },
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val fen = parseYuanToFen(amount)
                    if (fen == null || fen == 0L) {
                        error = this@MainActivity.getString(R.string.invalid_amount)
                        return@Button
                    }
                    container.manualAdd(
                        amountFen = fen,
                        type = type,
                        merchant = merchant.ifBlank { null },
                        accountId = accounts.getOrNull(accountIdx)?.id,
                        categoryId = categories.getOrNull(catIdx)?.id,
                    )
                    onDismiss()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    /** 通用下拉选择（账户/分类）。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LabeledDropdown(
        label: String,
        items: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }
        val selected = items.getOrNull(selectedIndex) ?: "（无）"
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEachIndexed { index, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(index); expanded = false })
                }
            }
        }
    }

    private fun parseYuanToFen(s: String): Long? = runCatching {
        (BigDecimal(s.trim().removePrefix("¥")) * BigDecimal(100)).longValueExact()
    }.getOrNull()

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
