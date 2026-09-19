package com.coindcx.trading.engine

import android.content.Context
import com.coindcx.trading.engine.strategies.*
import com.coindcx.trading.util.AppLogManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime execution mode for strategies.
 */
enum class StrategyMode {
    DISABLED, // Strategy is not evaluated
    SHADOW,   // Strategy is evaluated, signals and rejections logged to telemetry, but live orders NOT executed
    LIVE      // Strategy is evaluated and signals routed to live execution engine
}

/**
 * Central Strategy Registry (§1.1, §1.7).
 * Holds all 10 quantitative institutional Kotlin strategies + legacy baselines.
 * Controls runtime execution modes (DISABLED, SHADOW, LIVE) and dynamic strategy dispatch.
 */
object StrategyRegistry {

    private const val PREFS_NAME = "trading_strategy_prefs"
    private const val KEY_ACTIVE_STRATEGY_ID = "active_strategy_id"
    private const val KEY_MULTI_STRATEGY_ENABLED = "multi_strategy_enabled"

    // Institutional Strategy Library
    val pbcStrategy = PbcStrategy()         // S1: Pullback Continuation
    val vcebStrategy = VcebStrategy()       // S2: Volatility Compression Expansion Breakout
    val lsrStrategy = LsrStrategy()         // S3: Liquidity Sweep Reversal
    val sormStrategy = SormStrategy()       // S4: Session Opening Range Momentum
    val xrsStrategy = XrsStrategy()         // S5: Cross-Sectional Relative Strength (Universe)
    val fpxStrategy = FpxStrategy()         // S6: Funding & Positioning Extreme Fade
    val rzmrStrategy = RzmrStrategy()       // S7: Range-Bound Z-Score Mean Reversion
    val ircStrategy = IrcStrategy()         // S8: Impulse-Retest Continuation
    val sbobStrategy = SbobStrategy()       // S9: Smart Money Structure Breakout
    val edtmStrategy = EdtmStrategy()       // S10: Exponential Decay Trend Momentum

    // Legacy Strategies
    val emaCrossoverStrategy = EmaCrossoverStrategy()
    val confluenceStrategy = ConfluenceStrategy()

    /**
     * All single-symbol strategies evaluated per pair.
     */
    val availableStrategies: List<Strategy> = listOf(
        pbcStrategy,
        vcebStrategy,
        lsrStrategy,
        sormStrategy,
        fpxStrategy,
        rzmrStrategy,
        ircStrategy,
        sbobStrategy,
        edtmStrategy,
        emaCrossoverStrategy,
        confluenceStrategy
    )

    /**
     * All cross-sectional universe strategies evaluated per multi-asset cycle.
     */
    val availableUniverseStrategies: List<UniverseStrategy> = listOf(
        xrsStrategy
    )

    // Strategy Execution Mode map: strategyId -> StrategyMode
    private val strategyModes = ConcurrentHashMap<String, StrategyMode>().apply {
        // Core institutional strategies default to LIVE
        put("pbc", StrategyMode.LIVE)
        put("edtm", StrategyMode.LIVE)
        put("vceb", StrategyMode.LIVE)
        put("lsr", StrategyMode.LIVE)
        put("sorm", StrategyMode.LIVE)
        put("xrs", StrategyMode.LIVE)
        put("fpx", StrategyMode.LIVE)
        put("rzmr", StrategyMode.LIVE)
        put("irc", StrategyMode.LIVE)
        put("sbob", StrategyMode.LIVE)

        // Legacy strategies default to SHADOW
        put("ema_crossover", StrategyMode.SHADOW)
        put("confluence", StrategyMode.SHADOW)
    }

    var isMultiStrategyEnabled: Boolean = true
        private set

    var activeStrategy: Strategy = pbcStrategy
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_ACTIVE_STRATEGY_ID, null)
        val target = availableStrategies.find { it.id == savedId } ?: pbcStrategy
        activeStrategy = target

        isMultiStrategyEnabled = prefs.getBoolean(KEY_MULTI_STRATEGY_ENABLED, true)

        if (savedId != target.id) {
            prefs.edit().putString(KEY_ACTIVE_STRATEGY_ID, target.id).apply()
        }
        AppLogManager.i("STRATEGY", "StrategyRegistry initialized. Active=${activeStrategy.name}, MultiStrategyEnabled=$isMultiStrategyEnabled (${availableStrategies.size} single-symbol, ${availableUniverseStrategies.size} universe strategies registered)")
    }

    /**
     * Returns the strategies that will be executed in the market scan (LIVE + SHADOW).
     */
    fun getScanningStrategies(): List<Strategy> {
        return if (isMultiStrategyEnabled) {
            availableStrategies.filter { getStrategyMode(it.id) != StrategyMode.DISABLED }
        } else {
            listOf(activeStrategy)
        }
    }

    /**
     * Returns the universe strategies that will be executed in the market scan (LIVE + SHADOW).
     */
    fun getScanningUniverseStrategies(): List<UniverseStrategy> {
        return if (isMultiStrategyEnabled) {
            availableUniverseStrategies.filter { getStrategyMode(it.id) != StrategyMode.DISABLED }
        } else {
            emptyList()
        }
    }

    /**
     * Returns strategies marked for LIVE order execution.
     */
    fun getLiveStrategies(): List<Strategy> {
        return availableStrategies.filter { getStrategyMode(it.id) == StrategyMode.LIVE }
    }

    /**
     * Returns strategies running in SHADOW telemetry mode.
     */
    fun getShadowStrategies(): List<Strategy> {
        return availableStrategies.filter { getStrategyMode(it.id) == StrategyMode.SHADOW }
    }

    fun getStrategyMode(strategyId: String): StrategyMode {
        return strategyModes[strategyId.lowercase()] ?: StrategyMode.SHADOW
    }

    fun setStrategyMode(strategyId: String, mode: StrategyMode) {
        strategyModes[strategyId.lowercase()] = mode
        AppLogManager.i("STRATEGY", "Strategy [$strategyId] mode updated to $mode")
    }

    fun getStrategy(strategyId: String): Strategy? {
        return availableStrategies.find { it.id.equals(strategyId, ignoreCase = true) }
    }

    fun getUniverseStrategy(strategyId: String): UniverseStrategy? {
        return availableUniverseStrategies.find { it.id.equals(strategyId, ignoreCase = true) }
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
