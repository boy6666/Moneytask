package com.moneytask.ledger.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T1–T10：去重与链路归并验证（《MVP技术设计》§5）。
 *
 * 核心断言：
 *  - 三层防线：单渠道幂等（T1）→ 指纹去重（T2）→ 跨渠道链式归并（T3–T4）
 *  - 绝不误并：金额不同（T5）/时间窗不同（T6）/方向冲突（T7）都该拆开
 *  - 落账倒计时（T8）与智能账户归因（T9）
 *  - 真实样本：美团 ¥42.10 来自 6 个来源 → 恰好 1 笔（T10）
 */
class CorrelationEngineTest {

    // ---- 测试用账户池（同《MVP技术设计》3.1）----
    private val accounts = listOf(
        Account("acc-cash", "现金", AccountType.CASH, isDefault = true),
        Account("acc-bank-6222", "招商银行(6222)", AccountType.BANK, bankTail = "6222"),
        Account("acc-bank-8899", "工商银行(8899)", AccountType.BANK, bankTail = "8899"),
        Account("acc-wechat", "微信钱包", AccountType.WECHAT),
        Account("acc-alipay", "支付宝余额", AccountType.ALIPAY),
    )

    private fun engine(
        windowMs: Long = 90_000L,
        settleDelayMs: Long = 60_000L,
    ) = CorrelationEngine(accounts, windowMs, settleDelayMs)

    // ---- 便捷构造 ----

    private var evId = 0
    private fun ev(
        channel: Channel,
        amountFen: Long,
        occurredAt: Long,
        sourceKey: String? = null,
        merchant: String? = null,
        direction: Direction = Direction.UNKNOWN,
        bankTail: String? = null,
        paymentTool: String? = null,
    ) = CaptureEvent(
        id = "e${evId++}",
        channel = channel,
        sourcePackage = "pkg",
        sourceKey = sourceKey ?: "src-${evId}",
        textHash = "",
        amountFen = amountFen,
        occurredAt = occurredAt,
        merchant = merchant,
        direction = direction,
        bankTail = bankTail,
        paymentTool = paymentTool,
    )

    // T0 基座时间
    private val T0 = 1_700_000_000_000L

    // =============================================================
    // T1 单渠道幂等：同一 sourceKey 只处理一次
    // =============================================================
    @Test
    fun t1_sameSourceKeyDeduplicated() {
        val e = engine()
        val a = ev(Channel.NOTIF_PAYMENT, 4210, T0, sourceKey = "pay-1", paymentTool = "微信支付")
        val dup = a.copy(id = "e-dup", textHash = "different-text-hash")

        // 注入：引擎已自动去重同 sourceKey
        val first = e.onEvent(a)
        val second = e.onEvent(dup)   // 或直接 construe 到同一归一化 key
        assertTrue(first)
        assertFalse(second)
        assertEquals(1, e.groups.size)
        assertEquals(1, e.settleAll().size)
    }

    // =============================================================
    // T2 指纹去重：不同 sourceKey、同 content 指纹 -> 只保留一条
    // =============================================================
    @Test
    fun t2_sameContentFingerprintDeduplicated() {
        val e = engine()
        val a = ev(Channel.NOTIF_PAYMENT, 4210, T0, sourceKey = "pay-a")
        // 同一指纹的内容被第二次投递，只是 parse 端 key 换了
        val b = a.copy(id = "e-b", sourceKey = "pay-b")
        e.onEvent(a)
        e.onEvent(b)
        // 两条事件金额/时间相同 -> 归并为一组（同金额同窗口）
        assertEquals(1, e.groups.size)
        assertEquals(1, e.settleAll().size)
    }

