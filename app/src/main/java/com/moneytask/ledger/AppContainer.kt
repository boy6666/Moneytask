package com.moneytask.ledger

import android.content.Context
import com.moneytask.ledger.capture.Account
import com.moneytask.ledger.capture.CapturePipeline
import com.moneytask.ledger.capture.Category
import com.moneytask.ledger.capture.CorrelationEngine
import com.moneytask.ledger.capture.LedgerWriter
import com.moneytask.ledger.capture.NotificationAdapter
import com.moneytask.ledger.capture.NotificationParser
import com.moneytask.ledger.capture.RawNotification
import com.moneytask.ledger.capture.Transaction
import com.moneytask.ledger.capture.TxnSource
import com.moneytask.ledger.capture.TxnType
import com.moneytask.ledger.db.AppDatabase
import com.moneytask.ledger.db.DatabaseSeeder
import com.moneytask.ledger.db.LedgerDao
import com.moneytask.ledger.db.LedgerMappers.toDomain
import com.moneytask.ledger.db.LedgerMappers.toEntity
import com.moneytask.ledger.db.RoomLedgerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

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

    // ---- 补录 / 复核（手动记账入口） ----

    /** 待复核账目流（自动落账但方向/账户不足、需人工确认）。 */
    val pendingReview: Flow<List<Transaction>> =
        dao.observePendingReview().map { list -> list.map { it.toDomain() } }

    /** 可选账户池（Core 领域模型），供补录/复核 UI 选择。 */
    val accounts: List<Account> get() = store.accounts()

    /** 某方向下的分类池（按 sortOrder 排序），供补录/复核 UI 选择。 */
    fun categories(type: TxnType): List<Category> = runBlocking(io) {
        dao.categories(type.name).map { Category(it.id, it.name, TxnType.valueOf(it.type), it.icon, it.isSystemDefault) }
    }

    /** 手动补录一笔账目（source=MANUAL、manuallyEdited=true）。 */
    fun manualAdd(
        amountFen: Long,
        type: TxnType,
        merchant: String?,
        accountId: String?,
        categoryId: String?,
        note: String? = null,
    ): Transaction {
        val now = System.currentTimeMillis()
        val tx = Transaction(
            id = "manual-${UUID.randomUUID().toString().take(8)}",
            amountFen = amountFen,
            type = type,
            time = now,
            accountId = accountId,
            categoryId = categoryId,
            merchant = merchant,
            paymentMethod = null,
            note = note,
            groupId = null,
            source = TxnSource.MANUAL,
            isPendingReview = false,
            manuallyEdited = true,
            createdAt = now,
            updatedAt = now,
        )
        runBlocking(io) { dao.insert(tx.toEntity()) }
        return tx
    }

    /** 复核确认：清除待复核标记，可选修正分类/账户，标记人工处理过。 */
    fun confirmReview(id: String, categoryId: String? = null, accountId: String? = null): Boolean =
        runBlocking(io) {
            val ent = dao.findById(id) ?: return@runBlocking false
            dao.update(
                ent.copy(
                    isPendingReview = false,
                    manuallyEdited = true,
                    categoryId = categoryId ?: ent.categoryId,
                    accountId = accountId ?: ent.accountId,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            true
        }

    /** 删除一笔账目（软删，便于误操作恢复/导出前离线审计）。 */
    fun deleteTransaction(id: String) = runBlocking(io) {
        dao.softDeleteById(id, System.currentTimeMillis())
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
                "com.cmbchina.ccd.pluto.cmbActivity", "招商银行", "招商银行",
                "信用卡通知：您尾号1356的招行信用卡消费42.10人民币。",
                System.currentTimeMillis() + 3_000,
            )
        )
    }
}
