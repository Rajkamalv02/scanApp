package com.coindcx.trading.engine

import android.content.Context
import com.coindcx.trading.engine.strategies.ConfluenceStrategy
import com.coindcx.trading.engine.strategies.EmaCrossoverStrategy
import com.coindcx.trading.util.AppLogManager

object StrategyRegistry {

    private const val PREFS_NAME = "trading_strategy_prefs"
    private const val KEY_ACTIVE_STRATEGY_ID = "active_strategy_id"
    private const val KEY_MULTI_STRATEGY_ENABLED = "multi_strategy_enabled"

    val emaCrossoverStrategy = EmaCrossoverStrategy()
    val confluenceStrategy = ConfluenceStrategy()

    /**
     * All registered strategies available in the application.
     */
    val availableStrategies: List<Strategy> = listOf(
        emaCrossoverStrategy,
        confluenceStrategy
    )

    /**
     * Phase 2 default: Multi-strategy parallel scanning is enabled.
     * Can be set to false to test EMA-only or single-strategy regression behavior.
     */
    var isMultiStrategyEnabled: Boolean = true
        private set

    var activeStrategy: Strategy = availableStrategies.first()
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_ACTIVE_STRATEGY_ID, null)
        val target = availableStrategies.find { it.id == savedId } ?: availableStrategies.first()
        activeStrategy = target

        // Multi-strategy is enabled by default in Phase 2
        isMultiStrategyEnabled = prefs.getBoolean(KEY_MULTI_STRATEGY_ENABLED, true)

        if (savedId != target.id) {
            prefs.edit().putString(KEY_ACTIVE_STRATEGY_ID, target.id).apply()
        }
        AppLogManager.i("STRATEGY", "StrategyRegistry initialized. Active=${activeStrategy.name}, MultiStrategyEnabled=$isMultiStrategyEnabled (${availableStrategies.size} strategies registered)")
    }

    /**
     * Returns the strategies that will be executed in the market scan.
     * In multi-strategy mode, returns all available strategies.
     * When multi-strategy is disabled, returns only the activeStrategy.
     */
    fun getScanningStrategies(): List<Strategy> {
        return if (isMultiStrategyEnabled) {
            availableStrategies
        } else {
            listOf(activeStrategy)
        }
    }

    fun setMultiStrategyEnabled(context: Context, enabled: Boolean) {
        isMultiStrategyEnabled = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MULTI_STRATEGY_ENABLED, enabled).apply()
        AppLogManager.i("STRATEGY", "Multi-strategy execution set to: $enabled (Active scanning strategies: ${getScanningStrategies().map { it.name }})")
    }

    fun selectStrategy(context: Context, strategyId: String): Boolean {
        val found = availableStrategies.find { it.id == strategyId } ?: return false
        activeStrategy = found

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_STRATEGY_ID, strategyId).apply()
        AppLogManager.i("STRATEGY", "Selected single active strategy: ${found.name}")
        return true
    }
}
