package com.moneytask.ledger.capture

/**
 * 渠道分类器（《MVP技术设计》§3.4）。
 *
 * 把一条通知的来源判定到 4 个采集渠道之一，供归因时决定"商户/支付/银行"的优先级。
 *
 * 判定优先级（channel 影响商品优先级 + 账户归因，准确度很重要）：
 *   银行 > 支付钱包 > 商户。
 * 通过「来源名 + 标题 + 正文」关键词命中（银行/信用卡通知永远按银行渠道，防止
 * 把招行公众号里出现的"支付宝-xxx商户"误判成支付渠道）。
 *
 * 本类是 MVP 级启发式；后续可用包名白名单（supported_sources）精化。
 */
class ChannelClassifier(
    bankNames: List<String> = defaultBankNames,
    payWallets: List<String> = defaultPayWallets,
    merchantNames: List<String> = defaultMerchantNames,
) {
    private val bank = bankNames
    private val pay = payWallets
    private val merchant = merchantNames

    /** 判定渠道；无法判定时返回 null（事件仍可采集，但归因优先级受影响）。 */
    fun classify(sourceAppName: String, title: String, text: String): Channel? {
        // 来源身份优先：微信支付通知的正文可能提到"招商银行信用卡"（资金方），
        // 但它的渠道是支付而非银行，所以来源名先于正文判定。
        if (bank.any(sourceAppName::contains)) return Channel.NOTIF_BANK
        if (pay.any(sourceAppName::contains)) return Channel.NOTIF_PAYMENT
        if (merchant.any(sourceAppName::contains)) return Channel.NOTIF_MERCHANT
        // 来源名无身份时，退回到标题+正文关键词兜底。
        val body = title + text
        if (bank.any(body::contains)) return Channel.NOTIF_BANK
        if (pay.any(body::contains)) return Channel.NOTIF_PAYMENT
        if (merchant.any(body::contains)) return Channel.NOTIF_MERCHANT
        return null
    }

    companion object {
        val defaultBankNames = listOf(
            "招商银行", "工商银行", "建设银行", "交通银行", "中国银行", "农业银行",
            "邮政储蓄", "民生银行", "中信银行", "光大银行", "平安银行", "浦发银行",
            "掌上生活", "云闪付", "银联",
        )
        val defaultPayWallets = listOf("微信支付", "支付宝", "财付通")
        val defaultMerchantNames = listOf(
            "美团", "京东", "滴滴", "饿了么", "淘宝", "天猫", "拼多多", "唯品会",
            "去哪儿", "飞猪", "大众点评", "盒马", "瑞幸", "星巴克", "肯德基",
            "麦当劳", "喜茶", "蜜雪冰城",
        )
    }
}
