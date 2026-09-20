package com.coindcx.trading.data.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TradingConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<TradingConfig> = _configFlow.asStateFlow()

    companion object {
        private const val PREFS_NAME = "trading_config_preferences"
        private const val KEY_MIN_MARGIN_INR = "min_margin_inr"
        private const val KEY_RISK_PROFILE = "risk_profile"
        private const val KEY_RISK_PER_TRADE = "risk_per_trade_percent"
        private const val KEY_SAFETY_RESERVE = "safety_reserve_percent"
        private const val KEY_MAX_SINGLE_EXPOSURE = "max_single_exposure_percent"
        private const val KEY_LEVERAGE = "leverage"
        private const val KEY_TIMEFRAME = "timeframe"
        private const val KEY_SCAN_INTERVAL = "scan_interval_minutes"
        private const val KEY_MARKET_WIDE = "market_wide"
        private const val KEY_MAX_DAILY_LOSS = "max_daily_loss"
        private const val KEY_BOT_RUNNING = "is_bot_running"
        private const val KEY_FAST_EMA = "fast_ema"
        private const val KEY_SLOW_EMA = "slow_ema"
        private const val KEY_ATR_MULT = "atr_mult"
        private const val KEY_RSI_PERIOD = "rsi_period"
        private const val KEY_RSI_OVERSOLD = "rsi_oversold"
        private const val KEY_RSI_OVERBOUGHT = "rsi_overbought"
        private const val KEY_ALLOW_TIER2_LIVE = "allow_tier2_live"
        private const val KEY_LIVE_MODE = "is_live_mode"

        @Volatile
        private var INSTANCE: TradingConfigRepository? = null

        fun getInstance(context: Context): TradingConfigRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TradingConfigRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun loadConfig(): TradingConfig {
        return TradingConfig(
            riskProfile = prefs.getString(KEY_RISK_PROFILE, "BALANCED") ?: "BALANCED",
            riskPerTradePercent = prefs.getFloat(KEY_RISK_PER_TRADE, 1.0f).toDouble(),
            safetyReservePercent = prefs.getFloat(KEY_SAFETY_RESERVE, 0.0f).toDouble(),
            maxSingleExposurePercent = prefs.getFloat(KEY_MAX_SINGLE_EXPOSURE, 30.0f).toDouble(),
            minMarginPerTradeInr = prefs.getFloat(KEY_MIN_MARGIN_INR, 500.0f).toDouble(),
            leverage = prefs.getInt(KEY_LEVERAGE, 2),
            timeframe = prefs.getString(KEY_TIMEFRAME, "15m") ?: "15m",
            scanIntervalMinutes = prefs.getInt(KEY_SCAN_INTERVAL, 2),
            isMarketWideScan = prefs.getBoolean(KEY_MARKET_WIDE, true),
            maxDailyLossInr = prefs.getFloat(KEY_MAX_DAILY_LOSS, 2000.0f).toDouble(),
            fastEmaPeriod = prefs.getInt(KEY_FAST_EMA, 9),
            slowEmaPeriod = prefs.getInt(KEY_SLOW_EMA, 21),
            atrMultiplier = prefs.getFloat(KEY_ATR_MULT, 1.5f).toDouble(),
            rsiPeriod = prefs.getInt(KEY_RSI_PERIOD, 14),
            rsiOversold = prefs.getFloat(KEY_RSI_OVERSOLD, 30.0f).toDouble(),
            rsiOverbought = prefs.getFloat(KEY_RSI_OVERBOUGHT, 70.0f).toDouble(),
            allowTier2AltcoinsLive = prefs.getBoolean(KEY_ALLOW_TIER2_LIVE, true)
        )
    }

    fun isBotRunning(): Boolean = prefs.getBoolean(KEY_BOT_RUNNING, false)

    fun setBotRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_BOT_RUNNING, running).apply()
    }

    /**
     * Default execution mode is Live Mode (true).
     * Once set by the user, it strictly persists across refreshes, navigation, and restarts.
     */
    fun isLiveMode(): Boolean = prefs.getBoolean(KEY_LIVE_MODE, true)

    fun setLiveMode(isLive: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_MODE, isLive).apply()
    }

    fun updateRiskProfile(profile: String, riskPct: Double) {
        prefs.edit()
            .putString(KEY_RISK_PROFILE, profile)
            .putFloat(KEY_RISK_PER_TRADE, riskPct.toFloat())
            .apply()
        _configFlow.value = _configFlow.value.copy(
            riskProfile = profile,
            riskPerTradePercent = riskPct
        )
    }

    fun updateMinMargin(marginInr: Double) {
        prefs.edit().putFloat(KEY_MIN_MARGIN_INR, marginInr.toFloat()).apply()
        _configFlow.value = _configFlow.value.copy(minMarginPerTradeInr = marginInr)
    }

    fun updateLeverage(leverage: Int) {
        val clamped = leverage.coerceIn(1, 20)
        prefs.edit().putInt(KEY_LEVERAGE, clamped).apply()
        _configFlow.value = _configFlow.value.copy(leverage = clamped)
    }

    fun updateTimeframe(timeframe: String) {
        val valid = if (timeframe in listOf("1m", "15m", "1h", "1d")) timeframe else "15m"
        prefs.edit().putString(KEY_TIMEFRAME, valid).apply()
        _configFlow.value = _configFlow.value.copy(timeframe = valid)
    }

    fun updateScanInterval(minutes: Int) {
        val valid = if (minutes in listOf(1, 2, 5, 15, 30, 60)) minutes else 2
        prefs.edit().putInt(KEY_SCAN_INTERVAL, valid).apply()
        _configFlow.value = _configFlow.value.copy(scanIntervalMinutes = valid)
    }

    fun updateScanMode(isMarketWide: Boolean) {
        prefs.edit().putBoolean(KEY_MARKET_WIDE, isMarketWide).apply()
        _configFlow.value = _configFlow.value.copy(isMarketWideScan = isMarketWide)
    }

    fun updateAllowTier2Live(allow: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_TIER2_LIVE, allow).apply()
        _configFlow.value = _configFlow.value.copy(allowTier2AltcoinsLive = allow)
    }

    fun updateStrategyTuning(
        fastEma: Int,
        slowEma: Int,
        atrMult: Double,
        rsiPeriod: Int,
        rsiOversold: Double,
        rsiOverbought: Double
    ) {
        prefs.edit()
            .putInt(KEY_FAST_EMA, fastEma)
            .putInt(KEY_SLOW_EMA, slowEma)
            .putFloat(KEY_ATR_MULT, atrMult.toFloat())
            .putInt(KEY_RSI_PERIOD, rsiPeriod)
            .putFloat(KEY_RSI_OVERSOLD, rsiOversold.toFloat())
            .putFloat(KEY_RSI_OVERBOUGHT, rsiOverbought.toFloat())
            .apply()

        _configFlow.value = _configFlow.value.copy(
            fastEmaPeriod = fastEma,
            slowEmaPeriod = slowEma,
            atrMultiplier = atrMult,
            rsiPeriod = rsiPeriod,
            rsiOversold = rsiOversold,
            rsiOverbought = rsiOverbought
        )
    }
}
