package com.coindcx.trading.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.coindcx.trading.ui.MainActivity
import kotlinx.coroutines.*

class TradingForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isTradingEnabled = false

    companion object {
        const val CHANNEL_ID = "trading_bot_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.coindcx.trading.ACTION_STOP"
        const val ACTION_START = "com.coindcx.trading.ACTION_START"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startPaused = intent?.getBooleanExtra("START_PAUSED", false) ?: false

        when (intent?.action) {
            ACTION_STOP -> {
                isTradingEnabled = false
                updateNotification("Trading Bot Paused", "Automated execution is stopped")
            }
            ACTION_START -> {
                isTradingEnabled = true
                updateNotification("Trading Bot Active", "Monitoring futures market & positions")
            }
            else -> {
                if (startPaused) {
                    isTradingEnabled = false
                    updateNotification("Trading Bot Paused (Post-Reboot)", "Manual confirmation required")
                } else {
                    isTradingEnabled = true
                    updateNotification("Trading Bot Active", "Running foreground trading service")
                }
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Trading Service Initialized", "Connecting to CoinDCX..."))
        return START_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CoinDCXTrading::WakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trading Bot Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CoinDCX auto-trading bot alive and monitors orders"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val notification = buildNotification(title, text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
