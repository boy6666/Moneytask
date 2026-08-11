package com.moneytask.ledger.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 账单 CSV 解析：三种平台（微信/支付宝/银行流水）真实表头 → 正确的行。 */
class BillCsvParserTest {

    // =============================================================
    // 微信支付交易明细
    // =============================================================
    @Test
    fun wechatBill_parsesAmountDirectionMerchant() {
        val csv = """
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2024-05-06 12:34:56,商户消费,美团,餐饮,支出,¥42.10,零钱,支付成功,4200000012345678,,
            2024-05-06 13:00:00,转账到账,张三,,收入,¥100.00,零钱,已存入,4200000012345679,,
        """.trimIndent()

        val r = BillCsvParser.parse(csv)
        assertEquals(BillPlatform.WECHAT, r.platform)
        assertEquals(emptyList(), r.errors)
        assertEquals(2, r.rows.size)

        val ex = r.rows[0]
        assertEquals(4210L, ex.amountFen)
        assertEquals(TxnType.EXPENSE, ex.type)
        assertEquals("美团", ex.merchant)

        val inc = r.rows[1]
        assertEquals(10000L, inc.amountFen)
        assertEquals(TxnType.INCOME, inc.type)
        assertEquals("张三", inc.merchant)
        assertTrue(inc.time > 0)
    }

    // =============================================================
    // 支付宝账单
    // =============================================================
    @Test
    fun alipayBill_parsesAmountDirectionMerchant() {
        val csv = """
            交易号,商户订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态
            2024050622001400,MT1,2024-05-06 12:00:01,2024-05-06 12:00:03,2024-05-06 12:00:03,支付宝App,即时到账,美团,团购套餐,42.10,支出,交易成功,0.00,0.00,,已成功
            2024050622001401,MT2,2024-05-06 14:00:01,2024-05-06 14:00:03,2024-05-06 14:00:03,支付宝App,即时到账,李某,转账收入,200.00,收入,交易成功,0.00,0.00,,已成功
        """.trimIndent()

        val r = BillCsvParser.parse(csv)
        assertEquals(BillPlatform.ALIPAY, r.platform)
        assertEquals(2, r.rows.size)

        assertEquals(TxnType.EXPENSE, r.rows[0].type)
        assertEquals(4210L, r.rows[0].amountFen)
        assertEquals("美团", r.rows[0].merchant)
        assertEquals(TxnType.INCOME, r.rows[1].type)
        assertEquals(20000L, r.rows[1].amountFen)
    }

    // =============================================================
    // 银行流水（收入/支出两列）
    // =============================================================
    @Test
    fun bankBill_twoAmountColumns() {
        val csv = """
            交易时间,交易类型,对方户名,收入金额,支出金额,余额,摘要
            2024-05-06 12:34:56,消费,美团,,42.10,1057.90,美团平台消费
            2024-05-07 09:00:00,代发工资,某某公司,5000.00,,1000.00,工资
        """.trimIndent()

        val r = BillCsvParser.parse(csv)
        assertEquals(BillPlatform.BANK, r.platform)
        assertEquals(2, r.rows.size)

        assertEquals(TxnType.EXPENSE, r.rows[0].type)
        assertEquals(4210L, r.rows[0].amountFen)
        assertEquals("美团", r.rows[0].merchant)

        assertEquals(TxnType.INCOME, r.rows[1].type)
        assertEquals(500000L, r.rows[1].amountFen)
        assertEquals("某某公司", r.rows[1].merchant)
    }

    // =============================================================
    // 无法方向的行被跳过并报错；未知文件被拒绝
    // =============================================================
    @Test
    fun unknownFile_rejected() {
        val r = BillCsvParser.parse("name,value\nfoo,1\n")
        assertEquals(BillPlatform.UNKNOWN, r.platform)
        assertEquals(0, r.rows.size)
        assertTrue(r.errors.isNotEmpty())
    }
}
