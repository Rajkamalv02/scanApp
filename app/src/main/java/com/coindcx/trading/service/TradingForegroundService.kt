package com.coindcx.trading.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.coindcx.trading.data.api.ApiClient
import com.coindcx.trading.data.config.TradingConfigRepository
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.allocation.AllocationEngine
import com.coindcx.trading.engine.currency.CurrencyConverter
import com.coindcx.trading.engine.scanner.MarketOpportunity
import com.coindcx.trading.engine.scanner.MarketScanState
import com.coindcx.trading.engine.scanner.MarketScannerEngine
import com.coindcx.trading.engine.scanner.OpportunityLifecycle
import com.coindcx.trading.engine.scanner.OpportunityRanker
import com.coindcx.trading.ui.MainActivity
import kotlinx.coroutines.*

class TradingForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isTradingActive = false

    private val db by lazy { AppDatabase.getInstance(this) }
    private val configRepo by lazy { TradingConfigRepository.getInstance(this) }
    private val currencyConverter by lazy { CurrencyConverter(ApiClient.apiService) }

    private val orderManager by lazy { OrderManager(ApiClient.apiService, db.orderDao()) }
    private val paperEngine by lazy { PaperExecutionEngine(this, db, currencyConverter) }
    private val liveEngine by lazy { LiveExecutionEngine(orderManager, ApiClient.apiService, currencyConverter) }

    private val scannerEngine by lazy { MarketScannerEngine(ApiClient.apiService) }
    private val ranker by lazy { OpportunityRanker() }
    private val allocator by lazy { AllocationEngine() }

    private var executionEngine: ExecutionEngine = paperEngine

    companion object {
        const val CHANNEL_ID = "trading_bot_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.coindcx.trading.ACTION_START"
        const val ACTION_STOP = "com.coindcx.trading.ACTION_STOP"
        const val ACTION_SET_MODE = "com.coindcx.trading.ACTION_SET_MODE"
        const val ACTION_SET_STRATEGY = "com.coindcx.trading.ACTION_SET_STRATEGY"
        const val ACTION_TRIGGER_SCAN = "com.coindcx.trading.ACTION_TRIGGER_SCAN"
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
                        SystemLogEntity(level = "INFO", tag = "STRATEGY", message = "Active strategy: ${StrategyRegistry.activeStrategy.name}")
                    )
                }
            }
            ACTION_TRIGGER_SCAN -> {
                serviceScope.launch {
                    performMarketScanAndAllocation()
                }
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("CoinDCX Trading Bot", "Market Scanner Active"))
        return START_STICKY
    }

    private fun startTradingLoop() {
        serviceScope.launch {
            db.systemLogDao().insert(
                SystemLogEntity(
                    level = "INFO",
                    tag = "SERVICE",
                    message = "Market Scanning Loop started. Strategy: ${StrategyRegistry.activeStrategy.name} | Mode: ${if (executionEngine.isPaperTrading) "PAPER" else "LIVE"}"
                )
            )

            while (isTradingActive) {
                try {
                    performMarketScanAndAllocation()
                } catch (e: Exception) {
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "ERROR", tag = "SCANNER", message = "Scan cycle error: ${e.message}")
                    )
                }
                delay(20_000) // Scan market every 20 seconds
            }
        }
    }

    private suspend fun performMarketScanAndAllocation() {
        MarketScanState.setScanning(true)
        val config = configRepo.configFlow.value
        val activeStrategy = StrategyRegistry.activeStrategy

        // 1. Check Available Balance in INR
        val balanceInr = executionEngine.getAvailableBalanceInr()

        // 2. Scan Market Opportunities
        val rawOpportunities = scannerEngine.scanMarket(config, activeStrategy, executionEngine)

        // 3. Rank Opportunities from #1 to #5
        val rankedTop5 = ranker.rankOpportunities(rawOpportunities)

        // 4. Dynamic Capital Allocation Engine
        val allocation = allocator.allocateCapital(balanceInr, config.minMarginPerTradeInr, rankedTop5)

        // 5. Update Reactive State for UI
        MarketScanState.update(allocation.allRankedOpportunities, allocation)
        MarketScanState.setScanning(false)

        val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"

        // 6. Handle Insufficient Balance Warning
        if (allocation.isInsufficientBalance) {
            updateNotification("Bot Active ($modeLabel)", "Insufficient Balance: Available ₹%.2f < ₹%.2f".format(balanceInr, config.minMarginPerTradeInr))
            return
        }

        // 7. Execute Top N Funded Trades
        for (opp in allocation.fundedOpportunities) {
            val existingPos = executionEngine.getActivePosition(opp.pair)
            if (existingPos == null || !existingPos.isOpen) {
                val result = executionEngine.executeSignal(
                    signal = opp.signal,
                    pair = opp.pair,
                    currentPrice = opp.currentPrice,
                    marginInr = opp.allocatedMarginInr,
                    leverage = config.leverage
                )
                when (result) {
                    is ExecutionResult.Success -> {
                        db.systemLogDao().insert(
                            SystemLogEntity(level = "TRADE", tag = "EXEC", message = "Funded Rank #${opp.rank} ${opp.pair} (${opp.actionLabel}): ${result.message}")
                        )
                    }
                    is ExecutionResult.Failed -> {
                        db.systemLogDao().insert(
                            SystemLogEntity(level = "ERROR", tag = "EXEC", message = "Failed to execute ${opp.pair}: ${result.error}")
                        )
                    }
                }
            }
        }

        // 8. Monitor Paper SL/TP for open positions
        if (executionEngine is PaperExecutionEngine) {
            val openTrades = db.tradeDao().getOpenTrades()
            for (t in openTrades) {
                val candleResp = ApiClient.apiService.getCandles(t.pair, config.timeframe)
                val currentPrice = candleResp.body()?.lastOrNull()?.close
                if (currentPrice != null) {
                    paperEngine.checkPaperStopLossAndTakeProfit(t.pair, currentPrice)
                }
            }
        }

        // Update Notification
        val topPick = rankedTop5.firstOrNull()?.assetSymbol ?: "None"
        updateNotification(
            "Bot Active ($modeLabel) | Top: $topPick",
            "Funded: ${allocation.allocatedTradesCount}/${rankedTop5.size} | Rem: ₹%.0f".format(allocation.remainingBalanceInr)
        )
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
                description = "Keeps CoinDCX auto-trading market scanner alive"
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
