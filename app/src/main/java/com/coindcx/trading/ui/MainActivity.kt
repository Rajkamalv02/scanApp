package com.coindcx.trading.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coindcx.trading.R
import com.coindcx.trading.data.api.ApiClient
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.databinding.ActivityMainBinding
import com.coindcx.trading.engine.PnlEngine
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.StrategyRegistry
import com.coindcx.trading.service.TradingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getInstance(this) }
    private var isUserSwitchingMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        StrategyRegistry.init(this)

        setupStrategySelector()
        setupExecutionModeToggle()
        setupButtons()
        observePaperTrades()
        observeSystemLogs()
        checkBatteryOptimization()
        startLivePolling()
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

        // Set initial selection to current active strategy
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

                    // Notify background service
                    val intent = Intent(this@MainActivity, TradingForegroundService::class.java).apply {
                        action = TradingForegroundService.ACTION_SET_STRATEGY
                    }
                    startService(intent)

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
        binding.tvStrategyMeta.text = "Timeframe: ${strategy.defaultTimeframe} | Warmup: ${strategy.requiredCandleCount} candles"
    }

    private fun setupExecutionModeToggle() {
        binding.switchLiveMode.setOnCheckedChangeListener { _, isChecked ->
            if (!isUserSwitchingMode) return@setOnCheckedChangeListener

            if (isChecked) {
                // User wants to enable LIVE TRADING with real money! Confirm with alert
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Enable Live Trading?")
                    .setMessage("You are enabling LIVE TRADING on CoinDCX Futures.\n\nReal capital will be at risk and actual market orders will be placed.\n\nAre you sure you want to proceed?")
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
            binding.tvModeLabel.text = "Paper Trading (Virtual USDT)"
            binding.tvModeWarning.text = "Risk-free testing with simulated slippage and taker fees. Real funds are untouched."
            binding.tvModeWarning.setTextColor(getColor(R.color.text_secondary))
        }

        val intent = Intent(this, TradingForegroundService::class.java).apply {
            action = TradingForegroundService.ACTION_SET_MODE
            putExtra(TradingForegroundService.EXTRA_IS_PAPER, !isLive)
        }
        startService(intent)
    }

    private fun setupButtons() {
        binding.btnStartTrading.setOnClickListener {
            val isPaper = !binding.switchLiveMode.isChecked
            val intent = Intent(this, TradingForegroundService::class.java).apply {
                action = TradingForegroundService.ACTION_START
                putExtra(TradingForegroundService.EXTRA_IS_PAPER, isPaper)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            binding.tvBotStatus.text = "RUNNING"
            binding.tvBotStatus.setTextColor(getColor(R.color.accent_green))
            Toast.makeText(this, "Trading Bot Service Started", Toast.LENGTH_SHORT).show()

            // Trigger immediate sync
            lifecycleScope.launch(Dispatchers.IO) {
                refreshAccountData()
            }
        }

        binding.btnPauseTrading.setOnClickListener {
            val intent = Intent(this, TradingForegroundService::class.java).apply {
                action = TradingForegroundService.ACTION_STOP
            }
            startService(intent)
            binding.tvBotStatus.text = "PAUSED"
            binding.tvBotStatus.setTextColor(getColor(R.color.accent_amber))
            Toast.makeText(this, "Trading Bot Service Paused", Toast.LENGTH_SHORT).show()
        }

        binding.btnEmergencyStop.setOnClickListener {
            val intent = Intent(this, TradingForegroundService::class.java).apply {
                action = TradingForegroundService.ACTION_STOP
            }
            startService(intent)
            binding.tvBotStatus.text = "KILL SWITCH ACTIVE"
            binding.tvBotStatus.setTextColor(getColor(R.color.accent_red))
            Toast.makeText(this, "EMERGENCY STOP: Halting bot...", Toast.LENGTH_LONG).show()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    db.systemLogDao().insert(
                        SystemLogEntity(level = "RISK", tag = "KILL_SWITCH", message = "Emergency Kill Switch Activated!")
                    )
                    // Fetch open orders and cancel them
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

    private fun observePaperTrades() {
        lifecycleScope.launch {
            db.tradeDao().getAllTradesFlow().collectLatest { trades ->
                val openCount = trades.count { it.status == "OPEN" }
                val closedCount = trades.count { it.status == "CLOSED" }
                val totalRealizedPnl = trades.filter { it.status == "CLOSED" }.sumOf { it.realizedPnl ?: 0.0 }
                val totalFees = trades.sumOf { it.fees }

                withContext(Dispatchers.Main) {
                    binding.tvPaperOpenCount.text = "$openCount Open"
                    binding.tvPaperTotalTrades.text = "$closedCount Completed"
                    binding.tvPaperRealizedPnl.text = "P&L: ₹ %.2f".format(totalRealizedPnl)
                    binding.tvPaperFees.text = "Est Fees: ₹ %.4f".format(totalFees)

                    if (totalRealizedPnl >= 0) {
                        binding.tvPaperRealizedPnl.setTextColor(getColor(R.color.accent_green))
                    } else {
                        binding.tvPaperRealizedPnl.setTextColor(getColor(R.color.accent_red))
                    }
                }
            }
        }
    }

    private fun startLivePolling() {
        lifecycleScope.launch {
            while (true) {
                withContext(Dispatchers.IO) {
                    refreshAccountData()
                }
                delay(10_000) // Poll every 10 seconds
            }
        }
    }

    private suspend fun refreshAccountData() {
        try {
            // 1. Fetch Wallets
            val walletResp = ApiClient.apiService.getFuturesWallets()
            if (walletResp.isSuccessful && !walletResp.body().isNullOrEmpty()) {
                val wallet = walletResp.body()!!.first()
                val balanceFormatted = "₹ %.4f".format(wallet.balance.toDoubleOrNull() ?: 0.0)
                val availMarginFormatted = "Available Margin: ₹ %.4f".format(wallet.availableBalance)
                withContext(Dispatchers.Main) {
                    binding.tvWalletBalance.text = balanceFormatted
                    binding.tvAvailableMargin.text = availMarginFormatted
                }
            }

            // 2. Fetch Positions
            val posPayload = mapOf(
                "page" to "1",
                "size" to "50",
                "margin_currency_short_name" to listOf("USDT"),
                "timestamp" to System.currentTimeMillis()
            )
            val posResp = ApiClient.apiService.getPositions(posPayload)
            if (posResp.isSuccessful && posResp.body() != null) {
                val openPositions = posResp.body()!!.filter { it.isOpen }
                val totalUnrealizedPnl = openPositions.sumOf { PnlEngine.calculateUnrealizedPnl(it) }

                withContext(Dispatchers.Main) {
                    binding.tvPositionsCount.text = "${openPositions.size} Open Trades"
                    binding.tvUnrealizedPnl.text = "P&L: ₹ %.2f".format(totalUnrealizedPnl)
                    if (totalUnrealizedPnl >= 0) {
                        binding.tvUnrealizedPnl.setTextColor(getColor(R.color.accent_green))
                    } else {
                        binding.tvUnrealizedPnl.setTextColor(getColor(R.color.accent_red))
                    }
                }
            }
        } catch (e: Exception) {
            db.systemLogDao().insert(
                SystemLogEntity(level = "WARN", tag = "POLL", message = "Data refresh warning: ${e.message}")
            )
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
            } catch (e: Exception) {
                // Ignore if device disallows direct intent
            }
        }
    }
}
