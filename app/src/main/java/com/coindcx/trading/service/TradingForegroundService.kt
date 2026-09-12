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
import com.coindcx.trading.util.AppLogManager
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
    private lateinit var riskManager: RiskManager

    private var scanCycleCounter = 0
    private val scanMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile
    private var countdownResetRequested = false

    companion object {
        const val CHANNEL_ID = "trading_bot_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.coindcx.trading.ACTION_START"
        const val ACTION_STOP = "com.coindcx.trading.ACTION_STOP"
        const val ACTION_SET_MODE = "com.coindcx.trading.ACTION_SET_MODE"
        const val ACTION_SET_STRATEGY = "com.coindcx.trading.ACTION_SET_STRATEGY"
        const val ACTION_TRIGGER_SCAN = "com.coindcx.trading.ACTION_TRIGGER_SCAN"
        const val ACTION_UPDATE_CONFIG = "com.coindcx.trading.ACTION_UPDATE_CONFIG"
        const val ACTION_CLOSE_POSITION = "com.coindcx.trading.ACTION_CLOSE_POSITION"
        const val ACTION_REFRESH_EXCHANGE = "com.coindcx.trading.ACTION_REFRESH_EXCHANGE"
        const val ACTION_RESET_PAPER = "com.coindcx.trading.ACTION_RESET_PAPER"
        const val EXTRA_IS_PAPER = "EXTRA_IS_PAPER"
        const val EXTRA_PAIR = "EXTRA_PAIR"
        const val EXTRA_RESET_BALANCE = "EXTRA_RESET_BALANCE"
    }

    private var positionMonitorJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(applicationContext)
        AppLogManager.init(applicationContext, db)
        configRepo = TradingConfigRepository.getInstance(applicationContext)
        currencyConverter = CurrencyConverter(ApiClient.apiService)
        orderManager = OrderManager(ApiClient.apiService, db.orderDao())
        paperEngine = PaperExecutionEngine(applicationContext, db, currencyConverter, ApiClient.apiService)
        liveEngine = LiveExecutionEngine(orderManager, ApiClient.apiService, currencyConverter)
        scannerEngine = MarketScannerEngine(ApiClient.apiService)
        ranker = OpportunityRanker()
        allocator = AllocationEngine()
        riskManager = RiskManager()
        executionEngine = paperEngine

        paperEngine.onTradeClosed = { pnl ->
            serviceScope.launch {
                val bal = executionEngine.getAvailableBalanceInr()
                riskManager.recordTradeResult(pnl, bal)
                refreshPaperState()
            }
        }

        StrategyRegistry.init(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
        startPositionMonitorLoop()
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
                } else {
                    countdownResetRequested = true
                    serviceScope.launch {
                        performMarketScanAndAllocation()
                    }
                }
            }
            ACTION_STOP -> {
                isTradingActive = false
                countdownResetRequested = true
                MarketScanState.setNextScanSecondsRemaining(0)
                updateNotification("Bot Stopped", "Scanner & execution loop halted")
                AppLogManager.i("SERVICE", "Trading Bot Stopped by user.")
            }
            ACTION_SET_MODE -> {
                val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"
                updateNotification("Bot Mode Changed ($modeLabel)", "Strategy: ${StrategyRegistry.activeStrategy.name}")
                AppLogManager.i("MODE", "Switched execution mode to $modeLabel")
                serviceScope.launch {
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
                countdownResetRequested = true
                AppLogManager.i("STRATEGY", "Active strategy: ${StrategyRegistry.activeStrategy.name}")
            }
            ACTION_CLOSE_POSITION -> {
                val pairToClose = intent.getStringExtra(EXTRA_PAIR)
                if (!pairToClose.isNullOrBlank()) {
                    serviceScope.launch {
                        val candleResp = ApiClient.apiService.getCandles(pairToClose, "1m")
                        val currentPrice = candleResp.body()?.lastOrNull()?.close ?: 0.0
                        val res = executionEngine.exitPosition(pairToClose, currentPrice, "Manual close requested by user")
                        AppLogManager.trade("MANUAL_CLOSE", "Closed $pairToClose: $res")
                        val syncRes = executionEngine.refreshExchangeState()
                        if (syncRes.isSuccess) {
                            MarketScanState.updateExchangeSnapshot(syncRes.getOrThrow())
                        }
                    }
                }
            }
            ACTION_TRIGGER_SCAN -> {
                countdownResetRequested = true
                AppLogManager.i("SCANNER", "Manual scan requested by user.")
                serviceScope.launch {
                    performMarketScanAndAllocation()
                }
            }
            ACTION_UPDATE_CONFIG -> {
                countdownResetRequested = true
            }
            ACTION_REFRESH_EXCHANGE -> {
                serviceScope.launch {
                    MarketScanState.setRefreshingExchange(true)
                    try {
                        val syncResult = executionEngine.refreshExchangeState()
                        if (syncResult.isSuccess) {
                            MarketScanState.updateExchangeSnapshot(syncResult.getOrThrow())
                            AppLogManager.i("REFRESH", "Manual exchange refresh completed successfully.")
                        } else {
                            AppLogManager.w("REFRESH", "Manual refresh failed: ${syncResult.exceptionOrNull()?.message}")
                        }
                    } finally {
                        MarketScanState.setRefreshingExchange(false)
                    }
                }
            }
            ACTION_RESET_PAPER -> {
                val resetBalance = intent.getDoubleExtra(
                    EXTRA_RESET_BALANCE,
                    com.coindcx.trading.engine.paper.PaperAccountManager.DEFAULT_STARTING_BALANCE
                )
                serviceScope.launch {
                    paperEngine.accountManager.resetAccount(resetBalance)
                    refreshPaperState()
                    val syncRes = executionEngine.refreshExchangeState()
                    if (syncRes.isSuccess) {
                        MarketScanState.updateExchangeSnapshot(syncRes.getOrThrow())
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun startTradingLoop() {
        serviceScope.launch {
            AppLogManager.i("SERVICE", "Market Scanning Loop started. Strategy: ${StrategyRegistry.activeStrategy.name} | Mode: ${if (executionEngine.isPaperTrading) "PAPER" else "LIVE"}")

            while (isTradingActive) {
                try {
                    performMarketScanAndAllocation()
                } catch (e: Exception) {
                    AppLogManager.e("SCANNER", "Scan cycle error: ${e.message}", e)
                }

                countdownResetRequested = false
                var activeIntervalMinutes = configRepo.configFlow.value.scanIntervalMinutes
                var secondsRemaining = activeIntervalMinutes * 60

                while (isTradingActive && secondsRemaining > 0 && !countdownResetRequested) {
                    val currentInterval = configRepo.configFlow.value.scanIntervalMinutes
                    if (currentInterval != activeIntervalMinutes) {
                        activeIntervalMinutes = currentInterval
                        secondsRemaining = (currentInterval * 60).coerceAtMost(secondsRemaining)
                        AppLogManager.i("CONFIG", "Auto-scan countdown adjusted to ${currentInterval}m interval.")
                    }
                    MarketScanState.setNextScanSecondsRemaining(secondsRemaining)
                    delay(1000)
                    secondsRemaining--
                }
            }
            MarketScanState.setNextScanSecondsRemaining(0)
        }
    }

    private suspend fun performMarketScanAndAllocation() {
        if (!scanMutex.tryLock()) {
            return // Skip concurrent execution if a scan is already running
        }
        try {
            MarketScanState.setScanning(true)
            scanCycleCounter++
            val cycle = scanCycleCounter
            val config = configRepo.configFlow.value
            val activeStrategy = StrategyRegistry.activeStrategy
            val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"
            AppLogManager.scanner("Scan Cycle #$cycle started: Scanning ${if (config.isMarketWideScan) "market-wide" else "${config.selectedPairs.size} pairs"} (${config.timeframe}) with ${activeStrategy.name}...")

            // Circuit Breaker Check: Daily drawdown cap (4% loss limit)
            if (riskManager.isCircuitBreakerTripped()) {
                AppLogManager.w("RISK", "Scan #$cycle skipped: Daily drawdown circuit breaker tripped (4% loss limit reached).")
                return
            }

            val isCooldown = riskManager.isCooldownActive()
            if (isCooldown) {
                val remainingMins = riskManager.getCooldownRemainingMinutes()
                AppLogManager.w("RISK", "Scan #$cycle: Execution paused (90m cooldown active, $remainingMins min remaining).")
            }

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
            MarketScanState.update(allocation.allRankedOpportunities, allocation, cycle)

            // 5. Sequential Just-In-Time Pre-Trade Validation with In-Memory Counters
            val inMemoryOpenPositions = executionEngine.getAllOpenPositions().toMutableList()
            var inMemoryAvailableBalance = initialBalanceInr
            val audits = mutableListOf<com.coindcx.trading.engine.scanner.TradeExecutionAudit>()

            // Evaluate Bitcoin 1h Macro Trend (EMA 50) for portfolio regime gating
            val btcMacroBullish: Boolean? = try {
                val btcHtfResp = ApiClient.apiService.getCandles("B-BTC_USDT", "1h")
                if (btcHtfResp.isSuccessful && !btcHtfResp.body().isNullOrEmpty()) {
                    val btcCandles = btcHtfResp.body()!!.sortedBy { it.time }
                    val btcCloses = btcCandles.map { it.close }
                    val btcEma50 = com.coindcx.trading.engine.indicators.TechnicalIndicators.calculateEma(btcCloses, 50)
                    if (btcEma50.isNotEmpty()) {
                        btcCloses.last() >= btcEma50.last()
                    } else null
                } else null
            } catch (_: Exception) { null }

            for (opp in rankedTop5) {
                // Signal Action Check: Must be actionable entry
                if (!opp.isBuy && !opp.isSell) {
                    AppLogManager.d("EVAL", "[${opp.pair}] Watching: ${opp.signal.reason} [QualityScore: ${opp.qualityScore}]")
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.WATCHING,
                            reason = "Watching — ${opp.signal.reason} [Score: ${opp.qualityScore}]"
                        )
                    )
                    continue
                }

                // Circuit Breaker / Cooldown Gate
                if (isCooldown) {
                    AppLogManager.w("RISK", "[${opp.pair}] Skipped: Cooldown active (${riskManager.getCooldownRemainingMinutes()}m remaining)")
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_PORTFOLIO_LIMIT,
                            reason = "Skipped — Cooldown active (${riskManager.getCooldownRemainingMinutes()}m remaining after consecutive losses)"
                        )
                    )
                    continue
                }

                // Gate 1: Quality Score Rubric Gate (Must be approved by TradeQualityScorer)
                if (!opp.isApproved) {
                    AppLogManager.quality("[${opp.pair}] Gate 1 (Quality) REJECTED: Score ${opp.qualityScore}/100 (${opp.qualityCategory}). Rejection: ${opp.rejectionReason ?: "Insufficient confluence"}")
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.REJECTED_LOW_QUALITY,
                            reason = "Rejected — Quality Score ${opp.qualityScore}/100 (${opp.qualityCategory}). ${opp.rejectionReason ?: "Insufficient confluence"}"
                        )
                    )
                    continue
                }

                // Gate 2: Portfolio Exposure & Macro Regime BTC Gate
                val portfolioCheck = riskManager.checkPortfolioAndCorrelation(
                    candidatePair = opp.pair,
                    isBuy = opp.isBuy,
                    activePositions = inMemoryOpenPositions,
                    btcMacroTrendIsBullish = btcMacroBullish
                )
                if (portfolioCheck is RiskCheckResult.Rejected) {
                    AppLogManager.risk("[${opp.pair}] Gate 2 (Macro/Portfolio) REJECTED: ${portfolioCheck.reason}")
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_PORTFOLIO_LIMIT,
                            reason = "Skipped — ${portfolioCheck.reason}"
                        )
                    )
                    continue
                }

                // Gate 3: Volatility-Adjusted Risk Parity Sizing (1% account risk / SL distance %)
                val slPrice = opp.signal.stopLossPrice ?: (if (opp.isBuy) opp.currentPrice * 0.98 else opp.currentPrice * 1.02)
                val marginToAllocate = riskManager.calculateRiskSizedMargin(
                    balanceInr = inMemoryAvailableBalance,
                    entryPrice = opp.currentPrice,
                    stopLossPrice = slPrice,
                    leverage = config.leverage,
                    minMarginInr = config.minMarginPerTradeInr
                )
                AppLogManager.risk("[${opp.pair}] Gate 3 (Risk Sizing): Margin ₹%.2f @ ${config.leverage}x (Entry: ${opp.currentPrice}, SL: $slPrice)".format(marginToAllocate))

                // Gate 4: Fresh In-Memory Balance Check
                if (inMemoryAvailableBalance < marginToAllocate) {
                    AppLogManager.risk("[${opp.pair}] Gate 4 (Balance) REJECTED: Available ₹%.2f < Sized Margin ₹%.2f".format(inMemoryAvailableBalance, marginToAllocate))
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_INSUFFICIENT_BALANCE,
                            reason = "Skipped — Insufficient balance (Available: ₹%.2f < Sized Margin: ₹%.2f)".format(inMemoryAvailableBalance, marginToAllocate)
                        )
                    )
                    continue
                }

                // All gates passed -> Execute Order!
                AppLogManager.trade("EXEC", "[${opp.pair}] Gate 5: Submitting ${opp.actionLabel} order for ₹%.0f margin @ ${config.leverage}x...".format(marginToAllocate))
                val execResult = executionEngine.executeSignal(
                    signal = opp.signal,
                    pair = opp.pair,
                    currentPrice = opp.currentPrice,
                    marginInr = marginToAllocate,
                    leverage = config.leverage
                )

                when (execResult) {
                    is ExecutionResult.Success -> {
                        // Synchronously update in-memory counters to eliminate race conditions for subsequent candidates!
                        inMemoryAvailableBalance = (inMemoryAvailableBalance - marginToAllocate).coerceAtLeast(0.0)
                        inMemoryOpenPositions.add(
                            com.coindcx.trading.data.api.models.FuturesPosition(
                                id = execResult.orderId,
                                pair = opp.pair,
                                activePos = if (opp.isBuy) 1.0 else -1.0,
                                inactivePosBuy = 0.0,
                                inactivePosSell = 0.0,
                                avgPrice = opp.currentPrice,
                                liquidationPrice = 0.0,
                                lockedMargin = marginToAllocate,
                                lockedUserMargin = marginToAllocate,
                                lockedOrderMargin = 0.0,
                                takeProfitTrigger = opp.signal.takeProfitPrice,
                                stopLossTrigger = opp.signal.stopLossPrice,
                                leverage = config.leverage.toDouble(),
                                maintenanceMargin = null,
                                markPrice = opp.currentPrice,
                                marginType = "ISOLATED",
                                settlementCurrencyAvgPrice = null,
                                cumulativeFundingFee = null,
                                marginCurrencyShortName = "INR",
                                updatedAt = System.currentTimeMillis()
                            )
                        )

                        audits.add(
                            com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                                rank = opp.rank,
                                pair = opp.pair,
                                action = opp.actionLabel,
                                status = com.coindcx.trading.engine.scanner.AuditStatus.EXECUTED,
                                reason = "Executed — Placed ${opp.actionLabel} [Score: ${opp.qualityScore}] with ₹%.0f risk margin @ ${config.leverage}x".format(marginToAllocate)
                            )
                        )
                        AppLogManager.trade("EXEC", "Rank #${opp.rank} ${opp.pair} (${opp.actionLabel}, Score: ${opp.qualityScore}): ${execResult.message}")

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
                        AppLogManager.e("EXEC", "Failed to execute ${opp.pair}: ${execResult.error}")
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

            // 8. Monitor Paper Positions & Refresh State
            if (executionEngine is PaperExecutionEngine) {
                paperEngine.positionManager.updateOpenPositions()
                refreshPaperState()
            }

            val executedCount = audits.count { it.status == com.coindcx.trading.engine.scanner.AuditStatus.EXECUTED }
            val rejectedCount = audits.count { it.status == com.coindcx.trading.engine.scanner.AuditStatus.REJECTED_LOW_QUALITY }
            val skippedLimitCount = audits.count { it.status == com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_PORTFOLIO_LIMIT }
            val skippedExistingCount = audits.count { it.status == com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_EXISTING_POSITION }
            val skippedBalanceCount = audits.count { it.status == com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_INSUFFICIENT_BALANCE }
            val watchingCount = audits.count { it.status == com.coindcx.trading.engine.scanner.AuditStatus.WATCHING }

            AppLogManager.scanner("Scan #$cycle complete: Scanned ${rawOpportunities.size} pairs. Ranked Top ${rankedTop5.size}. Executed: $executedCount | Filtered: $watchingCount watching, $rejectedCount low quality, $skippedLimitCount risk limit, $skippedExistingCount held, $skippedBalanceCount balance.")

            // Update Notification
            val topPick = rankedTop5.firstOrNull()?.assetSymbol ?: "None"
            val finalBalance = executionEngine.getAvailableBalanceInr()
            updateNotification(
                "Bot Active ($modeLabel) | Top: $topPick",
                "Cycle #$cycle | Bal: ₹%.0f | Audited ${audits.size} | Next: ${config.scanIntervalMinutes}m".format(finalBalance)
            )
        } catch (e: Exception) {
            AppLogManager.e("SCANNER", "Scan cycle #$scanCycleCounter error: ${e.message}", e)
        } finally {
            MarketScanState.setScanning(false)
            scanMutex.unlock()
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

    private fun startPositionMonitorLoop() {
        positionMonitorJob?.cancel()
        positionMonitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (executionEngine is PaperExecutionEngine) {
                        val updated = paperEngine.positionManager.updateOpenPositions()
                        refreshPaperState()
                        if (updated > 0) {
                            val syncRes = executionEngine.refreshExchangeState()
                            if (syncRes.isSuccess) {
                                MarketScanState.updateExchangeSnapshot(syncRes.getOrThrow())
                            }
                        }
                    }
                } catch (_: Exception) {}
                delay(7000) // Batched check every 7 seconds
            }
        }
    }

    private suspend fun refreshPaperState() {
        try {
            val summary = paperEngine.accountManager.getAccountSummary()
            val report = paperEngine.analyticsEngine.computeAnalytics(summary.sessionId)
            MarketScanState.updatePaperState(summary, report)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isTradingActive = false
        positionMonitorJob?.cancel()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
