package com.moneytask.ledger.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneytask.ledger.AppContainer
import com.moneytask.ledger.MoneytaskApplication
import com.moneytask.ledger.R
import com.moneytask.ledger.capture.Transaction
import com.moneytask.ledger.capture.TxnType
import com.moneytask.ledger.service.CaptureForegroundService
import com.moneytask.ledger.service.PaymentNotificationListenerService
import kotlinx.coroutines.flow.first

/** 记账 App 专属配色：偏青绿的"金流"主色，替代默认紫。 */
private val MoneytaskColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandSoft,
    onPrimaryContainer = BrandDeep,
    surface = Color(0xFFF6F8F8),
    surfaceVariant = Color(0xFFECF0F0),
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = MoneytaskColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }

    private fun container() = (application as MoneytaskApplication).container

    // ------------------------------------------------------------------ 首启引导 / 主壳（底部导航）

    @Composable
    private fun Root() {
        val ctx = LocalContext.current
        val prefs = remember { ctx.getSharedPreferences("moneytask_prefs", Context.MODE_PRIVATE) }
        var showOnboarding by remember { mutableStateOf(!prefs.getBoolean("onboarded", false)) }

        if (showOnboarding) {
            OnboardingScreen(onDone = {
                prefs.edit().putBoolean("onboarded", true).apply()
                showOnboarding = false
            })
        } else {
            val c = (ctx.applicationContext as MoneytaskApplication).container
            AppShell(c, onReplayOnboarding = { showOnboarding = true })
        }
    }

    @Composable
    private fun AppShell(c: AppContainer, onReplayOnboarding: () -> Unit) {
        var tab by remember { mutableStateOf(0) }
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.List, null) }, label = { Text("流水") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.DateRange, null) }, label = { Text("报表") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("我的") })
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> HomeTab(c)
                    1 -> StatsScreen(c)
                    2 -> MeScreen(c, onReplayOnboarding)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 主页

    @Composable
    private fun HomeTab(c: AppContainer) {
        val txns by c.pipeline.recentTransactions.collectAsStateWithLifecycle(initialValue = emptyList())
        val pending by c.pendingReview.collectAsStateWithLifecycle(initialValue = emptyList())
        var listenerGranted by remember { mutableStateOf(isListenerEnabled()) }
        var showPending by remember { mutableStateOf(false) }
        var showAdd by remember { mutableStateOf(false) }
        var pendingDelete by remember { mutableStateOf<Transaction?>(null) }
        // 首帧加载标记：recentTransactions 首次发射前为 false，避免「空态」闪一下。
        var txnsLoaded by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            c.pipeline.recentTransactions.first()
            txnsLoaded = true
        }
        val onResumeGranted = remember {
            {
                listenerGranted = isListenerEnabled()
                if (listenerGranted) CaptureForegroundService.start(this@MainActivity)
            }
        }
        // 用生命周期观察器在每次 RESUME 都重查通知监听授权并拉起前台服务。
        // 修 bug：旧的 LaunchedEffect(Unit) 只在首次装配执行一次、无 onResume 钩子，
        // 用户从系统设置授权返回后 listenerGranted 仍是旧值 → 提示卡残留且服务不启动。
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, onResumeGranted) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) onResumeGranted()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onResumeGranted() // ON_RESUME 首次也会触发，等价替换原 LaunchedEffect(Unit)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // 分类图标映射（给交易行配一个彩色圆底 emoji 头像）。
        val iconOf = remember {
            buildMap<String, String> {
                (container().categories(TxnType.EXPENSE) + container().categories(TxnType.INCOME))
                    .forEach { put(it.id, it.icon) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 顶部摘要
                item { SummaryHeader(c) }
                item { ActionRow(pending.size, onAdd = { showAdd = true }, onTogglePending = { showPending = !showPending }, showPending = showPending) }

                if (showPending) {
                    item { SectionTitle(stringResource(R.string.pending_review)) }
                    if (pending.isEmpty()) {
                        item { EmptyState(emoji = "🎊", title = stringResource(R.string.pending_empty), sub = "没有等待确认的账目") }
                    } else {
                        items(pending, key = { it.id }) { t ->
                            PendingRow(t, onRequestDelete = { pendingDelete = it })
                        }
                    }
                } else {
                    item { SectionTitle(stringResource(R.string.recent_title)) }
                    when {
                        !txnsLoaded -> item { LoadingState() }
                        txns.isEmpty() -> item { EmptyState(emoji = "🧾", title = stringResource(R.string.empty_state), sub = "点右下角“记一笔”，或授予通知使用权后自动记账") }
                        else -> items(txns, key = { it.id }) { t -> TransactionRow(t, iconOf) }
                    }
                }

                item { Spacer(Modifier.height(4.dp)) }
                // 已授予通知使用权后不再显示该提示卡；仅未授权时提示去开启。
                if (!listenerGranted) {
                    item { ListenerCard(onOpenSettings = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) }
                }
                item { SimulateCard {
                    c.simulateMeituanPurchase()
                    // 归并无感、入账需等结算窗口，点击即给即时确认，避免用户以为没生效。
                    Toast.makeText(this@MainActivity, "已模拟，稍后自动入账", Toast.LENGTH_SHORT).show()
                } }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }

        if (showAdd) {
            ManualEntryDialog(container = c, onDismiss = { showAdd = false })
        }

        // 删除待复核为破坏性操作且无撤销，先确认再执行。
        pendingDelete?.let { del ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除这笔待复核账目？") },
                text = { Text("将删除\"${del.merchant ?: "未识别商户"}\" ¥${formatYuan(del.amountFen)}，删除后不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteClick(del.id)
                        pendingDelete = null
                    }) { Text("删除", color = MoneyRed) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }

    /** 品牌 + 资金总览。 */
    @Composable
    private fun SummaryHeader(c: AppContainer) {
        val txns by c.pipeline.recentTransactions.collectAsStateWithLifecycle(initialValue = emptyList())
        var expense = 0L
        var income = 0L
        txns.forEach { if (it.type == TxnType.EXPENSE) expense += it.amountFen else income += it.amountFen }
        val balance = income - expense

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(Brand, BrandDeep)),
                    RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("🧾 Moneytask", color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.local_badge), color = Color(0xFFB2DFDB)) },
                    colors = AssistChipDefaultsTransparent(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("最近 ${txns.size} 笔记账", color = Color(0xFFB2DFDB),
                style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                (if (balance < 0) "−" else "") + "¥ " + formatYuan(kotlin.math.abs(balance)),
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SummaryStat("支出", expense, Color(0xFFFF8A80))
                SummaryStat("收入", income, Color.White)
            }
        }
    }

    @Composable
    private fun SummaryStat(label: String, fen: Long, valueColor: Color) {
        Column {
            Text(label, color = Color(0xFFB2DFDB), style = MaterialTheme.typography.labelMedium)
            Text("¥ ${formatYuan(fen)}", color = valueColor,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }

    /** 记一笔 / 待复核 操作行。 */
    @Composable
    private fun ActionRow(pendingCount: Int, onAdd: () -> Unit, onTogglePending: () -> Unit, showPending: Boolean) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onAdd,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_entry), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onTogglePending,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Warning, null,
                    tint = if (pendingCount > 0) MoneyRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (showPending) stringResource(R.string.view_recent)
                    else "${stringResource(R.string.pending_review)} · $pendingCount",
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    @Composable
    private fun SectionTitle(title: String) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("自动分类 · 绝不重复记账", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
    }

    /** 带分类图标与方向色金额的交易行。 */
    @Composable
    private fun TransactionRow(t: Transaction, iconOf: Map<String, String>) {
        val isExpense = t.type == TxnType.EXPENSE
        val icon = iconOf[t.categoryId] ?: if (isExpense) "💸" else "💰"
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(if (isExpense) Color(0xFFFFF3E0) else Color(0xFFE8F5E9), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(icon, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.merchant ?: "（未识别商户）",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1)
                    Text(
                        buildString {
                            append(relativeTime(t.time))
                            append("  ·  ")
                            append(t.paymentMethod ?: "未知方式")
                            if (t.isPendingReview) append("  ·  待复核")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    "${if (isExpense) "−" else "+"} ¥ ${formatYuan(t.amountFen)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isExpense) MoneyRed else MoneyGreen,
                )
            }
        }
    }

    /** 待复核卡：黄色警示底，明显 确认/删除。 */
    @Composable
    private fun PendingRow(t: Transaction, onRequestDelete: (Transaction) -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDF5)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(Color(0xFFFFF3CD), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Warning, null, tint = Color(0xFFB26A00), modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.merchant ?: "（未识别商户）",
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("账户/方向信息不足，需要您确认",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("¥ ${formatYuan(t.amountFen)}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MoneyRed)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onConfirmClick(t.id) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoneyGreen)) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.confirm))
                    }
                    OutlinedButton(onClick = { onRequestDelete(t) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MoneyRed)) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.delete))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(t.time.let { relativeTime(it) } + " 入账",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }

    private fun onConfirmClick(id: String) {
        container().confirmReview(id)
        Toast.makeText(this@MainActivity, "已确认 ✓", Toast.LENGTH_SHORT).show()
    }

    private fun onDeleteClick(id: String) {
        container().deleteTransaction(id)
        Toast.makeText(this@MainActivity, "已删除", Toast.LENGTH_SHORT).show()
    }

    /** 首帧加载过渡：数据流尚未完成首次发射时显示转菊花。 */
    @Composable
    private fun LoadingState() {
        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(10.dp))
            Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }

    /** 精致空状态。 */
    @Composable
    private fun EmptyState(emoji: String, title: String, sub: String) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, fontSize = 44.sp)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }

    /** 通知权限提示卡：仅在未授予通知使用权时渲染（已授权后主页不再出现）。 */
    @Composable
    private fun ListenerCard(onOpenSettings: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F9FF)),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(Color(0xFFE1F0FF), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Notifications, null, tint = BrandDeep, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("开启自动记账", fontWeight = FontWeight.SemiBold)
                        Text("需要通知使用权，微信/支付宝付款后自动入账",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)) {
                    Text("去授予通知使用权", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    /** 模拟演示卡。 */
    @Composable
    private fun SimulateCard(onSimulate: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🧪 无真实支付时，可生成一条美团消费模拟全链路（含绝不重复记账）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onSimulate) { Text("模拟一笔", color = Brand, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    // ------------------------------------------------------------------ 补录弹窗

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
        val context = LocalContext.current

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📝", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_entry))
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == TxnType.EXPENSE,
                            onClick = { type = TxnType.EXPENSE },
                            label = { Text("支出 💸") })
                        FilterChip(selected = type == TxnType.INCOME,
                            onClick = { type = TxnType.INCOME },
                            label = { Text("收入 💰") })
                    }
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it },
                        label = { Text(stringResource(R.string.amount_label)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
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
                        items = categories.map { it.icon + " " + it.name },
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
                    // 仅接受正金额：拒绝 null/0/负数（负数会让报表聚合与图表占比失真）。
                    if (fen == null || fen <= 0L) {
                        error = this@MainActivity.getString(R.string.invalid_amount)
                        return@Button
                    }
                    // try/catch 兜住落库异常：给出可读错误而非崩溃；成功后弹「已保存」反馈，
                    // 避免用户点保存后窗口关闭却不知道系统是否记上了。
                    try {
                        container.manualAdd(
                            amountFen = fen,
                            type = type,
                            merchant = merchant.ifBlank { null },
                            accountId = accounts.getOrNull(accountIdx)?.id,
                            categoryId = categories.getOrNull(catIdx)?.id,
                        )
                        Toast.makeText(context, "已保存 ✓", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } catch (e: Exception) {
                        error = "保存失败，请重试"
                    }
                }, shape = RoundedCornerShape(10.dp)) { Text(stringResource(R.string.save)) }
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
            OutlinedTextField(
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

    private fun isListenerEnabled(): Boolean {
        val flat = ComponentName(this, PaymentNotificationListenerService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(":").any { it == flat }
    }

    /** 准透明的 AssistChip 配色（顶部品牌右侧"纯本地"标签）。 */
    @Composable
    private fun AssistChipDefaultsTransparent() = androidx.compose.material3.AssistChipDefaults.assistChipColors(
        containerColor = Color.Transparent,
        labelColor = Color(0xFFB2DFDB),
        disabledContainerColor = Color.Transparent,
        disabledLabelColor = Color(0xFFB2DFDB),
        disabledLeadingIconContentColor = Color(0xFFB2DFDB),
        disabledTrailingIconContentColor = Color(0xFFB2DFDB),
    )
}
