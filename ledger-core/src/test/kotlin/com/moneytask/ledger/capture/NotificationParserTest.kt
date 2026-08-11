package com.moneytask.ledger.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 解析器 + 适配器 + 引擎 端到端验证。
 *
 * 复现《记账App-开源复用与通知样本.md》中的真实通知文本：
 *  - 美团 ¥42.10：微信支付 / 掌上生活 / 招商银行App / 美团App / 招行公众号 / 美团公众号，共 6 条
 *  - 滴滴 ¥48.10：掌上生活 / 招行公众号，共 2 条
 *  - 还款 ¥3895.55：交通银行微银行 / 招行还款 / 云闪付
 */
class NotificationParserTest {

    private val parser = NotificationParser()

    // =============================================================
    // 单元：金额歧义（"可用额度：￥58539.79" 必须被低权排除）
    // =============================================================
    @Test
    fun amount_ignoresAvailableQuotaAndPicksRealPaid() {
        val p = parser.parse(RawNotification(
            sourcePackage = "com.tencent.mm",
            sourceAppName = "招商银行信用卡",
            title = "交易成功提醒",
            text = "交易时间：尾号1356信用卡\n交易类型：消费\n交易金额：42.10人民币\n交易商户：财付通-美团平台商户\n可用额度：￥58497.69",
            timestamp = 1_700_000_000_000L,
        ))
        assertEquals(4210L, p.amountFen)
        assertEquals(Channel.NOTIF_BANK, p.channel)
        assertEquals("1356", p.bankTail)
        assertEquals(Direction.EXPENSE, p.direction)
        assertTrue(p.matched)
    }

    // =============================================================
    // 单元：识别具体银行名（来源名优先；正文兜底；取最长命中）
    // =============================================================
    @Test
    fun bankName_fromSourceOrBody() {
        // 来源名带银行名 -> 识别为招商银行
        val fromSource = parser.parse(RawNotification(
            sourcePackage = "com.cmbchina.ccd.pluto.cmbActivity",
            sourceAppName = "招商银行",
            title = "消费提醒",
            text = "您尾号6222的信用卡消费42.10人民币",
            timestamp = 1_700_000_000_000L,
        ))
        assertEquals("招商银行", fromSource.bankName)
        assertEquals(Channel.NOTIF_BANK, fromSource.channel)
        assertEquals("6222", fromSource.bankTail)

        // 来源名无身份、银行名出现在正文 -> 从正文捞出来
        val fromBody = parser.parse(RawNotification(
            sourcePackage = "com.sms",
            sourceAppName = "短信",
            title = "【建设银行】",
            text = "您尾号8899的储蓄卡消费42.10元。",
            timestamp = 1_700_000_000_000L,
        ))
        assertEquals("建设银行", fromBody.bankName)
    }

    // =============================================================
    // 单元：微信支付通知 -> 支付渠道 + 卡尾号 + 支付工具
    // =============================================================
    @Test
    fun wechatPayNotification_resolvesToolTailAndChannel() {
        val p = parser.parse(RawNotification(
            sourcePackage = "com.tencent.mm",
            sourceAppName = "微信支付",
            title = "已支付¥42.10",
            text = "使用招商银行信用卡(1356)支付￥42.10",
            timestamp = 1_700_000_000_000L,
        ))
        assertEquals(4210L, p.amountFen)
        assertEquals(Channel.NOTIF_PAYMENT, p.channel)
        assertEquals("1356", p.bankTail)
        assertEquals("招商银行信用卡", p.paymentTool)
    }

    // =============================================================
    // 单元：美团 App 商户渠道 -> 商户优先；付款金额正确
    // =============================================================
    @Test
    fun merchantApp_channelIsMerchant() {
        val p = parser.parse(RawNotification(
            sourcePackage = "com.sankuai.meituan",
            sourceAppName = "美团",
            title = "您已成功付款42.10元",
            text = "您的美团订单已支付成功",
            timestamp = 1_700_000_000_000L,
        ))
        assertEquals(4210L, p.amountFen)
        assertEquals(Channel.NOTIF_MERCHANT, p.channel)
        assertEquals(Direction.EXPENSE, p.direction)
    }

