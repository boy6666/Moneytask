package com.moneytask.ledger.db

import com.moneytask.ledger.capture.DefaultCategories

/** 启动预置：默认分类 + 默认账户（现金/招商银行/其他）。幂等——只在对应表为空时写入。 */
object DatabaseSeeder {

    suspend fun seed(dao: LedgerDao, now: Long) {
        seedCategories(dao, now)
        seedAccounts(dao, now)
    }

    private suspend fun seedCategories(dao: LedgerDao, now: Long) {
        if (dao.categoryCount() > 0) return
        dao.insertCategories(DefaultCategories.all.map { c ->
            CategoryEntity(
                id = c.id,
                name = c.name,
                type = c.type.name,
                icon = c.icon,
                isSystemDefault = c.isSystemDefault,
                createdAt = now,
                updatedAt = now,
            )
        })
    }

    private suspend fun seedAccounts(dao: LedgerDao, now: Long) {
        if (dao.accountCount() > 0) return
        dao.insertAccounts(listOf(
            AccountEntity("acc_cash", "现金", "CASH", null, isSystemDefault = true, now, now),
            AccountEntity("acc_bank_cmb", "招商银行", "BANK", null, isSystemDefault = true, now, now),
            AccountEntity("acc_other", "其他", "OTHER", null, isSystemDefault = false, now, now),
        ))
    }
}
