package com.moneytask.ledger.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 全链路集成验证：真实通知文本 → 解析 → 归并 → 结算 → 落账。
 *
 * 覆盖「绝不重复 / 不误并 / 智能归因 / 智能分类 / 落账幂等」：
 *  - 美团 6 条真实通知 -> 恰好 1 笔账目（核心验收）
 *  - 落账幂等：同一结论重复 persist 也只写 1 笔
 *  - 两笔真消费（美团外卖 + 滴滴）-> 2 笔，绝不误并
 */
class PipelineIntegrationTest {

    private val parser = NotificationParser()

    private fun feed(
        engine: CorrelationEngine,
        sourceAppName: String,
        title: String,
        text: String,
        occurredAt: Long,
    ) {
        val raw = RawNotification("pkg", sourceAppName, title, text, occurredAt)
        engine.onEvent(NotificationAdapter.toCaptureEvent(raw, parser.parse(raw)))
    }

    private fun meituanRaw(t: Long): List<RawNotification> = listOf(
        RawNotification("com.tencent.mm", "微信支付", "已支付¥42.10", "使用招商银行信用卡(1356)支付￥42.10", t),
        RawNotification("com.cmbchina.ccd.pluto.cmbActivity", "掌上生活", "交易提醒",
            "您在财付通-美团平台商户有一笔42.10人民币的消费已成功，点击查看详情", t + 4_000),
        RawNotification("cmb.pb", "招商银行", "招商银行",
            "信用卡通知：您尾号1356的招行信用卡消费42.10人民币。", t + 5_000),
        RawNotification("com.sankuai.meituan", "美团", "您已成功付款42.10元", "您的美团订单已支付成功", t + 6_000),
        RawNotification("com.tencent.mm", "招商银行信用卡", "交易成功提醒",
            "交易时间：尾号1356信用卡\n交易类型：消费\n交易金额：42.10人民币\n交易商户：财付通-美团平台商户\n可用额度：￥58497.69", t + 9_000),
        RawNotification("com.tencent.mm", "美团公众号", "支付成功通知",
            "消费账户：185****6230\n支付金额：42.10元\n支付方式：微信支付\n支付时间：11-07 17:26", t + 10_000),
    )

    private fun accounts() = listOf(
        Account("acc-bank-1356", "招商银行(1356)", AccountType.BANK, bankTail = "1356", isDefault = true),
        Account("acc-wechat", "微信钱包", AccountType.WECHAT),
        Account("acc-alipay", "支付宝余额", AccountType.ALIPAY),
    )

    // =============================================================
    // 核心验收：一条真实消费 -> 恰好 1 笔，自动落账、自动分类
    // =============================================================
    @Test
    fun fullPipeline_oneRealPurchase_oneAutoEntry() {
        val t = 1_700_000_000_000L
        val engine = CorrelationEngine(accounts())
        for (r in meituanRaw(t)) {
            engine.onEvent(NotificationAdapter.toCaptureEvent(r, parser.parse(r)))
        }

        val conclusions = engine.settleAll()
        assertEquals(1, conclusions.size)

        val store = InMemoryLedgerStore()
        val writer = LedgerWriter(store).apply { setNow(t) }
        val tx = writer.persist(conclusions.first())

        assertNotNull(tx)
        assertEquals(1, store.count())
        assertEquals(4210L, tx.amountFen)
        assertEquals("acc-bank-1356", tx.accountId)   // 智能归因：真实扣款卡
        assertEquals(TxnType.EXPENSE, tx.type)
        assertEquals("expense_餐饮", tx.categoryId)    // 智能分类：含"美团" -> 餐饮
        assertEquals("美团平台商户", tx.merchant)      // 归一化后：剥掉"财付通-"前缀
        assertEquals("招商银行信用卡", tx.paymentMethod)
        assertEquals(TxnSource.AUTO, tx.source)
        assertTrue(!tx.isPendingReview)
    }

    // =============================================================
    // 落账幂等：同一 groupId 重复 persist 只写 1 笔（绝不重复记账）
    // =============================================================
    @Test
    fun persist_isIdempotent_sameGroupOnlyOneEntry() {
        val store = InMemoryLedgerStore()
        val writer = LedgerWriter(store).apply { setNow(1_700_000_000_000L) }
        val conclusion = Conclusion(
            groupId = "g1", amountFen = 4210L, direction = Direction.EXPENSE,
            merchant = "美团", accountId = "acc-bank-1356", paymentMethod = "微信支付",
            time = 1_700_000_000_000L, status = GroupStatus.CLOSED,
        )
        val first = writer.persist(conclusion)
        val second = writer.persist(conclusion) // 同结论重复落账
        assertNotNull(first)
        assertNull(second)
        assertEquals(1, store.count())
    }

    // =============================================================
    // 绝不误并：两笔真消费（美团外卖 + 滴滴）-> 2 笔，各自独立
    // =============================================================
    @Test
    fun twoRealPurchases_twoSeparateEntries() {
        val t = 1_700_000_000_000L
        val engine = CorrelationEngine(accounts())
        // 美团外卖 42.10
        feed(engine, "美团", "已成功付款42.10元", "您的美团订单已支付成功", t)
        // 滴滴 18.50：同窗口、不同金额 -> 不会与美团误并
        feed(engine, "微信支付", "已支付¥18.50", "使用微信零钱支付￥18.50", t + 5_000)
        // 滴滴银行扣款（同金额 18.50、同窗口、同方向）-> 与滴滴条归并
        feed(engine, "招商银行", "招商银行", "您尾号1356的招行信用卡消费18.50人民币。", t + 8_000)

        val conclusions = engine.settleAll()
        assertEquals(2, conclusions.size, "two real purchases must stay separate")
        val amounts = conclusions.map { it.amountFen }.sorted()
        assertEquals(listOf(1850L, 4210L), amounts)
    }
}
