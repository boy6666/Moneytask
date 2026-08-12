package com.moneytask.ledger.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneytask.ledger.AppContainer
import com.moneytask.ledger.capture.BillPlatform
import com.moneytask.ledger.capture.ParsedBill
import com.moneytask.ledger.db.AccountEntity
import com.moneytask.ledger.db.PeriodEntity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 账户类型可选项（写库用 type 常量）。 */
private val ACCOUNT_TYPES = listOf(
    "CASH" to "现金", "BANK" to "银行卡", "WECHAT" to "微信",
    "ALIPAY" to "支付宝", "OTHER" to "其他",
)

private fun typeLabel(type: String): String = ACCOUNT_TYPES.firstOrNull { it.first == type }?.second ?: type

/**
 * 「我的」页（M4 收尾）：账户管理、备份导出/恢复、重看引导、关于与隐私说明。
 */
@Composable
internal fun MeScreen(c: AppContainer, onReplayOnboarding: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refresh by remember { mutableIntStateOf(0) }
    val accounts = remember(refresh) { c.accountsDb }
    val txnCount = remember { c.transactionCount() }

    var showAddAccount by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showPeriodDialog by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var deletingAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var deletingPeriod by remember { mutableStateOf<PeriodEntity?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }

    val budgetFen = remember(refresh) { c.monthlyBudgetFen() }
    val periods by c.periodsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // ---- 账单导入 ----
    var billParsing by remember { mutableStateOf(false) }
    var billImportBusy by remember { mutableStateOf(false) }
    var billPreview by remember { mutableStateOf<ParsedBill?>(null) }

    val billImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            billParsing = true
            scope.launch {
                billPreview = runCatching { withContext(Dispatchers.IO) { c.parseBillUri(uri) } }
                    .getOrElse { ParsedBill(BillPlatform.UNKNOWN, emptyList(), listOf(it.message ?: "解析失败")) }
                billParsing = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importUri = uri }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Section("📒 账户管理") }

            accounts.forEach { acc ->
                item { AccountCard(acc.name, acc.type, acc.isSystemDefault,
                    onSetDefault = { c.setDefaultAccount(acc.id); refresh++ },
                    onDelete = { deletingAccount = acc }) }
            }

            item {
                OutlinedButton(onClick = { showAddAccount = true }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新增账户", fontWeight = FontWeight.Medium)
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
            item { Section("💰 月度预算") }
            item {
                BudgetCard(budgetFen, onEdit = { showBudgetDialog = true })
            }

            item { Spacer(Modifier.height(4.dp)) }
            item { Section("🗓️ 时段设置（寒假/暑假/学期）") }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFCFC))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("把时间段标为寒假/暑假/学期，报表页可按时段分开统计、并与同类历史时段对比。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        periods.forEach { p ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${p.name} · ${periodTypeLabel(p.type)}",
                                        fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                    Text("${formatDate(p.startMillis)} ~ ${formatDate(p.endMillis - 1)}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { deletingPeriod = p }) {
                                    Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFB0BEC5), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        OutlinedButton(onClick = { showPeriodDialog = true }, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("新增时段")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
            item { Section("💾 数据备份（纯本地）") }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFCFC))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("全部数据保存于本机，绝不联网。可导出 JSON 备份到文件/网盘，需要时可整库恢复。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    if (exportBusy || importBusy) return@Button
                                    exportBusy = true
                                    scope.launch {
                                        // 生成备份文件在 IO；分享面板需主线程拉起。
                                        val (intent, msg) = withContext(Dispatchers.IO) { buildExport(context, c) }
                                        exportBusy = false
                                        intent?.let { context.startActivity(it) }
                                        snackbar.showSnackbar(msg)
                                    }
                                },
                                enabled = !exportBusy && !importBusy,
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                if (exportBusy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(if (exportBusy) "导出中…" else "导出备份")
                            }
                            OutlinedButton(
                                onClick = {
                                    importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                                },
                                enabled = !exportBusy && !importBusy,
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                Text(if (importBusy) "恢复中…" else "恢复备份")
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
            item { Section("📥 导入账单") }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFCFC))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("支持微信支付 / 支付宝 / 银行 App 导出的账单 CSV。去重后并入账本：已记过的自动覆盖补全、绝不重复记。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { billImportLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/*", "*/*")) },
                            enabled = !billParsing && !billImportBusy,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                            if (billParsing || billImportBusy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(if (billParsing) "解析中…" else if (billImportBusy) "导入中…" else "选择账单 CSV")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
            item { Section("ℹ️ 关于") }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFCFC))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Moneytask · 离线无感记账", fontWeight = FontWeight.SemiBold)
                        Text("已有 $txnCount 笔记账", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Notifications, null, tint = Brand, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("通知读取仅在本机解析，不上传任何数据", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onReplayOnboarding) {
                            Text("重新查看使用引导", color = Brand, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showAddAccount) {
        AddAccountDialog(c, onCreate = { ok, msg ->
            scope.launch { if (msg.isNotBlank()) snackbar.showSnackbar(msg) }
            refresh++
            showAddAccount = false
        })
    }
    if (showBudgetDialog) {
        AddBudgetDialog(current = budgetFen) { fen ->
            showBudgetDialog = false
            if (fen != null) {
                c.setMonthlyBudgetFen(fen)
                refresh++
                scope.launch {
                    snackbar.showSnackbar(
                        if (fen <= 0) "已清除月度预算"
                        else "月度预算设为 ¥${formatYuan(fen)}/月"
                    )
                }
            }
        }
    }
    if (showPeriodDialog) {
        AddPeriodDialog { name, type, start, end ->
            showPeriodDialog = false
            if (name == null) return@AddPeriodDialog
            val ok = c.addPeriod(name, type, start, end)
            refresh++
            scope.launch {
                snackbar.showSnackbar(if (ok) "已添加时段「$name」" else "时段信息不完整，请检查名称与起止日期")
            }
        }
    }
    deletingPeriod?.let { p ->
        AlertDialog(
            onDismissRequest = { deletingPeriod = null },
            title = { Text("删除时段「${p.name}」？") },
            text = { Text("只删除该时段标记，不会删除任何账目。") },
            confirmButton = {
                TextButton(onClick = {
                    c.deletePeriod(p.id)
                    deletingPeriod = null
                    refresh++
                }) { Text("删除", color = MoneyRed) }
            },
            dismissButton = { TextButton(onClick = { deletingPeriod = null }) { Text("取消") } },
        )
    }
    // 删除账户不可逆且其历史流水仍留在报表中（会显示为原始 ID），先确认再删。
    deletingAccount?.let { acc ->
        AlertDialog(
            onDismissRequest = { deletingAccount = null },
            title = { Text("删除账户「${acc.name}」？") },
            text = { Text("账户将被删除。该账户已有的流水不会被删除，只是报表中不再显示账户名。${if (acc.isSystemDefault) "\n\n此账户为系统默认账户，请谨慎操作。" else ""}") },
            confirmButton = {
                TextButton(onClick = {
                    c.deleteAccount(acc.id)
                    deletingAccount = null
                    scope.launch { snackbar.showSnackbar("已删除账户「${acc.name}」") }
                    refresh++
                }) { Text("删除", color = MoneyRed) }
            },
            dismissButton = {
                TextButton(onClick = { deletingAccount = null }) { Text("取消") }
            },
        )
    }
    importUri?.let { uri ->
        ImportConfirmDialog(c, uri,
            onConfirm = {
                importUri = null
                importBusy = true
                scope.launch {
                    // 整库重建在 IO 执行，期间按钮显示「恢复中…」并禁用，避免主线程冻结无反馈。
                    val msg = withContext(Dispatchers.IO) { doImport(context, c, uri) }
                    importBusy = false
                    snackbar.showSnackbar(msg)
                    refresh++
                }
            },
            onDismiss = { importUri = null })
    }

    billPreview?.let { preview ->
        val rowsOk = preview.rows.isNotEmpty()
        AlertDialog(
            onDismissRequest = { billPreview = null },
            title = { Text("导入账单确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("识别为「${platformLabel(preview.platform)}」账单", fontWeight = FontWeight.SemiBold)
                    Text("将导入 ${preview.rows.size} 笔：已存在（按金额+时间+商户匹配）的自动覆盖补全，绝不重复记。",
                        style = MaterialTheme.typography.bodyMedium)
                    if (preview.errors.isNotEmpty()) {
                        Text("另有 ${preview.errors.size} 行被跳过（金额/方向无法识别）：",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Text(preview.errors.take(3).joinToString("\n"), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall, maxLines = 5)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = preview
                        billPreview = null
                        billImportBusy = true
                        scope.launch {
                            val r = runCatching { withContext(Dispatchers.IO) { c.commitBillImport(p) } }
                            billImportBusy = false
                            snackbar.showSnackbar(r.fold(
                                onSuccess = { res ->
                                    "已导入 ${res.added.size} 笔新增、覆盖 ${res.updated.size} 笔"
                                },
                                onFailure = { "导入失败：${it.message}" })
                            )
                            refresh++
                        }
                    },
                    enabled = rowsOk && !billImportBusy,
                    shape = RoundedCornerShape(10.dp)) {
                    Text(if (billImportBusy) "导入中…" else "导入 ${preview.rows.size} 笔")
                }
            },
            dismissButton = { TextButton(onClick = { billPreview = null }) { Text("取消") } },
        )
    }
}

private fun platformLabel(p: BillPlatform): String = when (p) {
    BillPlatform.WECHAT -> "微信支付"
    BillPlatform.ALIPAY -> "支付宝"
    BillPlatform.BANK -> "银行流水"
    BillPlatform.UNKNOWN -> "未知"
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun AccountCard(name: String, type: String, isDefault: Boolean,
                        onSetDefault: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(Color(0xFFE0F2F1), CircleShape),
                contentAlignment = Alignment.Center) {
                Text(when (type) {
                    "BANK" -> "🏦"; "WECHAT", "ALIPAY" -> "📱"; else -> "💵"
                }, fontSize = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontWeight = FontWeight.Medium)
                    if (isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Text("默认", color = BrandDeep, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(typeLabel(type), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (!isDefault) {
                TextButton(onClick = onSetDefault) { Text("设默认", color = Brand) }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFB0BEC5), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** 在 IO 线程生成备份文件；返回 (要启动的分享 Intent, 结果文案)。 */
private fun buildExport(context: Context, c: AppContainer): Pair<Intent?, String> {
    val count = c.transactionCount()
    val file = runCatching { c.exportBackup() }.getOrNull()
        ?: return null to "导出失败，请重试"
    val uri = c.backupShareUri(file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(intent, "导出 Moneytask 备份（$count 笔记账）") to
        "已生成 $count 笔记账的备份"
}

private fun doImport(context: Context, c: AppContainer, uri: Uri): String = runCatching {
    "已恢复 ${c.importBackup(uri)} 笔记账"
}.getOrElse { "恢复失败：${it.message}" }

/** 新增账户弹窗：名称 + 类型选择。 */
@Composable
private fun AddAccountDialog(c: AppContainer, onCreate: (Boolean, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("CASH") }

    AlertDialog(
        onDismissRequest = { onCreate(false, "") },
        title = { Text("新增账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("账户名称，如 招商银行卡") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ACCOUNT_TYPES.take(3).forEach { (t, label) ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ACCOUNT_TYPES.drop(3).forEach { (t, label) ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val ok = c.addAccount(name, type)
                onCreate(ok, if (ok) "已添加账户" else "账户名称不能为空")
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { onCreate(false, "") }) { Text("取消") } },
    )
}

/** 恢复前确认 + 结果。 */
@Composable
private fun ImportConfirmDialog(c: AppContainer, uri: Uri, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val count = remember { c.transactionCount() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复备份？") },
        text = { Text("将清空当前 $count 笔记账及账户/分类，并替换为备份中的内容。建议先导出一次当前数据。") },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MoneyRed)) {
                Text("确认恢复")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---- 月度预算 / 时段设置 ----

/** 时段类型可选项（code → 中文标签）。相同 type 的时段在报表里归为一类对比。 */
private val PERIOD_TYPES = listOf(
    "WINTER" to "寒假", "SUMMER" to "暑假", "TERM" to "学期", "CUSTOM" to "自定义",
)

/** 「我的」页预算卡片：显示/编辑每月预算。 */
@Composable
private fun BudgetCard(budgetFen: Long?, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFCFC))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (budgetFen == null) {
                    Text("尚未设置每月花销预算", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("设置后报表页会显示本月剩余可花", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("每月预算", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium)
                    Text("¥ ${formatYuan(budgetFen)} /月", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = BrandDeep)
                }
            }
            TextButton(onClick = onEdit) {
                Text(if (budgetFen == null) "设置" else "修改", color = Brand, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 设置/修改月度预算弹窗。onDone(null)=取消；onDone(x)=设为 x 分（x<=0 表示清除）。 */
@Composable
private fun AddBudgetDialog(current: Long?, onDone: (Long?) -> Unit) {
    var text by remember { mutableStateOf(if (current != null) formatYuan(current).replace(",", "") else "") }
    val parsed = parseYuanToFen(text)
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(if (current == null) "设置每月预算" else "修改每月预算") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it },
                    label = { Text("每月预算（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("例如 1500 表示每月可花 1500 元；报表页会显示本月剩余。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(parsed) }, enabled = parsed != null) { Text("保存") }
        },
        dismissButton = {
            if (current != null) {
                TextButton(onClick = { onDone(0L) }) { Text("清除", color = MoneyRed) }
            }
            TextButton(onClick = { onDone(null) }) { Text("取消") }
        },
    )
}

/** 新增时段弹窗。onDone(null,..)=取消；否则为 (名称, 类型, 起始毫秒, 结束毫秒[次日0点,不含])。 */
@Composable
private fun AddPeriodDialog(onDone: (String?, String, Long, Long) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("SUMMER") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    val startMs = parseDateToMillis(start)
    val endMs = parseDateToMillis(end)
    val dayMs = 86_400_000L
    val valid = name.isNotBlank() && startMs != null && endMs != null && endMs >= startMs

    AlertDialog(
        onDismissRequest = { onDone(null, type, 0, 0) },
        title = { Text("新增时段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("名称，如 2026 暑假") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PERIOD_TYPES.forEach { (code, label) ->
                        FilterChip(selected = type == code, onClick = { type = code }, label = { Text(label) })
                    }
                }
                OutlinedTextField(value = start, onValueChange = { start = it },
                    label = { Text("开始日期 yyyy-MM-dd") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = end, onValueChange = { end = it },
                    label = { Text("结束日期 yyyy-MM-dd（含当天）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(name.trim(), type, startMs!!, endMs!! + dayMs)
            }, enabled = valid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { onDone(null, type, 0, 0) }) { Text("取消") } },
    )
}
