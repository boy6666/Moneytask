package com.moneytask.ledger.capture

/**
 * 账目持久化抽象。核心模块只依赖该接口（纯 JVM、可无头测试）；
 * Android 应用层提供 Room 实现（原子上：RawCapture→Group→Transaction 同一 @Transaction）。
 */
interface LedgerStore {
    /** 写入一笔账目；[insert] 需幂等（同 [Transaction.groupId] 不得重复落账）。 */
    fun insert(tx: Transaction)

    fun findById(id: String): Transaction?
    fun findByGroupId(groupId: String): Transaction?
    fun all(): List<Transaction>
    fun count(): Int

    /** 按 [Transaction.groupId] 查询是否已落账（用于绝不重复记账的最终闸门）。 */
    fun existsByGroupId(groupId: String): Boolean
}

/** 纯内存实现：供核心逻辑单测与脚手架演示，无磁盘副作用。 */
class InMemoryLedgerStore : LedgerStore {
    private val byId = LinkedHashMap<String, Transaction>()
    private val byGroup = HashMap<String, String>() // groupId -> txId

    override fun insert(tx: Transaction) {
        if (byId.containsKey(tx.id)) return
        byId[tx.id] = tx
        tx.groupId?.let { byGroup[it] = tx.id }
    }

    override fun findById(id: String): Transaction? = byId[id]
    override fun findByGroupId(groupId: String): Transaction? =
        byGroup[groupId]?.let { byId[it] }

    override fun all(): List<Transaction> = byId.values.toList()
    override fun count(): Int = byId.size
    override fun existsByGroupId(groupId: String): Boolean = byGroup.containsKey(groupId)
}
