package com.coindcx.trading.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coindcx.trading.data.api.ApiClient
import com.coindcx.trading.data.db.AppDatabase
import com.coindcx.trading.data.db.entities.SystemLogEntity
import com.coindcx.trading.databinding.ActivityMainBinding
import com.coindcx.trading.engine.PnlEngine
import com.coindcx.trading.service.TradingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        observeSystemLogs()
        checkBatteryOptimization()
        startLivePolling()
    }

    private fun setupButtons() {
        binding.btnStartTrading.setOnClickListener {
            val intent = Intent(this, TradingForegroundService::class.java).apply {
                action = TradingForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            binding.tvBotStatus.text = "RUNNING"
            binding.tvBotStatus.setTextColor(getColor(android.R.color.holo_green_light))
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
            binding.tvBotStatus.setTextColor(getColor(android.R.color.holo_orange_light))
            Toast.makeText(this, "Trading Bot Service Paused", Toast.LENGTH_SHORT).show()
        }

        binding.btnEmergencyStop.setOnClickListener {
            val intent = Intent(this, TradingForegroundService::class.java).apply {
                action = TradingForegroundService.ACTION_STOP
            }
            startService(intent)
            binding.tvBotStatus.text = "KILL SWITCH ACTIVE"
            binding.tvBotStatus.setTextColor(getColor(android.R.color.holo_red_light))
            Toast.makeText(this, "EMERGENCY STOP: Halting bot...", Toast.LENGTH_LONG).show()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Log emergency stop
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
                        binding.tvUnrealizedPnl.setTextColor(getColor(android.R.color.holo_green_light))
                    } else {
                        binding.tvUnrealizedPnl.setTextColor(getColor(android.R.color.holo_red_light))
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
