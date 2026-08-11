package com.moneytask.ledger.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 折线/面积图：points 为数值序列，自动归一化到 [min,max] 纵向铺满。
 * 面积渐变填充（fillTop→fillBottom），首尾打点；数据穿零时绘制零轴虚线。
 */
@Composable
fun AreaLineChart(
    points: List<Long>,
    modifier: Modifier = Modifier,
    lineColor: Color,
    fillTop: Color,
    fillBottom: Color,
    thickness: Dp = 3.dp,
    zeroColor: Color = Color(0x40000000),
) {
    val tPx = with(LocalDensity.current) { thickness.toPx() }
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val min = points.minOrNull() ?: 0L
        val max = points.maxOrNull() ?: 0L
        val flat = min == max // 全相等（如结余恒为 0）时在纵向居中画一条水平线，而非贴底细线
        val span = (max - min).toFloat().coerceAtLeast(1f)
        val px: (Int) -> Float = { i ->
            if (points.size == 1) size.width / 2f
            else size.width * i / (points.size - 1).toFloat()
        }
        val py: (Long) -> Float = { v ->
            if (flat) size.height / 2f
            else size.height - ((v - min).toFloat() / span) * size.height
        }

        // 穿零轴虚线
        if (min < 0 && max > 0) {
            val zy = py(0L)
            drawLine(zeroColor, Offset(0f, zy), Offset(size.width, zy), 1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }

        // 折线
        val line = Path()
        points.forEachIndexed { i, v ->
            val x = px(i); val y = py(v)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        // 面积：向「最小值基线」（图表底部）闭合
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, brush = Brush.verticalGradient(listOf(fillTop, fillBottom)))
        drawPath(line, color = lineColor, style = Stroke(tPx, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // 数据点（点数较多时不打点，避免噪点）
        if (points.size <= 31) {
            points.forEachIndexed { i, v ->
                drawCircle(lineColor, radius = tPx * 1.5f, center = Offset(px(i), py(v)))
            }
        }
    }
}
