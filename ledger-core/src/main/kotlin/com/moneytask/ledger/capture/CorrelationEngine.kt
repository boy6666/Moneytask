package com.moneytask.ledger.capture

import kotlin.math.abs

/**
 * 链路归并引擎（《MVP技术设计》§4 核心）。
 *
 * 职责：把指向**同一笔真实消费**的多渠道 CaptureEvent（商户 + 微信/支付宝 + 银行）
 * 正确归并为一个交易组并结算为一笔账目；同时保证——
 *  1. 绝不重复：同一 sourceKey 只处理一次；同一组只产出一条 Conclusion。
 *  2. 绝不误并：两条真消费被判定可关联时按「宁可拆开」处理。
 *
 * 本类纯内存、无 Android / I/O 依赖，便于独立单测（T1–T10）。
 * 真实环境：把通知流喂给 [onEvent]，并用 [settleExpired] 周期触发落账倒计时结算。
 *
 * @param windowMs      关联时间窗口（默认 90s）：事件与组内任一成员时间差在窗口内才可关联。
 * @param settleDelayMs 落账倒计时（默认 60s）：组在最后一次活跃后，经过该时长即结算。
 */
class CorrelationEngine(
    private val accounts: List<Account>,
    private val windowMs: Long = 90_000L,
    private val settleDelayMs: Long = 60_000L,
    private val idGen: (Long) -> String = { seq -> "g$seq" },
) {
    private val _groups = mutableListOf<CaptureGroup>()
    private val seenSourceKeys = mutableSetOf<String>()
    private var seq = 0L

    /** 当前所有交易组（含已结算）。 */
    val groups: List<CaptureGroup> get() = _groups.toList()

    /** 已结算结论（只增，供落账/测试校验）。 */
    val settled: MutableList<Conclusion> = mutableListOf()

    /**
     * 接收一个新的采集事件。
     * @return true 表示事件被接受（归入某组）；false 表示被去重忽略（重复的 sourceKey）。
     */
    fun onEvent(event: CaptureEvent): Boolean {
        // 三层防线·第一层：单渠道幂等（同一通知 key 只处理一次）
        if (!seenSourceKeys.add(event.sourceKey)) return false

        // 三层防线·归并：尝试归入一个可关联的 OPEN 组
        val match = _groups.firstOrNull { g -> g.status == GroupStatus.OPEN && canJoin(g, event) }
        if (match != null) {
            match.add(event)
        } else {
            val group = CaptureGroup(idGen(seq++), mutableListOf(event))
            group.lastActiveAt = event.occurredAt
            _groups.add(group)
        }
        return true
    }

    /** 是否可把 [event] 归并进 [group]：同金额 + 时间窗口内 + 方向不冲突。 */
    private fun canJoin(group: CaptureGroup, event: CaptureEvent): Boolean {
        if (group.members.first().amountFen != event.amountFen) return false
        val withinWindow = group.members.any { abs(it.occurredAt - event.occurredAt) <= windowMs }
        if (!withinWindow) return false
        val groupDir = group.members.first().direction
        val conflict = groupDir != Direction.UNKNOWN &&
            event.direction != Direction.UNKNOWN &&
            groupDir != event.direction
        return !conflict
    }

    /**
     * 结算所有「落账倒计时已到」的 OPEN 组。返回本次新产生的结论。
     * @param now 当前时刻（绝对时间戳）。
     */
    fun settleExpired(now: Long): List<Conclusion> =
        _groups.filter { it.status == GroupStatus.OPEN && (now - it.lastActiveAt) >= settleDelayMs }
            .map { conclude(it) }

    /** 结算全部剩余 OPEN 组（测试/退出时调用）。 */
    fun settleAll(): List<Conclusion> =
        _groups.filter { it.status == GroupStatus.OPEN }.map { conclude(it) }

    private fun conclude(group: CaptureGroup): Conclusion {
        group.status = GroupStatus.CLOSED
        val resolve = resolve(group)
        val conclusion = Conclusion(
            groupId = group.id,
            amountFen = group.amountFen,
            direction = resolve.direction,
            merchant = resolve.merchant,
            accountId = resolve.accountId,
            paymentMethod = resolve.paymentMethod,
            time = resolve.time,
            status = GroupStatus.CLOSED,
        )
        settled.add(conclusion)
        return conclusion
    }

    // ---- 归因（《MVP技术设计》§4.3）----

    private data class Resolve(
        val direction: Direction,
        val merchant: String?,
        val accountId: String?,
        val paymentMethod: String?,
        val time: Long,
    )

    private fun resolve(group: CaptureGroup): Resolve {
        val members = group.members

        // 方向：收入（退款/到账）优先于支出（支付/扣款），且取组内非 UNKNOWN 者
        val direction =
            members.asSequence()
                .filter { it.direction != Direction.UNKNOWN }
                .maxByOrNull { when (it.direction) { Direction.INCOME -> 2; Direction.EXPENSE -> 1; else -> 0 } }
                ?.direction ?: Direction.UNKNOWN

        // 商家：商户渠道 > 支付渠道 > 银行渠道（取第一个非空）
        val merchant = listOf(
            members.firstOrNull { it.channel == Channel.NOTIF_MERCHANT }?.merchant,
            members.firstOrNull { it.channel == Channel.NOTIF_PAYMENT }?.merchant,
            members.firstOrNull { it.channel == Channel.NOTIF_BANK }?.merchant,
            members.firstOrNull { it.channel == Channel.MANUAL }?.merchant,
        ).firstNotNullOfOrNull { it }

        val bankEv = members.firstOrNull { it.channel == Channel.NOTIF_BANK }
        val payEv = members.firstOrNull { it.paymentTool != null }

        // 智能判断真实账户（§4.3）：
        //  有银行渠道 -> 记真实扣款的银行卡（卡尾号匹配优先；无尾号或匹配不到则按银行名
        //                反查账户名；再兜底取任一银行账户）
        //  仅支付工具  -> 记微信钱包 / 支付宝余额
        //  信息不足    -> 默认账户（可改，不阻塞自动记账）
        val accountId: String? = when {
            bankEv != null -> {
                val byTail = bankEv.bankTail?.let { tail ->
                    accounts.firstOrNull { it.bankTail == tail }?.id
                }
                val byName = bankEv.bankName?.let { bn ->
                    accounts.firstOrNull { it.type == AccountType.BANK && it.name.contains(bn) }?.id
                }
                byTail ?: byName ?: accounts.firstOrNull { it.type == AccountType.BANK }?.id
            }

            payEv != null -> when {
                payEv.paymentTool!!.contains("微信") -> accounts.firstOrNull { it.type == AccountType.WECHAT }?.id
                payEv.paymentTool!!.contains("支付宝") -> accounts.firstOrNull { it.type == AccountType.ALIPAY }?.id
                else -> accounts.firstOrNull { it.isDefault }?.id
            }

            members.firstOrNull()?.channel == Channel.MANUAL ->
                accounts.firstOrNull { it.isDefault }?.id

            else -> accounts.firstOrNull { it.isDefault }?.id
        }

        val paymentMethod = payEv?.paymentTool
        val time = group.earliestAt
        return Resolve(direction, merchant, accountId, paymentMethod, time)
    }
}
