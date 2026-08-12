package com.moneytask.ledger

import android.content.Context
import android.net.Uri
import com.moneytask.ledger.capture.Account
import com.moneytask.ledger.capture.BillCsvParser
import com.moneytask.ledger.capture.BillImportEngine
import com.moneytask.ledger.capture.CapturePipeline
import com.moneytask.ledger.capture.Category
import com.moneytask.ledger.capture.ParsedBill
import com.moneytask.ledger.capture.CorrelationEngine
import com.moneytask.ledger.capture.LedgerWriter
import com.moneytask.ledger.capture.NotificationAdapter
import com.moneytask.ledger.capture.NotificationParser
import com.moneytask.ledger.capture.RawNotification
import com.moneytask.ledger.capture.Transaction
import com.moneytask.ledger.capture.TxnSource
import com.moneytask.ledger.capture.TxnType
import com.moneytask.ledger.db.AccountEntity
import com.moneytask.ledger.db.AppDatabase
import com.moneytask.ledger.db.BackupManager
import com.moneytask.ledger.db.DatabaseSeeder
import com.moneytask.ledger.db.LedgerDao
import com.moneytask.ledger.db.LedgerMappers.toDomain
import com.moneytask.ledger.db.LedgerMappers.toEntity
import com.moneytask.ledger.db.RoomLedgerStore
import com.moneytask.ledger.db.DayStat
import com.moneytask.ledger.db.PeriodEntity
import com.moneytask.ledger.db.SettingEntity
import com.moneytask.ledger.db.SumRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 手动 DI 容器：装配数据库、预置数据、归因引擎、落账器与采集管线。
 * MVP 阶段不引入 Hilt，保持依赖尽量少；后续可平滑替换为 Hilt 模块。
 */
class AppContainer(private val context: Context) {
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

    // ---- 备份 / 恢复 ----

    /** 全量导出为 JSON 文件（应用私有 external files，可用 FileProvider 分享）。 */
    fun exportBackup(): File = BackupManager.export(context, dao)

    /** 导出文件对应的可分享 Uri。 */
    fun backupShareUri(file: File): Uri = BackupManager.shareUri(context, file)

    /** 从系统文件选择器选中 Uri 整库恢复，返回恢复的账目条数。 */
    fun importBackup(uri: Uri): Int = BackupManager.import(context, dao, uri)

    // ---- 账单导入 ----

