package com.moneytask.ledger.capture

import java.security.MessageDigest

/**
 * 把「原始通知 → 解析结果」适配成引擎能消费的 [CaptureEvent]。
 *
 * 三层防线在这里各取所需：
 *  - [sourceKey]：单渠道幂等（T1），优先取系统通知 key，缺省用「包名#标题#正文」哈希。
 *  - [textHash]：内容指纹（T2），原文字符串 SHA-256。
 *  - 归并字段：金额/方向/商家/卡尾号/支付工具，来自 [ParsedCapture]。
 */
object NotificationAdapter {

    fun toCaptureEvent(raw: RawNotification, parsed: ParsedCapture): CaptureEvent {
        val content = raw.title + "\n" + raw.text
        return CaptureEvent(
            id = "ev-${sha256(content)}",
            channel = parsed.channel ?: Channel.NOTIF_PAYMENT,
            sourcePackage = raw.sourcePackage,
            sourceKey = if (raw.notificationId.isNotBlank()) raw.notificationId else sha256(content),
            textHash = sha256(content),
            amountFen = parsed.amountFen ?: 0L,
            occurredAt = raw.timestamp,
            merchant = parsed.merchant,
            direction = parsed.direction,
            bankTail = parsed.bankTail,
            paymentTool = parsed.paymentTool,
        )
    }

    /** 内容指纹（不含时间/来源，仅看事件实质，用于第二层跨渠道指纹去重）。 */
    fun contentFingerprint(e: CaptureEvent): String =
        "${e.amountFen}|${e.merchant}|${e.direction}|${e.bankTail}|${e.paymentTool}"

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
