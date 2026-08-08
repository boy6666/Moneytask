package com.moneytask.ledger.capture

/** 账户类型。与《MVP技术设计》3.1 一致。 */
enum class AccountType { CASH, BANK, WECHAT, ALIPAY, OTHER }

/**
 * 记账账户（纯本地）。
 *
 * @param bankTail 卡尾号（4 位），用于把银行通知解析出的卡尾号反查到具体银行卡账户。
 */
data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val bankTail: String? = null,
    val isDefault: Boolean = false,
)