    /** 读取并解析账单 URI → [ParsedBill]（供 UI 预览；不落库）。 */
    fun parseBillUri(uri: Uri): ParsedBill = runBlocking(io) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("无法读取账单文件")
        BillCsvParser.parse(text)
    }

    /**
     * 把已解析且用户确认的账单并入账本：
     * 按「金额+时间+商户」去重——已存在则覆盖补全（清除待复核），否则新增。
     * @return [BillImportEngine.BillImportResult]，其中 added/updated 已持久化。
     */
    fun commitBillImport(bill: ParsedBill): BillImportEngine.BillImportResult = runBlocking(io) {
        val existing = dao.all().map { it.toDomain() }
        val result = BillImportEngine().run(bill.rows, existing, store.accounts(), System.currentTimeMillis())
        result.added.forEach { dao.insert(it.toEntity()) }
        result.updated.forEach { dao.update(it.toEntity()) }
        result
    }

    // ---- 统计 ----

    /** 当前自然月的 [起始时间, 结束时间) 毫秒区间。 */
    fun monthRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val ld = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val start = ld.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = ld.plusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return start to end
    }

    fun sumByType(start: Long, end: Long): List<SumRow> = runBlocking(io) { dao.sumByType(start, end) }

    fun expenseByCategory(start: Long, end: Long): List<SumRow> = runBlocking(io) { dao.expenseByCategory(start, end) }

    fun sumByAccount(start: Long, end: Long): List<SumRow> = runBlocking(io) { dao.sumByAccount(start, end) }

    /** 按天汇总收支（供趋势图）。 */
    fun dailySum(start: Long, end: Long): List<DayStat> = runBlocking(io) { dao.dailySum(start, end) }

    /**
     * 近 N 个自然日的 [起始, 次日0点) 毫秒区间（含今天）。
     * 例如 lastDaysRange(7) 返回 [6 天前 0 点, 今天 24 点)。
     */
    fun lastDaysRange(days: Int, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val start = today.minusDays((days - 1).toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return start to end
    }

    /**
     * 把近 N 天的按天汇总补成连续天数序列（无账日的补 0），供柱状图/折线图按序绘制。
     * @return 每个元素的 day 为该日 "MM/dd" 标签。
     */
    fun dailySeries(days: Int, now: Long = System.currentTimeMillis()): List<Triple<String, Long, Long>> {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val (start, end) = lastDaysRange(days, now)
        val map = dailySum(start, end).associate { it.day to it }
        return (0 until days).map { offset ->
            val d = today.minusDays((days - 1 - offset).toLong())
            val key = d.toString()
            val stat = map[key]
            Triple(d.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")),
                stat?.expenseFen ?: 0L, stat?.incomeFen ?: 0L)
        }
    }

    /** 分类 id → (图标, 名称)，供报表把聚合 key 还原成可读名称。 */
    fun categoriesById(): Map<String, Pair<String, String>> = runBlocking(io) {
        dao.categoriesAll().associate { it.id to (it.icon to it.name) }
    }

    /** 账户 id → 名称，供报表还原账户聚合。 */
    fun accountsById(): Map<String, String> = runBlocking(io) {
        dao.accounts().associate { it.id to it.name }
    }

    /** 当前有效账目条数（含自动与手动）。 */
    fun transactionCount(): Int = runBlocking(io) { dao.count() }

    // ---- 月度预算（v3 设置表） ----

    /** 月度预算的设置键，值为「分」的字符串。 */
    companion object { const val KEY_MONTHLY_BUDGET_FEN = "monthly_budget_fen" }

    /** 当前月度预算（分）；未设置返回 null。 */
    fun monthlyBudgetFen(): Long? = runBlocking(io) {
        dao.getSetting(KEY_MONTHLY_BUDGET_FEN)?.toLongOrNull()
    }

    /** 设置/更新月度预算（分）。传 0 视为清除。 */
    fun setMonthlyBudgetFen(fen: Long) = runBlocking(io) {
        if (fen <= 0) dao.putSetting(SettingEntity(KEY_MONTHLY_BUDGET_FEN, "", System.currentTimeMillis()))
        else dao.putSetting(SettingEntity(KEY_MONTHLY_BUDGET_FEN, fen.toString(), System.currentTimeMillis()))
    }

    // ---- 时段（寒假/暑假/学期，v3） ----

    /** 全部时段，按起始时间升序（供报表范围选择与「我的」页管理）。 */
    val periodsFlow: Flow<List<PeriodEntity>> = dao.observePeriods()

    /** 新增一个时段；起止必须构成有效区间。返回是否成功。 */
    fun addPeriod(name: String, type: String, startMillis: Long, endMillis: Long): Boolean =
        runBlocking(io) {
            if (name.isBlank() || endMillis <= startMillis) return@runBlocking false
            val now = System.currentTimeMillis()
            dao.insertPeriod(
                PeriodEntity("period_${UUID.randomUUID().toString().take(8)}", name.trim(), type, startMillis, endMillis, now)
            )
            true
        }

    fun deletePeriod(id: String) = runBlocking(io) { dao.deletePeriod(id) }

    /** 区间内的支出合计（分），用于同类时段对比。 */
    fun expenseBetween(start: Long, end: Long): Long = runBlocking(io) {
        dao.sumByType(start, end).firstOrNull { it.key == "EXPENSE" }?.total ?: 0L
    }

    // ---- 账户管理 ----

    /** 账户池（含 bankTail/isSystemDefault，供管理页展示）。 */
    val accountsDb: List<AccountEntity> get() = runBlocking(io) { dao.accounts() }

    /** 新增账户（type 取 CASH/BANK/WECHAT/ALIPAY/OTHER）。 */
    fun addAccount(name: String, type: String): Boolean = runBlocking(io) {
        if (name.isBlank()) return@runBlocking false
        val now = System.currentTimeMillis()
        dao.insertAccount(
            AccountEntity("acc_c_${UUID.randomUUID().toString().take(8)}", name.trim(), type, null, false, now, now)
        )
        true
    }

    fun deleteAccount(id: String) = runBlocking(io) { dao.deleteAccountById(id) }

    /** 设某账户为默认（先清空全部默认标记，再置该账户）。 */
    fun setDefaultAccount(id: String) = runBlocking(io) {
        dao.clearAccountDefaults()
        dao.setAccountDefault(id)
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
