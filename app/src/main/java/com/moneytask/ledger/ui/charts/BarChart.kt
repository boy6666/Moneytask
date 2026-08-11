package com.moneytask.ledger.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 收支成对柱状图：values 为 [(支出, 收入), ...] 序列，每组两柱（左支出/右收入）。
 * 按全部数据中的最大值归一化；背景网格线随数据量自动分档。
 */
@Composable
fun GroupedBarChart(
    values: List<Pair<Long, Long>>,
    modifier: Modifier = Modifier,
    expenseColor: Color,
    incomeColor: Color,
    gridColor: Color = Color(0x14000000),
    barCorner: Dp = 4.dp,
) {
    val cornerPx = with(LocalDensity.current) { barCorner.toPx() }
    Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas
        val maxVal = maxOf(values.maxOfOrNull { maxOf(it.first, it.second) } ?: 1L, 1L)
        val n = values.size
        val slot = size.width / n
        val barW = (slot * 0.30f).coerceAtMost(30f)
        val half = barW / 2

        // 基准线
        drawLine(gridColor, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)

        values.forEachIndexed { i, (exp, inc) ->
            val cx = slot * i + slot / 2
            val expH = size.height * (exp.toFloat() / maxVal)
            val incH = size.height * (inc.toFloat() / maxVal)
            // 双柱都加 >0 守卫：负数（历史脏数据/边界）会画出负高度矩形，此处静默跳过。
            if (expH > 0f) drawRoundRect(
                color = expenseColor,
                topLeft = Offset(cx - barW - half * 0.18f, size.height - expH),
                size = Size(barW, expH),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Fill,
            )
            if (incH > 0f) drawRoundRect(
                color = incomeColor,
                topLeft = Offset(cx + half * 0.18f, size.height - incH),
                size = Size(barW, incH),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Fill,
            )
        }

        // 顶部刻度虚线（正整数轴）
        drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), 1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
    }
}
