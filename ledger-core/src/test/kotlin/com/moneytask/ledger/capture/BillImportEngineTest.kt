package com.moneytask.ledger.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 账单导入引擎：恰好不重复记 + 已存在覆盖补全。
 * 匹配键 = 金额 + 时间(容差窗口) + 商户。
 */
class BillImportEngineTest {

    private val T0 = 1_700_000_000_000L

    private val accounts = listOf(
        Account("acc-cash", "现金", AccountType.CASH, isDefault = true),
        Account("acc-bank", "招商银行", AccountType.BANK),
        Account("acc-wechat", "微信钱包", AccountType.WECHAT),
    )

    private fun row(
        amountFen: Long, type: TxnType = TxnType.EXPENSE, time: Long = T0,
        merchant: String? = "美团", platform: BillPlatform = BillPlatform.WECHAT, line: Int = 1,
    ) = BillRow(amountFen, type, time, merchant, platform, line)

    private fun tx(
        id: String, amountFen: Long = 4210L, time: Long = T0, merchant: String? = "美团",
        categoryId: String? = null, pending: Boolean = false,
    ) = Transaction(
        id = id, amountFen = amountFen, type = TxnType.EXPENSE, time = time,
        accountId = "acc-cash", categoryId = categoryId, merchant = merchant,
        paymentMethod = null, note = null, groupId = null, source = TxnSource.AUTO,
        isPendingReview = pending, createdAt = T0, updatedAt = T0,
    )

    // =============================================================
    // 未匹配 -> 新增；金额/方向/时间/商户衔接正确
    // =============================================================
    @Test
    fun unmatched_newEntry() {
        val e = BillImportEngine()
        val r = e.run(
            listOf(row(4210L), row(10000L, TxnType.INCOME, platform = BillPlatform.ALIPAY)),
            existing = emptyList(), accounts = accounts, now = T0,
        )

        assertEquals(2, r.added.size)
        assertEquals(0, r.updated.size)
        assertEquals(0, r.matchedLocked)

        val first = r.added[0]
        assertEquals(4210L, first.amountFen)
        assertEquals(TxnType.EXPENSE, first.type)
        assertEquals("美团", first.merchant)
        assertEquals(TxnSource.IMPORT, first.source)
        // 微信平台 -> 微信钱包账户（新增归因）
        assertEquals("acc-wechat", first.accountId)
        assertFalse(first.isPendingReview)

        val income = r.added.find { it.type == TxnType.INCOME }!!
        assertEquals("acc-cash", income.accountId) // 无支付宝账户 -> 默认账户
    }

    // =============================================================
    // 同 key（金额+时间桶+商户）匹配到已存在 -> 覆盖补全，绝不新增
    // =============================================================
    @Test
    fun matched_existingEntries_updatedNotDuplicated() {
        val existing = listOf(tx("t1", categoryId = null, pending = true))
        val r = BillImportEngine().run(listOf(row(4210L, merchant = "美团")), existing = existing, accounts = accounts, now = T0)

        assertEquals(0, r.added.size, "绝不能重复记")
        assertEquals(1, r.updated.size)
        val upd = r.updated.first()
        assertEquals("t1", upd.id)              // 原地更新
        assertFalse(upd.isPendingReview)         // 账单确认后清除待复核
        assertNotNull(upd.categoryId)            // 自动分类补齐
    }

    // =============================================================
    // 不同金额 -> 视为不同笔 -> 新增（即使同时间同商户）
    // =============================================================
    @Test
    fun differentAmount_notMatched_newEntry() {
        val existing = listOf(tx("t1", amountFen = 4210L, categoryId = "expense_餐饮"))
        val r = BillImportEngine().run(listOf(row(4210L), row(5000L, time = T0, merchant = "美团")), existing = existing, accounts = accounts, now = T0)

        assertEquals(1, r.added.size)  // 5000 是新的一笔
        assertEquals(0, r.updated.size)
    }

    // =============================================================
    // 时间容差：账单时间略偏移仍在窗口内 -> 视为同一笔
    // =============================================================
    @Test
    fun timeWithinWindow_matches() {
        // 现有账目 T0（已确认、分类已补全），账单时间 T0+3 分钟（< 10 分钟窗口）
        val existing = listOf(tx("t1", categoryId = "expense_餐饮"))
        val r = BillImportEngine(timeWindowMs = 600_000L)
            .run(listOf(row(4210L, time = T0 + 180_000L, merchant = "美团")), existing = existing, accounts = accounts, now = T0)

        assertEquals(0, r.added.size)
        assertEquals(0, r.updated.size)  // 已确认且无缺字段 -> 无需改动
        assertEquals(1, r.matchedLocked)
    }

    // =============================================================
    // 超出时间窗口 -> 视为两笔 -> 新增
    // =============================================================
    @Test
    fun timeOutsideWindow_newEntry() {
        val existing = listOf(tx("t1"))
        val r = BillImportEngine(timeWindowMs = 600_000L)
            .run(listOf(row(4210L, time = T0 + 3_600_000L, merchant = "美团")), existing = existing, accounts = accounts, now = T0)

        assertEquals(1, r.added.size)
    }

    // =============================================================
    // 账户归因矩阵：微信/支付宝/银行/未知
    // =============================================================
    @Test
    fun accountAttribution_byPlatform() {
        val e = BillImportEngine()
        fun accFor(p: BillPlatform): String? = e.run(
            listOf(row(1000L, platform = p)), existing = emptyList(), accounts = accounts, now = T0
        ).added.first().accountId

        assertEquals("acc-wechat", accFor(BillPlatform.WECHAT))
        assertEquals("acc-cash", accFor(BillPlatform.ALIPAY))    // 无支付宝账户 -> 默认
        assertEquals("acc-bank", accFor(BillPlatform.BANK))
        assertEquals("acc-cash", accFor(BillPlatform.UNKNOWN))   // 未知 -> 默认
    }

    private fun assertNotNull(v: String?) = kotlin.test.assertNotNull(v)
}
