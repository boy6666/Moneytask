package com.moneytask.ledger.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 环形占比图（Donut）：segments 为 (占比, 颜色) 列表，按序从 12 点方向顺时针绘制。
 * 占比按各自数值归一化（无需外部先算百分比），多段时自动留细缝。
 */
@Composable
fun DonutChart(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 30.dp,
) {
    val strokePx = with(LocalDensity.current) { strokeWidth.toPx() }
    Canvas(modifier = modifier) {
        if (segments.isEmpty()) return@Canvas
        val inset = strokePx / 2
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        val topLeft = Offset(inset, inset)
        val total = segments.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        val hasGap = segments.size > 1
        var start = -90f
        segments.forEach { (fraction, color) ->
            if (fraction <= 0f) return@forEach
            val raw = (fraction / total) * 360f
            val sweep = if (hasGap) (raw - 2.4f).coerceAtLeast(0.5f) else raw
            drawArc(
                color = color,
                startAngle = start + (if (hasGap) 1.2f else 0f),
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Butt),
            )
            start += raw
        }
    }
}
