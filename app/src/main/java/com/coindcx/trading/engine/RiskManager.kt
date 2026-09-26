package com.coindcx.trading.engine

import android.content.Context
import android.content.SharedPreferences
import com.coindcx.trading.data.api.models.FuturesPosition
import kotlin.math.abs

data class RiskSettings(
    val riskPerTradePercent: Double = 1.0,          // 1% fixed dollar risk of available balance
    val maxLeverage: Int = 5,
    val enableDailyLossLimit: Boolean = false,       // Daily loss limit check disabled per user request
    val maxDailyLossPercent: Double = 4.0,           // 4% daily drawdown circuit breaker
    val maxDailyLossInr: Double = 2000.0,            // Fallback absolute limit
    val maxConcurrentPositions: Int = 4,             // Max total concurrent positions across portfolio
    val maxDirectionalPositions: Int = 2,            // Backwards compatibility
    val maxLongPositions: Int = maxDirectionalPositions,
    val maxShortPositions: Int = maxDirectionalPositions,
    val consecutiveLossLimit: Int = 3,               // 3 consecutive losses triggers cooldown
    val consecutiveLossCooldownMinutes: Long = 90L,  // 90-minute cooldown duration
    val liquidationBufferMultiplier: Double = 1.25,  // Tunable: Minimum 25% clearance between SL and Liquidation
    val maxFloorRiskPercent: Double = 2.5,           // Max single-trade risk cap on exchange floor bump (allows micro-accounts to trade at 2x)
    val symbolLossCooldownMinutes: Long = 30L,       // 30-minute lockout on a specific pair after taking a loss
    val entryOrderTtlSeconds: Long = 120L            // Cancel unfilled entry limit orders after 120 seconds
)

sealed class RiskCheckResult {
    data class Approved(val allocatedMarginInr: Double, val adjustedLeverage: Int) : RiskCheckResult()
    data class Rejected(val reason: String) : RiskCheckResult()
}

sealed class SizingResult {
    data class Sized(
        val allocatedMarginInr: Double,
        val effectiveLeverage: Int,
        val notionalInr: Double,
        val isAdjustedForExchangeFloor: Boolean
    ) : SizingResult()

    data class Rejected(val reason: String) : SizingResult()
}

/**
 * Institutional Risk Manager
 * First line of defense — enforces hard limits before allowing any order execution:
 * 1. Daily drawdown circuit breaker (4% account loss)
 * 2. Consecutive loss circuit breaker (3 losses -> 90-min cooldown)
 * 3. Portfolio exposure limits (Max 3 total, max 2 directional)
 * 4. BTC correlation anchor (Never 2 altcoins Long without BTC)
 * 5. Volatility-adjusted risk parity sizing (1% risk / SL distance %)
 */
