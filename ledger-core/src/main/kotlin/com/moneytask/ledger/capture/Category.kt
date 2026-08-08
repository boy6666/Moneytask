package com.moneytask.ledger.capture

/** 分类（《MVP技术设计》3.2 领域模型，纯 JVM）。 */
data class Category(
    val id: String,
    val name: String,
    val type: TxnType,
    val icon: String = "",
    val isSystemDefault: Boolean = true,
)

/** 启动预置的默认分类（同 RealtimeLedger DatabaseSeeder，MIT）。 */
object DefaultCategories {
    private fun exp(name: String, icon: String = "") = Category("expense_$name", name, TxnType.EXPENSE, icon)
    private fun inc(name: String, icon: String = "") = Category("income_$name", name, TxnType.INCOME, icon)

    val all: List<Category> = listOf(
        exp("餐饮", "🍜"), exp("购物", "🛍️"), exp("交通", "🚕"), exp("居住", "🏠"),
        exp("水电燃气", "💡"), exp("通讯", "📱"), exp("娱乐", "🎮"), exp("医疗", "🏥"),
        exp("教育", "📚"), exp("人情", "🎁"), exp("旅行", "✈️"), exp("数码", "💻"),
        exp("办公", "🖥️"), exp("订阅服务", "🔁"), exp("其他", "📦"),
        inc("工资", "💰"), inc("奖金", "🏆"), inc("经营收入", "🏪"), inc("退款", "↩️"),
        inc("转账收入", "🔁"), inc("其他收入", "📥"),
    )

    fun byId(id: String): Category? = all.firstOrNull { it.id == id }
}
