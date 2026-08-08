package com.moneytask.ledger.capture

/**
 * 落账器：把链路归并的 [Conclusion] 写成一笔 [Transaction]。
 *
 * 这是「绝不重复记账」的**最终闸门**：
 *  - 同一 [Conclusion.groupId] 只落一笔（由 [LedgerStore] 幂等保证）；
 *  - 自动分类（[AutoCategorizer]）；
 *  - 方向/账户信息不足时标 [Transaction.isPendingReview]（供人工复核，不阻塞自动记账）。
 *
 * Android 层把 [persist] 放进 Room @Transaction（RawCapture→Group→Transaction 原子写）。
 */
class LedgerWriter(
    private val store: LedgerStore,
    private val categorizer: AutoCategorizer = AutoCategorizer(),
    private val idGen: (String) -> String = { gid -> "tx-$gid" },
) {
    private var nowMs: Long = 0L

    /** 注入当前时刻；不注入时用本机时钟。 */
    fun setNow(now: Long) { nowMs = now }

    /**
     * @return 成功落账的账目；若该 groupId 已落账则返回 null（幂等，绝不重复）。
     */
    fun persist(conclusion: Conclusion): Transaction? {
        if (conclusion.groupId.isBlank()) return null
        if (store.existsByGroupId(conclusion.groupId)) return null

        val type = when (conclusion.direction) {
            Direction.INCOME -> TxnType.INCOME
            else -> TxnType.EXPENSE
        }
        val now = if (nowMs > 0) nowMs else System.currentTimeMillis()
        val pending = conclusion.direction == Direction.UNKNOWN || conclusion.accountId == null
        val tx = Transaction(
            id = idGen(conclusion.groupId),
            amountFen = conclusion.amountFen,
            type = type,
            time = conclusion.time,
            accountId = conclusion.accountId,
            categoryId = categorizer.categorize(conclusion.merchant, type),
            merchant = conclusion.merchant,
            paymentMethod = conclusion.paymentMethod,
            note = null,
            groupId = conclusion.groupId,
            source = TxnSource.AUTO,
            isPendingReview = pending,
            createdAt = now,
            updatedAt = now,
        )
        store.insert(tx)
        return tx
    }
}
