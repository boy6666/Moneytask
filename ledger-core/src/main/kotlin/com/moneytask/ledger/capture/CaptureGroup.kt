package com.moneytask.ledger.capture

/** 交易组状态（《MVP技术设计》§4）。 */
enum class GroupStatus { OPEN, CLOSED, DISPUTED }

/**
 * 交易组（链路归并的落点）：若干指向同一笔真实消费的 CaptureEvent 归入一组。
 *
 * - 组内成员必属同一金额、落在同一关联时间窗口、方向不冲突。
 * - 组结算后应恰好产出一条账目（对应一篇 Transaction）。
 */
class CaptureGroup(
    val id: String,
    val members: MutableList<CaptureEvent> = mutableListOf(),
) {
    var status: GroupStatus = GroupStatus.OPEN
        internal set

    /** 组内「最后活跃时刻」，用于判定落账倒计时（§4.4）。 */
    var lastActiveAt: Long = 0L
        internal set

    /** 组金额 = 首个成员金额（成员已保证金额一致）。 */
    val amountFen: Long get() = members.first().amountFen

    /** 组内最早的事件时刻，作为归因时间基准。 */
    val earliestAt: Long get() = members.minOf { it.occurredAt }

    fun add(event: CaptureEvent) {
        members.add(event)
        lastActiveAt = maxOf(lastActiveAt, event.occurredAt)
    }
}

/**
 * 一个交易组结算后的结论（可直接据此落一笔账目）。
 *
 * @param direction 归因后的方向。
 * @param merchant  归因后的商家（优先商户渠道；否则支付渠道；否则银行渠道）。可为空。
 * @param accountId 智能判断后的真实账户（《MVP技术设计》§4.3）。信息不足时为默认账户 id。
 * @param paymentMethod 归因后的支付方式（"微信支付"/"支付宝"/空）。
 * @param time      归因后的真实消费时刻（取组内最早/商户侧时间）。
 */
data class Conclusion(
    val groupId: String,
    val amountFen: Long,
    val direction: Direction,
    val merchant: String?,
    val accountId: String?,
    val paymentMethod: String?,
    val time: Long,
    val status: GroupStatus,
)
