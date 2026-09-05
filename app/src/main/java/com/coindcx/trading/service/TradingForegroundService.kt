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

    private lateinit var db: AppDatabase
    private lateinit var configRepo: TradingConfigRepository
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var orderManager: OrderManager
    private lateinit var paperEngine: PaperExecutionEngine
    private lateinit var liveEngine: LiveExecutionEngine
    private lateinit var scannerEngine: MarketScannerEngine
    private lateinit var ranker: OpportunityRanker
    private lateinit var allocator: AllocationEngine
    private lateinit var executionEngine: ExecutionEngine

    companion object {
        const val CHANNEL_ID = "trading_bot_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.coindcx.trading.ACTION_START"
        const val ACTION_STOP = "com.coindcx.trading.ACTION_STOP"
        const val ACTION_SET_MODE = "com.coindcx.trading.ACTION_SET_MODE"
        const val ACTION_SET_STRATEGY = "com.coindcx.trading.ACTION_SET_STRATEGY"
        const val ACTION_TRIGGER_SCAN = "com.coindcx.trading.ACTION_TRIGGER_SCAN"
        const val ACTION_CLOSE_POSITION = "com.coindcx.trading.ACTION_CLOSE_POSITION"
        const val ACTION_REFRESH_EXCHANGE = "com.coindcx.trading.ACTION_REFRESH_EXCHANGE"
        const val EXTRA_IS_PAPER = "EXTRA_IS_PAPER"
        const val EXTRA_PAIR = "EXTRA_PAIR"
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(applicationContext)
        configRepo = TradingConfigRepository.getInstance(applicationContext)
        currencyConverter = CurrencyConverter(ApiClient.apiService)
        orderManager = OrderManager(ApiClient.apiService, db.orderDao())
        paperEngine = PaperExecutionEngine(applicationContext, db, currencyConverter)
        liveEngine = LiveExecutionEngine(orderManager, ApiClient.apiService, currencyConverter)
        scannerEngine = MarketScannerEngine(ApiClient.apiService)
        ranker = OpportunityRanker()
        allocator = AllocationEngine()
        executionEngine = paperEngine

        StrategyRegistry.init(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure foreground notification immediately for Android 8+ requirement
        val initialNotification = buildNotification("CoinDCX Trading Bot", "Market Scanner Service Running")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

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
                MarketScanState.setNextScanSecondsRemaining(0)
                updateNotification("Bot Stopped", "Scanner & execution loop halted")
                serviceScope.launch {
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "INFO", tag = "SERVICE", message = "Trading Bot Stopped by user.")
                    )
                }
            }
            ACTION_SET_MODE -> {
                val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"
                updateNotification("Bot Mode Changed ($modeLabel)", "Strategy: ${StrategyRegistry.activeStrategy.name}")
                serviceScope.launch {
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "INFO", tag = "MODE", message = "Switched execution mode to $modeLabel")
                    )
                    // Trigger refresh to align state with newly selected engine
                    val syncRes = executionEngine.refreshExchangeState()
                    if (syncRes.isSuccess) {
                        MarketScanState.updateExchangeSnapshot(syncRes.getOrThrow())
                    }
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
            ACTION_CLOSE_POSITION -> {
                val pairToClose = intent.getStringExtra(EXTRA_PAIR)
                if (!pairToClose.isNullOrBlank()) {
                    serviceScope.launch {
                        val candleResp = ApiClient.apiService.getCandles(pairToClose, "1m")
                        val currentPrice = candleResp.body()?.lastOrNull()?.close ?: 0.0
                        val res = executionEngine.exitPosition(pairToClose, currentPrice, "Manual close requested by user")
                        db.systemLogDao().insert(
                            SystemLogEntity(level = "TRADE", tag = "MANUAL_CLOSE", message = "Closed $pairToClose: ${res}")
                        )
                        val syncRes = executionEngine.refreshExchangeState()
                        if (syncRes.isSuccess) {
                            MarketScanState.updateExchangeSnapshot(syncRes.getOrThrow())
                        }
                    }
                }
            }
            ACTION_TRIGGER_SCAN -> {
                serviceScope.launch {
                    performMarketScanAndAllocation()
                }
            }
            ACTION_REFRESH_EXCHANGE -> {
                serviceScope.launch {
                    MarketScanState.setRefreshingExchange(true)
                    try {
                        val syncResult = executionEngine.refreshExchangeState()
                        if (syncResult.isSuccess) {
                            MarketScanState.updateExchangeSnapshot(syncResult.getOrThrow())
                            db.systemLogDao().insert(
                                SystemLogEntity(level = "INFO", tag = "REFRESH", message = "Manual exchange refresh completed successfully.")
                            )
                        } else {
                            db.systemLogDao().insert(
                                SystemLogEntity(level = "WARN", tag = "REFRESH", message = "Manual refresh failed: ${syncResult.exceptionOrNull()?.message}")
                            )
                        }
                    } finally {
                        MarketScanState.setRefreshingExchange(false)
                    }
                }
            }
        }

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

                // Configurable interval countdown (1m, 5m, 15m, 30m, 1h)
                val intervalMinutes = configRepo.configFlow.value.scanIntervalMinutes
                var secondsRemaining = intervalMinutes * 60
                while (isTradingActive && secondsRemaining > 0) {
                    MarketScanState.setNextScanSecondsRemaining(secondsRemaining)
                    delay(1000)
                    secondsRemaining--
                }
            }
            MarketScanState.setNextScanSecondsRemaining(0)
        }
    }

    private suspend fun performMarketScanAndAllocation() {
        MarketScanState.setScanning(true)
        val config = configRepo.configFlow.value
        val activeStrategy = StrategyRegistry.activeStrategy
        val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"

        // 1. Initial State Sync (Exchange is SSOT)
        val preSync = executionEngine.refreshExchangeState()
        if (preSync.isSuccess) {
            MarketScanState.updateExchangeSnapshot(preSync.getOrThrow())
        }
        val initialBalanceInr = executionEngine.getAvailableBalanceInr()

        // 2. Scan Futures Market Opportunities
        val rawOpportunities = scannerEngine.scanMarket(config, activeStrategy, executionEngine)

        // 3. Rank Opportunities from #1 to #5
        val rankedTop5 = ranker.rankOpportunities(rawOpportunities)

        // 4. Initial Dynamic Allocation
        val allocation = allocator.allocateCapital(initialBalanceInr, config.minMarginPerTradeInr, rankedTop5)
        MarketScanState.update(allocation.allRankedOpportunities, allocation)

        // 5. Sequential Just-In-Time Pre-Trade Validation
        val audits = mutableListOf<com.coindcx.trading.engine.scanner.TradeExecutionAudit>()

        for (opp in rankedTop5) {
            // Check 1: Mandatory Fresh Balance Check
            val currentAvailableInr = executionEngine.getAvailableBalanceInr()
            if (currentAvailableInr < config.minMarginPerTradeInr) {
                audits.add(
                    com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                        rank = opp.rank,
                        pair = opp.pair,
                        action = opp.actionLabel,
                        status = com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_INSUFFICIENT_BALANCE,
                        reason = "Skipped — Insufficient available margin (Available: ₹%.2f < ₹%.2f)".format(currentAvailableInr, config.minMarginPerTradeInr)
                    )
                )
                continue
            }

            // Check 2: Mandatory Existing Open Position Check
            val existingPos = executionEngine.getActivePosition(opp.pair)
            if (existingPos != null && (existingPos.isOpen || existingPos.inactivePosBuy > 0 || existingPos.inactivePosSell > 0)) {
                audits.add(
                    com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                        rank = opp.rank,
                        pair = opp.pair,
                        action = opp.actionLabel,
                        status = com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_EXISTING_POSITION,
                        reason = "Skipped — Existing open position (${if (existingPos.isLong) "LONG" else "SHORT"} ${existingPos.leverage.toInt()}x)"
                    )
                )
                continue
            }

            // Check 3: Check opportunity signal is an entry signal
            if (!opp.isBuy && !opp.isSell) {
                continue
            }

            // Both checks pass -> Execute Order!
            val marginToAllocate = config.minMarginPerTradeInr
            val execResult = executionEngine.executeSignal(
                signal = opp.signal,
                pair = opp.pair,
                currentPrice = opp.currentPrice,
                marginInr = marginToAllocate,
                leverage = config.leverage
            )

            when (execResult) {
                is ExecutionResult.Success -> {
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.EXECUTED,
                            reason = "Executed — Placed ${opp.actionLabel} with ₹%.0f margin @ ${config.leverage}x".format(marginToAllocate)
                        )
                    )
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "TRADE", tag = "EXEC", message = "Rank #${opp.rank} ${opp.pair} (${opp.actionLabel}): ${execResult.message}")
                    )

                    // Immediate Post-Order State Sync to reflect deducted balance and added position!
                    val syncResult = executionEngine.refreshExchangeState()
                    if (syncResult.isSuccess) {
                        MarketScanState.updateExchangeSnapshot(syncResult.getOrThrow())
                    }
                }
                is ExecutionResult.Failed -> {
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.FAILED,
                            reason = "Failed — ${execResult.error}"
                        )
                    )
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "ERROR", tag = "EXEC", message = "Failed to execute ${opp.pair}: ${execResult.error}")
                    )
                }
            }
        }

        // 6. Update Audit Map
        MarketScanState.updateAudit(audits)

        // 7. Final Post-Execution State Synchronization
        val postSync = executionEngine.refreshExchangeState()
        if (postSync.isSuccess) {
            MarketScanState.updateExchangeSnapshot(postSync.getOrThrow())
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

        MarketScanState.setScanning(false)

        // Update Notification
        val topPick = rankedTop5.firstOrNull()?.assetSymbol ?: "None"
        val finalBalance = executionEngine.getAvailableBalanceInr()
        updateNotification(
            "Bot Active ($modeLabel) | Top: $topPick",
            "Bal: ₹%.0f | Audited ${audits.size} Trades | Scan: ${config.scanIntervalMinutes}m".format(finalBalance)
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
