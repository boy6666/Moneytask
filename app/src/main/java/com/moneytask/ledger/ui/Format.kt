package com.moneytask.ledger.ui

import androidx.compose.ui.graphics.Color
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- 主题色（供各页面统一使用） ----

internal val Brand = Color(0xFF00897B)
internal val BrandDeep = Color(0xFF00695C)
internal val BrandSoft = Color(0xFFE0F2F1)
internal val MoneyRed = Color(0xFFE53935)
internal val MoneyGreen = Color(0xFF2E7D32)
internal val CardBg = Color(0xFFFFFFFF)

// ---- 金额 / 时间格式化 ----

/** "42.10" 或 "¥ 42.10" 输入 → 分（Long）。解析失败返回 null。 */
internal fun parseYuanToFen(s: String): Long? = runCatching {
    (BigDecimal(s.trim().removePrefix("¥")) * BigDecimal(100)).longValueExact()
}.getOrNull()

/** 分 → 带千分位的元字符串（如 4210 → "42.10"）。 */
internal fun formatYuan(fen: Long): String {
    val nf = NumberFormat.getNumberInstance(Locale.US)
    nf.minimumFractionDigits = 2
    nf.maximumFractionDigits = 2
    return nf.format(BigDecimal(fen).movePointLeft(2))
}

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

/** 相对时间："今天 08:30" / "昨天 22:10" / "8月2日 09:00"。 */
internal fun relativeTime(millis: Long): String {
    val zdt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val hhmm = timeFmt.format(zdt)
    return when (zdt.toLocalDate()) {
        today -> "今天 $hhmm"
        today.minusDays(1) -> "昨天 $hhmm"
        else -> dayFmt.format(zdt) + " $hhmm"
    }
}

/** 当前自然月标签，如 "8月"。 */
internal fun currentMonthLabel(): String =
    LocalDate.now().format(DateTimeFormatter.ofPattern("M月"))

/** "yyyy-MM-dd" → 本地 0 点毫秒；解析失败返回 null。 */
internal fun parseDateToMillis(s: String): Long? = runCatching {
    LocalDate.parse(s.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

/** 毫秒 → "yyyy-MM-dd"（本地时区）。 */
internal fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

/** 时段类型代号 → 中文标签（寒/暑/学期/自定义）。 */
internal fun periodTypeLabel(type: String): String = when (type) {
    "WINTER" -> "寒假"; "SUMMER" -> "暑假"; "TERM" -> "学期"; else -> "自定义"
}
