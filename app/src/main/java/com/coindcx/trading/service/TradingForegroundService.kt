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
import com.coindcx.trading.engine.scanner.TradeCandidateSelector
import com.coindcx.trading.ui.MainActivity
import com.coindcx.trading.util.AppLogManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

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
    private lateinit var tradeCandidateSelector: TradeCandidateSelector
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

    data class ActiveTradeMetadata(
        val pair: String,
        val isLong: Boolean,
        val entryPrice: Double,
        val initialSl: Double,
        val initialTp: Double,
        val riskPerUnit: Double,
        val strategyId: String,
        val entryTimestamp: Long = System.currentTimeMillis()
    )

    private var positionMonitorJob: kotlinx.coroutines.Job? = null
    private val activeTradeMetadata = java.util.concurrent.ConcurrentHashMap<String, ActiveTradeMetadata>()

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(applicationContext)
        AppLogManager.init(applicationContext, db)
        configRepo = TradingConfigRepository.getInstance(applicationContext)
        currencyConverter = CurrencyConverter(ApiClient.apiService)
        orderManager = OrderManager(ApiClient.apiService, db.orderDao(), currencyConverter)
        paperEngine = PaperExecutionEngine(applicationContext, db, currencyConverter, ApiClient.apiService)
        scannerEngine = MarketScannerEngine(ApiClient.apiService)
        liveEngine = LiveExecutionEngine(orderManager, ApiClient.apiService, currencyConverter, scannerEngine.universeManager)
        tradeCandidateSelector = TradeCandidateSelector()
        allocator = AllocationEngine()
        val initConfig = configRepo.configFlow.value
        riskManager = RiskManager(
            settings = RiskSettings(
                enableDailyLossLimit = initConfig.enableDailyLossLimit,
                maxDailyLossInr = initConfig.maxDailyLossInr
            ),
            context = applicationContext
        )
        val isLive = configRepo.isLiveMode()
        executionEngine = if (isLive) liveEngine else paperEngine

        val reconciliationEngine = ReconciliationEngine(
            apiService = ApiClient.apiService,
            tradeDao = db.tradeDao(),
            orderDao = db.orderDao(),
            logDao = db.systemLogDao()
        )

        serviceScope.launch {
            try {
                currencyConverter.refreshRatesOnStartup()
            } catch (e: Exception) {
                AppLogManager.e("SERVICE", "Failed to refresh currency converter rates on startup: ${e.message}", e)
            }
            try {
                reconciliationEngine.reconcile(executionEngine.isPaperTrading)
            } catch (e: Exception) {
                AppLogManager.e("SERVICE", "Failed running startup reconciliation: ${e.message}", e)
            }
        }

        serviceScope.launch {
            configRepo.configFlow.collect { cfg ->
                riskManager.settings = riskManager.settings.copy(
                    enableDailyLossLimit = cfg.enableDailyLossLimit,
                    maxDailyLossInr = cfg.maxDailyLossInr
                )
            }
        }

        paperEngine.onTradeClosed = { pair, pnl ->
            serviceScope.launch {
                val bal = executionEngine.getAvailableBalanceInr()
                riskManager.recordTradeResult(pnl, bal, pair)
                refreshPaperState()
            }
        }

        liveEngine.onTradeClosed = { pair, pnl ->
            serviceScope.launch {
                val bal = executionEngine.getAvailableBalanceInr()
                riskManager.recordTradeResult(pnl, bal, pair)
                val syncRes = executionEngine.refreshExchangeState()
                if (syncRes.isSuccess) {
                    MarketScanState.updateExchangeSnapshot(syncRes.getOrThrow())
                }
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

        val isPaper = if (intent?.hasExtra(EXTRA_IS_PAPER) == true) {
            intent.getBooleanExtra(EXTRA_IS_PAPER, !configRepo.isLiveMode())
        } else {
            !configRepo.isLiveMode()
        }
        executionEngine = if (isPaper) paperEngine else liveEngine
        configRepo.setLiveMode(!isPaper)

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
                configRepo.setLiveMode(!executionEngine.isPaperTrading)
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
                        scanMutex.withLock {
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
            riskManager.settings = riskManager.settings.copy(
                enableDailyLossLimit = config.enableDailyLossLimit,
                maxDailyLossInr = config.maxDailyLossInr
            )
            val scanningStrategies = StrategyRegistry.getScanningStrategies()
            val scanningUniverseStrategies = StrategyRegistry.getScanningUniverseStrategies()
            val stratNames = (scanningStrategies.map { it.name } + scanningUniverseStrategies.map { it.name }).joinToString(", ")
            val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"
            AppLogManager.scanner("Scan Cycle #$cycle started: Scanning ${if (config.isMarketWideScan) "market-wide" else "${config.selectedPairs.size} pairs"} (${config.timeframe}) with $stratNames...")

            // Circuit Breaker Check: Daily drawdown cap (loss limit)
            if (riskManager.settings.enableDailyLossLimit && riskManager.isCircuitBreakerTripped()) {
                AppLogManager.w("RISK", "Scan #$cycle skipped: Daily drawdown circuit breaker tripped (loss limit reached).")
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
            val settlementRate = currencyConverter.getSettlementConversionRate()

            // 1.1 Pre-filtering Account Constraints (Balance -> Leverage -> Margin Budget -> Affordability)
            val accountConstraints = com.coindcx.trading.engine.scanner.FuturesUniverseManager.AccountConstraints(
                availableBalanceInr = initialBalanceInr,
                leverage = config.leverage,
                riskPerTradePercent = config.riskPerTradePercent,
                maxSingleExposurePercent = config.maxSingleExposurePercent,
                usdtInrRate = settlementRate,
                isLiveTrading = !executionEngine.isPaperTrading
            )

            // 2. Scan Futures Market Opportunities (Parallel Multi-Strategy with Account Constraints)
            val rawOpportunities = scannerEngine.scanMarket(
                config = config,
                strategies = scanningStrategies,
                universeStrategies = scanningUniverseStrategies,
                executionEngine = executionEngine,
                accountConstraints = accountConstraints
            )

            // 2.5. Process Strategy-Triggered Exits on Open Positions (Strict Ownership Isolation)
            for (opp in rawOpportunities) {
                if (opp.signal.action == com.coindcx.trading.engine.SignalAction.EXIT) {
                    val tradeMeta = activeTradeMetadata[opp.pair]
                    val oppStratId = opp.strategyId.ifBlank { opp.signal.strategyId }
                    if (tradeMeta != null && !oppStratId.equals(tradeMeta.strategyId, ignoreCase = true)) {
                        AppLogManager.d(
                            "STRATEGY_EXIT",
                            "Ignoring EXIT signal on ${opp.pair} from [$oppStratId] because position is owned by [${tradeMeta.strategyId}]"
                        )
                        continue
                    }

                    val activePos = executionEngine.getActivePosition(opp.pair)
                    if (activePos != null && activePos.isOpen) {
                        val posTradeId = activePos.id
                        val exitCondition = "${oppStratId.uppercase()}_EXIT_SIGNAL"
                        AppLogManager.tradeLifecycle(
                            event = "EXIT_SIGNAL",
                            tradeId = posTradeId,
                            symbol = opp.pair,
                            mode = modeLabel,
                            attributes = mapOf(
                                "exit_condition" to exitCondition,
                                "strategy" to oppStratId,
                                "reason" to opp.signal.reason,
                                "current_price" to "%.4f".format(opp.currentPrice)
                            ),
                            narrative = "Strategy [%s] EXIT signal on %s: %s @ %.4f".format(oppStratId.uppercase(), opp.pair, opp.signal.reason, opp.currentPrice)
                        )
                        executionEngine.exitPosition(opp.pair, opp.currentPrice, opp.signal.reason, posTradeId)
                        activeTradeMetadata.remove(opp.pair)
                    }
                }
            }

            // 3. Trade Candidate Selection & Dynamic Capacity Allocation (§E, §F)
            val openPositions = executionEngine.getAllOpenPositions().filter { it.isOpen }
            val totalEquityInr = if (executionEngine.isPaperTrading) {
                paperEngine.accountManager.getAccountSummary().totalEquityInr
            } else {
                val lockedMarginInr = openPositions.sumOf { pos ->
                    val posMarginUsdt = pos.lockedMargin.takeIf { it > 0.0 } ?: pos.lockedUserMargin
                    currencyConverter.convertUsdtToInr(posMarginUsdt)
                }
                val unrealizedPnlInr = openPositions.sumOf { pos ->
                    val pnlUsdt = com.coindcx.trading.engine.PnlEngine.calculateUnrealizedPnl(pos)
                    currencyConverter.convertUsdtToInr(pnlUsdt)
                }
                (initialBalanceInr + lockedMarginInr + unrealizedPnlInr).coerceAtLeast(initialBalanceInr)
            }

            val dynamicMinNotionalInr = currencyConverter.getDynamicMinNotionalInr()
            val selection = tradeCandidateSelector.selectCandidates(
                candidates = rawOpportunities,
                accountEquityInr = totalEquityInr,
                availableCashInr = initialBalanceInr,
                activePositionsCount = openPositions.size,
                maxConcurrentPositions = riskManager.settings.maxConcurrentPositions.coerceAtLeast(1),
                leverage = config.leverage,
                minExchangeNotionalInr = dynamicMinNotionalInr,
                riskPerTradePercent = config.riskPerTradePercent,
                maxPortfolioRiskPercent = 4.0
            )

            val approvedCandidates = selection.approvedTrades

            // 4. Initial Dynamic Allocation (Risk-Parity & Balance-Aware) for Approved Candidates (M <= K)
            val allocation = allocator.allocateCapital(
                accountEquityInr = totalEquityInr,
                availableCashInr = initialBalanceInr,
                activePositionsCount = openPositions.size,
                leverage = config.leverage,
                rankedOpportunities = approvedCandidates,
                riskSettings = riskManager.settings,
                minExchangeNotionalInr = dynamicMinNotionalInr,
                riskPerTradePercent = config.riskPerTradePercent,
                safetyReservePercent = 0.0,
                maxSingleExposurePercent = config.maxSingleExposurePercent
            )
            val selectedOpportunities = allocation.allRankedOpportunities
            MarketScanState.update(selectedOpportunities, allocation, cycle)

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

            for (opp in approvedCandidates) {
                val tradeId = opp.signal.tradeId ?: AppLogManager.TradeIdGenerator.generate(opp.pair)

                // Signal Action Check: Must be actionable entry
                if (!opp.isBuy && !opp.isSell) {
                    AppLogManager.d("EVAL", "[${opp.pair}] Watching: ${opp.signal.reason} [Conf: ${"%.1f".format(opp.confidenceScore)}%]")
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.WATCHING,
                            reason = "Watching — ${opp.signal.reason} [Conf: ${"%.1f".format(opp.confidenceScore)}%]"
                        )
                    )
                    continue
                }

                // Initial Entry Evaluation Structured Log
                val activeStratName = opp.strategyName.ifBlank { (opp.strategyId.ifBlank { opp.signal.strategyId }).uppercase() }
                val evalAttributes = mutableMapOf<String, Any>(
                    "rank" to opp.rank,
                    "strategy" to activeStratName,
                    "action" to opp.actionLabel,
                    "price" to "%.4f".format(opp.currentPrice),
                    "confidence_score" to opp.confidenceScore,
                    "net_rr" to "%.2f".format(opp.netRiskRewardRatio),
                    "htf_align" to opp.htfAlignment,
                    "selection_reason" to opp.selectionReason
                )
                if (opp.signal.fastEma > 0.0) evalAttributes["fast_ema"] = "%.4f".format(opp.signal.fastEma)
                if (opp.signal.slowEma > 0.0) evalAttributes["slow_ema"] = "%.4f".format(opp.signal.slowEma)
                if (opp.contributingStrategies.isNotEmpty()) {
                    evalAttributes["contributing_strategies"] = opp.contributingStrategies.joinToString(", ") { "${it.strategyName} (${"%.1f".format(it.confidence)}%)" }
                }

                AppLogManager.tradeLifecycle(
                    event = "ENTRY_EVALUATION",
                    tradeId = tradeId,
                    symbol = opp.pair,
                    mode = modeLabel,
                    attributes = evalAttributes,
                    narrative = "Evaluating %s candidate: Rank #%d [%s] %s %s @ %.4f (Confidence: %.1f%%, Net R:R: %.2f, HTF: %s)"
                        .format(modeLabel, opp.rank, activeStratName, opp.pair, opp.actionLabel, opp.currentPrice, opp.confidenceScore, opp.netRiskRewardRatio, opp.htfAlignment)
                )

                // Circuit Breaker / Cooldown Gate
                if (isCooldown) {
                    AppLogManager.tradeLifecycle(
                        event = "RISK_FILTER_REJECTED",
                        tradeId = tradeId,
                        symbol = opp.pair,
                        mode = modeLabel,
                        attributes = mapOf(
                            "gate" to "CIRCUIT_BREAKER_COOLDOWN",
                            "remaining_minutes" to riskManager.getCooldownRemainingMinutes(),
                            "reason" to "Consecutive loss cooldown active"
                        ),
                        narrative = "Gate 0 (Circuit Breaker Cooldown) REJECTED: %dm cooldown active after consecutive losses"
                            .format(riskManager.getCooldownRemainingMinutes())
                    )
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

                // Gate 1: Strategy Consensus Validation Gate
                if (!opp.isApproved) {
                    AppLogManager.tradeLifecycle(
                        event = "RISK_FILTER_REJECTED",
                        tradeId = tradeId,
                        symbol = opp.pair,
                        mode = modeLabel,
                        attributes = mapOf(
                            "gate" to "GATE_1_CONSENSUS",
                            "confidence_score" to opp.confidenceScore,
                            "reason" to (opp.rejectionReason ?: "Strategy consensus not met")
                        ),
                        narrative = "Gate 1 (Strategy Consensus) REJECTED: Confidence %.1f%%. Rejection: %s"
                            .format(opp.confidenceScore, opp.rejectionReason ?: "Strategy consensus not met")
                    )
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.REJECTED_LOW_QUALITY,
                            reason = "Rejected — Strategy consensus not met: ${opp.rejectionReason ?: "Insufficient confidence"}"
                        )
                    )
                    continue
                }

                // Gate 1.5: Canary Guardrail — Restrict Live Trading to Tier-1 Majors unless Tier-2 toggle is enabled
                val isTier1 = scannerEngine.universeManager.isTier1Major(opp.pair)
                val allowTier2 = config.allowTier2AltcoinsLive
                if (!executionEngine.isPaperTrading && !isTier1 && !allowTier2) {
                    AppLogManager.tradeLifecycle(
                        event = "RISK_FILTER_REJECTED",
                        tradeId = tradeId,
                        symbol = opp.pair,
                        mode = modeLabel,
                        attributes = mapOf(
                            "gate" to "GATE_1_5_CANARY",
                            "reason" to "Tier-2 Altcoin restricted to Paper trading until canary validation complete"
                        ),
                        narrative = "Gate 1.5 (Canary Guardrail) REJECTED: %s is Tier-2 Altcoin restricted to Paper trading".format(opp.pair)
                    )
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_PORTFOLIO_LIMIT,
                            reason = "Skipped — Tier-2 Altcoin restricted to Paper trading until canary validation complete"
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
                    AppLogManager.tradeLifecycle(
                        event = "RISK_FILTER_REJECTED",
                        tradeId = tradeId,
                        symbol = opp.pair,
                        mode = modeLabel,
                        attributes = mapOf(
                            "gate" to "GATE_2_PORTFOLIO_MACRO",
                            "reason" to portfolioCheck.reason
                        ),
                        narrative = "Gate 2 (Portfolio & Macro Correlation) REJECTED: %s".format(portfolioCheck.reason)
                    )
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

                // Gate 3: Volatility-Adjusted Risk Parity Sizing (Single-pass Allocation SSOT Contract)
                val fundedOpp = allocation.fundedOpportunities.firstOrNull { it.pair == opp.pair }
                if (fundedOpp == null) {
                    val unfundedOpp = allocation.unfundedOpportunities.firstOrNull { it.pair == opp.pair }
                    val rawReason = unfundedOpp?.statusMessage?.removePrefix("Unfunded: ")?.trim()
                    val rejectionReason = if (!rawReason.isNullOrBlank()) {
                        rawReason
                    } else {
                        opp.rejectionReason ?: "Capital allocation floor or budget exhausted"
                    }
                    AppLogManager.tradeLifecycle(
                        event = "RISK_FILTER_REJECTED",
                        tradeId = tradeId,
                        symbol = opp.pair,
                        mode = modeLabel,
                        attributes = mapOf(
                            "gate" to "GATE_3_SIZING",
                            "reason" to rejectionReason,
                            "available_balance_inr" to "₹%.2f".format(inMemoryAvailableBalance),
                            "dynamic_min_notional_floor_inr" to "₹%.2f".format(dynamicMinNotionalInr)
                        ),
                        narrative = "Gate 3 (Risk Sizing & Allocation) REJECTED: %s".format(rejectionReason)
                    )
                    AppLogManager.w("GATE_3_SIZING", "[${opp.pair}] Strategy consensus ${opp.actionLabel} -> rejected: $rejectionReason")
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_INSUFFICIENT_BALANCE,
                            reason = "Skipped — $rejectionReason"
                        )
                    )
                    continue
                }

                val hasExplicitSl = (opp.signal.stopLossPrice ?: 0.0) > 0.0
                val slPrice = if (hasExplicitSl) opp.signal.stopLossPrice!! else (if (opp.isBuy) opp.currentPrice * (1.0 - config.stopLossPercent / 100.0) else opp.currentPrice * (1.0 + config.stopLossPercent / 100.0))
                val slMethodTag = if (hasExplicitSl) "STRATEGY_SIGNAL" else "CONFIG_SL_${config.stopLossPercent}PCT"
                val slDistance = kotlin.math.abs(opp.currentPrice - slPrice)
                val slDistPct = if (opp.currentPrice > 0) (slDistance / opp.currentPrice) * 100.0 else config.stopLossPercent

                val marginToAllocate: Double = fundedOpp.allocatedMarginInr
                val actualLeverage: Int = config.leverage.coerceIn(1, riskManager.settings.maxLeverage)
                val notionalInr: Double = marginToAllocate * actualLeverage
                val targetRiskInr: Double = notionalInr * (slDistPct / 100.0)

                val tpPrice = opp.signal.takeProfitPrice ?: (if (opp.isBuy) opp.currentPrice * (1.0 + config.targetPricePercent / 100.0) else opp.currentPrice * (1.0 - config.targetPricePercent / 100.0))
                val targetDistance = kotlin.math.abs(tpPrice - opp.currentPrice)
                val targetDistPct = if (opp.currentPrice > 0) (targetDistance / opp.currentPrice) * 100.0 else config.targetPricePercent
                val rrRatio = if (opp.signal.riskRewardRatio > 0) opp.signal.riskRewardRatio else (config.targetPricePercent / config.stopLossPercent)
                val expectedProfitInr = targetRiskInr * rrRatio
                val expectedLossInr = targetRiskInr

                // Stop-Loss Calculation Log
                AppLogManager.tradeLifecycle(
                    event = "STOP_LOSS_CALCULATION",
                    tradeId = tradeId,
                    symbol = opp.pair,
                    mode = modeLabel,
                    attributes = mapOf(
                        "side" to (if (opp.isBuy) "LONG" else "SHORT"),
                        "entry_price" to "%.4f".format(opp.currentPrice),
                        "sl_method" to slMethodTag,
                        "atr_14" to "%.4f".format(opp.signal.atr),
                        "atr_mult" to "%.2fx".format(opp.signal.atrMultiplier),
                        "sl_dist" to "%.4f".format(slDistance),
                        "sl_dist_pct" to "%.2f%%".format(slDistPct),
                        "stop_loss" to "%.4f".format(slPrice),
                        "account_balance_inr" to "₹%.2f".format(inMemoryAvailableBalance),
                        "risk_pct" to "%.1f%%".format(config.riskPerTradePercent),
                        "risk_amount_inr" to "₹%.2f".format(targetRiskInr)
                    ),
                    narrative = "Entry = %.4f -> SL distance = %.4f (%.2f%%) [%s] -> SL = %.4f -> Risk = ₹%.2f (%.1f%% of equity)"
                        .format(opp.currentPrice, slDistance, slDistPct, slMethodTag, slPrice, targetRiskInr, config.riskPerTradePercent)
                )

                // Target Calculation Log
                AppLogManager.tradeLifecycle(
                    event = "TARGET_CALCULATION",
                    tradeId = tradeId,
                    symbol = opp.pair,
                    mode = modeLabel,
                    attributes = mapOf(
                        "side" to (if (opp.isBuy) "LONG" else "SHORT"),
                        "entry_price" to "%.4f".format(opp.currentPrice),
                        "stop_loss" to "%.4f".format(slPrice),
                        "target_method" to "FIXED_RISK_REWARD",
                        "rr_ratio" to "1:%.1f".format(rrRatio),
                        "target_dist" to "%.4f".format(targetDistance),
                        "target_dist_pct" to "%.2f%%".format(targetDistPct),
                        "target" to "%.4f".format(tpPrice),
                        "expected_profit_inr" to "₹%.2f".format(expectedProfitInr),
                        "expected_loss_inr" to "₹%.2f".format(expectedLossInr)
                    ),
                    narrative = "Entry = %.4f -> Stop Loss = %.4f -> Risk = ₹%.2f -> R:R 1:%.1f -> Target = %.4f (Exp Profit: ₹%.2f, Exp Loss: ₹%.2f)"
                        .format(opp.currentPrice, slPrice, targetRiskInr, rrRatio, tpPrice, expectedProfitInr, expectedLossInr)
                )

                // Leverage & Sizing Log
                AppLogManager.tradeLifecycle(
                    event = "LEVERAGE_AND_SIZING",
                    tradeId = tradeId,
                    symbol = opp.pair,
                    mode = modeLabel,
                    attributes = mapOf(
                        "requested_leverage" to "${config.leverage}x",
                        "actual_leverage" to "${actualLeverage}x",
                        "margin_allocated_inr" to "₹%.2f".format(marginToAllocate),
                        "notional_value_inr" to "₹%.2f".format(notionalInr),
                        "dynamic_min_notional_floor_inr" to "₹%.2f".format(dynamicMinNotionalInr),
                        "available_balance_inr" to "₹%.2f".format(inMemoryAvailableBalance)
                    ),
                    narrative = "Account Balance = ₹%.2f -> Allocated Margin = ₹%.2f @ %dx leverage (Requested: %dx) -> Notional = ₹%.2f (Min Floor: ₹%.2f)"
                        .format(inMemoryAvailableBalance, marginToAllocate, actualLeverage, config.leverage, notionalInr, dynamicMinNotionalInr)
                )

                // Gate 4: Fresh In-Memory Balance Check (Defense-in-depth invariant)
                if (inMemoryAvailableBalance < marginToAllocate) {
                    AppLogManager.tradeLifecycle(
                        event = "RISK_FILTER_REJECTED",
                        tradeId = tradeId,
                        symbol = opp.pair,
                        mode = modeLabel,
                        attributes = mapOf(
                            "gate" to "GATE_4_BALANCE",
                            "available_balance_inr" to "₹%.2f".format(inMemoryAvailableBalance),
                            "margin_required_inr" to "₹%.2f".format(marginToAllocate),
                            "shortfall_inr" to "₹%.2f".format(marginToAllocate - inMemoryAvailableBalance)
                        ),
                        narrative = "Gate 4 (Defense-in-depth Balance Guard) REJECTED: Available ₹%.2f < Sized Margin ₹%.2f (Shortfall: ₹%.2f)"
                            .format(inMemoryAvailableBalance, marginToAllocate, marginToAllocate - inMemoryAvailableBalance)
                    )
                    audits.add(
                        com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                            rank = opp.rank,
                            pair = opp.pair,
                            action = opp.actionLabel,
                            status = com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_INSUFFICIENT_BALANCE,
                            reason = "Skipped — Insufficient balance guard (Available: ₹%.2f < Required: ₹%.2f)".format(inMemoryAvailableBalance, marginToAllocate)
                        )
                    )
                    continue
                }

                // All gates passed -> Approve & Construct Order!
                val approvedStratName = opp.strategyName.ifBlank { (opp.strategyId.ifBlank { opp.signal.strategyId }).uppercase() }
                val approvedAttributes = mutableMapOf<String, Any>(
                    "strategy" to approvedStratName,
                    "direction" to opp.actionLabel,
                    "entry_price" to "%.4f".format(opp.currentPrice),
                    "margin_inr" to "₹%.2f".format(marginToAllocate),
                    "leverage" to "${actualLeverage}x",
                    "confidence_score" to opp.confidenceScore,
                    "selection_reason" to opp.selectionReason
                )
                if (opp.contributingStrategies.isNotEmpty()) {
                    approvedAttributes["contributing_strategies"] = opp.contributingStrategies.joinToString(", ") { "${it.strategyName} (${it.direction}, conf: ${"%.1f".format(it.confidence)}%)" }
                }

                AppLogManager.tradeLifecycle(
                    event = "ENTRY_APPROVED",
                    tradeId = tradeId,
                    symbol = opp.pair,
                    mode = modeLabel,
                    attributes = approvedAttributes,
                    narrative = "[%s] %s signal on %s -> risk checks passed -> position size calculated -> leverage %dx -> entry approved"
                        .format(approvedStratName, opp.actionLabel, opp.pair, actualLeverage)
                )
                AppLogManager.trade("TRADE_SELECTED", "[${opp.pair}] ${opp.actionLabel} -> selected for execution | Conf: ${"%.1f".format(opp.confidenceScore)}% | Net R:R: ${"%.2f".format(opp.netRiskRewardRatio)} | Margin: ₹${"%.2f".format(marginToAllocate)}")

                try {
                    val auditJson = org.json.JSONObject().apply {
                        put("timestamp_utc", System.currentTimeMillis())
                        put("symbol", opp.pair)
                        put("is_dynamic_mover", true)
                        put("mover_mas_score", opp.marketActivityScore)
                        put("strategy_timeframe", config.timeframe)
                        put("consensus_count", opp.contributingStrategies.size.coerceAtLeast(1))
                        put("aggregated_confidence", opp.confidenceScore)
                        put("reconciled_levels", org.json.JSONObject().apply {
                            put("entry", opp.currentPrice)
                            put("stop_loss", slPrice)
                            put("take_profit", tpPrice)
                            put("net_rr", opp.netRiskRewardRatio)
                        })
                        put("account_capacity_snapshot", org.json.JSONObject().apply {
                            put("available_balance_inr", inMemoryAvailableBalance)
                            put("capacity_k", selection.capacityK)
                            put("active_positions", inMemoryOpenPositions.size)
                        })
                        put("final_decision", "APPROVED")
                        put("decision_reason", opp.selectionReason)
                    }
                    AppLogManager.scanner("[AUDIT_LINEAGE] ${auditJson.toString(2)}")
                } catch (_: Exception) {}

                AppLogManager.tradeLifecycle(
                    event = "ORDER_CONSTRUCTION",
                    tradeId = tradeId,
                    symbol = opp.pair,
                    mode = modeLabel,
                    attributes = mapOf(
                        "strategy" to approvedStratName,
                        "side" to (if (opp.isBuy) "BUY" else "SELL"),
                        "direction" to (if (opp.isBuy) "LONG" else "SHORT"),
                        "order_type" to (if (executionEngine.isPaperTrading) "MARKET" else "LIMIT"),
                        "price" to "%.4f".format(opp.currentPrice),
                        "margin_inr" to "₹%.2f".format(marginToAllocate),
                        "leverage" to "${actualLeverage}x",
                        "stop_loss" to "%.4f".format(slPrice),
                        "target" to "%.4f".format(tpPrice),
                        "reduce_only" to false,
                        "time_in_force" to "GTC"
                    ),
                    narrative = "Constructed %s %s [%s] order for %s @ %.4f (Margin: ₹%.2f @ %dx leverage | SL: %.4f | TP: %.4f)"
                        .format(modeLabel, if (opp.isBuy) "BUY" else "SELL", approvedStratName.uppercase(), opp.pair, opp.currentPrice, marginToAllocate, actualLeverage, slPrice, tpPrice)
                )

                val orderStartTime = System.currentTimeMillis()
                val execResult = try {
                    executionEngine.executeSignal(
                        signal = opp.signal.copy(stopLossPrice = slPrice, takeProfitPrice = tpPrice),
                        pair = opp.pair,
                        currentPrice = opp.currentPrice,
                        marginInr = marginToAllocate,
                        leverage = actualLeverage,
                        tradeId = tradeId
                    )
                } catch (e: Exception) {
                    AppLogManager.e("EXEC", "[${opp.pair}] Exception during order execution: ${e.message}", e)
                    ExecutionResult.Failed("Order execution threw exception: ${e.message}")
                }
                val orderDurationMs = System.currentTimeMillis() - orderStartTime

                when (execResult) {
                    is ExecutionResult.Success -> {
                        // Synchronously update in-memory counters to eliminate race conditions for subsequent candidates!
                        inMemoryAvailableBalance = (inMemoryAvailableBalance - marginToAllocate).coerceAtLeast(0.0)
                        val estimatedLiqPrice = com.coindcx.trading.engine.MaintenanceMarginSchedule.calculateEstimatedLiquidationPrice(
                            side = if (opp.isBuy) "BUY" else "SELL",
                            entryPrice = opp.currentPrice,
                            leverage = actualLeverage
                        )
                        inMemoryOpenPositions.add(
                            com.coindcx.trading.data.api.models.FuturesPosition(
                                id = execResult.orderId,
                                pair = opp.pair,
                                activePos = if (opp.isBuy) 1.0 else -1.0,
                                inactivePosBuy = 0.0,
                                inactivePosSell = 0.0,
                                avgPrice = opp.currentPrice,
                                liquidationPrice = estimatedLiqPrice,
                                lockedMargin = marginToAllocate,
                                lockedUserMargin = marginToAllocate,
                                lockedOrderMargin = 0.0,
                                takeProfitTrigger = tpPrice,
                                stopLossTrigger = slPrice,
                                leverage = actualLeverage.toDouble(),
                                maintenanceMargin = com.coindcx.trading.engine.MaintenanceMarginSchedule.getMaintenanceMarginRate(actualLeverage) * notionalInr,
                                markPrice = opp.currentPrice,
                                marginType = "ISOLATED",
                                settlementCurrencyAvgPrice = null,
                                cumulativeFundingFee = null,
                                marginCurrencyShortName = "INR",
                                updatedAt = System.currentTimeMillis()
                            )
                        )

                        val realR = kotlin.math.abs(opp.currentPrice - slPrice).coerceAtLeast(opp.currentPrice * 0.005)
                        val tradeStratId = opp.strategyId.ifBlank { opp.signal.strategyId }
                        activeTradeMetadata[opp.pair] = ActiveTradeMetadata(
                            pair = opp.pair,
                            isLong = opp.isBuy,
                            entryPrice = opp.currentPrice,
                            initialSl = slPrice,
                            initialTp = tpPrice,
                            riskPerUnit = realR,
                            strategyId = tradeStratId
                        )

                        AppLogManager.trade("ORDER_SUBMITTED", "[${opp.pair}] ${opp.actionLabel} -> order placed on exchange | Margin: ₹${"%.2f".format(marginToAllocate)} | Leverage: ${actualLeverage}x")
                        audits.add(
                            com.coindcx.trading.engine.scanner.TradeExecutionAudit(
                                rank = opp.rank,
                                pair = opp.pair,
                                action = opp.actionLabel,
                                status = com.coindcx.trading.engine.scanner.AuditStatus.EXECUTED,
                                reason = "Executed — Placed ${opp.actionLabel} [Conf: ${"%.1f".format(opp.confidenceScore)}%] with ₹${"%.0f".format(marginToAllocate)} risk margin @ ${actualLeverage}x"
                            )
                        )
                        AppLogManager.trade("EXEC", "Rank #${opp.rank} ${opp.pair} (${opp.actionLabel}, Conf: ${"%.1f".format(opp.confidenceScore)}%) executed in ${orderDurationMs}ms: ${execResult.message}")

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
                                reason = "Failed (${orderDurationMs}ms) — ${execResult.error}"
                            )
                        )
                        AppLogManager.e("EXEC", "[${opp.pair}] Order execution failed in ${orderDurationMs}ms: ${execResult.error}")
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

            AppLogManager.scanner("Scan #$cycle complete: Scanned ${rawOpportunities.size} pairs. Approved candidates: ${approvedCandidates.size} (Dynamic Capacity K=${selection.capacityK}: Slots: ${selection.capacitySlots}, Margin: ${selection.capacityMargin}, Risk: ${selection.capacityRisk}). Executed: $executedCount | Filtered: $watchingCount watching, $rejectedCount low quality, $skippedLimitCount risk limit, $skippedExistingCount held, $skippedBalanceCount balance.")

            // Update Notification
            val finalBalance = executionEngine.getAvailableBalanceInr()
            updateNotification(
                "Bot Active ($modeLabel) | Approved: ${approvedCandidates.size} (K=${selection.capacityK})",
                "Cycle #$cycle | Bal: ₹%.0f | Executed: $executedCount | Next: ${config.scanIntervalMinutes}m".format(finalBalance)
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
                    } else {
                        val syncRes = executionEngine.refreshExchangeState()
                        if (syncRes.isSuccess) {
                            val snapshot = syncRes.getOrThrow()
                            MarketScanState.updateExchangeSnapshot(snapshot)

                            // 1. Stale Entry Order Reaper & Partial Fill Pruning (P1)
                            val activePairs = snapshot.openPositions
                                .filter { it.isOpen && kotlin.math.abs(it.activePos) > 0.0 }
                                .map { it.pair }
                                .toSet()
                            val ttlMs = riskManager.settings.entryOrderTtlSeconds * 1000L
                            orderManager.reapStaleEntryOrders(ttlMs = ttlMs, activePositionPairs = activePairs)

                            // 2. Client-side Stop Loss, Dynamic Breakeven & Take Profit Watchdog
                            val currentOpenPairs = mutableSetOf<String>()
                            for (pos in snapshot.openPositions) {
                                if (!pos.isOpen || kotlin.math.abs(pos.activePos) <= 0.0) continue
                                currentOpenPairs.add(pos.pair)

                                val markPrice = pos.markPrice ?: continue
                                val meta = activeTradeMetadata[pos.pair]
                                val originalSl = meta?.initialSl ?: pos.stopLossTrigger
                                val tp = meta?.initialTp ?: pos.takeProfitTrigger

                                // Client-side Fixed Stop Loss & Take Profit Watchdog (Emergency Fallback)
                                var shouldTriggerExit = false
                                var exitReason = ""

                                if (pos.isLong) {
                                    if (originalSl != null && originalSl > 0.0 && markPrice <= originalSl) {
                                        shouldTriggerExit = true
                                        exitReason = "Fixed SL watchdog breach (Mark: %.4f <= SL: %.4f)".format(markPrice, originalSl)
                                    } else if (tp != null && tp > 0.0 && markPrice >= tp) {
                                        shouldTriggerExit = true
                                        exitReason = "Fixed TP watchdog breach (Mark: %.4f >= TP: %.4f)".format(markPrice, tp)
                                    }
                                } else if (pos.isShort) {
                                    if (originalSl != null && originalSl > 0.0 && markPrice >= originalSl) {
                                        shouldTriggerExit = true
                                        exitReason = "Fixed SL watchdog breach (Mark: %.4f >= SL: %.4f)".format(markPrice, originalSl)
                                    } else if (tp != null && tp > 0.0 && markPrice <= tp) {
                                        shouldTriggerExit = true
                                        exitReason = "Fixed TP watchdog breach (Mark: %.4f <= TP: %.4f)".format(markPrice, tp)
                                    }
                                }

                                if (shouldTriggerExit) {
                                    AppLogManager.w("WATCHDOG", "[${pos.pair}] Triggering emergency client-side exit: $exitReason")
                                    executionEngine.exitPosition(pos.pair, markPrice, exitReason)
                                    activeTradeMetadata.remove(pos.pair)
                                }
                            }
                            // Clean up active metadata for closed positions
                            activeTradeMetadata.keys.retainAll(currentOpenPairs)
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
