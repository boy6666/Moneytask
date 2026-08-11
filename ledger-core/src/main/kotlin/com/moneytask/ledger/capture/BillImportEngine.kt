package com.moneytask.ledger.capture

/**
 * 账单导入引擎：把 [BillCsvParser] 解析出的行并入既有账本。
 *
 * 去重（用户约定）：无交易单号，按「金额 + 时间(容差窗口) + 商户」判定是否已存在。
 *  - 命中 → **覆盖更新**：补全商户/分类并清除待复核，但绝不重复新增；不动金额/时间/账户。
 *  - 未命中 → **新增**：自动分类、按平台归因账户。
 *
 * 纯 JVM、无 I/O——持久化由调用方（Android Room）执行。
 */
class BillImportEngine(
    private val categorizer: AutoCategorizer = AutoCategorizer(),
    private val idGen: (Int) -> String = { "bill-import-$it" },
    /** 时间匹配容差窗口：同窗口 + 同金额 + 同商户 视为同一笔。 */
    private val timeWindowMs: Long = 600_000L,
) {

    /** 导入结果：待插入/待更新的账目（由调用方持久化），及计数。 */
    data class BillImportResult(
        val added: List<Transaction>,
        val updated: List<Transaction>,
        val totalRows: Int,
        /** 已存在且无需改动（未重复记，也未覆盖）的行数。 */
        val matchedLocked: Int,
    )

    fun run(
        rows: List<BillRow>,
        existing: List<Transaction>,
        accounts: List<Account>,
        now: Long,
    ): BillImportResult {
        val byKey = HashMap<String, MutableList<Transaction>>()
        existing.forEach { t ->
            byKey.getOrPut(keyOf(t.amountFen, t.time, t.merchant)) { mutableListOf() }.add(t)
        }

        val added = mutableListOf<Transaction>()
        val updated = mutableListOf<Transaction>()
        var matchedLocked = 0
        var seq = 0

        for (row in rows) {
            val k = keyOf(row.amountFen, row.time, row.merchant)
            val match = byKey[k]?.firstOrNull()
            if (match != null) {
                val touch = touchExisting(match, row, now)
                if (touch != null) updated.add(touch) else matchedLocked++
            } else {
                added.add(buildNew(row, accounts, now, idGen(seq++)))
            }
        }
        return BillImportResult(added, updated, rows.size, matchedLocked)
    }

    // ---- 唯一键 ----

    private fun keyOf(amountFen: Long, time: Long, merchant: String?): String =
        "$amountFen|${bucket(time)}|${normMerchant(merchant)}"

    private fun bucket(time: Long): Long = time - (time % timeWindowMs)

    private fun normMerchant(m: String?): String = m?.trim()?.replace(Regex("\\s+"), "") ?: ""

    // ---- 覆盖更新（补全，不覆盖已有非空字段、不动账户/金额/时间） ----

    private fun touchExisting(existing: Transaction, row: BillRow, now: Long): Transaction? {
        val merchant = existing.merchant ?: row.merchant
        val category = existing.categoryId
            ?: categorizer.categorize(row.merchant ?: existing.merchant, existing.type)
        val clearPending = existing.isPendingReview
        if (merchant == existing.merchant && category == existing.categoryId && !clearPending) return null
        return existing.copy(
            merchant = merchant,
            categoryId = category,
            isPendingReview = false,
            updatedAt = now,
        )
    }

    // ---- 新增 ----

    private fun buildNew(row: BillRow, accounts: List<Account>, now: Long, id: String): Transaction {
        val category = categorizer.categorize(row.merchant, row.type)
            ?: if (row.type == TxnType.INCOME) "income_其他收入" else "expense_其他"
        return Transaction(
            id = id,
            amountFen = row.amountFen,
            type = row.type,
            time = row.time,
            accountId = accountFor(row, accounts),
            categoryId = category,
            merchant = row.merchant,
            paymentMethod = null,
            note = "导入账单(${row.platform.name.lowercase()})",
            groupId = null,
            source = TxnSource.IMPORT,
            isPendingReview = false,
            manuallyEdited = false,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun accountFor(row: BillRow, accounts: List<Account>): String? {
        return when (row.platform) {
            BillPlatform.WECHAT -> accounts.firstOrNull { it.type == AccountType.WECHAT }?.id
            BillPlatform.ALIPAY -> accounts.firstOrNull { it.type == AccountType.ALIPAY }?.id
            BillPlatform.BANK -> accounts.firstOrNull { it.type == AccountType.BANK }?.id
            BillPlatform.UNKNOWN -> null
        } ?: accounts.firstOrNull { it.isDefault }?.id
    }
}
