package com.moneytask.ledger.capture

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 账单来源平台。 */
enum class BillPlatform { WECHAT, ALIPAY, BANK, UNKNOWN }

/**
 * 一条已解析的账单行（校验通过、可入账）。
 * 金额用**无符号正分** + [type] 区分方向，与 [Transaction] 一致。
 */
data class BillRow(
    val amountFen: Long,
    val type: TxnType,
    val time: Long,
    val merchant: String?,
    val platform: BillPlatform,
    val lineNo: Int,
)

/** 解析结果：有效行 + 被跳过/报错的行（可读原因）。 */
data class ParsedBill(
    val platform: BillPlatform,
    val rows: List<BillRow>,
    val errors: List<String>,
)

/**
 * 账单 CSV 解析器（纯 JVM、可无头单测）。
 *
 * 支持三种平台导出（按表头关键词识别，而非固定列位，对字段顺序变化健壮）：
 *  - 微信支付交易明细（收/支 + 金额(元) + 交易对方 + 交易时间）
 *  - 支付宝交易明细（收/支 + 金额（元）+ 交易对方/商品名称 + 付款时间/交易创建时间）
 *  - 银行流水（收入金额/支出金额 两列，或 单金额列 + 借贷/收支，交易日期/时间 + 对方户名/摘要）
 *
 * 金额单位「元」，统一换算成「分」；时间字符串按常见格式解析为 epoch 毫秒（本地时区）。
 * 方向不明确的行会被跳过并记入 errors——导入只收能明确收支的账目。
 */
object BillCsvParser {

