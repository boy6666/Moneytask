package com.moneytask.ledger.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 账户（《MVP技术设计》3.1）。ID 用稳定字符串，便于与 ledger-core 领域模型映射。 */
@Entity(tableName = "accounts", indices = [Index(value = ["bankTail"], unique = true)])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val bankTail: String?,
    val isSystemDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 分类（《MVP技术设计》3.2）。sortOrder 为 v2 迁移新增（分类排序）。 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val icon: String = "",
    val isSystemDefault: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 账目（《MVP技术设计》3.3）。与 ledger-core 的 [com.moneytask.ledger.capture.Transaction] 对应。
 * groupId 唯一索引兜底「同一交易组只落一笔」（绝不重复记账的持久层保险）。
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index("groupId"),
        Index("time"),
        Index("accountId"),
        Index("categoryId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val amountFen: Long,
    val type: String,
    val time: Long,
    val accountId: String?,
    val categoryId: String?,
    val merchant: String?,
    val paymentMethod: String?,
    val note: String?,
    val groupId: String?,
    val source: String,
    val isPendingReview: Boolean,
    /** v2 迁移新增：是否被人工修改/复核过（手动补录或复核落库后为 true）。 */
    val manuallyEdited: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** 聚合查询结果行：key 为分类/账户/方向 id，total 为金额（分）。 */
data class SumRow(val key: String, val total: Long)

/** 按天分组汇总：day 为 "YYYY-MM-DD"（受时区影响），expenseFen/incomeFen 单位分。 */
data class DayStat(val day: String, val expenseFen: Long, val incomeFen: Long)
