package com.moneytask.ledger.capture

/**
 * 一条原始系统通知（解析与去重的输入）。
 *
 * @param sourcePackage Android 应用包名（如 com.tencent.mm）。
 * @param sourceAppName 来源显示名/来源名（如 "招商银行"、"微信支付"、"美团"）。
 * @param notificationId 系统通知 key（用于单渠道幂等 sourceKey）。
 * @param timestamp      通知到达/事件发生时间（epoch 毫秒）。
 */
data class RawNotification(
    val sourcePackage: String,
    val sourceAppName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val notificationId: String = "${sourcePackage}#$timestamp#$title",
)

/**
 * 解析后的结构化采集结果（可据此构建 [CaptureEvent]）。
 *
 * @param matched       是否判定为一条可采集的真实交易通知。
 * @param confidence    置信度 [0,1]；MVP 阈值取 0.55（低于需人工复核，参照 RealtimeLedger）。
 */
data class ParsedCapture(
    val matched: Boolean,
    val amountFen: Long?,
    val direction: Direction,
    val merchant: String?,
    val bankTail: String?,
    val paymentTool: String?,
    val channel: Channel?,
    val confidence: Double,
    val evidence: List<String>,
)
