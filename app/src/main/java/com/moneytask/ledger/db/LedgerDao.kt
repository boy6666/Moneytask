package com.moneytask.ledger.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    // ---- 账目 ----

    /** 落账。groupId 唯一索引（DB 层）兜底：同一交易组重复插入会冲突，由上层先查后写。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tx: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id AND deletedAt IS NULL")
    suspend fun findById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE groupId = :groupId AND deletedAt IS NULL LIMIT 1")
    suspend fun findByGroupId(groupId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY time DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE deletedAt IS NULL")
    suspend fun count(): Int

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<TransactionEntity>>

    // ---- 账户 / 分类（供 DatabaseSeeder 与归因引擎读取） ----

    @Query("SELECT * FROM accounts ORDER BY isSystemDefault DESC, name")
    suspend fun accounts(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccounts(accounts: List<AccountEntity>)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun accountCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int
}
