package com.moneytask.ledger.db

import com.moneytask.ledger.capture.Account
import com.moneytask.ledger.capture.LedgerStore
import com.moneytask.ledger.capture.Transaction
import com.moneytask.ledger.db.LedgerMappers.toDomain
import com.moneytask.ledger.db.LedgerMappers.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking

/**
 * 以 Room 为背板的 [LedgerStore]。
 *
 * ledger-core 的 [LedgerStore] 是同步接口（便于纯 JVM 单测）；这里用 runBlocking 桥接到 Room
 * 的 suspend DAO。调用方（CapturePipeline / 预置）都在 IO 调度上，不会阻塞主线程。
 */
class RoomLedgerStore(
    private val dao: LedgerDao,
    private val ioDispatcher: CoroutineDispatcher,
) : LedgerStore {

    override fun insert(tx: Transaction) = runBlocking(ioDispatcher) {
        dao.insert(tx.toEntity())
    }

    override fun findById(id: String): Transaction? = runBlocking(ioDispatcher) {
        dao.findById(id)?.toDomain()
    }

    override fun findByGroupId(groupId: String): Transaction? = runBlocking(ioDispatcher) {
        dao.findByGroupId(groupId)?.toDomain()
    }

    override fun all(): List<Transaction> = runBlocking(ioDispatcher) {
        dao.all().map { it.toDomain() }
    }

    override fun count(): Int = runBlocking(ioDispatcher) { dao.count() }

    override fun existsByGroupId(groupId: String): Boolean = runBlocking(ioDispatcher) {
        dao.findByGroupId(groupId) != null
    }

    // ---- 归因引擎需要的账户池 ----

    fun accounts(): List<Account> = runBlocking(ioDispatcher) {
        dao.accounts().map { it.toDomain() }
    }

    fun saveAccounts(list: List<AccountEntity>) = runBlocking(ioDispatcher) {
        dao.insertAccounts(list)
    }
}