    private val dateTimeFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
    )
    private val dateFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
    )

    /** 表头列映射（下标，-1 表示缺该列）。 */
    private data class Cols(
        val time: Int,
        val amount: Int,      // 单金额列（微信/支付宝，及部分银行）
        val income: Int,      // 银行「收入金额」列（-1 表示无）
        val expense: Int,     // 银行「支出金额」列（-1 表示无）
        val direction: Int,   // 「收/支」列（-1 表示无）
        val debitCredit: Int, // 银行「借/贷」列（-1 表示无）
        val merchant: Int,
    ) {
        /** 必备列缺失说明；为空表示可解析。 */
        val missing: List<String> get() = buildList {
            if (time < 0) add("时间")
            if (amount < 0 && income < 0 && expense < 0) add("金额")
            if (amount < 0 && income < 0 && expense < 0 && direction < 0 && debitCredit < 0) add("收支/方向")
        }
    }

    fun parse(text: String): ParsedBill {
        if (text.isBlank()) return ParsedBill(BillPlatform.UNKNOWN, emptyList(), listOf("文件为空"))
        val lines = splitCsv(text)
        if (lines.isEmpty()) return ParsedBill(BillPlatform.UNKNOWN, emptyList(), listOf("未解析到任何行"))

        val headerIdx = lines.indexOfFirst { it.isNotEmpty() }.takeIf { it >= 0 }
            ?: return ParsedBill(BillPlatform.UNKNOWN, emptyList(), listOf("没有表头"))
        val header = lines[headerIdx]
        val platform = detectPlatform(header)
        val cols = when (platform) {
            BillPlatform.WECHAT -> mapWechat(header)
            BillPlatform.ALIPAY -> mapAlipay(header)
            BillPlatform.BANK -> mapBank(header)
            BillPlatform.UNKNOWN -> null
        }
        if (cols == null) return ParsedBill(BillPlatform.UNKNOWN, emptyList(), listOf("无法识别该文件为微信/支付宝/银行账单"))

        val missing = cols.missing
        if (missing.isNotEmpty()) {
            return ParsedBill(platform, emptyList(), listOf("缺少必要列：${missing.joinToString("、")}"))
        }

        val rows = mutableListOf<BillRow>()
        val errors = mutableListOf<String>()
        for (i in (headerIdx + 1) until lines.size) {
            val cells = lines[i]
            if (cells.isEmpty()) continue
            try {
                parseRow(cells, cols, platform, i + 1)?.let { rows.add(it) }
            } catch (e: Exception) {
                errors.add("第${i + 1}行：${e.message}")
            }
        }
        return ParsedBill(platform, rows, errors)
    }

    // ---- 平台与列识别 ----

    /** 表头含 [key] 的任一关键词即 true。 */
    private fun hasHeader(header: List<String>, vararg keys: String): Boolean =
        header.any { h -> keys.any { h.contains(it) } }

    private fun detectPlatform(header: List<String>): BillPlatform = when {
        hasHeader(header, "交易单号") && hasHeader(header, "收/支") -> BillPlatform.WECHAT
        hasHeader(header, "交易号") && hasHeader(header, "收/支") && hasHeader(header, "金额") -> BillPlatform.ALIPAY
        (hasHeader(header, "交易日期") || hasHeader(header, "交易时间") || hasHeader(header, "对方户名")) &&
            (hasHeader(header, "金额") || hasHeader(header, "收入") || hasHeader(header, "支出")) -> BillPlatform.BANK
        else -> BillPlatform.UNKNOWN
    }

    /** 首个匹配任一关键词的表头列下标，否则 -1。 */
    private fun col(header: List<String>, vararg keys: String): Int =
        header.indexOfFirst { h -> keys.any { h.contains(it) } }

    /** 金额列：微信/支付宝的单「金额」列（排除退款/服务费/收入支出这些旁支列）。 */
    private fun amountCol(header: List<String>): Int =
        header.indexOfFirst { h ->
            h.contains("金额") && !h.contains("收入") && !h.contains("支出") &&
                !h.contains("退款") && !h.contains("服务费") && !h.contains("优惠")
        }

    private fun mapWechat(header: List<String>) = Cols(
        time = col(header, "交易时间"),
        amount = amountCol(header),
        income = -1, expense = -1,
        direction = col(header, "收/支"),
        debitCredit = -1,
        merchant = col(header, "交易对方", "商品"),
    )

    private fun mapAlipay(header: List<String>) = Cols(
        time = col(header, "付款时间", "交易创建时间", "交易创建"),
        amount = amountCol(header),
        income = -1, expense = -1,
        direction = col(header, "收/支"),
        debitCredit = -1,
        merchant = col(header, "交易对方", "商品名称", "商品"),
    )

    private fun mapBank(header: List<String>) = Cols(
        time = col(header, "交易时间", "交易日期", "记账日期", "日期"),
        amount = amountCol(header),
        income = col(header, "收入金额", "收入"),
        expense = col(header, "支出金额", "支出"),
        direction = col(header, "收/支", "收支", "交易类型"),
        debitCredit = col(header, "借/贷", "借贷"),
        merchant = col(header, "交易对方", "对方户名", "对方名称", "摘要"),
    )

    // ---- 行解析 ----

    private fun parseRow(cells: List<String>, cols: Cols, platform: BillPlatform, lineNo: Int): BillRow? {
        val time = parseTime(cells.getOrNull(cols.time)) ?: throw IllegalArgumentException("时间无法解析")

        // 金额 + 方向：优先银行两列金额；否则单金额列 + 方向/借贷。
        var amountFen: Long
        var type: TxnType
        if (cols.income >= 0 && cols.expense >= 0) {
            val ex = parseFen(cells.getOrNull(cols.expense))
            val inc = parseFen(cells.getOrNull(cols.income))
            when {
                ex != null -> { amountFen = ex; type = TxnType.EXPENSE }
                inc != null -> { amountFen = inc; type = TxnType.INCOME }
                else -> throw IllegalArgumentException("该行既无支出金额也无收入金额")
            }
        } else {
            val raw = cells.getOrNull(cols.amount)
            val sign = raw?.contains("-") ?: false
            amountFen = parseFen(raw) ?: throw IllegalArgumentException("金额无法解析")
            type = when {
                cols.direction >= 0 -> directionOf(cells.getOrNull(cols.direction))
                    ?: throw IllegalArgumentException("方向无法识别")
                cols.debitCredit >= 0 ->
                    if ((cells.getOrNull(cols.debitCredit) ?: "").contains("贷")) TxnType.INCOME else TxnType.EXPENSE
                sign -> TxnType.EXPENSE
                else -> throw IllegalArgumentException("方向无法识别")
            }
            if (sign) amountFen = -amountFen  // 负号转成正分
        }

        val merchant = cells.getOrNull(cols.merchant)?.trim()?.takeIf { it.isNotBlank() && it != "-" }
        return BillRow(amountFen, type, time, merchant, platform, lineNo)
    }

    /** 「收/支」/「交易类型」列值 → 方向；空或不明确返回 null。 */
    private fun directionOf(v: String?): TxnType? {
        val s = v?.trim() ?: return null
        if (s.contains("支出") || s.contains("消费") || s.contains("扣款") || s.contains("付款") || s == "借") return TxnType.EXPENSE
        if (s.contains("收入") || s.contains("转入") || s.contains("到账") || s.contains("退款") || s == "贷") return TxnType.INCOME
        return null
    }

    private fun parseFen(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val cleaned = s.replace("¥", "").replace("￥", "").replace(",", "").replace("-", "").trim()
        if (cleaned.isEmpty()) return null
        return runCatching {
            BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        }.getOrNull()
    }

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        for (f in dateTimeFormats) {
            runCatching {
                return LocalDateTime.parse(t, f).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        for (f in dateFormats) {
            runCatching {
                return LocalDate.parse(t, f).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        return null
    }

    // ---- CSV 解析（引号感知，处理 BOM / 空行 / 引号内换行） ----

    private fun splitCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        val str = text.removePrefix("﻿")
        while (i < str.length) {
            val ch = str[i]
            when {
                inQuotes -> when {
                    ch == '"' && i + 1 < str.length && str[i + 1] == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> inQuotes = false
                    else -> sb.append(ch)
                }
                ch == '"' -> inQuotes = true
                ch == ',' -> { fields.add(sb.toString()); sb.setLength(0) }
                ch == '\r' -> { /* 忽略 */ }
                ch == '\n' -> {
                    fields.add(sb.toString()); sb.setLength(0)
                    if (fields.any { it.isNotBlank() }) rows.add(fields.toList())
                    fields.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        // 收尾
        if (sb.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(sb.toString())
            if (fields.any { it.isNotBlank() }) rows.add(fields.toList())
        }
        return rows
    }
}
