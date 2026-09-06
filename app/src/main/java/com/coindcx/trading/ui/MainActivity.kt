package com.coindcx.trading.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coindcx.trading.R
import com.coindcx.trading.data.api.ApiClient
import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.config.TradingConfigRepository
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.OrderEntity
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.data.db.entities.TradeEntity
import com.coindcx.trading.databinding.ActivityMainBinding
import com.coindcx.trading.databinding.DialogClosedTradeDetailBinding
import com.coindcx.trading.databinding.ItemActivePositionBinding
import com.coindcx.trading.databinding.ItemPaperClosedBinding
import com.coindcx.trading.databinding.ItemPaperHoldingBinding
import com.coindcx.trading.databinding.ItemPaperPendingBinding
import com.coindcx.trading.databinding.ItemRankedOpportunityBinding
import com.coindcx.trading.engine.PnlEngine
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.StrategyRegistry
import com.coindcx.trading.engine.allocation.AllocationEngine
import com.coindcx.trading.engine.currency.CurrencyConverter
import com.coindcx.trading.engine.scanner.MarketOpportunity
import com.coindcx.trading.engine.scanner.MarketScanState
import com.coindcx.trading.engine.scanner.OpportunityLifecycle
import com.coindcx.trading.engine.paper.PaperAccountManager
import com.coindcx.trading.service.TradingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private val configRepo by lazy { TradingConfigRepository.getInstance(this) }
    private val currencyConverter by lazy { CurrencyConverter(ApiClient.apiService) }
    private val allocationEngine by lazy { AllocationEngine() }
    private val paperAccountManager by lazy { PaperAccountManager(this, db) }

    private var isUserSwitchingMode = true
    private var currentPortfolioTab = 0 // 0 = Holding, 1 = Pending, 2 = Closed
    private var cachedHoldingTrades: List<TradeEntity> = emptyList()
    private var cachedPendingOrders: List<OrderEntity> = emptyList()
    private var cachedClosedTrades: List<TradeEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        StrategyRegistry.init(this)

        setupTradingControls()
        setupStrategySelector()
        setupExecutionModeToggle()
        setupButtons()
        setupPaperAccountControls()

        observeMarketScanState()
        observePaperTrades()
        observeSystemLogs()
        checkBatteryOptimization()
        startLivePolling()

        // Auto-resume scanner if bot was active
        if (configRepo.isBotRunning()) {
            binding.tvBotStatus.text = "ACTIVE"
            binding.tvBotStatus.setTextColor(getColor(R.color.accent_green))
            val isPaper = !binding.switchLiveMode.isChecked
            sendServiceIntent(TradingForegroundService.ACTION_START) {
                putExtra(TradingForegroundService.EXTRA_IS_PAPER, isPaper)
            }
        }
    }

    private fun sendServiceIntent(actionName: String, configure: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, TradingForegroundService::class.java).apply {
            action = actionName
            configure?.invoke(this)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Service command: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTradingControls() {
        val config = configRepo.configFlow.value

        // 1. Leverage Slider
        binding.sliderLeverage.value = config.leverage.toFloat()
        updateLeverageUi(config.leverage)

        binding.sliderLeverage.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val lev = value.toInt()
                updateLeverageUi(lev)
                configRepo.updateLeverage(lev)

                if (lev >= 8) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ High Leverage Warning (${lev}x)")
                        .setMessage("High leverage drastically tightens your liquidation threshold.\n\nEven small market retracements can lead to rapid capital loss. Ensure stop losses are respected.")
                        .setPositiveButton("I Understand", null)
                        .show()
                }
            }
        }

        // 2. Timeframe Selection
        when (config.timeframe) {
            "1m" -> binding.chipTimeframe1m.isChecked = true
            "15m" -> binding.chipTimeframe15m.isChecked = true
            "1h" -> binding.chipTimeframe1h.isChecked = true
            "1d" -> binding.chipTimeframe1d.isChecked = true
            else -> binding.chipTimeframe15m.isChecked = true
        }

        binding.chipGroupTimeframe.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val selectedTimeframe = when (selectedId) {
                R.id.chipTimeframe1m -> "1m"
                R.id.chipTimeframe15m -> "15m"
                R.id.chipTimeframe1h -> "1h"
                R.id.chipTimeframe1d -> "1d"
                else -> "15m"
            }
            configRepo.updateTimeframe(selectedTimeframe)
            Toast.makeText(this, "Scan Timeframe: $selectedTimeframe", Toast.LENGTH_SHORT).show()
        }

        // 2b. Automatic Scan Frequency Selection
        when (config.scanIntervalMinutes) {
            1 -> binding.chipInterval1m.isChecked = true
            2 -> binding.chipInterval2m.isChecked = true
            5 -> binding.chipInterval5m.isChecked = true
            15 -> binding.chipInterval15m.isChecked = true
            30 -> binding.chipInterval30m.isChecked = true
            60 -> binding.chipInterval1h.isChecked = true
            else -> binding.chipInterval2m.isChecked = true
        }

        binding.chipGroupScanInterval.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val minutes = when (selectedId) {
                R.id.chipInterval1m -> 1
                R.id.chipInterval2m -> 2
                R.id.chipInterval5m -> 5
                R.id.chipInterval15m -> 15
                R.id.chipInterval30m -> 30
                R.id.chipInterval1h -> 60
                else -> 2
            }
            configRepo.updateScanInterval(minutes)
            sendServiceIntent(TradingForegroundService.ACTION_UPDATE_CONFIG)
            Toast.makeText(this, "Auto-Scan Interval: ${minutes}m", Toast.LENGTH_SHORT).show()
        }

        // 3. Minimum Margin Allocation Presets
        when (config.minMarginPerTradeInr.toInt()) {
            500 -> binding.chipMargin500.isChecked = true
            1000 -> binding.chipMargin1000.isChecked = true
            2500 -> binding.chipMargin2500.isChecked = true
            5000 -> binding.chipMargin5000.isChecked = true
            else -> binding.chipMargin500.isChecked = true
        }

        binding.chipGroupMargin.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val marginInr = when (selectedId) {
                R.id.chipMargin500 -> 500.0
                R.id.chipMargin1000 -> 1000.0
                R.id.chipMargin2500 -> 2500.0
                R.id.chipMargin5000 -> 5000.0
                else -> 500.0
            }
            configRepo.updateMinMargin(marginInr)
            binding.tvMinMarginDisplay.text = "₹ %.0f".format(marginInr)
            Toast.makeText(this, "Min Margin per Trade: ₹%.0f".format(marginInr), Toast.LENGTH_SHORT).show()
        }

        // 4. Market Wide Scan Toggle
        binding.switchMarketWide.isChecked = config.isMarketWideScan
        binding.switchMarketWide.setOnCheckedChangeListener { _, isChecked ->
            configRepo.updateScanMode(isChecked)
            val modeText = if (isChecked) "Market-Wide (All Liquid Futures)" else "Specific Top Pairs"
            Toast.makeText(this, "Scan Scope: $modeText", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLeverageUi(leverage: Int) {
        binding.tvLeverageValue.text = "${leverage}x"
        when {
            leverage <= 3 -> {
                binding.tvLeverageValue.setTextColor(getColor(R.color.accent_green))
                binding.tvLeverageRiskBadge.text = "LOW RISK"
                binding.tvLeverageRiskBadge.setTextColor(getColor(R.color.accent_green))
            }
            leverage <= 7 -> {
                binding.tvLeverageValue.setTextColor(getColor(R.color.accent_amber))
                binding.tvLeverageRiskBadge.text = "MODERATE RISK"
                binding.tvLeverageRiskBadge.setTextColor(getColor(R.color.accent_amber))
            }
            else -> {
                binding.tvLeverageValue.setTextColor(getColor(R.color.accent_red))
                binding.tvLeverageRiskBadge.text = "HIGH RISK"
                binding.tvLeverageRiskBadge.setTextColor(getColor(R.color.accent_red))
            }
        }
    }

    private fun setupStrategySelector() {
        val strategies = StrategyRegistry.availableStrategies
        val names = strategies.map { it.name }

        val adapter = ArrayAdapter(
            this,
            R.layout.item_strategy_spinner,
            names
        ).apply {
            setDropDownViewResource(R.layout.item_strategy_dropdown)
        }

        binding.spinnerStrategy.adapter = adapter

        val activeIndex = strategies.indexOfFirst { it.id == StrategyRegistry.activeStrategy.id }
        if (activeIndex >= 0) {
            binding.spinnerStrategy.setSelection(activeIndex)
            updateStrategyInfo(StrategyRegistry.activeStrategy)
        }

        binding.spinnerStrategy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = strategies[position]
                if (selected.id != StrategyRegistry.activeStrategy.id) {
                    StrategyRegistry.selectStrategy(this@MainActivity, selected.id)
                    updateStrategyInfo(selected)
                    sendServiceIntent(TradingForegroundService.ACTION_SET_STRATEGY)
                    Toast.makeText(this@MainActivity, "Strategy switched to ${selected.name}", Toast.LENGTH_SHORT).show()
                } else {
                    updateStrategyInfo(selected)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateStrategyInfo(strategy: Strategy) {
        binding.tvStrategyDescription.text = strategy.description
        binding.tvStrategyParams.text = "Params: ${strategy.parametersSummary}"
    }

    private fun setupExecutionModeToggle() {
        binding.switchLiveMode.setOnCheckedChangeListener { _, isChecked ->
            if (!isUserSwitchingMode) return@setOnCheckedChangeListener

            if (isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Enable Live Trading?")
                    .setMessage("You are enabling LIVE TRADING on CoinDCX Futures.\n\nReal capital will be at risk and actual market orders will be placed in INR.\n\nAre you sure you want to proceed?")
                    .setPositiveButton("ENABLE LIVE") { _, _ ->
                        applyModeChange(isLive = true)
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                        isUserSwitchingMode = false
                        binding.switchLiveMode.isChecked = false
                        isUserSwitchingMode = true
                    }
                    .setCancelable(false)
                    .show()
            } else {
                applyModeChange(isLive = false)
            }
        }
    }

    private fun applyModeChange(isLive: Boolean) {
        if (isLive) {
            binding.tvModeBadge.text = "LIVE (REAL CAPITAL)"
            binding.tvModeBadge.setTextColor(getColor(R.color.accent_red))
            binding.tvModeLabel.text = "Live Trading (CoinDCX Futures)"
            binding.tvModeWarning.text = "⚠️ Real capital is at risk. Real futures orders are active."
            binding.tvModeWarning.setTextColor(getColor(R.color.accent_amber))
        } else {
            binding.tvModeBadge.text = "SIMULATION (SAFE)"
            binding.tvModeBadge.setTextColor(getColor(R.color.accent_green))
            binding.tvModeLabel.text = "Paper Trading (Virtual ₹ INR)"
            binding.tvModeWarning.text = "Risk-free simulation with slippage & taker fees. Real capital is untouched."
            binding.tvModeWarning.setTextColor(getColor(R.color.text_secondary))
        }

        sendServiceIntent(TradingForegroundService.ACTION_SET_MODE) {
            putExtra(TradingForegroundService.EXTRA_IS_PAPER, !isLive)
        }
    }

    private fun setupButtons() {
        binding.btnStartTrading.setOnClickListener {
            val isPaper = !binding.switchLiveMode.isChecked
            configRepo.setBotRunning(true)
            sendServiceIntent(TradingForegroundService.ACTION_START) {
                putExtra(TradingForegroundService.EXTRA_IS_PAPER, isPaper)
            }
            binding.tvBotStatus.text = "ACTIVE"
            binding.tvBotStatus.setTextColor(getColor(R.color.accent_green))
            Toast.makeText(this, "Market Scanner & Bot Started", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch(Dispatchers.IO) {
                refreshAccountData()
            }
        }

        binding.btnStopTrading.setOnClickListener {
            configRepo.setBotRunning(false)
            sendServiceIntent(TradingForegroundService.ACTION_STOP)
            binding.tvBotStatus.text = "STOPPED"
            binding.tvBotStatus.setTextColor(getColor(R.color.accent_amber))
            Toast.makeText(this, "Trading Bot Stopped", Toast.LENGTH_SHORT).show()
        }

        binding.btnRefreshExchange.setOnClickListener {
            val isPaper = !binding.switchLiveMode.isChecked
            sendServiceIntent(TradingForegroundService.ACTION_REFRESH_EXCHANGE) {
                putExtra(TradingForegroundService.EXTRA_IS_PAPER, isPaper)
            }
            lifecycleScope.launch(Dispatchers.IO) {
                refreshAccountData()
            }
            Toast.makeText(this, "Refreshing Exchange Live Data...", Toast.LENGTH_SHORT).show()
        }

        binding.btnScanNow.setOnClickListener {
            sendServiceIntent(TradingForegroundService.ACTION_TRIGGER_SCAN)
            Toast.makeText(this, "Manual scan requested...", Toast.LENGTH_SHORT).show()
        }

        binding.btnEmergencyStop.setOnClickListener {
            sendServiceIntent(TradingForegroundService.ACTION_STOP)
            binding.tvBotStatus.text = "KILL SWITCH ACTIVE"
            binding.tvBotStatus.setTextColor(getColor(R.color.accent_red))
            Toast.makeText(this, "EMERGENCY STOP: Cancelling open orders...", Toast.LENGTH_LONG).show()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "RISK", tag = "KILL_SWITCH", message = "Emergency Kill Switch Activated!")
                    )
                    val ordersPayload = mapOf("page" to "1", "size" to "50", "timestamp" to System.currentTimeMillis())
                    val ordersResp = ApiClient.apiService.getOpenOrders(ordersPayload)
                    if (ordersResp.isSuccessful && ordersResp.body() != null) {
                        for (order in ordersResp.body()!!) {
                            ApiClient.apiService.cancelOrder(mapOf("id" to order.id, "timestamp" to System.currentTimeMillis()))
                        }
                    }
                    refreshAccountData()
                } catch (e: Exception) {
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "ERROR", tag = "KILL_SWITCH", message = "Kill switch error: ${e.message}")
                    )
                }
            }
        }
    }

    private fun setupPaperAccountControls() {
        binding.btnResetPaperAccount.setOnClickListener {
            val selectedBalance = when (binding.chipGroupPaperCapital.checkedChipId) {
                R.id.chipPaper1k -> 1000.0
                R.id.chipPaper2k -> 2000.0
                R.id.chipPaper3k -> 3000.0
                R.id.chipPaper5k -> 5000.0
                R.id.chipPaper10k -> 10000.0
                else -> 5000.0
            }

            AlertDialog.Builder(this)
                .setTitle("Reset Paper Trading Account")
                .setMessage("Are you sure you want to reset the paper trading account?\n\nThis will:\n• Close all active paper positions\n• Start a new session with ₹%.0f starting capital\n• Safely archive previous trade history".format(selectedBalance))
                .setPositiveButton("Reset") { _, _ ->
                    sendServiceIntent(TradingForegroundService.ACTION_RESET_PAPER) {
                        putExtra(TradingForegroundService.EXTRA_RESET_BALANCE, selectedBalance)
                    }
                    Toast.makeText(this, "Paper account reset to ₹%.0f".format(selectedBalance), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnTabHolding.setOnClickListener {
            currentPortfolioTab = 0
            updatePortfolioTabUi()
            renderPortfolio()
        }

        binding.btnTabPending.setOnClickListener {
            currentPortfolioTab = 1
            updatePortfolioTabUi()
            renderPortfolio()
        }

        binding.btnTabClosed.setOnClickListener {
            currentPortfolioTab = 2
            updatePortfolioTabUi()
            renderPortfolio()
        }
    }

    private fun updatePortfolioTabUi() {
        val activeBg = getColor(R.color.surface_elevated)
        val inactiveBg = getColor(android.R.color.transparent)
        val activeText = getColor(R.color.accent_blue)
        val inactiveText = getColor(R.color.text_secondary)

        binding.btnTabHolding.backgroundTintList = android.content.res.ColorStateList.valueOf(if (currentPortfolioTab == 0) activeBg else inactiveBg)
        binding.btnTabHolding.setTextColor(if (currentPortfolioTab == 0) activeText else inactiveText)

        binding.btnTabPending.backgroundTintList = android.content.res.ColorStateList.valueOf(if (currentPortfolioTab == 1) activeBg else inactiveBg)
        binding.btnTabPending.setTextColor(if (currentPortfolioTab == 1) activeText else inactiveText)

        binding.btnTabClosed.backgroundTintList = android.content.res.ColorStateList.valueOf(if (currentPortfolioTab == 2) activeBg else inactiveBg)
        binding.btnTabClosed.setTextColor(if (currentPortfolioTab == 2) activeText else inactiveText)
    }

    private fun observeMarketScanState() {
        lifecycleScope.launch {
            MarketScanState.isScanning.collectLatest { scanning ->
                binding.progressScanning.visibility = if (scanning) View.VISIBLE else View.GONE
                if (scanning) {
                    binding.tvBotStatus.text = "SCANNING"
                    binding.tvNextScanCountdown.text = "Scanning..."
                }
            }
        }

        lifecycleScope.launch {
            MarketScanState.nextScanSecondsRemaining.collectLatest { sec ->
                if (sec > 0) {
                    val mins = sec / 60
                    val remSec = sec % 60
                    binding.tvNextScanCountdown.text = "Scan in: %02d:%02d".format(mins, remSec)
                } else if (!MarketScanState.isScanning.value) {
                    binding.tvNextScanCountdown.text = "Scan in: --"
                }
            }
        }

        lifecycleScope.launch {
            combine(
                MarketScanState.lastScanTimestamp,
                MarketScanState.scanCycleCount
            ) { ts, cycle -> Pair(ts, cycle) }.collectLatest { (ts, cycle) ->
                if (ts > 0) {
                    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    binding.tvLastScanTime.text = "Last Scan: ${sdf.format(java.util.Date(ts))} (Cycle #$cycle)"
                } else {
                    binding.tvLastScanTime.text = "Last Scan: Never"
                }
            }
        }

        lifecycleScope.launch {
            MarketScanState.isRefreshingExchange.collectLatest { refreshing ->
                binding.progressRefreshExchange.visibility = if (refreshing) View.VISIBLE else View.GONE
                binding.btnRefreshExchange.isEnabled = !refreshing
            }
        }

        lifecycleScope.launch {
            MarketScanState.lastExchangeRefreshTimestamp.collectLatest { ts ->
                if (ts > 0) {
                    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    binding.tvLastUpdatedTime.text = "Last Synced: ${sdf.format(java.util.Date(ts))}"
                } else {
                    binding.tvLastUpdatedTime.text = "Last Synced: Never"
                }
            }
        }

        lifecycleScope.launch {
            MarketScanState.latestAllocation.collectLatest { allocation ->
                if (allocation != null) {
                    binding.tvAvailableBalance.text = "₹ %.2f".format(allocation.availableBalanceInr)
                    binding.tvMaxTradesFormula.text = "Max Trades: ${allocation.maxTradesAllowed}"
                    binding.tvMinMarginDisplay.text = "₹ %.0f".format(allocation.minMarginPerTradeInr)
                    binding.tvSelectedOpportunitiesCount.text = "Top ${allocation.allocatedTradesCount} of 5"
                    binding.tvTotalAllocatedDisplay.text = "₹ %.2f".format(allocation.totalAllocatedInr)
                    binding.tvRemainingBalanceDisplay.text = "Remaining Unused Balance: ₹ %.2f".format(allocation.remainingBalanceInr)

                    if (allocation.isInsufficientBalance) {
                        binding.bannerInsufficientBalance.visibility = View.VISIBLE
                        binding.tvInsufficientMessage.text = "⚠️ " + allocation.statusMessage
                    } else {
                        binding.bannerInsufficientBalance.visibility = View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            MarketScanState.topOpportunities.collectLatest { opportunities ->
                renderTopOpportunities(opportunities)
            }
        }

        lifecycleScope.launch {
            MarketScanState.executionAuditMap.collectLatest {
                renderTopOpportunities(MarketScanState.topOpportunities.value)
            }
        }
    }

    private fun renderTopOpportunities(opportunities: List<MarketOpportunity>) {
        val container = binding.containerRankedOpportunities
        container.removeAllViews()

        if (opportunities.isEmpty()) {
            binding.tvEmptyRankingsPlaceholder.visibility = View.VISIBLE
            container.addView(binding.tvEmptyRankingsPlaceholder)
            return
        }

        binding.tvEmptyRankingsPlaceholder.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        val usdtInrRate = 90.0
        val audits = MarketScanState.executionAuditMap.value

        for (opp in opportunities.take(5)) {
            val itemBinding = ItemRankedOpportunityBinding.inflate(inflater, container, false)

            itemBinding.tvRankBadge.text = "#${opp.rank}"
            itemBinding.tvAssetSymbol.text = opp.assetSymbol + " Futures"

            // Quality Badge: Score & Category
            itemBinding.tvQualityBadge.text = "${opp.qualityCategory.name} ${opp.qualityScore}"
            val qualityColor = when (opp.qualityCategory) {
                com.coindcx.trading.engine.scanner.QualityCategory.PRIME -> getColor(R.color.accent_green)
                com.coindcx.trading.engine.scanner.QualityCategory.ACCEPTABLE -> getColor(R.color.accent_blue)
                com.coindcx.trading.engine.scanner.QualityCategory.WATCH -> getColor(R.color.accent_amber)
                com.coindcx.trading.engine.scanner.QualityCategory.REJECT -> getColor(R.color.accent_red)
            }
            itemBinding.tvQualityBadge.setTextColor(qualityColor)

            if (opp.isBuy) {
                itemBinding.tvActionBadge.text = "LONG"
                itemBinding.tvActionBadge.setTextColor(getColor(R.color.accent_green))
            } else {
                itemBinding.tvActionBadge.text = "SHORT"
                itemBinding.tvActionBadge.setTextColor(getColor(R.color.accent_red))
            }

            // Check if there is an execution audit for this pair
            val audit = audits[opp.pair]
            if (audit != null) {
                when (audit.status) {
                    com.coindcx.trading.engine.scanner.AuditStatus.EXECUTED -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_funded)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.accent_green))
                        itemBinding.tvAllocationStatus.text = "✓ " + audit.reason
                    }
                    com.coindcx.trading.engine.scanner.AuditStatus.REJECTED_LOW_QUALITY -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_unfunded)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.accent_red))
                        itemBinding.tvAllocationStatus.text = "✗ " + audit.reason
                    }
                    com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_PORTFOLIO_LIMIT -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_background)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.accent_amber))
                        itemBinding.tvAllocationStatus.text = "⚠️ " + audit.reason
                    }
                    com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_EXISTING_POSITION -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_background)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.accent_amber))
                        itemBinding.tvAllocationStatus.text = "⚠️ " + audit.reason
                    }
                    com.coindcx.trading.engine.scanner.AuditStatus.SKIPPED_INSUFFICIENT_BALANCE -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_unfunded)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.accent_red))
                        itemBinding.tvAllocationStatus.text = "⚠️ " + audit.reason
                    }
                    com.coindcx.trading.engine.scanner.AuditStatus.FAILED -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_unfunded)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.accent_red))
                        itemBinding.tvAllocationStatus.text = "✗ " + audit.reason
                    }
                    else -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_background)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.text_secondary))
                        itemBinding.tvAllocationStatus.text = audit.reason
                    }
                }
            } else {
                when (opp.lifecycleState) {
                    OpportunityLifecycle.SELECTED_FOR_TRADE -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_funded)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.accent_green))
                        itemBinding.tvAllocationStatus.text = "SELECTED FOR TRADE (Allocated: ₹%.0f Margin)".format(opp.allocatedMarginInr)
                    }
                    OpportunityLifecycle.UNFUNDED -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_unfunded)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.accent_red))
                        itemBinding.tvAllocationStatus.text = "RANKED #${opp.rank} - UNFUNDED (Insufficient Balance)"
                    }
                    OpportunityLifecycle.ACTIVE_POSITION -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_funded)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.accent_green))
                        itemBinding.tvAllocationStatus.text = "ACTIVE POSITION ON EXCHANGE"
                    }
                    else -> {
                        itemBinding.tvAllocationStatus.setBackgroundResource(R.drawable.badge_background)
                        itemBinding.tvAllocationStatus.setTextColor(getColor(R.color.text_secondary))
                        itemBinding.tvAllocationStatus.text = opp.statusMessage
                    }
                }
            }

            itemBinding.tvOpportunityReason.text = "Setup: ${opp.signal.reason}"
            val priceInr = opp.currentPrice * usdtInrRate
            itemBinding.tvPriceInfo.text = "Price: $%.2f (~₹%.2f)".format(opp.currentPrice, priceInr)
            itemBinding.tvMetricsInfo.text = "Net R:R: %.1fx (fee-adj) | ADX: %.1f".format(opp.netRiskRewardRatio, opp.adxValue)

            container.addView(itemBinding.root)
        }
    }

    private fun observePaperTrades() {
        // 1. Observe Paper Account Summary
        lifecycleScope.launch {
            MarketScanState.paperAccountSummary.collectLatest { summary ->
                if (summary != null) {
                    val sessionNum = summary.sessionId.removePrefix("session_")
                    val displaySession = if (sessionNum.length > 8) "SESSION: " + sessionNum.takeLast(6) else "SESSION: ${summary.sessionId}"
                    binding.tvPaperSessionBadge.text = displaySession
                    binding.tvPaperTotalEquity.text = "₹ %.2f".format(summary.totalEquityInr)
                    binding.tvPaperTotalReturn.text = "%+.2f%%".format(summary.totalReturnPct)
                    binding.tvPaperTotalReturn.setTextColor(
                        getColor(if (summary.totalReturnPct >= 0) R.color.accent_green else R.color.accent_red)
                    )
                    binding.tvPaperAvailableBalance.text = "Available: ₹ %.2f".format(summary.availableBalanceInr)
                    binding.tvPaperUsedMargin.text = "Margin: ₹ %.2f".format(summary.usedMarginInr)
                    binding.tvPaperUnrealizedPnl.text = "Unrealized: %+.2f".format(summary.unrealizedPnlInr)
                    binding.tvPaperUnrealizedPnl.setTextColor(
                        getColor(if (summary.unrealizedPnlInr >= 0) R.color.accent_green else R.color.accent_red)
                    )
                }
            }
        }

        // 2. Observe Performance Analytics
        lifecycleScope.launch {
            MarketScanState.paperAnalyticsReport.collectLatest { report ->
                if (report != null) {
                    binding.tvAnalyticsWinRate.text = "%.1f%% (%d/%d)".format(report.winRatePct, report.winCount, report.totalTrades)
                    binding.tvAnalyticsProfitFactor.text = "%.2f".format(report.profitFactor)
                    binding.tvAnalyticsMaxDrawdown.text = "%.1f%%".format(report.maxDrawdownPct)
                    binding.tvAnalyticsAvgDuration.text = "Avg Hold: %dm".format(report.avgDurationMinutes)
                    binding.tvAnalyticsAvgWinLoss.text = "Avg W: ₹%.0f | L: ₹%.0f".format(report.avgWinInr, report.avgLossInr)
                    binding.tvAnalyticsConsecutive.text = "Streak: %dW / %dL".format(report.maxConsecutiveWins, report.maxConsecutiveLosses)
                }
            }
        }

        // 3. Observe Open Holding Trades
        lifecycleScope.launch {
            db.tradeDao().getOpenTradesFlow().collectLatest { openTrades ->
                cachedHoldingTrades = openTrades
                binding.btnTabHolding.text = "HOLDING (${openTrades.size})"
                if (currentPortfolioTab == 0) {
                    renderPortfolio()
                }
                // Also update exchange live positions card if in paper mode
                if (!binding.switchLiveMode.isChecked) {
                    renderPaperOpenPositions(openTrades)
                }
            }
        }

        // 4. Observe Pending Orders
        lifecycleScope.launch {
            db.orderDao().getPendingOrdersFlow().collectLatest { pendingOrders ->
                cachedPendingOrders = pendingOrders
                binding.btnTabPending.text = "PENDING (${pendingOrders.size})"
                if (currentPortfolioTab == 1) {
                    renderPortfolio()
                }
            }
        }

        // 5. Observe Closed Trades History
        lifecycleScope.launch {
            db.tradeDao().getClosedTradesFlow().collectLatest { closedTrades ->
                cachedClosedTrades = closedTrades
                binding.btnTabClosed.text = "CLOSED (${closedTrades.size})"
                if (currentPortfolioTab == 2) {
                    renderPortfolio()
                }
            }
        }
    }

    private fun renderPortfolio() {
        val container = binding.containerPaperPortfolio
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        when (currentPortfolioTab) {
            0 -> {
                // HOLDING
                if (cachedHoldingTrades.isEmpty()) {
                    binding.tvPaperPortfolioPlaceholder.text = "No holding positions currently active."
                    container.addView(binding.tvPaperPortfolioPlaceholder)
                    return
                }

                for (trade in cachedHoldingTrades) {
                    val itemBinding = ItemPaperHoldingBinding.inflate(inflater, container, false)
                    val symbol = trade.pair.removePrefix("B-").removeSuffix("_USDT")
                    itemBinding.tvHoldingPair.text = "$symbol Futures"
                    itemBinding.tvHoldingSide.text = trade.side
                    itemBinding.tvHoldingLev.text = "${trade.leverage}x"

                    val isLong = trade.side.equals("LONG", ignoreCase = true)
                    itemBinding.tvHoldingSide.setTextColor(getColor(if (isLong) R.color.accent_green else R.color.accent_red))

                    val mark = trade.currentPrice ?: trade.entryPrice
                    itemBinding.tvHoldingPrices.text = "$%.2f → $%.2f".format(trade.entryPrice, mark)

                    val pnl = trade.unrealizedPnl ?: 0.0
                    val roi = trade.roiPercent ?: 0.0
                    itemBinding.tvHoldingPnl.text = formatRupeePnl(pnl, roi)
                    itemBinding.tvHoldingPnl.setTextColor(getColor(if (pnl >= 0) R.color.accent_green else R.color.accent_red))

                    val slText = if (trade.stopLoss != null) "$%.2f".format(trade.stopLoss) else "None"
                    val tpText = if (trade.takeProfit != null) "$%.2f".format(trade.takeProfit) else "None"
                    itemBinding.tvHoldingSlTp.text = "SL: $slText | TP: $tpText"

                    val estLiq = trade.estimatedLiquidationPrice ?: 0.0
                    itemBinding.tvHoldingEstLiq.text = "EST. LIQ: $%.2f".format(estLiq)

                    val notional = if (trade.notionalValueInr > 0) trade.notionalValueInr else trade.allocatedMarginInr * trade.leverage
                    itemBinding.tvHoldingMargin.text = "Margin: ₹%.0f | Notional: ₹%.0f".format(trade.allocatedMarginInr, notional)
                    itemBinding.tvHoldingFunding.text = "Funding: ₹%.2f (UTC)".format(trade.fundingFees)

                    itemBinding.btnHoldingClose.setOnClickListener {
                        sendServiceIntent(TradingForegroundService.ACTION_CLOSE_POSITION) {
                            putExtra(TradingForegroundService.EXTRA_PAIR, trade.pair)
                        }
                        Toast.makeText(this, "Closing ${trade.pair}...", Toast.LENGTH_SHORT).show()
                    }

                    container.addView(itemBinding.root)
                }
            }
            1 -> {
                // PENDING
                if (cachedPendingOrders.isEmpty()) {
                    binding.tvPaperPortfolioPlaceholder.text = "No pending or submitted orders in queue."
                    container.addView(binding.tvPaperPortfolioPlaceholder)
                    return
                }

                val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                for (order in cachedPendingOrders) {
                    val itemBinding = ItemPaperPendingBinding.inflate(inflater, container, false)
                    val symbol = order.pair.removePrefix("B-").removeSuffix("_USDT")
                    itemBinding.tvPendingPair.text = "$symbol Futures"
                    itemBinding.tvPendingSide.text = order.side
                    itemBinding.tvPendingSide.setTextColor(getColor(if (order.side == "LONG") R.color.accent_green else R.color.accent_red))

                    itemBinding.tvPendingStatus.text = order.status
                    val priceStr = if (order.price != null && order.price > 0) "$%.2f".format(order.price) else "Market"
                    itemBinding.tvPendingPriceQty.text = "Price: $priceStr | Qty: %.4f".format(order.totalQuantity)
                    itemBinding.tvPendingTime.text = sdf.format(java.util.Date(order.createdAt))

                    container.addView(itemBinding.root)
                }
            }
            2 -> {
                // CLOSED
                if (cachedClosedTrades.isEmpty()) {
                    binding.tvPaperPortfolioPlaceholder.text = "No closed trades recorded in this session yet."
                    container.addView(binding.tvPaperPortfolioPlaceholder)
                    return
                }

                for (trade in cachedClosedTrades) {
                    try {
                        val itemBinding = ItemPaperClosedBinding.inflate(inflater, container, false)
                        val symbol = trade.pair.removePrefix("B-").removeSuffix("_USDT")
                        itemBinding.tvClosedPair.text = "$symbol Futures"
                        itemBinding.tvClosedSide.text = trade.side
                        val isLong = trade.side.equals("LONG", ignoreCase = true)
                        itemBinding.tvClosedSide.setTextColor(getColor(if (isLong) R.color.accent_green else R.color.accent_red))

                        val result = trade.tradeResult ?: if ((trade.realizedPnl ?: 0.0) >= 0) "WIN" else "LOSS"
                        itemBinding.tvClosedResultBadge.text = result
                        when (result) {
                            "WIN" -> itemBinding.tvClosedResultBadge.setTextColor(getColor(R.color.accent_green))
                            "LOSS" -> itemBinding.tvClosedResultBadge.setTextColor(getColor(R.color.accent_red))
                            else -> itemBinding.tvClosedResultBadge.setTextColor(getColor(R.color.accent_amber))
                        }

                        val pnl = trade.realizedPnl ?: 0.0
                        val roi = trade.roiPercent ?: 0.0
                        itemBinding.tvClosedPnl.text = formatRupeePnl(pnl, roi)
                        itemBinding.tvClosedPnl.setTextColor(getColor(if (pnl >= 0) R.color.accent_green else R.color.accent_red))

                        val exitPrice = trade.exitPrice ?: trade.entryPrice
                        itemBinding.tvClosedPrices.text = "$%.2f → $%.2f".format(trade.entryPrice, exitPrice)

                        val durSec = if (trade.durationMillis != null) trade.durationMillis / 1000 else 0
                        val durMins = durSec / 60
                        val durSecRem = durSec % 60
                        itemBinding.tvClosedDuration.text = "Duration: %dm %02ds".format(durMins, durSecRem)

                        itemBinding.tvClosedExitReason.text = "Reason: ${trade.exitReason ?: "Closed"}"
                        itemBinding.tvClosedFees.text = "Fees: ₹%.2f".format(trade.fees + trade.fundingFees)

                        itemBinding.root.setOnClickListener {
                            showClosedTradeDetailDialog(trade)
                        }

                        container.addView(itemBinding.root)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error rendering closed trade item: ${trade.id}", e)
                    }
                }
            }
        }
    }

    private fun renderPaperOpenPositions(openTrades: List<TradeEntity>) {
        val container = binding.containerActivePositions
        container.removeAllViews()

        if (openTrades.isEmpty()) {
            binding.tvNoPositionsPlaceholder.visibility = View.VISIBLE
            container.addView(binding.tvNoPositionsPlaceholder)
            return
        }

        binding.tvNoPositionsPlaceholder.visibility = View.GONE
        val inflater = LayoutInflater.from(this)

        for (trade in openTrades) {
            val itemBinding = ItemActivePositionBinding.inflate(inflater, container, false)
            val symbol = trade.pair.removePrefix("B-").removeSuffix("_USDT")
            itemBinding.tvPosSymbol.text = "$symbol Futures"
            itemBinding.tvPosSideLeverage.text = "${trade.side} ${trade.leverage}x"

            if (trade.side == "LONG") {
                itemBinding.tvPosSideLeverage.setTextColor(getColor(R.color.accent_green))
            } else {
                itemBinding.tvPosSideLeverage.setTextColor(getColor(R.color.accent_red))
            }

            val pnl = trade.unrealizedPnl ?: 0.0
            itemBinding.tvPosPnlInr.text = if (pnl >= 0) "+₹%.2f".format(pnl) else "-₹%.2f".format(-pnl)
            itemBinding.tvPosPnlInr.setTextColor(getColor(if (pnl >= 0) R.color.accent_green else R.color.accent_red))

            itemBinding.tvPosEntryPrice.text = "Entry: $%.2f".format(trade.entryPrice)
            itemBinding.tvPosMargin.text = "Margin: ₹%.0f".format(trade.allocatedMarginInr)

            val slText = if (trade.stopLoss != null) "$%.2f".format(trade.stopLoss) else "None"
            val tpText = if (trade.takeProfit != null) "$%.2f".format(trade.takeProfit) else "None"
            itemBinding.tvPosTargets.text = "SL: $slText | TP: $tpText"

            itemBinding.btnPosClose.setOnClickListener {
                sendServiceIntent(TradingForegroundService.ACTION_CLOSE_POSITION) {
                    putExtra(TradingForegroundService.EXTRA_PAIR, trade.pair)
                }
                Toast.makeText(this, "Closing ${trade.pair}...", Toast.LENGTH_SHORT).show()
            }

            container.addView(itemBinding.root)
        }
    }

    private fun startLivePolling() {
        lifecycleScope.launch {
            while (true) {
                withContext(Dispatchers.IO) {
                    refreshAccountData()
                }
                delay(10_000)
            }
        }
    }

    private suspend fun refreshAccountData() {
        withContext(Dispatchers.Main) {
            binding.progressRefreshExchange.visibility = View.VISIBLE
            binding.btnRefreshExchange.isEnabled = false
        }
        try {
            // 1. Fetch Wallets in INR
            val walletResp = ApiClient.apiService.getFuturesWallets()
            if (walletResp.isSuccessful && !walletResp.body().isNullOrEmpty()) {
                val inrWallet = walletResp.body()!!.find { it.currencyShortName.equals("INR", ignoreCase = true) }
                val availableInr = inrWallet?.availableBalance ?: run {
                    val usdtWallet = walletResp.body()!!.find { it.currencyShortName.equals("USDT", ignoreCase = true) }
                    if (usdtWallet != null) currencyConverter.convertUsdtToInr(usdtWallet.availableBalance) else 0.0
                }

                val config = configRepo.configFlow.value
                val allocation = allocationEngine.allocateCapital(
                    availableInr,
                    config.minMarginPerTradeInr,
                    MarketScanState.topOpportunities.value
                )

                withContext(Dispatchers.Main) {
                    binding.tvAvailableBalance.text = "₹ %.2f".format(availableInr)
                    binding.tvMaxTradesFormula.text = "Max Trades: ${allocation.maxTradesAllowed}"
                    binding.tvMinMarginDisplay.text = "₹ %.0f".format(allocation.minMarginPerTradeInr)
                    binding.tvSelectedOpportunitiesCount.text = "Top ${allocation.allocatedTradesCount} of 5"
                    binding.tvTotalAllocatedDisplay.text = "₹ %.2f".format(allocation.totalAllocatedInr)
                    binding.tvRemainingBalanceDisplay.text = "Remaining Unused Balance: ₹ %.2f".format(allocation.remainingBalanceInr)

                    if (allocation.isInsufficientBalance) {
                        binding.bannerInsufficientBalance.visibility = View.VISIBLE
                        binding.tvInsufficientMessage.text = "⚠️ " + allocation.statusMessage
                    } else {
                        binding.bannerInsufficientBalance.visibility = View.GONE
                    }
                }
            }

            // 2. Fetch Live Positions
            val posPayload = mapOf(
                "page" to "1",
                "size" to "50",
                "margin_currency_short_name" to listOf("USDT"),
                "timestamp" to System.currentTimeMillis()
            )
            val posResp = ApiClient.apiService.getPositions(posPayload)
            if (posResp.isSuccessful && posResp.body() != null) {
                val openPositions = posResp.body()!!.filter { it.isOpen }
                val totalUnrealizedPnlUsdt = openPositions.sumOf { PnlEngine.calculateUnrealizedPnl(it) }
                val totalUnrealizedPnlInr = currencyConverter.convertUsdtToInr(totalUnrealizedPnlUsdt)

                withContext(Dispatchers.Main) {
                    binding.tvPositionsCount.text = "${openPositions.size} Open Trades"
                    binding.tvUnrealizedPnl.text = "P&L: ₹ %.2f".format(totalUnrealizedPnlInr)
                    if (totalUnrealizedPnlInr >= 0) {
                        binding.tvUnrealizedPnl.setTextColor(getColor(R.color.accent_green))
                    } else {
                        binding.tvUnrealizedPnl.setTextColor(getColor(R.color.accent_red))
                    }

                    if (binding.switchLiveMode.isChecked) {
                        renderLiveOpenPositions(openPositions)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                binding.tvLastUpdatedTime.text = "Last Synced: ${sdf.format(java.util.Date())}"
            }
        } catch (e: Exception) {
            db.systemLogDao().insert(
                SystemLogEntity(level = "WARN", tag = "POLL", message = "Data refresh: ${e.message}")
            )
        } finally {
            withContext(Dispatchers.Main) {
                binding.progressRefreshExchange.visibility = View.GONE
                binding.btnRefreshExchange.isEnabled = true
            }
        }
    }

    private fun renderLiveOpenPositions(openPositions: List<FuturesPosition>) {
        val container = binding.containerActivePositions
        container.removeAllViews()

        if (openPositions.isEmpty()) {
            binding.tvNoPositionsPlaceholder.visibility = View.VISIBLE
            container.addView(binding.tvNoPositionsPlaceholder)
            return
        }

        binding.tvNoPositionsPlaceholder.visibility = View.GONE
        val inflater = LayoutInflater.from(this)

        for (pos in openPositions) {
            val itemBinding = ItemActivePositionBinding.inflate(inflater, container, false)
            val symbol = pos.pair.removePrefix("B-").removeSuffix("_USDT")
            itemBinding.tvPosSymbol.text = "$symbol Futures"
            itemBinding.tvPosSideLeverage.text = "${if (pos.isLong) "LONG" else "SHORT"} ${pos.leverage.toInt()}x"

            if (pos.isLong) {
                itemBinding.tvPosSideLeverage.setTextColor(getColor(R.color.accent_green))
            } else {
                itemBinding.tvPosSideLeverage.setTextColor(getColor(R.color.accent_red))
            }

            val pnlUsdt = PnlEngine.calculateUnrealizedPnl(pos)
            val pnlInr = pnlUsdt * 90.0
            itemBinding.tvPosPnlInr.text = if (pnlInr >= 0) "+₹%.2f".format(pnlInr) else "-₹%.2f".format(-pnlInr)
            itemBinding.tvPosPnlInr.setTextColor(getColor(if (pnlInr >= 0) R.color.accent_green else R.color.accent_red))

            itemBinding.tvPosEntryPrice.text = "Entry: $%.2f".format(pos.avgPrice)
            itemBinding.tvPosMargin.text = "Margin: ₹%.0f".format(pos.lockedMargin * 90.0)

            val slText = if (pos.stopLossTrigger != null) "$%.2f".format(pos.stopLossTrigger) else "None"
            val tpText = if (pos.takeProfitTrigger != null) "$%.2f".format(pos.takeProfitTrigger) else "None"
            itemBinding.tvPosTargets.text = "SL: $slText | TP: $tpText"

            itemBinding.btnPosClose.setOnClickListener {
                sendServiceIntent(TradingForegroundService.ACTION_CLOSE_POSITION) {
                    putExtra(TradingForegroundService.EXTRA_PAIR, pos.pair)
                }
                Toast.makeText(this, "Closing live position on ${pos.pair}...", Toast.LENGTH_SHORT).show()
            }

            container.addView(itemBinding.root)
        }
    }

    private fun observeSystemLogs() {
        lifecycleScope.launch {
            db.systemLogDao().getRecentLogsFlow().collectLatest { logs ->
                if (logs.isNotEmpty()) {
                    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    val logText = logs.take(15).joinToString("\n") {
                        "[${sdf.format(java.util.Date(it.timestamp))}] [${it.level}] ${it.tag}: ${it.message}"
                    }
                    binding.tvRecentLogs.text = logText
                }
            }
        }
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun formatRupeePnl(pnl: Double, roi: Double): String {
        val sign = if (pnl >= 0) "+₹" else "-₹"
        return "%s%.2f (%+.1f%%)".format(sign, kotlin.math.abs(pnl), roi)
    }

    private fun showClosedTradeDetailDialog(trade: TradeEntity) {
        try {
            val dialogBinding = DialogClosedTradeDetailBinding.inflate(layoutInflater)
            val dialog = AlertDialog.Builder(this)
                .setView(dialogBinding.root)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            // Header & Identifiers
            dialogBinding.tvDetailPair.text = trade.pair
            dialogBinding.tvDetailSide.text = trade.side
            val isLong = trade.side.equals("LONG", ignoreCase = true)
            dialogBinding.tvDetailSide.setTextColor(getColor(if (isLong) R.color.accent_green else R.color.accent_red))

            val result = trade.tradeResult ?: if ((trade.realizedPnl ?: 0.0) >= 0) "WIN" else "LOSS"
            dialogBinding.tvDetailResultBadge.text = result
            when (result) {
                "WIN" -> dialogBinding.tvDetailResultBadge.setTextColor(getColor(R.color.accent_green))
                "LOSS" -> dialogBinding.tvDetailResultBadge.setTextColor(getColor(R.color.accent_red))
                else -> dialogBinding.tvDetailResultBadge.setTextColor(getColor(R.color.accent_amber))
            }

            val shortOrderId = if (trade.clientOrderId.length > 8) trade.clientOrderId.takeLast(8) else trade.clientOrderId
            dialogBinding.tvDetailTradeId.text = "Trade ID: #${trade.id} ($shortOrderId)"

            // Net Realized PnL & ROI
            val netPnl = trade.realizedPnl ?: 0.0
            val netSign = if (netPnl >= 0) "+₹" else "-₹"
            dialogBinding.tvDetailNetPnl.text = "%s%.2f".format(netSign, kotlin.math.abs(netPnl))
            dialogBinding.tvDetailNetPnl.setTextColor(getColor(if (netPnl >= 0) R.color.accent_green else R.color.accent_red))

            val roi = trade.roiPercent ?: 0.0
            val startingCapital = paperAccountManager.getStartingBalanceInr().coerceAtLeast(1.0)
            val accountReturn = (netPnl / startingCapital) * 100.0
            dialogBinding.tvDetailRoi.text = "Position ROI: %+.2f%% | Account Impact: %+.2f%%".format(roi, accountReturn)
            dialogBinding.tvDetailRoi.setTextColor(getColor(if (netPnl >= 0) R.color.accent_green else R.color.accent_red))

            // Strategy & Timing
            dialogBinding.tvDetailStrategy.text = trade.strategyName.ifBlank { "EMA Crossover" }
            dialogBinding.tvDetailTimeframe.text = trade.timeframe.ifBlank { "15m" }

            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm:ss", java.util.Locale.getDefault())
            val signalTime = if (trade.signalPrice > 0) (trade.entryTime - 5000).coerceAtLeast(0) else trade.entryTime
            dialogBinding.tvDetailSignalTime.text = sdf.format(java.util.Date(signalTime))
            dialogBinding.tvDetailEntryTime.text = sdf.format(java.util.Date(trade.entryTime))
            dialogBinding.tvDetailExitTime.text = if (trade.exitTime != null && trade.exitTime > 0) sdf.format(java.util.Date(trade.exitTime)) else "--"

            val durMillis = trade.durationMillis ?: if (trade.exitTime != null) (trade.exitTime - trade.entryTime).coerceAtLeast(0) else 0L
            val durSec = durMillis / 1000
            val durMins = durSec / 60
            val durSecRem = durSec % 60
            dialogBinding.tvDetailDuration.text = "%dm %02ds".format(durMins, durSecRem)

            dialogBinding.tvDetailExitReason.text = trade.exitReason ?: "Closed"

            // Pricing & Slippage
            val sigPrice = if (trade.signalPrice > 0) trade.signalPrice else trade.entryPrice
            dialogBinding.tvDetailSignalPrice.text = "$%.4f".format(sigPrice)
            dialogBinding.tvDetailEntryPrice.text = "$%.4f".format(trade.entryPrice)
            val exitPrice = trade.exitPrice ?: trade.currentPrice ?: trade.entryPrice
            dialogBinding.tvDetailExitPrice.text = "$%.4f".format(exitPrice)
            dialogBinding.tvDetailSlippage.text = "%.2f%% per fill".format(trade.slippageRate * 100.0)

            // Position & Margin
            val baseSymbol = trade.pair.removePrefix("B-").removeSuffix("_USDT")
            dialogBinding.tvDetailQuantity.text = "%.4f %s".format(trade.quantity, baseSymbol)
            dialogBinding.tvDetailLeverage.text = "${trade.leverage}x (Simulated Isolated)"
            dialogBinding.tvDetailMargin.text = "₹%.2f".format(trade.allocatedMarginInr)
            val notional = if (trade.notionalValueInr > 0) trade.notionalValueInr else trade.allocatedMarginInr * trade.leverage
            dialogBinding.tvDetailNotional.text = "₹%.2f".format(notional)

            // Financial Breakdown
            val grossPnl = trade.grossPnl ?: (netPnl + trade.fees + trade.fundingFees)
            val grossSign = if (grossPnl >= 0) "+₹" else "-₹"
            dialogBinding.tvDetailGrossPnl.text = "%s%.2f".format(grossSign, kotlin.math.abs(grossPnl))
            dialogBinding.tvDetailGrossPnl.setTextColor(getColor(if (grossPnl >= 0) R.color.accent_green else R.color.accent_red))

            dialogBinding.tvDetailFees.text = "-₹%.2f".format(trade.fees)
            dialogBinding.tvDetailFundingFees.text = "₹%.2f".format(trade.fundingFees)
            dialogBinding.tvDetailAccountReturn.text = "%+.2f%%".format(accountReturn)
            dialogBinding.tvDetailAccountReturn.setTextColor(getColor(if (accountReturn >= 0) R.color.accent_green else R.color.accent_red))

            dialogBinding.btnDetailClose.setOnClickListener { dialog.dismiss() }
            dialogBinding.btnDetailDismiss.setOnClickListener { dialog.dismiss() }

            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to show trade detail dialog", e)
            Toast.makeText(this, "Unable to load trade details: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
