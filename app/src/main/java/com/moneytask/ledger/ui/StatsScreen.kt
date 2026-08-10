package com.moneytask.ledger.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneytask.ledger.AppContainer

/**
 * 报表页（M1 基础报表 + M4 收尾）：自然月收支汇总、按分类支出占比、按账户分布。
 */
@Composable
internal fun StatsScreen(c: AppContainer) {
    val (start, end) = remember { c.monthRange() }
    val month = currentMonthLabel()
    val byType = remember { c.sumByType(start, end) }
    val byCategory = remember { c.expenseByCategory(start, end) }
    val byAccount = remember { c.sumByAccount(start, end) }
    val cats = remember { c.categoriesById() }
    val accs = remember { c.accountsById() }

    var expense = 0L
    var income = 0L
    byType.forEach {
        when (it.key) {
            "EXPENSE" -> expense = it.total
            "INCOME" -> income = it.total
        }
    }
    val balance = income - expense

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { OverviewCard(month, balance, expense, income) }

        item { Section("支出分类占比", "按金额降序") }
        if (byCategory.isEmpty()) {
            item { EmptyState("📊", "本月暂无支出", "记几笔后这里会显示分类结构") }
        } else {
            val totalExpense = byCategory.sumOf { it.total }.coerceAtLeast(1L)
            items(byCategory.size) { i ->
                val row = byCategory[i]
                val percent = row.total.toFloat() / totalExpense
                val d = cats[row.key] ?: ("🧾" to row.key.ifEmpty { "未分类" })
                CategoryBar(d.first, d.second, percent, row.total)
            }
        }

        item { Section("账户分布", "按账户汇总") }
        if (byAccount.isEmpty()) {
            item { EmptyState("💳", "暂无账户流水", "入账后会按账户统计") }
        } else {
            items(byAccount.size) { i ->
                val row = byAccount[i]
                val name = accs[row.key] ?: row.key.ifEmpty { "未指定账户" }
                AccountRow(name, row.total)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun OverviewCard(month: String, balance: Long, expense: Long, income: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BrandDeep),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("$month 收支总览", color = Color(0xFFB2DFDB),
                style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    (if (balance < 0) "−" else "") + "¥ " + formatYuan(kotlin.math.abs(balance)),
                    color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(8.dp))
                Text("结余", color = Color(0xFFB2DFDB),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("支出", expense, Color(0xFFFF8A80))
                Stat("收入", income, Color(0xFF80CBC4))
            }
        }
    }
}

@Composable
private fun Stat(label: String, fen: Long, color: Color) {
    Column {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
        Text("¥ ${formatYuan(fen)}", color = Color.White,
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Section(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryBar(icon: String, name: String, percent: Float, total: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(Color(0xFFE0F2F1), CircleShape),
                    contentAlignment = Alignment.Center) { Text(icon, fontSize = 18.sp) }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Text("${(percent * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                }
                Text("¥ ${formatYuan(total)}", fontWeight = FontWeight.Bold, color = MoneyRed)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { percent.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Brand,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountRow(name: String, total: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFCFC)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("💰", fontSize = 18.sp)
            Spacer(Modifier.size(10.dp))
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("¥ ${formatYuan(total)}", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyState(emoji: String, title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 38.sp)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}