    // =============================================================
    // 端到端：美团 6 条真实通知 -> 1 笔，账户=招商银行卡(1356)
    // =============================================================
    @Test
    fun endToEnd_meituanSixNotifications_oneEntry() {
        val t = 1_700_000_000_000L
        val raws = listOf(
            RawNotification("com.tencent.mm", "微信支付", "已支付¥42.10", "使用招商银行信用卡(1356)支付￥42.10", t),
            RawNotification("com.cmbchina.ccd.pluto.cmbActivity", "掌上生活", "交易提醒",
                "您在财付通-美团平台商户有一笔42.10人民币的消费已成功，点击查看详情【0元抽手机 11.11巅峰盛典】", t + 4_000),
            RawNotification("cmb.pb", "招商银行", "招商银行",
                "信用卡通知：您尾号1356的招行信用卡消费42.10人民币。", t + 5_000),
            RawNotification("com.sankuai.meituan", "美团", "您已成功付款42.10元",
                "您的美团订单已支付成功，点击查看详情>", t + 6_000),
            RawNotification("com.tencent.mm", "招商银行信用卡", "交易成功提醒",
                "交易时间：尾号1356信用卡\n交易类型：消费\n交易金额：42.10人民币\n交易商户：财付通-美团平台商户\n可用额度：￥58497.69", t + 9_000),
            RawNotification("com.tencent.mm", "美团公众号", "支付成功通知",
                "消费账户：185****6230\n支付金额：42.10元\n支付方式：微信支付\n支付时间：2025年11月07日 17:26", t + 10_000),
        )
        val accounts = listOf(
            Account("acc-bank-1356", "招商银行(1356)", AccountType.BANK, bankTail = "1356", isDefault = true),
            Account("acc-wechat", "微信钱包", AccountType.WECHAT),
        )
        val engine = CorrelationEngine(accounts)

        var matched = 0
        for (r in raws) {
            val p = parser.parse(r)
            if (p.matched && p.amountFen != null) matched++
            engine.onEvent(NotificationAdapter.toCaptureEvent(r, p))
        }

        // 6 条全部被解析为有效交易
        assertEquals(6, matched)

        val results = engine.settleAll()
        assertEquals(1, results.size, "6 real notifications must collapse to 1 entry")
        val c = results.first()
        assertEquals(4210L, c.amountFen)
        assertEquals("acc-bank-1356", c.accountId)  // 真实扣款卡 1356
        assertEquals(Direction.EXPENSE, c.direction)
    }

    // =============================================================
    // 端到端：滴滴 2 条真实通知 -> 1 笔
    // =============================================================
    @Test
    fun endToEnd_didiTwoNotifications_oneEntry() {
        val t = 1_700_100_000_000L
        val raws = listOf(
            RawNotification("com.cmbchina.ccd.pluto.cmbActivity", "掌上生活", "交易提醒",
                "您在支付宝-北京嘀嘀无限科技发展有限公司有一笔48.10人民币的消费已成功，点击查看详情", t),
            RawNotification("com.tencent.mm", "招商银行信用卡", "交易成功提醒",
                "交易时间：尾号1356信用卡11月07日08:29\n交易类型：消费\n交易金额：48.10人民币\n交易商户：支付宝-北京嘀嘀无限科技发展有限公司\n可用额度：￥58539.79", t + 10_000),
        )
        val accounts = listOf(
            Account("acc-bank-1356", "招商银行(1356)", AccountType.BANK, bankTail = "1356", isDefault = true),
        )
        val engine = CorrelationEngine(accounts)
        for (r in raws) engine.onEvent(NotificationAdapter.toCaptureEvent(r, parser.parse(r)))

        val results = engine.settleAll()
        assertEquals(1, results.size)
        assertEquals(4810L, results.first().amountFen)
    }

    // =============================================================
    // 端到端：信用卡还款 3 条 -> 1 笔（先按支出，可重分类）
    // =============================================================
    @Test
    fun endToEnd_repaymentThreeNotifications_oneEntry() {
        val t = 1_700_200_000_000L
        val raws = listOf(
            RawNotification("com.tencent.mm", "交通银行微银行", "账户变动提醒",
                "账号：*9412\n账户名称：交通银行账户\n交易时间：2025-11-10 12:12\n交易类型：网络支付消费\n交易金额：3895.55元", t),
            RawNotification("com.tencent.mm", "招商银行信用卡", "还款提醒",
                "账户类型:个人消费卡账户\n还款时间:11月10日 12:12:34\n还款金额:人民币3895.55元\n还款结果:账单已还清", t + 1000),
            RawNotification("com.unionpay", "云闪付", "支付助手：付款成功",
                "您尾号为9412的银行卡于10日12时12分消费3895.55元", t + 2000),
        )
        val accounts = listOf(
            Account("acc-bank-9412", "交通银行(9412)", AccountType.BANK, bankTail = "9412", isDefault = true),
        )
        val engine = CorrelationEngine(accounts)
        var matched = 0
        for (r in raws) {
            val p = parser.parse(r)
            if (p.matched) matched++
            engine.onEvent(NotificationAdapter.toCaptureEvent(r, p))
        }
        assertEquals(3, matched)
        val results = engine.settleAll()
        assertEquals(1, results.size)
        assertEquals(389555L, results.first().amountFen)
    }
}
