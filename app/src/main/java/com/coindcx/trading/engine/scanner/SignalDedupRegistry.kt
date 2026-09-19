package com.coindcx.trading.engine.scanner

import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalDirection
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.scanner.gate.GateResult
import com.coindcx.trading.engine.telemetry.RejectionCode
import com.coindcx.trading.engine.time.TradingClock
import java.util.concurrent.ConcurrentHashMap

enum class StrategyFamily {
    TREND,      // S1 (PBC), S10 (EDTM)
    BREAKOUT,   // S2 (VCEB), S4 (SORM), S8 (IRC)
    MEANREV,    // S3 (LSR), S6 (FPX), S7 (RZMR)
    STRUCTURE,  // S9 (SBOB)
    ROTATION    // S5 (XRS)
}

/**
 * Signal Deduplication & Cooldown Registry (§1.4).
 * Replaces ambiguous G7 bar cooldowns with wall-clock family cooldowns,
 * and tracks signal history required for cross-strategy conflict resolution.
 */
class SignalDedupRegistry(
    val familyCooldownMs: Long = 90 * 60_000L // 90 minutes default cooldown within family
) {
    data class EmittedSignalRecord(
        val symbol: String,
        val strategyId: String,
        val family: StrategyFamily,
        val direction: SignalDirection,
        val barOpenTimeUtc: Long,
        val emissionTimeMs: Long
    )

    // Key: "$symbol:$family" -> last emission timestamp
    private val familyCooldowns = ConcurrentHashMap<String, Long>()

    // Ring buffer of recent signals per symbol (retains up to 20 signals per symbol)
    private val recentSignalsBySymbol = ConcurrentHashMap<String, MutableList<EmittedSignalRecord>>()

    companion object {
        val default = SignalDedupRegistry()

        fun getFamilyForStrategy(strategyId: String): StrategyFamily = when (strategyId.lowercase()) {
            "pbc", "s1", "edtm", "s10", "ema_crossover" -> StrategyFamily.TREND
            "vceb", "s2", "sorm", "s4", "irc", "s8" -> StrategyFamily.BREAKOUT
            "lsr", "s3", "fpx", "s6", "rzmr", "s7" -> StrategyFamily.MEANREV
            "sbob", "s9", "confluence" -> StrategyFamily.STRUCTURE
            "xrs", "s5" -> StrategyFamily.ROTATION
            else -> StrategyFamily.TREND
        }
    }

    /**
     * Checks if a new signal in this family is permitted on this symbol.
     */
    fun evaluate(symbol: String, strategyId: String, clock: TradingClock): GateResult {
        val family = getFamilyForStrategy(strategyId)
        val key = "$symbol:$family"
        val lastTime = familyCooldowns[key] ?: return GateResult.PASS

        val elapsed = clock.nowUtcMillis() - lastTime
        return if (elapsed < familyCooldownMs) {
            val remainingMin = (familyCooldownMs - elapsed) / 60_000.0
            GateResult.reject(
                RejectionCode.GATE_G7_COOLDOWN,
                "G7: Symbol $symbol in $family cooldown (remaining ${"%.1f".format(remainingMin)} min)"
            )
        } else {
            GateResult.PASS
        }
    }

    fun recordSignal(signal: Signal, clock: TradingClock) {
        val family = getFamilyForStrategy(signal.strategyId)
        val now = clock.nowUtcMillis()
        familyCooldowns["${signal.symbol}:$family"] = now

        val record = EmittedSignalRecord(
            symbol = signal.symbol,
            strategyId = signal.strategyId,
            family = family,
            direction = signal.direction,
            barOpenTimeUtc = signal.barOpenTimeUtc,
            emissionTimeMs = now
        )

        val list = recentSignalsBySymbol.computeIfAbsent(signal.symbol) { mutableListOf() }
        synchronized(list) {
            list.add(record)
            if (list.size > 20) {
                list.removeAt(0)
            }
        }
    }

    /**
     * Checks if a specific strategy produced a signal on this symbol within the last [maxBars] of [interval].
     * Required for S2 ↔ S3 conflict resolution ("within 3 bars of S2 signal").
     */
    fun hasSignalWithinBars(
        symbol: String,
        targetStrategyId: String,
        interval: Interval,
        maxBars: Int,
        currentBarTime: Long
    ): Boolean {
        val list = recentSignalsBySymbol[symbol] ?: return false
        val windowMs = maxBars * interval.durationMs

        synchronized(list) {
            return list.any { record ->
                record.strategyId.equals(targetStrategyId, ignoreCase = true) &&
                        (currentBarTime - record.barOpenTimeUtc) in 0..windowMs
            }
        }
    }

    fun clear() {
        familyCooldowns.clear()
        recentSignalsBySymbol.clear()
    }
}
