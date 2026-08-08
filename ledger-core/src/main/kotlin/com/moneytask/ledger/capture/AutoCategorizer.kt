package com.moneytask.ledger.capture

/**
 * 分类规则（《MVP技术设计》3.6）。
 * @param keyword  匹配关键词（越长越具体，通常在 [priority] 里体现）。
 * @param priority 越大越优先。规则按 merchant 子串命中，取命中项中 priority 最高者。
 */
data class CategoryRule(
    val keyword: String,
    val categoryId: String,
    val priority: Int,
)

/**
 * 本地智能自动分类器（纯 JVM）。按商户名命中最优规则。
 *
 * 设计要点：规则与分类解耦——删分类不丢规则；命中优先级 = 关键词越具体越优先。
 * MVP 用静态默认规则，接口预留后期自学习（Phase 4）。
 */
class AutoCategorizer(
    rules: List<CategoryRule> = defaultRules,
) {
    private val rules = rules

    /** 归因：根据商户与方向返回 [Category] id（无命中返回 null，由调用方落默认分类）。 */
    fun categorize(merchant: String?, type: TxnType): String? {
        if (merchant.isNullOrBlank()) return null
        // 退款：方向即分类（收入·退款）
        if (type == TxnType.INCOME && listOf("退款", "退货", "原路退回").any(merchant::contains))
            return "income_退款"
        return rules.filter { merchant.contains(it.keyword) }.maxByOrNull { it.priority }?.categoryId
    }

    companion object {
        /** 默认规则（关键词越具体 priority 越高）。 */
        val defaultRules = listOf(
            CategoryRule("滴滴", "expense_交通", 30),
            CategoryRule("高德打车", "expense_交通", 25),
            CategoryRule("地铁", "expense_交通", 20),
            CategoryRule("公交", "expense_交通", 20),
            CategoryRule("12306", "expense_旅行", 25),
            CategoryRule("携程", "expense_旅行", 25),
            CategoryRule("去哪儿", "expense_旅行", 25),
            CategoryRule("飞猪", "expense_旅行", 25),
            CategoryRule("古茗", "expense_餐饮", 30),
            CategoryRule("瑞幸", "expense_餐饮", 25),
            CategoryRule("肯德基", "expense_餐饮", 25),
            CategoryRule("麦当劳", "expense_餐饮", 25),
            CategoryRule("星巴克", "expense_餐饮", 25),
            CategoryRule("美团外卖", "expense_餐饮", 25),
            CategoryRule("美团", "expense_餐饮", 10),       // 美团兜底(外卖/买菜居多)
            CategoryRule("饿了么", "expense_餐饮", 25),
            CategoryRule("京东", "expense_购物", 20),
            CategoryRule("淘宝", "expense_购物", 20),
            CategoryRule("拼多多", "expense_购物", 20),
            CategoryRule("天猫", "expense_购物", 20),
            CategoryRule("唯品会", "expense_购物", 20),
            CategoryRule("苹果", "expense_数码", 15),
            CategoryRule("小米", "expense_数码", 15),
            CategoryRule("58到家", "expense_居住", 15),
            CategoryRule("自如", "expense_居住", 15),
            CategoryRule("电费", "expense_水电燃气", 20),
            CategoryRule("水费", "expense_水电燃气", 20),
            CategoryRule("燃气", "expense_水电燃气", 20),
            CategoryRule("中国移动", "expense_通讯", 20),
            CategoryRule("中国联通", "expense_通讯", 20),
            CategoryRule("中国电信", "expense_通讯", 20),
            CategoryRule("爱奇艺", "expense_订阅服务", 25),
            CategoryRule("腾讯视频", "expense_订阅服务", 25),
            CategoryRule("优酷", "expense_订阅服务", 25),
            CategoryRule("bilibili", "expense_订阅服务", 30),
            CategoryRule("网飞", "expense_订阅服务", 25),
            CategoryRule("医院", "expense_医疗", 25),
            CategoryRule("药房", "expense_医疗", 25),
            CategoryRule("电影", "expense_娱乐", 15),
            CategoryRule("游戏", "expense_娱乐", 15),
        )
    }
}
