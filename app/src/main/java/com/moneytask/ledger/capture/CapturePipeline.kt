package com.moneytask.ledger.capture

import com.moneytask.ledger.capture.Transaction
import com.moneytask.ledger.db.LedgerDao
import com.moneytask.ledger.db.LedgerMappers.toDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 采集管线：把系统通知一条条喂进去，最终落到账本。
 *
 * ```
 * RawNotification → NotificationParser → NotificationAdapter.toCaptureEvent
 *   → CorrelationEngine.onEvent（归并） → settleExpired（定时结算）
 *   → LedgerWriter.persist（幂等落账） → RoomLedgerStore → Room
 * ```
 *
 * 在 Android 层由 [com.moneytask.ledger.service.PaymentNotificationListenerService] 调用
 * [onNotification]；启动时开启定期结算协程（因为链式通知有先后，需等落账倒计时走完再结算）。
 */
class CapturePipeline(
    private val parser: NotificationParser = NotificationParser(),
    private val engine: CorrelationEngine,
    private val writer: LedgerWriter,
    private val dao: LedgerDao,
    private val scope: CoroutineScope,
    private val settleIntervalMs: Long = 15_000L,
) {
    private var settleJob: Job? = null

    fun start() {
        if (settleJob?.isActive == true) return
        settleJob = scope.launch {
            while (true) {
                delay(settleIntervalMs)
                flushSettled()
            }
        }
    }

    fun stop() {
        settleJob?.cancel()
        settleJob = null
    }

    /**
     * 接收一条系统通知。
     * @return true 表示被解析为有效交易并进入管线。
     */
    fun onNotification(raw: RawNotification): Boolean {
        // 来源白名单：只处理可信支付/银行/商户渠道，其余通知直接丢弃（省电 + 防误采）。
        if (!SupportedSources.isSupported(raw.sourcePackage)) return false
        val parsed = parser.parse(raw)
        if (!parsed.matched || parsed.amountFen == null) return false
        val accepted = engine.onEvent(NotificationAdapter.toCaptureEvent(raw, parsed))
        if (accepted) flushSettled()
        return accepted
    }

    /** 把已到期的组结算并落账（由归并结论生成账目，绝不重复）。 */
    private fun flushSettled() {
        val now = System.currentTimeMillis()
        engine.settleExpired(now).forEach { writer.persist(it) }
    }

    /** 供 UI 观察最近账目的数据流。 */
    val recentTransactions: Flow<List<Transaction>> =
        dao.observeRecent().map { list -> list.map { it.toDomain() } }
}
