package com.coindcx.trading.engine.state

import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.util.AppLogManager
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory thread-safe state store with staleness detection and active universe eviction.
 */
class StrategyStateStore {

    private val store = ConcurrentHashMap<String, StrategyState>()

    private fun makeKey(symbol: String, strategyId: String): String = "$symbol:$strategyId"

    fun get(symbol: String, strategyId: String): StrategyState? {
        return store[makeKey(symbol, strategyId)]
    }

    /**
     * Retrieves state with strict staleness check.
     * If the state's last update is older than maxToleranceBars * intervalMs, it is
     * purged and null is returned, forcing the strategy to rebuild deterministically from historical candles.
     */
    fun getIfFresh(
        symbol: String,
        strategyId: String,
        currentBarOpenTime: Long,
        interval: Interval,
        maxToleranceBars: Int
    ): StrategyState? {
        val key = makeKey(symbol, strategyId)
        val state = store[key] ?: return null

        val deltaMs = currentBarOpenTime - state.lastUpdatedBarOpenTime
        val elapsedBars = if (interval.durationMs > 0) deltaMs / interval.durationMs else Long.MAX_VALUE

        return if (elapsedBars in 0..maxToleranceBars.toLong()) {
            state
        } else {
            AppLogManager.d("STATE", "[$symbol] [$strategyId] State stale ($elapsedBars bars elapsed > max $maxToleranceBars). Evicting to force clean rebuild.")
            store.remove(key)
            null
        }
    }

    fun put(symbol: String, strategyId: String, state: StrategyState) {
        store[makeKey(symbol, strategyId)] = state
    }

    fun remove(symbol: String, strategyId: String) {
        store.remove(makeKey(symbol, strategyId))
    }

    fun clear() {
        store.clear()
    }

    /**
     * Evicts states for symbols no longer in the active scanning universe.
     */
    fun evictNotIn(activeSymbols: Set<String>) {
        val keysToRemove = store.keys().toList().filter { key ->
            val sym = key.substringBefore(":")
            !activeSymbols.contains(sym)
        }
        for (k in keysToRemove) {
            store.remove(k)
        }
    }

    fun size(): Int = store.size
}
