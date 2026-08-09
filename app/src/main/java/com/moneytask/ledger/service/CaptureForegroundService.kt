package com.moneytask.ledger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.moneytask.ledger.MoneytaskApplication
import com.moneytask.ledger.R
import com.moneytask.ledger.ui.MainActivity

/**
 * 常驻/省电前台服务（采集保活层）。
 *
 * 通知监听服务由系统保持绑定、本身可常驻；但对厂商激进杀后台的 ROM 而言，把采集管线
 * 挂到前台进程并置 START_STICKY，是更稳的兜底，避免「通知一到、进程已睡」丢采集。
 *
 * 省电设计：
 *  - 前台通知走 MIN 优先级渠道（不打扰、不常亮，只在最近任务里可见）；
 *  - 服务本身不做任何轮询——真正的工作由「数据到达驱动的采集管线」完成；
 *  - START_STICKY：进程被系统回收后由系统带 intent 重建并自动续跑。
 */
class CaptureForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        pipeline()?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次启动/重建都确保管线在跑；数据由监听服务喂入，这里只管保活。
        pipeline()?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // 前台服务被销毁即用户停止采集：停止结算协程。
        pipeline()?.stop()
    }

    private fun pipeline() = (application as? MoneytaskApplication)?.container?.pipeline

    private fun buildNotification(): Notification {
        val channelId = createChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createChannel(): String {
        val id = CHANNEL_ID
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    id, getString(R.string.service_channel),
                    NotificationManager.IMPORTANCE_MIN,
                )
            )
        }
        return id
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "capture_foreground"

        /** 从 Activity（前台）启动保活服务。幂等：已运行时仅触发 onStartCommand。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, CaptureForegroundService::class.java)
            )
        }
    }
}
