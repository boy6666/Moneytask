package com.moneytask.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneytask.ledger.AppContainer
import kotlin.math.roundToInt
import com.moneytask.ledger.ui.charts.AreaLineChart
import com.moneytask.ledger.ui.charts.DonutChart
import com.moneytask.ledger.ui.charts.GroupedBarChart

/** 报表分类的固定配色盘（供环形图与图例对齐）。 */
private val ChartPalette = listOf(
    Color(0xFF00897B), Color(0xFF5C6BC0), Color(0xFFEF6C00), Color(0xFFD81B60),
    Color(0xFF7CB342), Color(0xFF3949AB), Color(0xFF8E24AA), Color(0xFFF4511E),
)
private val OtherColor = Color(0xFFB0BEC5)

/** 图例行：色点 + emoji + 名称 + 占比 + 金额。 */
private data class LegendRow(val color: Color, val icon: String, val name: String,
                             val percent: Float, val total: Long)

/**
 * 报表页（M4 收尾 + 前端增强）：自然月收支总览、支出分类环形占比、
 * 近 7 天收支柱状、近 30 天累计结余走势、账户分布。全部图表为 Compose Canvas 自绘（离线、无第三方库）。
 */
@Composable
internal fun StatsScreen(c: AppContainer) {
    val month = currentMonthLabel()
    // 数据修订信号：任何落账/删除/复核都会令 recentTransactions 重新发射。
    // 用它作聚合 remember 的 key，使停留在本页时新账目也能即时刷新（而非永久快照）。
    val revision = c.pipeline.recentTransactions
        .collectAsStateWithLifecycle(initialValue = emptyList()).value

    val range = remember(c) { c.monthRange() }
    val byType = remember(range, revision) { c.sumByType(range.first, range.second) }
    val byCategory = remember(range, revision) { c.expenseByCategory(range.first, range.second) }
    val byAccount = remember(range, revision) { c.sumByAccount(range.first, range.second) }
    val cats = remember(range) { c.categoriesById() }
    val accs = remember(range) { c.accountsById() }

    // 只需一次「近30天」查询：近7天是其连续子窗口（takeLast(7) 的 MM/dd 标签一致），
    // 避免 dailySeries(7) 再跑一次重叠的全表 GROUP BY。
    val monthSeries = remember(range, revision) { c.dailySeries(30) }
    val weekSeries = remember(range, revision) { monthSeries.takeLast(7) }
    val hasWeek = remember(weekSeries) { weekSeries.any { it.second > 0 || it.third > 0 } }
    val hasMonth = remember(monthSeries) { monthSeries.any { it.second > 0 || it.third > 0 } }
    val balancePoints = remember(monthSeries) {
        val arr = ArrayList<Long>(monthSeries.size)
        var run = 0L
        monthSeries.forEach { run += it.third - it.second; arr.add(run) }
        // 结余按「元」绘制，与文案「单位元」一致（分值 /100），避免 100 倍错位。
        arr.map { it / 100L }
    }

    var expense = 0L
    var income = 0L
    byType.forEach {
        when (it.key) {
            "EXPENSE" -> expense = it.total
            "INCOME" -> income = it.total
        }
    }
    val balance = income - expense

    // 分类 → 配色与图例（仅占比 top7，其余并入「其他」）
    val donutSegments = remember(byCategory) {
        byCategory.take(7).mapIndexed { i, r ->
            r.total.toFloat() to ChartPalette[i % ChartPalette.size]
        } + if (byCategory.size > 7) {
            listOf(byCategory.drop(7).sumOf { it.total }.toFloat() to OtherColor)
        } else emptyList()
    }
    val legendRows = remember(byCategory, cats) {
        val total = byCategory.sumOf { it.total }.coerceAtLeast(1L)
        val rows = byCategory.take(7).mapIndexed { i, r ->
            val d = cats[r.key] ?: ("🧾" to r.key.ifEmpty { "未分类" })
            LegendRow(ChartPalette[i % ChartPalette.size], d.first, d.second,
                r.total.toFloat() / total, r.total)
        }.toMutableList()
        if (byCategory.size > 7) {
            val rest = byCategory.drop(7).sumOf { it.total }
            rows += LegendRow(OtherColor, "🗂", "其他", rest.toFloat() / total, rest)
        }
        rows
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { OverviewCard(month, balance, expense, income) }

        item { Section("支出分类", "$month 消费结构") }
        if (byCategory.isEmpty()) {
            item { EmptyState("📊", "本月暂无支出", "记几笔后这里会显示消费分类结构") }
        } else {
            item {
                DonutCard(segments = donutSegments, centerValue = "¥${formatYuan(expense)}")
            }
            items(legendRows.size) { i ->
                LegendRowCard(legendRows[i])
            }
        }

        item { Section("近7天收支", "柱状对比") }
        if (hasWeek) {
            item { BarCard(weekSeries) }
        } else {
            item { EmptyState("📅", "近7天暂无账目", "支出与收入会以红绿双色柱展示") }
        }

        item { Section("近30天结余走势", "累计净收支") }
        if (hasMonth) {
            item { LineCard(balancePoints) }
        } else {
            item { EmptyState("📈", "近30天暂无账目", "每天的收入减支出累计，形成结余曲线") }
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

/** 白卡片包裹一个小节内容（标题放 Section，这里放图表/列表体）。 */
@Composable
private fun ChartCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { Column(Modifier.fillMaxWidth().padding(16.dp), content = content) }
}

/** 环形占比：中央显示支出总额。 */
@Composable
private fun DonutCard(segments: List<Pair<Float, Color>>, centerValue: String) {
    ChartCard {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            DonutChart(segments, Modifier.fillMaxSize().padding(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("本月支出", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
                Text(centerValue, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    color = MoneyRed, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LegendRowCard(row: LegendRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(row.color, CircleShape))
            Spacer(Modifier.size(10.dp))
            Text(row.icon, fontSize = 16.sp)
            Spacer(Modifier.size(6.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text("${(row.percent.coerceIn(0f, 1f) * 100).roundToInt()}%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
            Text("¥${formatYuan(row.total)}",
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 近7天收支柱状图 + 首中末日期轴标签。 */
@Composable
private fun BarCard(series: List<Triple<String, Long, Long>>) {
    val values = series.map { it.second to it.third }
    ChartCard {
        GroupedBarChart(
            values = values,
            modifier = Modifier.fillMaxWidth().height(170.dp),
            expenseColor = MoneyRed,
            incomeColor = MoneyGreen,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(series.first().first, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(series[series.size / 2].first, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(series.last().first, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DotLegend(MoneyRed, "支出")
            DotLegend(MoneyGreen, "收入")
        }
    }
}

/** 近30天累计结余走势（面积折线）。 */
@Composable
private fun LineCard(points: List<Long>) {
    ChartCard {
        AreaLineChart(
            points = points,
            modifier = Modifier.fillMaxWidth().height(170.dp),
            lineColor = Brand,
            fillTop = Brand,
            fillBottom = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Text("结余 = 累计收入 − 累计支出（单位元）", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DotLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.size(5.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
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
