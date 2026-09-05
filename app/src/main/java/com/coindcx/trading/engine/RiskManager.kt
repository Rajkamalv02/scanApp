package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.FuturesPosition
import kotlin.math.abs

data class RiskSettings(
    val riskPerTradePercent: Double = 1.0,          // 1% fixed dollar risk of available balance
    val maxLeverage: Int = 5,
    val maxDailyLossPercent: Double = 4.0,           // 4% daily drawdown circuit breaker
    val maxDailyLossInr: Double = 2000.0,            // Fallback absolute limit
    val maxConcurrentPositions: Int = 3,             // Max 3 total concurrent positions
    val maxDirectionalPositions: Int = 2,            // Max 2 Longs or 2 Shorts
    val consecutiveLossLimit: Int = 3,               // 3 consecutive losses triggers cooldown
    val consecutiveLossCooldownMinutes: Long = 90L   // 90-minute cooldown duration
)

sealed class RiskCheckResult {
    data class Approved(val allocatedMarginInr: Double, val adjustedLeverage: Int) : RiskCheckResult()
    data class Rejected(val reason: String) : RiskCheckResult()
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
    var settings: RiskSettings = RiskSettings()
) {
    private var todayRealizedLossInr: Double = 0.0
    private var consecutiveLossCount: Int = 0
    private var circuitBreakerTripped: Boolean = false
    private var cooldownUntilTimestampMs: Long = 0L

    fun isCircuitBreakerTripped(): Boolean = circuitBreakerTripped

    fun isCooldownActive(): Boolean = System.currentTimeMillis() < cooldownUntilTimestampMs

    fun getCooldownRemainingMinutes(): Long {
        val remainingMs = cooldownUntilTimestampMs - System.currentTimeMillis()
        return if (remainingMs > 0) (remainingMs / 60000L) + 1 else 0L
    }

    fun getConsecutiveLossCount(): Int = consecutiveLossCount

    /**
     * Calculates required margin based on 1% fixed risk and exact stop-loss distance:
     * Notional = TargetRiskInr / SL_Distance%
     * Margin = Notional / Leverage
     */
    fun calculateRiskSizedMargin(
        balanceInr: Double,
        entryPrice: Double,
        stopLossPrice: Double,
        leverage: Int,
        minMarginInr: Double = 500.0
    ): Double {
        if (balanceInr <= 0.0 || entryPrice <= 0.0 || stopLossPrice <= 0.0) {
            return minMarginInr
        }

        val targetRiskInr = balanceInr * (settings.riskPerTradePercent / 100.0)
        val slDistPercent = abs(entryPrice - stopLossPrice) / entryPrice

        if (slDistPercent <= 0.0001) {
            return minMarginInr
        }

        val notionalInr = targetRiskInr / slDistPercent
        val effectiveLeverage = leverage.coerceIn(1, settings.maxLeverage)
        val calculatedMargin = notionalInr / effectiveLeverage

        // Enforce bounds: minMarginInr <= margin <= safe per-trade cap (balance / maxConcurrentPositions)
        val maxMarginCap = (balanceInr / settings.maxConcurrentPositions).coerceAtLeast(minMarginInr)
        return calculatedMargin.coerceIn(minMarginInr, maxMarginCap)
    }

    /**
     * Validates portfolio-level constraints:
     * - Circuit breakers & cooldowns
     * - Total active positions <= 3
     * - Max 2 Longs, Max 2 Shorts
     * - BTC Correlation Anchor: Never hold 2 altcoins Long simultaneously unless one is BTC
     */
    fun checkPortfolioAndCorrelation(
        candidatePair: String,
        isBuy: Boolean,
        activePositions: List<FuturesPosition>
    ): RiskCheckResult {
        if (circuitBreakerTripped) {
            return RiskCheckResult.Rejected("Daily circuit breaker tripped (4% loss limit reached).")
        }

        if (isCooldownActive()) {
            return RiskCheckResult.Rejected("Cooldown active ($consecutiveLossCount consecutive losses, ${getCooldownRemainingMinutes()}m remaining).")
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

        if (isBuy) {
            if (openLongs.size >= settings.maxDirectionalPositions) {
                return RiskCheckResult.Rejected("Max Long positions reached (${openLongs.size}/${settings.maxDirectionalPositions}).")
            }

            val isCandidateBtc = candidatePair.contains("BTC", ignoreCase = true)
            val hasBtcLong = openLongs.any { it.pair.contains("BTC", ignoreCase = true) }
            val altLongsCount = openLongs.count { !it.pair.contains("BTC", ignoreCase = true) }

            // If candidate is an altcoin Long and we already hold an altcoin Long without BTC,
            // opening this would result in 2 altcoins Long without BTC anchor!
            if (!isCandidateBtc && altLongsCount >= 1 && !hasBtcLong) {
                return RiskCheckResult.Rejected("BTC correlation rule: Cannot hold 2 altcoin Longs simultaneously without B-BTC_USDT.")
            }
        } else {
            if (openShorts.size >= settings.maxDirectionalPositions) {
                return RiskCheckResult.Rejected("Max Short positions reached (${openShorts.size}/${settings.maxDirectionalPositions}).")
            }
        }

        return RiskCheckResult.Approved(0.0, settings.maxLeverage)
    }

    /**
     * Records realized trade outcome in INR:
     * - Loss increments consecutive losses and adds to daily loss pool.
     * - 3 consecutive losses triggers 90-minute cooldown.
     * - Profit strictly resets consecutive losses to 0.
     */
    fun recordTradeResult(realizedPnlInr: Double, currentBalanceInr: Double = 0.0) {
        if (realizedPnlInr < 0) {
            todayRealizedLossInr += abs(realizedPnlInr)
            consecutiveLossCount++
            if (consecutiveLossCount >= settings.consecutiveLossLimit) {
                cooldownUntilTimestampMs = System.currentTimeMillis() + (settings.consecutiveLossCooldownMinutes * 60 * 1000L)
            }
            if (currentBalanceInr > 0) {
                val lossPercent = (todayRealizedLossInr / (currentBalanceInr + todayRealizedLossInr)) * 100.0
                if (lossPercent >= settings.maxDailyLossPercent) {
                    circuitBreakerTripped = true
                }
            } else if (todayRealizedLossInr >= settings.maxDailyLossInr) {
                circuitBreakerTripped = true
            }
        } else if (realizedPnlInr > 0) {
            consecutiveLossCount = 0
        }
    }

    fun resetDaily() {
        todayRealizedLossInr = 0.0
        circuitBreakerTripped = false
    }

    fun resetCooldown() {
        consecutiveLossCount = 0
        cooldownUntilTimestampMs = 0L
    }
}
