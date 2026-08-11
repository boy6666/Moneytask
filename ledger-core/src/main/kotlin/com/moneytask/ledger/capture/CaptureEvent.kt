package com.moneytask.ledger.capture

/** 采集来源渠道（《MVP技术设计》§1/3.4）。 */
enum class Channel { NOTIF_PAYMENT, NOTIF_MERCHANT, NOTIF_BANK, MANUAL }

/** 收支方向（《MVP技术设计》§4.3）。 */
enum class Direction { EXPENSE, INCOME, UNKNOWN }

/**
 * 一次「原始采集」事件（等价于一条被解析成功的支付/商户/银行通知）。
 *
 * 同一笔真实消费会从多个渠道各产生一条 CaptureEvent，本模型的职责就是
 * 把这些事件正确归并为一笔账目，绝不重复（不重记）、也不误并（不错并）。
 *
 * @param sourceKey 单渠道幂等键（通常是通知的 key）。同一 key 只应处理一次。
 * @param textHash  原文 SHA-256，参与指纹去重。
 * @param amountFen 金额，单位「分」（整数，避免浮点误差）。
 * @param occurredAt 事件发生时间（epoch 毫秒）。
 */
data class CaptureEvent(
    val id: String,
    val channel: Channel,
    val sourcePackage: String,
    val sourceKey: String,
    val textHash: String,
    val amountFen: Long,
    val occurredAt: Long,
    val merchant: String? = null,
    val direction: Direction = Direction.UNKNOWN,
    val bankTail: String? = null,
    val paymentTool: String? = null,
    /** 识别出的具体银行名（如"招商银行"）；与 [bankTail] 互补，用于按银行名归因。 */
    val bankName: String? = null,
)
