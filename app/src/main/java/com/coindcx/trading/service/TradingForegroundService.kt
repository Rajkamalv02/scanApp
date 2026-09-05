package com.coindcx.trading.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.coindcx.trading.data.api.ApiClient
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.engine.*
import com.coindcx.trading.ui.MainActivity
import kotlinx.coroutines.*

class TradingForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isTradingActive = false

    private val db by lazy { AppDatabase.getInstance(this) }
    private val riskManager by lazy { RiskManager() }
    private val orderManager by lazy { OrderManager(ApiClient.apiService, db.orderDao()) }

    private val paperEngine by lazy { PaperExecutionEngine(db) }
    private val liveEngine by lazy { LiveExecutionEngine(orderManager, ApiClient.apiService) }

    private var executionEngine: ExecutionEngine = paperEngine
    private val targetPair = "B-BTC_USDT"

    companion object {
        const val CHANNEL_ID = "trading_bot_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.coindcx.trading.ACTION_START"
        const val ACTION_STOP = "com.coindcx.trading.ACTION_STOP"
        const val ACTION_SET_MODE = "com.coindcx.trading.ACTION_SET_MODE"
        const val ACTION_SET_STRATEGY = "com.coindcx.trading.ACTION_SET_STRATEGY"
        const val EXTRA_IS_PAPER = "EXTRA_IS_PAPER"
    }

    override fun onCreate() {
        super.onCreate()
        StrategyRegistry.init(this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_IS_PAPER) == true) {
            val isPaper = intent.getBooleanExtra(EXTRA_IS_PAPER, true)
            executionEngine = if (isPaper) paperEngine else liveEngine
        }

        when (intent?.action) {
            ACTION_START -> {
                if (!isTradingActive) {
                    isTradingActive = true
                    startTradingLoop()
                    val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"
                    updateNotification("Bot Active ($modeLabel)", "Strategy: ${StrategyRegistry.activeStrategy.name}")
                }
            }
            ACTION_STOP -> {
                isTradingActive = false
                updateNotification("Bot Paused", "Execution loop halted")
            }
            ACTION_SET_MODE -> {
                val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"
                updateNotification("Bot Mode Changed ($modeLabel)", "Strategy: ${StrategyRegistry.activeStrategy.name}")
                serviceScope.launch {
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "INFO", tag = "MODE", message = "Switched execution mode to $modeLabel")
                    )
                }
            }
            ACTION_SET_STRATEGY -> {
                val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"
                updateNotification("Strategy Updated", "${StrategyRegistry.activeStrategy.name} ($modeLabel)")
                serviceScope.launch {
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "INFO", tag = "STRATEGY", message = "Switched active strategy to: ${StrategyRegistry.activeStrategy.name}")
                    )
                }
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("CoinDCX Trading Bot", "Service running"))
        return START_STICKY
    }

    private fun startTradingLoop() {
        serviceScope.launch {
            db.systemLogDao().insert(
                SystemLogEntity(
                    level = "INFO",
                    tag = "SERVICE",
                    message = "Trading loop started. Strategy: ${StrategyRegistry.activeStrategy.name} | Mode: ${if (executionEngine.isPaperTrading) "PAPER" else "LIVE"}"
                )
            )

            while (isTradingActive) {
                try {
                    evaluateMarketAndTrade()
                } catch (e: Exception) {
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "ERROR", tag = "ENGINE", message = "Evaluation error: ${e.message}")
                    )
                }
                delay(15_000) // Evaluate every 15 seconds
            }
        }
    }

    private suspend fun evaluateMarketAndTrade() {
        val activeStrategy = StrategyRegistry.activeStrategy

        // 1. Fetch recent candles
        val candleResp = ApiClient.apiService.getCandles(targetPair, activeStrategy.defaultTimeframe)
        if (!candleResp.isSuccessful || candleResp.body().isNullOrEmpty()) {
            return
        }

        val candles = candleResp.body()!!
        val latestCandle = candles.last()
        val currentPrice = latestCandle.close

        // Check SL/TP if paper trading
        if (executionEngine is PaperExecutionEngine) {
            paperEngine.checkPaperStopLossAndTakeProfit(targetPair, currentPrice)
        }

        // 2. Fetch live or paper active position
        val activePosition = executionEngine.getActivePosition(targetPair)

        // 3. Strategy Evaluation
        val signal = activeStrategy.evaluate(candles, activePosition)

        if (signal.action == SignalAction.HOLD) {
            return
        }

        // 4. Risk Manager Check
        val allPositions = if (activePosition != null) listOf(activePosition) else emptyList()
        val riskResult = riskManager.checkSignal(signal, currentPrice, allPositions)

        when (riskResult) {
            is RiskCheckResult.Rejected -> {
                db.systemLogDao().insert(
                    SystemLogEntity(level = "WARN", tag = "RISK", message = "Signal ${signal.action} rejected: ${riskResult.reason}")
                )
            }
            is RiskCheckResult.Approved -> {
                if (signal.action == SignalAction.EXIT) {
                    executionEngine.exitPosition(targetPair, currentPrice, signal.reason)
                } else {
                    val quantity = riskResult.adjustedQuantity.coerceAtLeast(0.001)
                    val leverage = riskResult.adjustedLeverage
                    executionEngine.executeSignal(signal, targetPair, currentPrice, quantity, leverage)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CoinDCXTrading::ServiceLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trading Bot Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CoinDCX auto-trading bot alive and monitoring market"
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
        isTradingActive = false
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
