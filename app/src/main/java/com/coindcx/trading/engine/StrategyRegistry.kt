package com.coindcx.trading.engine

import android.content.Context
import com.coindcx.trading.engine.strategies.SupplyDemandBidirectionalStrategy
import com.coindcx.trading.engine.strategies.SupplyDemandEngulfingMacdLongStrategy
import com.coindcx.trading.engine.strategies.SupplyDemandEngulfingMacdStrategy

object StrategyRegistry {

    private const val PREFS_NAME = "trading_strategy_prefs"
    private const val KEY_ACTIVE_STRATEGY_ID = "active_strategy_id"

    // Institutional Strategies: Two-Way Bidirectional by default, with discrete Long & Short options
    val availableStrategies: List<Strategy> = listOf(
        SupplyDemandBidirectionalStrategy(),
        SupplyDemandEngulfingMacdLongStrategy(),
        SupplyDemandEngulfingMacdStrategy()
    )

    var activeStrategy: Strategy = availableStrategies.first()
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_ACTIVE_STRATEGY_ID, null)
        val target = availableStrategies.find { it.id == savedId } ?: availableStrategies.first()
        activeStrategy = target

        // Safely migrate legacy preferences (e.g. "ema_crossover", "rsi_mean_reversion")
        if (savedId != target.id) {
            prefs.edit().putString(KEY_ACTIVE_STRATEGY_ID, target.id).apply()
        }
    }

    fun selectStrategy(context: Context, strategyId: String): Boolean {
        val found = availableStrategies.find { it.id == strategyId } ?: return false
        activeStrategy = found

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_STRATEGY_ID, strategyId).apply()
        return true
    }
}
