package com.moneytask.ledger.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.moneytask.ledger.MoneytaskApplication
import com.moneytask.ledger.capture.RawNotification

/**
 * 通知监听服务（《MVP技术设计》采集层）。
 *
 * 系统把每条已发布的通知回调到这里；我们把「标题 + 正文 + 来源 + 时间」转成 [RawNotification]
 * 交给管线。是否可采集、金额歧义、渠道归类、去重归并全部发生在后续的纯 JVM 逻辑里。
 *
 * 注意：
 *  - 本服务**只做监听与转发**，不做任何记账决策（决策已沉淀在可单测的 ledger-core 中）。
 *  - 需要用户在系统设置里授予「通知使用权」，否则 onNotificationPosted 不会回调。
 *  - [onNotificationRemoved] 暂不处理（链路结算由落账倒计时驱动，移除通知不改变事实）。
 */
class PaymentNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 连接成功即把当前已存在于通知栏的历史通知补采一遍（如来电/重启后）。
        // runCatching：不同厂商权限激进程度不同，历史通知可能不可读，失败静默。
        runCatching {
            activeNotifications?.forEach(::forward)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        forward(sbn)
    }

    private fun forward(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // 忽略本应用自己的通知，避免自吞
        val raw = toRawNotification(sbn) ?: return
        val app = applicationContext as? MoneytaskApplication ?: return
        app.container.pipeline.onNotification(raw)
    }

    private fun toRawNotification(sbn: StatusBarNotification): RawNotification? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: sbn.notification.tickerText?.toString()
            ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val pkg = sbn.packageName
        return RawNotification(
            sourcePackage = pkg,
            sourceAppName = resolveAppName(pkg),
            title = title,
            text = text,
            timestamp = sbn.postTime,
            notificationId = "${pkg}#${sbn.id}#${sbn.postTime}",
        )
    }

    private fun resolveAppName(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)
}
