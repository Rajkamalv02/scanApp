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
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.data.db.entities.TradeEntity
import com.coindcx.trading.databinding.ActivityMainBinding
import com.coindcx.trading.databinding.ItemActivePositionBinding
import com.coindcx.trading.databinding.ItemRankedOpportunityBinding
import com.coindcx.trading.engine.PnlEngine
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.StrategyRegistry
import com.coindcx.trading.engine.allocation.AllocationEngine
import com.coindcx.trading.engine.currency.CurrencyConverter
import com.coindcx.trading.engine.scanner.MarketOpportunity
import com.coindcx.trading.engine.scanner.MarketScanState
import com.coindcx.trading.engine.scanner.OpportunityLifecycle
import com.coindcx.trading.service.TradingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private val configRepo by lazy { TradingConfigRepository.getInstance(this) }
    private val currencyConverter by lazy { CurrencyConverter(ApiClient.apiService) }
    private val allocationEngine by lazy { AllocationEngine() }

    private var isUserSwitchingMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        StrategyRegistry.init(this)

        setupTradingControls()
        setupStrategySelector()
        setupExecutionModeToggle()
        setupButtons()

        observeMarketScanState()
        observePaperTrades()
        observeSystemLogs()
        checkBatteryOptimization()
        startLivePolling()
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
            5 -> binding.chipInterval5m.isChecked = true
            15 -> binding.chipInterval15m.isChecked = true
            30 -> binding.chipInterval30m.isChecked = true
            60 -> binding.chipInterval1h.isChecked = true
            else -> binding.chipInterval15m.isChecked = true
        }

        binding.chipGroupScanInterval.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val minutes = when (selectedId) {
                R.id.chipInterval1m -> 1
                R.id.chipInterval5m -> 5
                R.id.chipInterval15m -> 15
                R.id.chipInterval30m -> 30
                R.id.chipInterval1h -> 60
                else -> 15
            }
            configRepo.updateScanInterval(minutes)
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
            sendServiceIntent(TradingForegroundService.ACTION_START) {
                putExtra(TradingForegroundService.EXTRA_IS_PAPER, isPaper)
            }
            binding.tvBotStatus.text = "SCANNING"
            binding.tvBotStatus.setTextColor(getColor(R.color.accent_green))
            Toast.makeText(this, "Market Scanner & Bot Started", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch(Dispatchers.IO) {
                refreshAccountData()
            }
        }

        binding.btnStopTrading.setOnClickListener {
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
            itemBinding.tvConfidenceScore.text = "%.0f%%".format(opp.confidenceScore)

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

            container.addView(itemBinding.root)
        }
    }

    private fun observePaperTrades() {
        // Observe paper trade history & open holdings
        lifecycleScope.launch {
            db.tradeDao().getAllTradesFlow().collectLatest { trades ->
                val openTrades = trades.filter { it.status == "OPEN" }
                val closedTrades = trades.filter { it.status == "CLOSED" }
                val totalRealizedPnl = closedTrades.sumOf { it.realizedPnl ?: 0.0 }
                val totalFees = trades.sumOf { it.fees }

                withContext(Dispatchers.Main) {
                    binding.tvPaperOpenCount.text = "${openTrades.size} Open Trades"
                    binding.tvPaperTotalTrades.text = "${closedTrades.size} Completed"
                    binding.tvPaperRealizedPnl.text = "P&L: ₹ %.2f".format(totalRealizedPnl)
                    binding.tvPaperFees.text = "Est Fees: ₹ %.2f".format(totalFees)

                    if (totalRealizedPnl >= 0) {
                        binding.tvPaperRealizedPnl.setTextColor(getColor(R.color.accent_green))
                    } else {
                        binding.tvPaperRealizedPnl.setTextColor(getColor(R.color.accent_red))
                    }

                    // If in paper mode, populate active positions container
                    if (!binding.switchLiveMode.isChecked) {
                        renderPaperOpenPositions(openTrades)
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
                    val logText = logs.take(5).joinToString("\n") {
                        "[${it.level}] ${it.tag}: ${it.message}"
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
}
