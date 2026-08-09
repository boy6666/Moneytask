package com.moneytask.ledger

import android.content.Context
import com.moneytask.ledger.capture.CapturePipeline
import com.moneytask.ledger.capture.CorrelationEngine
import com.moneytask.ledger.capture.LedgerWriter
import com.moneytask.ledger.capture.NotificationAdapter
import com.moneytask.ledger.capture.NotificationParser
import com.moneytask.ledger.capture.RawNotification
import com.moneytask.ledger.db.AppDatabase
import com.moneytask.ledger.db.DatabaseSeeder
import com.moneytask.ledger.db.LedgerDao
import com.moneytask.ledger.db.RoomLedgerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * 手动 DI 容器：装配数据库、预置数据、归因引擎、落账器与采集管线。
 * MVP 阶段不引入 Hilt，保持依赖尽量少；后续可平滑替换为 Hilt 模块。
 */
class AppContainer(context: Context) {
    private val io = Dispatchers.IO
    private val appScope = CoroutineScope(SupervisorJob() + io)

    val database: AppDatabase = AppDatabase.get(context)
    val dao: LedgerDao = database.ledgerDao()
    val store = RoomLedgerStore(dao, io)

    lateinit var pipeline: CapturePipeline
        private set

    init {
        // 预置分类与默认账户（幂等），再读取账户池构建归因引擎。
        runBlocking(io) { DatabaseSeeder.seed(dao, System.currentTimeMillis()) }
        val engine = CorrelationEngine(store.accounts())
        val writer = LedgerWriter(store)
        pipeline = CapturePipeline(
            engine = engine,
            writer = writer,
            dao = dao,
            scope = appScope,
        ).also { it.start() }
    }

    /** 演示：喂一条真实美团链上的合成通知，用于在无真实通知时验证全链路。 */
    fun simulateMeituanPurchase(chat: Boolean = false) {
        val pkg = if (chat) "com.tencent.mm" else "com.sankuai.meituan"
        val name = if (chat) "微信支付" else "美团"
        val title = if (chat) "已支付¥42.10" else "您已成功付款42.10元"
        val text = if (chat) "使用招商银行信用卡(1356)支付￥42.10" else "您的美团订单已支付成功"
        pipeline.onNotification(
            RawNotification(pkg, name, title, text, System.currentTimeMillis())
        )
        // 若有通知监听权限，真实场景会多通知自动化触发；这里补一条银行扣款让归因判定到银行卡
        if (!chat) pipeline.onNotification(
            RawNotification(
                "cmb.pb", "招商银行", "招商银行",
                "信用卡通知：您尾号1356的招行信用卡消费42.10人民币。",
                System.currentTimeMillis() + 3_000,
            )
        )
    }
}
