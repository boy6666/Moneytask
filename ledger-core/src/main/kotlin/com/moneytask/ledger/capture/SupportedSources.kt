package com.moneytask.ledger.capture

/**
 * 采集来源白名单（supported_sources）。
 *
 * 只放行可信的支付 / 银行 / 常用商户渠道包名；白名单之外的通知（短信、系统提示、
 * 无关 App）一律丢弃，既省去解析开销，也避免误采——与"纯本地、只专注交易通知"的目标一致。
 *
 * 判定以来源包名为准（而非展示名），因为包名稳定、不会随应用显示名变化。
 * 默认集合为常用渠道；[withExtra] 允许后续设置页追加用户自定义来源。
 */
object SupportedSources {

    val packages: Set<String> = setOf(
        // 支付钱包
        "com.tencent.mm",                     // 微信（含微信支付 / 财付通）
        "com.eg.android.AlipayGphone",        // 支付宝

        // 常用商户
        "com.sankuai.meituan",                // 美团
        "com.sdu.didi.psnger",                // 滴滴
        "com.jingdong.app.mall",              // 京东
        "com.taobao.taobao",                  // 淘宝
        "me.ele",                             // 饿了么
        "com.xunmeng.pinduoduo",              // 拼多多

        // 银行 / 云闪付
        "com.cmbchina.ccd.pluto.cmbActivity", // 招商银行
        "com.unionpay",                       // 云闪付
        "com.icbc",                           // 工商银行
        "com.chinamworld.main",               // 中国银行
        "com.bankcomm.Bankcomm",              // 交通银行
    )

    /** 该来源是否允许采集（白名单命中）。null 或未知一律拒绝。 */
    fun isSupported(sourcePackage: String?): Boolean =
        sourcePackage != null && packages.contains(sourcePackage)

    /** 在默认白名单基础上追加自定义来源（供设置页/导入扩展）。 */
    fun withExtra(extra: Set<String>): Set<String> = packages + extra
}
