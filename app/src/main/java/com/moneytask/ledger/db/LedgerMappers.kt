package com.moneytask.ledger.db

import com.moneytask.ledger.capture.Account
import com.moneytask.ledger.capture.AccountType
import com.moneytask.ledger.capture.Transaction
import com.moneytask.ledger.capture.TxnSource
import com.moneytask.ledger.capture.TxnType

/** 领域模型 ↔ Room 实体 映射（ledger-core 的纯 JVM 模型 ↔ Android 持久层）。 */
object LedgerMappers {

    fun Transaction.toEntity() = TransactionEntity(
        id = id,
        amountFen = amountFen,
        type = type.name,
        time = time,
        accountId = accountId,
        categoryId = categoryId,
        merchant = merchant,
        paymentMethod = paymentMethod,
        note = note,
        groupId = groupId,
        source = source.name,
        isPendingReview = isPendingReview,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun TransactionEntity.toDomain(): Transaction = Transaction(
        id = id,
        amountFen = amountFen,
        type = if (type == "INCOME") TxnType.INCOME else TxnType.EXPENSE,
        time = time,
        accountId = accountId,
        categoryId = categoryId,
        merchant = merchant,
        paymentMethod = paymentMethod,
        note = note,
        groupId = groupId,
        source = if (source == "MANUAL") TxnSource.MANUAL else TxnSource.AUTO,
        isPendingReview = isPendingReview,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun AccountEntity.toDomain(): Account = Account(
        id = id,
        name = name,
        type = AccountType.valueOf(type),
        bankTail = bankTail,
        isDefault = isSystemDefault,
    )
}
