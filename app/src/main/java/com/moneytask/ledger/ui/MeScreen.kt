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
import kotlinx.coroutines.launch

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
    var importUri by remember { mutableStateOf<Uri?>(null) }

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
                    onDelete = { c.deleteAccount(acc.id); refresh++ }) }
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
                            Button(onClick = {
                                scope.launch { snackbar.showSnackbar(doExport(context, c)) }
                            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("导出备份")
                            }
                            OutlinedButton(onClick = {
                                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                Text("恢复备份")
                            }
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
    importUri?.let { uri ->
        ImportConfirmDialog(c, uri,
            onConfirm = {
                scope.launch { snackbar.showSnackbar(doImport(context, c, uri)) }
                refresh++
                importUri = null
            },
            onDismiss = { importUri = null })
    }
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

private fun doExport(context: Context, c: AppContainer): String {
    val count = c.transactionCount()
    val file = runCatching { c.exportBackup() }.getOrNull() ?: return "导出失败，请重试"
    val uri = c.backupShareUri(file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "导出 Moneytask 备份（$count 笔记账）"))
    return "已生成 $count 笔记账的备份"
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
