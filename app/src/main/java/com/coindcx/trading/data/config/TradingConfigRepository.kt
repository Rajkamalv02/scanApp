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
        private const val KEY_LEVERAGE = "leverage"
        private const val KEY_TIMEFRAME = "timeframe"
        private const val KEY_MARKET_WIDE = "market_wide"
        private const val KEY_MAX_DAILY_LOSS = "max_daily_loss"
        private const val KEY_FAST_EMA = "fast_ema"
        private const val KEY_SLOW_EMA = "slow_ema"
        private const val KEY_ATR_MULT = "atr_mult"
        private const val KEY_RSI_PERIOD = "rsi_period"
        private const val KEY_RSI_OVERSOLD = "rsi_oversold"
        private const val KEY_RSI_OVERBOUGHT = "rsi_overbought"

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
            minMarginPerTradeInr = prefs.getFloat(KEY_MIN_MARGIN_INR, 500.0f).toDouble(),
            leverage = prefs.getInt(KEY_LEVERAGE, 2),
            timeframe = prefs.getString(KEY_TIMEFRAME, "15m") ?: "15m",
            isMarketWideScan = prefs.getBoolean(KEY_MARKET_WIDE, true),
            maxDailyLossInr = prefs.getFloat(KEY_MAX_DAILY_LOSS, 2000.0f).toDouble(),
            fastEmaPeriod = prefs.getInt(KEY_FAST_EMA, 9),
            slowEmaPeriod = prefs.getInt(KEY_SLOW_EMA, 21),
            atrMultiplier = prefs.getFloat(KEY_ATR_MULT, 1.5f).toDouble(),
            rsiPeriod = prefs.getInt(KEY_RSI_PERIOD, 14),
            rsiOversold = prefs.getFloat(KEY_RSI_OVERSOLD, 30.0f).toDouble(),
            rsiOverbought = prefs.getFloat(KEY_RSI_OVERBOUGHT, 70.0f).toDouble()
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

    fun updateScanMode(isMarketWide: Boolean) {
        prefs.edit().putBoolean(KEY_MARKET_WIDE, isMarketWide).apply()
        _configFlow.value = _configFlow.value.copy(isMarketWideScan = isMarketWide)
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
