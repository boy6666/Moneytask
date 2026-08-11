package com.moneytask.ledger.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    // ---- 账目 ----

    /** 落账。groupId 唯一索引（DB 层）兜底：同一交易组重复插入会冲突，由上层先查后写。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tx: TransactionEntity)

    /** 复核/手动改账后整行更新。 */
    @Update
    suspend fun update(tx: TransactionEntity)

    /** 软删除：置 deletedAt（UI 删除、复核移除），不再被各类查询命中。 */
    @Query("UPDATE transactions SET deletedAt = :now WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDeleteById(id: String, now: Long): Int

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

    /** 待复核账目（自动落账但方向/账户信息不足）。 */
    @Query("SELECT * FROM transactions WHERE isPendingReview = 1 AND deletedAt IS NULL ORDER BY time DESC")
    fun observePendingReview(): Flow<List<TransactionEntity>>

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

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun categoriesAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder, name")
    suspend fun categories(type: String): List<CategoryEntity>

    // ---- 备份 / 恢复 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(txs: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    @Query("DELETE FROM accounts")
    suspend fun deleteAllAccounts()

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()

    // ---- 账户管理 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(a: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccountById(id: String)

    @Query("UPDATE accounts SET isSystemDefault = 0")
    suspend fun clearAccountDefaults()

    @Query("UPDATE accounts SET isSystemDefault = 1 WHERE id = :id")
    suspend fun setAccountDefault(id: String)

    // ---- 统计聚合 ----

    /** 区间内按方向汇总（支出/收入）。:isPending 排除待复核，仅计已确认账目。 */
    @Query("SELECT COALESCE(type,'') AS `key`, SUM(amountFen) AS total FROM transactions " +
        "WHERE deletedAt IS NULL AND isPendingReview = 0 AND time BETWEEN :start AND :end GROUP BY type")
    suspend fun sumByType(start: Long, end: Long): List<SumRow>

    /** 区间内支出按分类汇总（用于分类占比）。 */
    @Query("SELECT COALESCE(categoryId,'') AS `key`, SUM(amountFen) AS total FROM transactions " +
        "WHERE deletedAt IS NULL AND isPendingReview = 0 AND type='EXPENSE' AND time BETWEEN :start AND :end " +
        "GROUP BY categoryId ORDER BY total DESC")
    suspend fun expenseByCategory(start: Long, end: Long): List<SumRow>

    /** 区间内按账户汇总。 */
    @Query("SELECT COALESCE(accountId,'') AS `key`, SUM(amountFen) AS total FROM transactions " +
        "WHERE deletedAt IS NULL AND isPendingReview = 0 AND time BETWEEN :start AND :end " +
        "GROUP BY accountId ORDER BY total DESC")
    suspend fun sumByAccount(start: Long, end: Long): List<SumRow>

    /** 区间内按天汇总收支。用 SQLite date() 按本地时区切天（不含毫秒），供趋势图使用。 */
    @Query("SELECT date(time/1000, 'unixepoch', 'localtime') AS day, " +
        "SUM(CASE WHEN type='EXPENSE' THEN amountFen ELSE 0 END) AS expenseFen, " +
        "SUM(CASE WHEN type='INCOME' THEN amountFen ELSE 0 END) AS incomeFen " +
        "FROM transactions WHERE deletedAt IS NULL AND isPendingReview = 0 AND time BETWEEN :start AND :end " +
        "GROUP BY day ORDER BY day ASC")
    suspend fun dailySum(start: Long, end: Long): List<DayStat>
}
