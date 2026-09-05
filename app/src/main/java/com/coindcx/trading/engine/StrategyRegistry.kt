package com.coindcx.trading.engine

import android.content.Context
import com.coindcx.trading.engine.strategies.EmaCrossoverStrategy
import com.coindcx.trading.engine.strategies.RsiMeanReversionStrategy

object StrategyRegistry {

    private const val PREFS_NAME = "trading_strategy_prefs"
    private const val KEY_ACTIVE_STRATEGY_ID = "active_strategy_id"

    val availableStrategies: List<Strategy> = listOf(
        EmaCrossoverStrategy(),
        RsiMeanReversionStrategy()
    )

    var activeStrategy: Strategy = availableStrategies.first()
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_ACTIVE_STRATEGY_ID, availableStrategies.first().id)
        activeStrategy = availableStrategies.find { it.id == savedId } ?: availableStrategies.first()
    }

    fun selectStrategy(context: Context, strategyId: String): Boolean {
        val found = availableStrategies.find { it.id == strategyId } ?: return false
        activeStrategy = found

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_STRATEGY_ID, strategyId).apply()
        return true
    }
}
