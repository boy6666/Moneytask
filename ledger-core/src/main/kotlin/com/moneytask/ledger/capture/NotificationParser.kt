package com.moneytask.ledger.capture

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.min

/**
 * 通知文本解析器：把一条 [RawNotification] 解析成 [ParsedCapture]。
 *
 * 复用 RealtimeLedger(TransactionParser, MIT) 的金额多候选评分思路，并针对本产品差异点
 * 扩展：渠道分类、支付工具识别（微信/支付宝/卡）、卡尾号解析、方向判定（含退款/还款）。
 *
 * 金额歧义处理（一条通知里常有多个数字，如"支付金额42.10"+"可用额度￥58539.79"）：
 * 每个金额候选按「紧邻前缀关键词」打分——高权关键词（实付/支付金额/消费/扣款）加分，
 * 低权关键词（优惠/余额/可用/额度/剩余）扣分，取最高分者即真实消费金额。
 */
class NotificationParser(
    private val classifier: ChannelClassifier = ChannelClassifier(),
    private val bankNames: List<String> = ChannelClassifier.defaultBankNames,
) {
    // ---- 金额候选与语义 ----
    private val amountRegex = Regex("(?<![\\d:])(?:人民币\\s*)?[¥￥]?\\s*(\\d{1,3}(?:,\\d{3})+|\\d{1,8})(?:\\.(\\d{1,2}))?\\s*(?:元)?(?!\\d)")
    private val expense = listOf("支付成功", "付款成功", "消费成功", "扣款成功", "已支付", "支付", "支出", "付款", "消费", "扣款", "交易成功", "已扣款", "已成功付款")
    private val income = listOf("到账", "收款成功", "已收款", "收入", "转入", "入账", "退款")
    private val refund = listOf("退款成功", "退款到账", "已退款", "退回", "原路退回")
    private val repayment = listOf("还款提醒", "还款金额", "账单已还清", "还款结果")

    private val high = listOf("实付", "实际支付", "支付金额", "付款金额", "订单金额", "交易金额", "消费成功", "已成功付款", "消费", "扣款", "支出")
    private val low = listOf("优惠", "立减", "红包", "余额", "积分", "原价", "累计", "剩余", "额度", "可用", "退款前", "银行卡余额")
    private val marketing = listOf("优惠券", "最高", "立享", "活动", "抢购", "领券")
    private val success = listOf("成功", "已支付", "已成功付款", "到账", "已扣款", "已退款", "退回", "已还清")

    private val merchantPatterns = listOf(
        // 需带冒号，避免把正文中裸露的"商户"字样误当关键词（如"美团平台商户有一笔…"）
        Regex("(?:商户|收款方|付款给|交易对象|订单商户)\\s*[:：]\\s*([^\\n,，¥￥]{2,40})"),
        Regex("(?:向|在)([^\\n,，¥￥]{2,30}?)(?:支付|付款|消费)"),
    )
    private val bankTailPatterns = listOf(
        Regex("(?:尾号|末四位|末4位)\\s*(\\d{4})"),
        Regex("\\((\\d{4})\\)"),
    )
    private val payToolPatterns = listOf(
        Regex("(?:使用|通过)\\s*([^，。\\s(（]{2,16}?(?:卡|钱包))"),
    )

    fun parse(raw: RawNotification): ParsedCapture {
        val text = (raw.title + "\n" + raw.text).trim()
        if (text.isEmpty()) return unmatched("空通知")

        if (Regex("(?i)\\b(?:USD|EUR|JPY|HKD|美元|欧元|日元|港币)\\b").containsMatchIn(text))
            return unmatched("非人民币交易")

        val hasTxKeyword = (expense + income + refund + repayment).any(text::contains)
        if (marketing.any(text::contains) && !success.any(text::contains)) return unmatched("营销通知")

        val direction = detectDirection(text)
        val candidates = amountRegex.findAll(text).mapNotNull { m ->
            val value = m.groupValues[1].replace(",", "") +
                m.groupValues[2].takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
            val fen = runCatching {
                BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact()
            }.getOrNull() ?: return@mapNotNull null
            if (fen !in 1L..9_999_999_999L || isLikelyIdentifier(text, m.range)) return@mapNotNull null
            val prefix = text.substring((m.range.first - 12).coerceAtLeast(0), m.range.first)
            var score: Int = 0
            for (kw in high) {
                if (prefix.contains(kw)) {
                    score += when (kw) {
                        "实付", "实际支付", "支付金额", "付款金额", "订单金额" -> 50
                        "消费成功", "已成功付款", "交易金额", "消费", "扣款", "支出" -> 20
                        else -> 10
                    }
                }
            }
            score -= low.count(prefix::contains) * 20
            if (m.value.any { it == '¥' || it == '￥' || it == '元' }) score += 3
            AmountCandidate(fen, score, prefix.trim())
        }.toList()

        val amount = candidates.maxByOrNull { it.score }
        val merchant = detectMerchant(text)?.takeUnless { it in listOf("微信支付", "支付宝", raw.sourceAppName) }
        val bankTail = detectBankTail(text)
        val bankName = detectBankName(raw)
        val payTool = detectPayTool(text) ?: detectWallet(payToolText(text))
        val channel = classifier.classify(raw.sourceAppName, raw.title, raw.text)

        // 置信度评分（参照 RealtimeLedger，MVP 阈值 0.55）
        var confidence = 0.0
        val evidence = mutableListOf<String>()
        if (raw.sourcePackage.isNotBlank() && raw.sourceAppName.isNotBlank()) { confidence += 0.15; evidence += "来源应用" }
        if (hasTxKeyword) { confidence += 0.25; evidence += "交易关键词" }
        if (amount != null) { confidence += 0.30; evidence += "金额:$amount" }
        if (merchant != null) { confidence += 0.15; evidence += "商户:$merchant" }
        if (direction != Direction.UNKNOWN) { confidence += 0.10; evidence += "方向:$direction" }
        if (success.any(text::contains)) { confidence += 0.05; evidence += "完成状态" }
        confidence = confidence.coerceIn(0.0, 1.0)

        val matched = hasTxKeyword && amount != null && confidence >= 0.55
        return ParsedCapture(
            matched = matched,
            amountFen = amount?.fen,
            direction = direction,
            merchant = merchant,
            bankTail = bankTail,
            paymentTool = payTool,
            channel = channel,
            bankName = bankName,
            confidence = confidence,
            evidence = evidence,
        )
    }

    // ---- 识别辅助 ----

    private fun detectDirection(text: String): Direction = when {
        refund.any(text::contains) -> Direction.INCOME   // 退款到账 -> 记收入
        income.any(text::contains) -> Direction.INCOME
        repayment.any(text::contains) -> Direction.EXPENSE  // 信用卡还款：钱从账户流出，MVP 先按支出，可由用户重分类
        expense.any(text::contains) -> Direction.EXPENSE
        else -> Direction.UNKNOWN
    }

    private fun detectMerchant(text: String): String? {
        val raw = merchantPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            ?.trim()?.replace(Regex("[。；;]$"), "") ?: return null
        // 归一化：剥掉渠道前缀（财付通- / 支付宝-），并截掉"有一笔…"之类的事由尾巴
        val clean = raw
            .replace(Regex("^(财付通|支付宝|微信支付)[-—]?"), "")
            .replace(Regex("(有一笔|一笔).*$"), "")
            .takeIf { !it.matches(Regex("[\\d. ]+")) && it.length in 2..40 }
        return clean
    }

    private fun detectBankTail(text: String): String? =
        bankTailPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }

    /**
     * 识别具体银行名（如"招商银行"）。来源名优先，其次标题+正文；多个命中取**最长**者，
     * 避免"中国银行/农业银行"被单字子串误判。渠道判定仍走 [ChannelClassifier]，这里只负责把名字捞出来。
     */
    private fun detectBankName(raw: RawNotification): String? {
        val text = raw.sourceAppName + " | " + raw.title + " | " + raw.text
        return bankNames.filter { text.contains(it) }.maxByOrNull { it.length }
    }

    /** 支付方式中的"卡名"，如"招商银行信用卡"。 */
    private fun detectPayTool(text: String): String? =
        payToolPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }

    /** 正文中出现的钱包名（微信支付/支付宝）。 */
    private fun payToolText(text: String): String = text

    private fun detectWallet(text: String): String? = when {
        text.contains("微信支付") -> "微信支付"
        text.contains("支付宝") -> "支付宝"
        else -> null
    }

    private fun isLikelyIdentifier(text: String, range: IntRange): Boolean {
        val context = text.substring((range.first - 8).coerceAtLeast(0), min(text.length, range.last + 9))
        val prefix = text.substring((range.first - 8).coerceAtLeast(0), range.first)
        val identifierPrefix = listOf("订单号", "交易号", "流水号", "尾号", "时间", "验证码", "积分", "可用额度")
            .any(prefix::contains) && high.none(prefix::contains)
        return identifierPrefix ||
            Regex("\\d{4}年|\\d{1,2}:\\d{2}").containsMatchIn(context)
    }

    private fun unmatched(reason: String) = ParsedCapture(
        false, null, Direction.UNKNOWN, null, null, null, null, 0.0, listOf(reason)
    )

    private data class AmountCandidate(val fen: Long, val score: Int, val evidence: String)
}