class RiskManager(
    var settings: RiskSettings = RiskSettings(),
    private val context: Context? = null
) {
    companion object {
        const val PREFS_NAME = "trading_risk_prefs"
        const val KEY_DAILY_LOSS = "today_realized_loss_inr"
        const val KEY_CONSECUTIVE_LOSS = "consecutive_loss_count"
        const val KEY_CIRCUIT_BREAKER = "circuit_breaker_tripped"
        const val KEY_COOLDOWN_UNTIL = "cooldown_until_timestamp_ms"
        const val KEY_LAST_EPOCH_DAY = "last_epoch_day"
    }

    private var todayRealizedLossInr: Double = 0.0
    private var consecutiveLossCount: Int = 0
    private var circuitBreakerTripped: Boolean = false
    private var cooldownUntilTimestampMs: Long = 0L
    private var lastEpochDay: Long = System.currentTimeMillis() / 86400000L

    init {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPreferences(prefs)
        }
    }

    fun getTodayRealizedLossInr(): Double = todayRealizedLossInr

    fun isCircuitBreakerTripped(): Boolean {
        if (!settings.enableDailyLossLimit) return false
        checkAndResetIfNewDay()
        return circuitBreakerTripped
    }

    fun isCooldownActive(): Boolean = System.currentTimeMillis() < cooldownUntilTimestampMs

    fun getCooldownRemainingMinutes(): Long {
        val remainingMs = cooldownUntilTimestampMs - System.currentTimeMillis()
        return if (remainingMs > 0) (remainingMs / 60000L) + 1 else 0L
    }

    fun getConsecutiveLossCount(): Int = consecutiveLossCount

    private val symbolCooldownMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun recordSymbolLoss(pair: String, durationMinutes: Long = settings.symbolLossCooldownMinutes) {
        if (pair.isNotBlank()) {
            val cooldownUntil = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
            symbolCooldownMap[pair] = cooldownUntil
        }
    }

    fun isSymbolInCooldown(pair: String): Boolean {
        val until = symbolCooldownMap[pair] ?: return false
        return if (System.currentTimeMillis() < until) {
            true
        } else {
            symbolCooldownMap.remove(pair)
            false
        }
    }

    fun getSymbolCooldownRemainingMinutes(pair: String): Long {
        val until = symbolCooldownMap[pair] ?: return 0L
        val remainingMs = until - System.currentTimeMillis()
        return if (remainingMs > 0) (remainingMs / 60000L) + 1 else 0L
    }

    fun clearSymbolCooldown(pair: String) {
        symbolCooldownMap.remove(pair)
    }

    fun clearAllSymbolCooldowns() {
        symbolCooldownMap.clear()
    }

    private fun checkAndResetIfNewDay() {
        val currentEpochDay = System.currentTimeMillis() / 86400000L
        if (currentEpochDay > lastEpochDay) {
            todayRealizedLossInr = 0.0
            circuitBreakerTripped = false
            lastEpochDay = currentEpochDay
            persistState()
        }
    }

    fun saveToPreferences(prefs: SharedPreferences) {
        prefs.edit()
            .putFloat(KEY_DAILY_LOSS, todayRealizedLossInr.toFloat())
            .putInt(KEY_CONSECUTIVE_LOSS, consecutiveLossCount)
            .putBoolean(KEY_CIRCUIT_BREAKER, circuitBreakerTripped)
            .putLong(KEY_COOLDOWN_UNTIL, cooldownUntilTimestampMs)
            .putLong(KEY_LAST_EPOCH_DAY, lastEpochDay)
            .apply()
    }

    fun loadFromPreferences(prefs: SharedPreferences) {
        val currentEpochDay = System.currentTimeMillis() / 86400000L
        val savedEpochDay = prefs.getLong(KEY_LAST_EPOCH_DAY, currentEpochDay)
        if (currentEpochDay > savedEpochDay) {
            todayRealizedLossInr = 0.0
            circuitBreakerTripped = false
            consecutiveLossCount = prefs.getInt(KEY_CONSECUTIVE_LOSS, 0)
            cooldownUntilTimestampMs = prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)
            lastEpochDay = currentEpochDay
            saveToPreferences(prefs)
        } else {
            todayRealizedLossInr = prefs.getFloat(KEY_DAILY_LOSS, 0f).toDouble()
            consecutiveLossCount = prefs.getInt(KEY_CONSECUTIVE_LOSS, 0)
            circuitBreakerTripped = prefs.getBoolean(KEY_CIRCUIT_BREAKER, false)
            cooldownUntilTimestampMs = prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)
            lastEpochDay = savedEpochDay
        }
        if (!settings.enableDailyLossLimit) {
            circuitBreakerTripped = false
        }
    }

    private fun persistState() {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            saveToPreferences(prefs)
        }
    }

    /**
     * Calculates required margin using 1% risk parity, bounded by user budget cap
     * and CoinDCX exchange notional floor.
     *
     * Enforces:
     * 1. Budget ceiling = min(userBudget, balance / maxConcurrentPositions)
     * 2. Exchange floor = minExchangeNotional / leverage
     * 3. Liquidation Safety Guard: Auto-bumping leverage is strictly prohibited if
     *    estimated liquidation distance would be <= Stop Loss * liquidationBufferMultiplier
     */
    fun calculateRiskSizedMargin(
        balanceInr: Double,
        entryPrice: Double,
        stopLossPrice: Double,
        requestedLeverage: Int,
        userBudgetInr: Double,
        minOrderNotionalInr: Double
    ): SizingResult {
        if (balanceInr <= 0.0 || entryPrice <= 0.0 || stopLossPrice <= 0.0) {
            return SizingResult.Rejected("Invalid price, balance, or stop loss for sizing.")
        }

        // 1. Budget Ceiling
        val safePerTradeCap = balanceInr / settings.maxConcurrentPositions
        val ceilingMargin = userBudgetInr.coerceAtMost(safePerTradeCap)

        if (ceilingMargin <= 0.0 || balanceInr < ceilingMargin) {
            return SizingResult.Rejected("Insufficient balance (₹%.2f) for trade budget (₹%.2f)".format(balanceInr, userBudgetInr))
        }

        val slDistPercent = abs(entryPrice - stopLossPrice) / entryPrice
        if (slDistPercent <= 0.0001) {
            return SizingResult.Rejected("Stop loss distance too close to entry price.")
        }

        // 2. Resolve Floor vs Ceiling with Liquidation Safety Guard
        var effectiveLeverage = requestedLeverage.coerceIn(1, settings.maxLeverage)
        var floorMargin = minOrderNotionalInr / effectiveLeverage

        if (floorMargin > ceilingMargin) {
            var foundSafeLeverage = false
            val minRequiredLev = kotlin.math.ceil(minOrderNotionalInr / ceilingMargin).toInt()

            // Loop guarantees correctness regardless of future MMR schedule properties
            for (candidateLev in minRequiredLev..settings.maxLeverage) {
                val candidateFloorMargin = minOrderNotionalInr / candidateLev
                val candidateLiqDist = MaintenanceMarginSchedule.getEstimatedLiquidationDistancePct(candidateLev)
                val isLiquidationSafe = candidateLiqDist >= (slDistPercent * settings.liquidationBufferMultiplier)

                if (candidateFloorMargin <= ceilingMargin && isLiquidationSafe) {
                    effectiveLeverage = candidateLev
                    floorMargin = candidateFloorMargin
                    foundSafeLeverage = true
                    break
                }
            }

            if (!foundSafeLeverage) {
                val minReqLiqDist = MaintenanceMarginSchedule.getEstimatedLiquidationDistancePct(minRequiredLev.coerceAtMost(settings.maxLeverage))
                val requiredBuffer = slDistPercent * settings.liquidationBufferMultiplier

                return if (minRequiredLev <= settings.maxLeverage && minReqLiqDist < requiredBuffer) {
                    SizingResult.Rejected(
                        "Stop loss (%.2f%%) is too wide for required leverage: liquidation distance at %dx (%.2f%%) is within safety buffer (%.2f%%)."
                            .format(slDistPercent * 100.0, minRequiredLev, minReqLiqDist * 100.0, requiredBuffer * 100.0)
                    )
                } else {
                    SizingResult.Rejected(
                        "Exchange minimum order (₹%.0f) requires ₹%.0f margin at max %dx leverage, exceeding ₹%.0f budget."
                            .format(minOrderNotionalInr, minOrderNotionalInr / settings.maxLeverage, settings.maxLeverage, ceilingMargin)
                    )
                }
            }
        }

        // 3. 1% Risk Parity Calculation
        val targetRiskInr = balanceInr * (settings.riskPerTradePercent / 100.0)
        val idealNotional = targetRiskInr / slDistPercent
        val idealMargin = idealNotional / effectiveLeverage

        // 4. Guaranteed Safe Clamping (floorMargin <= ceilingMargin is mathematically proven)
        val finalMargin = idealMargin.coerceIn(floorMargin, ceilingMargin)
        val finalNotional = finalMargin * effectiveLeverage

        return SizingResult.Sized(
            allocatedMarginInr = finalMargin,
            effectiveLeverage = effectiveLeverage,
            notionalInr = finalNotional,
            isAdjustedForExchangeFloor = finalMargin == floorMargin && idealMargin < floorMargin
        )
    }

    /**
     * Validates portfolio-level constraints:
     * - Circuit breakers & cooldowns
     * - Total active positions <= 3
     * - Max 2 Longs, Max 2 Shorts
     * - BTC Macro Regime Anchor:
     *   - Cannot hold 2 altcoins Long simultaneously if BTC macro trend is bearish (or unverified without BTC Long).
     *   - Cannot hold 2 altcoins Short simultaneously if BTC macro trend is bullish (or unverified without BTC Short).
     */
    fun checkPortfolioAndCorrelation(
        candidatePair: String,
        isBuy: Boolean,
        activePositions: List<FuturesPosition>,
        btcMacroTrendIsBullish: Boolean? = null
    ): RiskCheckResult {
        checkAndResetIfNewDay()
        if (settings.enableDailyLossLimit && circuitBreakerTripped) {
            return RiskCheckResult.Rejected("Daily circuit breaker tripped (4% loss limit reached).")
        }

        if (isCooldownActive()) {
            return RiskCheckResult.Rejected("Cooldown active ($consecutiveLossCount consecutive losses, ${getCooldownRemainingMinutes()}m remaining).")
        }

        if (isSymbolInCooldown(candidatePair)) {
            val remainingMins = getSymbolCooldownRemainingMinutes(candidatePair)
            return RiskCheckResult.Rejected("Per-symbol cooldown active for $candidatePair ($remainingMins min remaining after recent loss).")
        }

        val openPositions = activePositions.filter { it.isOpen }
        if (openPositions.size >= settings.maxConcurrentPositions) {
            return RiskCheckResult.Rejected("Max concurrent positions reached (${openPositions.size}/${settings.maxConcurrentPositions}).")
        }

        if (openPositions.any { it.pair.equals(candidatePair, ignoreCase = true) }) {
            return RiskCheckResult.Rejected("Position already open on $candidatePair.")
        }

        val openLongs = openPositions.filter { it.isLong }
        val openShorts = openPositions.filter { it.isShort }
        // val isCandidateBtc = candidatePair.contains("BTC", ignoreCase = true)

        if (isBuy) {
            if (openLongs.size >= settings.maxLongPositions) {
                return RiskCheckResult.Rejected("Max Long positions reached (${openLongs.size}/${settings.maxLongPositions}).")
            }

            // BTC correlation rule for 2 altcoins disabled/commented out:
            // val altLongsCount = openLongs.count { !it.pair.contains("BTC", ignoreCase = true) }
            // if (!isCandidateBtc && altLongsCount >= 1) {
            //     if (btcMacroTrendIsBullish == false) {
            //         return RiskCheckResult.Rejected("BTC correlation rule: Cannot hold 2 altcoin Longs while BTC 1h trend is bearish.")
            //     } else if (btcMacroTrendIsBullish == null) {
            //         val hasBtcLong = openLongs.any { it.pair.contains("BTC", ignoreCase = true) }
            //         if (!hasBtcLong) {
            //             return RiskCheckResult.Rejected("BTC correlation rule: Cannot hold 2 altcoin Longs simultaneously without B-BTC_USDT.")
            //         }
            //     }
            // }
        } else {
            if (openShorts.size >= settings.maxShortPositions) {
                return RiskCheckResult.Rejected("Max Short positions reached (${openShorts.size}/${settings.maxShortPositions}).")
            }

            // BTC correlation rule for 2 altcoins disabled/commented out:
            // val altShortsCount = openShorts.count { !it.pair.contains("BTC", ignoreCase = true) }
            // if (!isCandidateBtc && altShortsCount >= 1) {
            //     if (btcMacroTrendIsBullish == true) {
            //         return RiskCheckResult.Rejected("BTC correlation rule: Cannot hold 2 altcoin Shorts while BTC 1h trend is bullish.")
            //     } else if (btcMacroTrendIsBullish == null) {
            //         val hasBtcShort = openShorts.any { it.pair.contains("BTC", ignoreCase = true) }
            //         if (!hasBtcShort) {
            //             return RiskCheckResult.Rejected("BTC correlation rule: Cannot hold 2 altcoin Shorts simultaneously without B-BTC_USDT.")
            //         }
            //     }
            // }
        }

        return RiskCheckResult.Approved(0.0, settings.maxLeverage)
    }

    /**
     * Records realized trade outcome in INR:
     * - Loss increments consecutive losses and adds to daily loss pool.
     * - Any loss on a specific pair initiates a per-symbol cooldown (default 30m) to avoid churn.
     * - 3 consecutive losses triggers 90-minute cooldown.
     * - Profit strictly resets consecutive losses to 0.
     */
    fun recordTradeResult(
        realizedPnlInr: Double,
        currentBalanceInr: Double = 0.0,
        pair: String? = null
    ) {
        checkAndResetIfNewDay()
        if (realizedPnlInr < 0) {
            todayRealizedLossInr += abs(realizedPnlInr)
            consecutiveLossCount++
            if (!pair.isNullOrBlank()) {
                recordSymbolLoss(pair)
            }
            if (consecutiveLossCount >= settings.consecutiveLossLimit) {
                cooldownUntilTimestampMs = System.currentTimeMillis() + (settings.consecutiveLossCooldownMinutes * 60 * 1000L)
            }
            if (settings.enableDailyLossLimit) {
                if (currentBalanceInr > 0) {
                    val lossPercent = (todayRealizedLossInr / (currentBalanceInr + todayRealizedLossInr)) * 100.0
                    if (lossPercent >= settings.maxDailyLossPercent) {
                        circuitBreakerTripped = true
                    }
                } else if (todayRealizedLossInr >= settings.maxDailyLossInr) {
                    circuitBreakerTripped = true
                }
            }
        } else if (realizedPnlInr > 0) {
            consecutiveLossCount = 0
        }
        persistState()
    }

    fun resetDaily() {
        todayRealizedLossInr = 0.0
        circuitBreakerTripped = false
        persistState()
    }

    fun resetCooldown() {
        consecutiveLossCount = 0
        cooldownUntilTimestampMs = 0L
        symbolCooldownMap.clear()
        persistState()
    }
}
