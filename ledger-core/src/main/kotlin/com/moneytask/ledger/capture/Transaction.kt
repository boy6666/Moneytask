package com.moneytask.ledger.capture

/** 账目类型（《MVP技术设计》3.3 type）。 */
enum class TxnType { EXPENSE, INCOME }

/** 账目来源（《MVP技术设计》3.3 source）。 */
enum class TxnSource { AUTO, MANUAL, IMPORT }

/**
 * 用户可见的一笔账目（《MVP技术设计》3.3 领域模型）。
 *
 * 由 [Conclusion]（链路归并结论）落账而来，或由手动录入产生。本类为纯 JVM 领域模型，
 * 不含 Room 注解——Room 实体与 Migration 放在 Android 应用层，这里只定义结构以便无头测试整条管线。
 */
data class Transaction(
    val id: String,
    val amountFen: Long,
    val type: TxnType,
    val time: Long,
    val accountId: String?,
    val categoryId: String?,
    val merchant: String?,
    val paymentMethod: String?,
    val note: String?,
    val groupId: String?,
    val source: TxnSource,
    val isPendingReview: Boolean = false,
    val manuallyEdited: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
