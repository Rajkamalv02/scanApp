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
        scannerEngine = MarketScannerEngine(ApiClient.apiService)
        liveEngine = LiveExecutionEngine(orderManager, ApiClient.apiService, currencyConverter, scannerEngine.universeManager)
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
            val scanningStrategies = StrategyRegistry.getScanningStrategies()
            val stratNames = scanningStrategies.joinToString(", ") { it.name }
            val modeLabel = if (executionEngine.isPaperTrading) "PAPER" else "LIVE"
            AppLogManager.scanner("Scan Cycle #$cycle started: Scanning ${if (config.isMarketWideScan) "market-wide" else "${config.selectedPairs.size} pairs"} (${config.timeframe}) with $stratNames...")

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

            // 2. Scan Futures Market Opportunities (Parallel Multi-Strategy)
            val rawOpportunities = scannerEngine.scanMarket(config, scanningStrategies, executionEngine)

            // 2.5. Process Strategy-Triggered Exits on Open Positions (e.g. EMA Reversal Crossover or Confluence Reversal)
            for (opp in rawOpportunities) {
                if (opp.signal.action == com.coindcx.trading.engine.SignalAction.EXIT) {
                    val activePos = executionEngine.getActivePosition(opp.pair)
                    if (activePos != null && activePos.isOpen) {
                        val posTradeId = activePos.id
                        val exitCondition = when {
                            opp.strategyId.contains("confluence") -> "CONFLUENCE_REVERSAL"
                            else -> "EMA_REVERSAL_CROSS"
                        }
                        AppLogManager.tradeLifecycle(
                            event = "EXIT_SIGNAL",
                            tradeId = posTradeId,
                            symbol = opp.pair,
                            mode = modeLabel,
                            attributes = mapOf(
                                "exit_condition" to exitCondition,
                                "strategy" to opp.strategyId.ifBlank { opp.signal.strategyId },
                                "reason" to opp.signal.reason,
                                "current_price" to "%.4f".format(opp.currentPrice)
                            ),
                            narrative = "Strategy [%s] EXIT signal on %s: %s @ %.4f".format(opp.strategyId.uppercase(), opp.pair, opp.signal.reason, opp.currentPrice)
                        )
                        executionEngine.exitPosition(opp.pair, opp.currentPrice, opp.signal.reason, posTradeId)
                    }
                }
            }

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
                val tradeId = opp.signal.tradeId ?: AppLogManager.TradeIdGenerator.generate(opp.pair)

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

                // Initial Entry Evaluation Structured Log
                AppLogManager.tradeLifecycle(
                    event = "ENTRY_EVALUATION",
                    tradeId = tradeId,
                    symbol = opp.pair,
                    mode = modeLabel,
                    attributes = mapOf(
                        "rank" to opp.rank,
                        "strategy" to opp.strategyId.ifBlank { opp.signal.strategyId },
                        "action" to opp.actionLabel,
                        "price" to "%.4f".format(opp.currentPrice),
                        "fast_ema" to "%.4f".format(opp.signal.fastEma),
                        "slow_ema" to "%.4f".format(opp.signal.slowEma),
                        "quality_score" to opp.qualityScore,
                        "quality_category" to opp.qualityCategory,
                        "net_rr" to "%.2f".format(opp.netRiskRewardRatio),
                        "htf_align" to opp.htfAlignment
                    ),
                    narrative = "Evaluating %s candidate: Rank #%d [%s] %s %s @ %.4f (Quality: %d/100 %s, Net R:R: %.2f, HTF: %s)"
                        .format(modeLabel, opp.rank, (opp.strategyId.ifBlank { opp.signal.strategyId }).uppercase(), opp.pair, opp.actionLabel, opp.currentPrice, opp.qualityScore, opp.qualityCategory, opp.netRiskRewardRatio, opp.htfAlignment)
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

                // Gate 1: Quality Score Rubric Gate (Must be approved by TradeQualityScorer)
                if (!opp.isApproved) {
                    AppLogManager.tradeLifecycle(
                        event = "RISK_FILTER_REJECTED",
                        tradeId = tradeId,
                        symbol = opp.pair,
                        mode = modeLabel,
                        attributes = mapOf(
                            "gate" to "GATE_1_QUALITY",
                            "quality_score" to opp.qualityScore,
                            "quality_category" to opp.qualityCategory,
                            "reason" to (opp.rejectionReason ?: "Insufficient confluence")
                        ),
                        narrative = "Gate 1 (Quality Rubric) REJECTED: Score %d/100 (%s). Rejection: %s"
                            .format(opp.qualityScore, opp.qualityCategory, opp.rejectionReason ?: "Insufficient confluence")
                    )
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

                // Gate 3: Volatility-Adjusted Risk Parity Sizing (1% account risk / SL distance %)
                val slPrice = opp.signal.stopLossPrice ?: (if (opp.isBuy) opp.currentPrice * 0.98 else opp.currentPrice * 1.02)
                val slDistance = kotlin.math.abs(opp.currentPrice - slPrice)
                val slDistPct = if (opp.currentPrice > 0) (slDistance / opp.currentPrice) * 100.0 else 0.0
                val riskPerTradePct = riskManager.settings.riskPerTradePercent
                val targetRiskInr = inMemoryAvailableBalance * (riskPerTradePct / 100.0)

                val dynamicMinNotionalInr = currencyConverter.getDynamicMinNotionalInr()
                val marginToAllocate = riskManager.calculateRiskSizedMargin(
                    balanceInr = inMemoryAvailableBalance,
                    entryPrice = opp.currentPrice,
                    stopLossPrice = slPrice,
                    leverage = config.leverage,
                    minMarginInr = config.minMarginPerTradeInr,
                    minOrderNotionalInr = dynamicMinNotionalInr
                )
                val requestedLeverage = config.leverage
                val actualLeverage = requestedLeverage.coerceIn(1, riskManager.settings.maxLeverage)
                val notionalInr = marginToAllocate * actualLeverage

                val tpPrice = opp.signal.takeProfitPrice ?: (if (opp.isBuy) opp.currentPrice + (slDistance * 2.0) else opp.currentPrice - (slDistance * 2.0))
                val targetDistance = kotlin.math.abs(tpPrice - opp.currentPrice)
                val targetDistPct = if (opp.currentPrice > 0) (targetDistance / opp.currentPrice) * 100.0 else 0.0
                val rrRatio = if (opp.signal.riskRewardRatio > 0) opp.signal.riskRewardRatio else 2.0
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
                        "sl_method" to "ATR_MULTIPLIER",
                        "atr_14" to "%.4f".format(opp.signal.atr),
                        "atr_mult" to "%.2fx".format(opp.signal.atrMultiplier),
                        "sl_dist" to "%.4f".format(slDistance),
                        "sl_dist_pct" to "%.2f%%".format(slDistPct),
                        "stop_loss" to "%.4f".format(slPrice),
                        "account_balance_inr" to "₹%.2f".format(inMemoryAvailableBalance),
                        "risk_pct" to "%.1f%%".format(riskPerTradePct),
                        "risk_amount_inr" to "₹%.2f".format(targetRiskInr)
                    ),
                    narrative = "Entry = %.4f -> SL distance = %.4f (%.2f%%) -> SL = %.4f -> Risk = ₹%.2f (%.1f%% of ₹%.2f)"
                        .format(opp.currentPrice, slDistance, slDistPct, slPrice, targetRiskInr, riskPerTradePct, inMemoryAvailableBalance)
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
                        "requested_leverage" to "${requestedLeverage}x",
                        "actual_leverage" to "${actualLeverage}x",
                        "margin_allocated_inr" to "₹%.2f".format(marginToAllocate),
                        "notional_value_inr" to "₹%.2f".format(notionalInr),
                        "available_balance_inr" to "₹%.2f".format(inMemoryAvailableBalance)
                    ),
                    narrative = "Account Balance = ₹%.2f -> Allocated Margin = ₹%.2f @ %dx leverage (Requested: %dx) -> Notional Value = ₹%.2f"
                        .format(inMemoryAvailableBalance, marginToAllocate, actualLeverage, requestedLeverage, notionalInr)
                )

                // Gate 4: Fresh In-Memory Balance Check
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
                        narrative = "Gate 4 (Balance Check) REJECTED: Available ₹%.2f < Sized Margin ₹%.2f (Shortfall: ₹%.2f)"
                            .format(inMemoryAvailableBalance, marginToAllocate, marginToAllocate - inMemoryAvailableBalance)
                    )
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

                // All gates passed -> Approve & Construct Order!
                AppLogManager.tradeLifecycle(
                    event = "ENTRY_APPROVED",
                    tradeId = tradeId,
                    symbol = opp.pair,
                    mode = modeLabel,
                    attributes = mapOf(
                        "direction" to opp.actionLabel,
                        "entry_price" to "%.4f".format(opp.currentPrice),
                        "margin_inr" to "₹%.2f".format(marginToAllocate),
                        "leverage" to "${actualLeverage}x"
                    ),
                    narrative = "EMA crossover detected -> %s signal -> risk checks passed -> position size calculated -> leverage %dx -> entry approved"
                        .format(opp.actionLabel, actualLeverage)
                )

                AppLogManager.tradeLifecycle(
                    event = "ORDER_CONSTRUCTION",
                    tradeId = tradeId,
                    symbol = opp.pair,
                    mode = modeLabel,
                    attributes = mapOf(
                        "strategy" to opp.strategyId.ifBlank { opp.signal.strategyId },
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
                        .format(modeLabel, if (opp.isBuy) "BUY" else "SELL", (opp.strategyId.ifBlank { opp.signal.strategyId }).uppercase(), opp.pair, opp.currentPrice, marginToAllocate, actualLeverage, slPrice, tpPrice)
                )

                val orderStartTime = System.currentTimeMillis()
                val execResult = try {
                    executionEngine.executeSignal(
                        signal = opp.signal,
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
                        AppLogManager.trade("EXEC", "Rank #${opp.rank} ${opp.pair} (${opp.actionLabel}, Score: ${opp.qualityScore}) executed in ${orderDurationMs}ms: ${execResult.message}")

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