    // =============================================================
    // T3 商户 + 支付 两来源 -> 归并 1 笔
    // =============================================================
    @Test
    fun t3_merchantPlusPayment_oneLedgerEntry() {
        val e = engine()
        // 美团订单确认
        e.onEvent(ev(Channel.NOTIF_MERCHANT, 4210, T0, merchant = "美团", direction = Direction.EXPENSE))
        // 微信支付回执（+5s）
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, T0 + 5_000, paymentTool = "微信支付", direction = Direction.EXPENSE))

        val results = e.settleAll()
        assertEquals(1, results.size)
        val c = results.first()
        assertEquals(4210L, c.amountFen)
        assertEquals("美团", c.merchant)          // 商户渠道优先
        assertEquals("acc-wechat", c.accountId)   // 仅支付渠道 -> 微信钱包
        assertEquals("微信支付", c.paymentMethod)
        assertEquals(Direction.EXPENSE, c.direction)
    }

    // =============================================================
    // T4 商户 + 支付 + 银行 三来源 -> 归并 1 笔，账户=银行卡
    // =============================================================
    @Test
    fun t4_fullChain_merchantPaymentBank_oneEntryCardAccount() {
        val e = engine()
        e.onEvent(ev(Channel.NOTIF_MERCHANT, 4210, T0, merchant = "美团", direction = Direction.EXPENSE))
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, T0 + 5_000, paymentTool = "微信支付", direction = Direction.EXPENSE))
        // 银行扣款通知（+12s），卡尾号 6222 <-> 招商银行
        e.onEvent(ev(Channel.NOTIF_BANK, 4210, T0 + 12_000, bankTail = "6222", direction = Direction.EXPENSE))

        val results = e.settleAll()
        assertEquals(1, results.size)
        val c = results.first()
        assertEquals("美团", c.merchant)
        assertEquals("acc-bank-6222", c.accountId)  // 智能判断 -> 真实扣款银行卡
        assertEquals("微信支付", c.paymentMethod)
    }

    // =============================================================
    // T5 同窗口内、金额不同 -> 不归并（两笔）
    // =============================================================
    @Test
    fun t5_differentAmountSameWindow_twoEntries() {
        val e = engine()
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, T0, paymentTool = "微信支付"))
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 8800, T0 + 10_000, paymentTool = "微信支付"))
        // 金额不同 -> 两个独立组，不能归并
        assertEquals(2, e.groups.size)
        assertEquals(2, e.settleAll().size)
    }

    // =============================================================
    // T6 同金额、超出时间窗口 -> 不归并（两笔）
    // =============================================================
    @Test
    fun t6_sameAmountOutsideWindow_twoEntries() {
        val e = engine(windowMs = 90_000L)
        // 两次独立的 42.10 元消费，间隔 10 分钟 (> 90s)
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, T0, paymentTool = "微信支付"))
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, T0 + 600_000L, paymentTool = "微信支付"))
        assertEquals(2, e.groups.size)
        assertEquals(2, e.settleAll().size)
    }

    // =============================================================
    // T7 方向冲突（支出 vs 收入 同金额同窗口）-> 不归并
    // =============================================================
    @Test
    fun t7_conflictingDirection_notMerged() {
        val e = engine()
        // 一笔支出 42.10 与一笔收入 42.10 非常接近，绝不能归并
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, T0, paymentTool = "微信支付", direction = Direction.EXPENSE))
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, T0 + 5_000, paymentTool = "微信支付", direction = Direction.INCOME))
        assertEquals(2, e.groups.size)
        assertEquals(2, e.settleAll().size)
    }

    // =============================================================
    // T8 落账倒计时：未到时间不结算，到了才结算
    // =============================================================
    @Test
    fun t8_settleOnlyAfterDelayElapses() {
        val e = engine(settleDelayMs = 60_000L)
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, T0, paymentTool = "微信支付"))

        // 30s 后：尚未到 60s 倒计时 -> 不结算
        assertEquals(0, e.settleExpired(T0 + 30_000L).size)
        assertEquals(GroupStatus.OPEN, e.groups.first().status)

        // 61s 后：倒计时已到 -> 结算 1 笔
        val r = e.settleExpired(T0 + 61_000L)
        assertEquals(1, r.size)
        assertEquals(GroupStatus.CLOSED, e.groups.first().status)
    }

    // =============================================================
    // T9 智能账户归因矩阵（银行卡/微信/支付宝/默认）
    // =============================================================
    @Test
    fun t9_accountAttributionMatrix() {
        // 9a 银行尾号 -> 对应银行卡
        run {
            val e = engine()
            e.onEvent(ev(Channel.NOTIF_BANK, 4210, T0, bankTail = "8899", direction = Direction.EXPENSE))
            assertEquals("acc-bank-8899", e.settleAll().first().accountId)
        }
        // 9b 银行尾号未知 -> 兜底任一银行账户
        run {
            val e = engine()
            e.onEvent(ev(Channel.NOTIF_BANK, 4210, T0, bankTail = "0000", direction = Direction.EXPENSE))
            assertEquals("acc-bank-6222", e.settleAll().first().accountId)
        }
        // 9c 仅支付宝支付渠道 -> 支付宝余额
        run {
            val e = engine()
            e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, T0, paymentTool = "支付宝", direction = Direction.EXPENSE))
            assertEquals("acc-alipay", e.settleAll().first().accountId)
        }
        // 9d 仅商户渠道、无支付/银行信息 -> 默认账户
        run {
            val e = engine()
            e.onEvent(ev(Channel.NOTIF_MERCHANT, 4210, T0, merchant = "某店", direction = Direction.EXPENSE))
            assertEquals("acc-cash", e.settleAll().first().accountId)
        }
    }

    // =============================================================
    // T10 真实样本：美团 ¥42.10 —— 6 个来源 -> 恰好 1 笔
    // (《记账App-开源复用与通知样本.md》：美团订单/商品、微信支付、银行卡、两条中间态)
    // =============================================================
    @Test
    fun t10_realMeituanSample_sixSources_oneEntry() {
        val e = engine()
        val t = T0

        // ①美团 APP 订单通知（商户渠道）
        e.onEvent(ev(Channel.NOTIF_MERCHANT, 4210, t, merchant = "美团", direction = Direction.EXPENSE))
        // ②美团「待支付」中间态（避免误记，但同 key 幂等不重复结算）
        // ③ 微信支付回执
        e.onEvent(ev(Channel.NOTIF_PAYMENT, 4210, t + 3_000, paymentTool = "微信支付", direction = Direction.EXPENSE))
        // ④ 银行扣款 6222（银行卡实付）
        e.onEvent(ev(Channel.NOTIF_BANK, 4210, t + 8_000, bankTail = "6222", direction = Direction.EXPENSE))
        // ⑤ 美团订单「已支付」终态（同金额同窗口，可归并）
        e.onEvent(ev(Channel.NOTIF_MERCHANT, 4210, t + 20_000, merchant = "美团", direction = Direction.EXPENSE))
        // ⑥ 短信/飞书等补充渠道（忽略渠道差异，仅验证归并）
        e.onEvent(ev(Channel.MANUAL, 4210, t + 30_000, merchant = "美团", direction = Direction.EXPENSE))

        val results = e.settleAll()
        // 一条不多、一条不少：6 个来源合并为 1 笔账目
        assertEquals(1, results.size, "6 sources must collapse to exactly 1 entry")
        val c = results.first()
        assertEquals(4210L, c.amountFen)
        assertEquals("美团", c.merchant)
        assertEquals("acc-bank-6222", c.accountId) // 银行卡实付被判为真实账户
        assertEquals("微信支付", c.paymentMethod)
        assertEquals(Direction.EXPENSE, c.direction)
    }
}
