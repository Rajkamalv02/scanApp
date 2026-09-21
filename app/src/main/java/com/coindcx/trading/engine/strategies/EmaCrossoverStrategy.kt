package com.coindcx.trading.engine.strategies

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.Strategy
import com.coindcx.trading.engine.StrategyDiagnostics
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.util.AppLogManager
import kotlin.math.max

/**
 * Pure, Institutional EMA Crossover Strategy.
 *
 * Signal Architecture:
 * - Evaluates Fast EMA and Slow EMA crossovers on completed bars (t-1 vs t-2) to eliminate live-candle repainting.
 * - Dynamic parameters (fastEmaPeriod, slowEmaPeriod, atrMultiplier) configurable at runtime.
 * - Confirmed Bullish Crossover: Fast EMA crosses strictly above Slow EMA on completed bar -> ENTER_LONG.
 * - Confirmed Bearish Crossover: Fast EMA crosses strictly below Slow EMA on completed bar -> ENTER_SHORT.
 * - Active Position Management: Reversal cross or SL/TP targets triggers EXIT.
 * - Risk Management: ATR-based Stop Loss with 2.0x R:R Take Profit (Net R:R >= 1.80 after taker fees).
 */
class EmaCrossoverStrategy(
    initialFastPeriod: Int = 9,
    initialSlowPeriod: Int = 21,
    initialAtrMultiplier: Double = 1.5,
    val riskRewardRatio: Double = 0.75,
    val stopLossPercent: Double = 3.0,
    val targetPricePercent: Double = 1.5
) : Strategy {

    override val id: String = "ema_crossover"
    override val name: String = "EMA Crossover Strategy"
    override val description: String = "Pure trend-following momentum strategy using Fast/Slow EMA confirmed bar crossovers with ATR volatility targets."
    override val defaultTimeframe: String = "15m"
    override val preferredRegime: com.coindcx.trading.engine.MarketRegimePreference = com.coindcx.trading.engine.MarketRegimePreference.TRENDING_MOMENTUM

    @Volatile
    var fastPeriod: Int = initialFastPeriod
        private set

    @Volatile
    var slowPeriod: Int = initialSlowPeriod
        private set

    @Volatile
    var atrMultiplier: Double = initialAtrMultiplier
        private set

    override val requiredCandleCount: Int
        get() = max(slowPeriod * 3, 50)

    override val parametersSummary: String
        get() = "Fast: $fastPeriod, Slow: $slowPeriod, ATR: ${atrMultiplier}x, R:R: 1:${riskRewardRatio.toInt()}"

    init {
        AppLogManager.i("STRATEGY", "Initialized $name: Fast=$fastPeriod, Slow=$slowPeriod, ATR Mult=${atrMultiplier}x, Default TF=$defaultTimeframe")
    }

    /**
     * Updates strategy tuning parameters dynamically from TradingConfig.
     */
    fun configure(fast: Int, slow: Int, atrMult: Double) {
        val updatedFast = if (fast in 2..100) fast else fastPeriod
        val updatedSlow = if (slow > updatedFast && slow <= 300) slow else slowPeriod
        val updatedAtrMult = if (atrMult in 0.5..5.0) atrMult else atrMultiplier

        if (updatedFast != fastPeriod || updatedSlow != slowPeriod || updatedAtrMult != atrMultiplier) {
            fastPeriod = updatedFast
            slowPeriod = updatedSlow
            atrMultiplier = updatedAtrMult
            AppLogManager.i("STRATEGY", "Reconfigured $name: Fast=$fastPeriod, Slow=$slowPeriod, ATR Mult=${atrMultiplier}x")
        }
    }

    override fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?, pair: String): Signal {
        if (candles.size < requiredCandleCount) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "Insufficient candles: ${candles.size}/$requiredCandleCount required for warmup",
                confidenceScore = 0.0
            )
        }

        val sortedCandles = if (candles.size > 1 && candles[0].time > candles.last().time) {
            candles.sortedBy { it.time }
        } else {
            candles
        }

        val closePrices = sortedCandles.map { it.close }
        val currentPrice = sortedCandles.last().close // Current live market price for order sizing

        // 1. Calculate Technical Indicators
        val fastEmas = TechnicalIndicators.calculateEma(closePrices, fastPeriod)
        val slowEmas = TechnicalIndicators.calculateEma(closePrices, slowPeriod)
        val atr = TechnicalIndicators.calculateAtr(sortedCandles, 14).coerceAtLeast(0.0001)

        if (fastEmas.size < 3 || slowEmas.size < 3) {
            return Signal(
                action = SignalAction.HOLD,
                reason = "EMA series warming up",
                confidenceScore = 0.0
            )
        }

        // Aligned EMA indexing:
        // Index size - 1 = Forming live candle (t)
        // Index size - 2 = Most recently completed candle (t-1)
        // Index size - 3 = Prior completed candle (t-2)
        val currFast = fastEmas[fastEmas.size - 2]
        val currSlow = slowEmas[slowEmas.size - 2]
        val prevFast = fastEmas[fastEmas.size - 3]
        val prevSlow = slowEmas[slowEmas.size - 3]
        val liveFast = fastEmas.last()
        val liveSlow = slowEmas.last()

        val isBullishCrossover = (prevFast <= prevSlow) && (currFast > currSlow)
        val isBearishCrossover = (prevFast >= prevSlow) && (currFast < currSlow)

        val emaSpreadPct = if (currSlow > 0) ((currFast - currSlow) / currSlow) * 100.0 else 0.0

        val diag = StrategyDiagnostics(
            stage = if (isBullishCrossover || isBearishCrossover) "CROSSOVER_TRIGGER" else "TREND_EVAL",
            indicators = mapOf(
                "fastEma" to currFast,
                "slowEma" to currSlow,
                "prevFastEma" to prevFast,
                "prevSlowEma" to prevSlow,
                "liveFastEma" to liveFast,
                "liveSlowEma" to liveSlow,
                "atr" to atr,
                "emaSpreadPct" to emaSpreadPct
            ),
            flags = mapOf(
                "isBullishCrossover" to isBullishCrossover,
                "isBearishCrossover" to isBearishCrossover
            )
        )

        // 2. Active Position Management
        if (activePosition != null && activePosition.isOpen) {
            if (activePosition.isLong) {
                if (isBearishCrossover) {
                    AppLogManager.trade("STRATEGY", "Long position exit triggered by confirmed Bearish EMA Crossover: Fast (%.4f) < Slow (%.4f)".format(currFast, currSlow))
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Bearish EMA Crossover: Fast ($fastPeriod) crossed below Slow ($slowPeriod). Trend reversal exit.",
                        confidenceScore = 90.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                if (activePosition.takeProfitTrigger != null && currentPrice >= activePosition.takeProfitTrigger) {
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Take Profit target filled @ %.4f".format(currentPrice),
                        confidenceScore = 95.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                if (activePosition.stopLossTrigger != null && currentPrice <= activePosition.stopLossTrigger) {
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Stop Loss trigger hit @ %.4f".format(currentPrice),
                        confidenceScore = 95.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                return Signal(
                    action = SignalAction.HOLD,
                    reason = "Holding Long position. Fast EMA: %.4f, Slow EMA: %.4f (Spread: %+.2f%%)".format(currFast, currSlow, emaSpreadPct),
                    confidenceScore = 50.0,
                    diagnostics = diag,
                    strategyId = id,
                    strategyName = name
                )
            } else if (activePosition.isShort) {
                if (isBullishCrossover) {
                    AppLogManager.trade("STRATEGY", "Short position exit triggered by confirmed Bullish EMA Crossover: Fast (%.4f) > Slow (%.4f)".format(currFast, currSlow))
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Bullish EMA Crossover: Fast ($fastPeriod) crossed above Slow ($slowPeriod). Trend reversal exit.",
                        confidenceScore = 90.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                if (activePosition.takeProfitTrigger != null && currentPrice <= activePosition.takeProfitTrigger) {
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Take Profit target filled @ %.4f".format(currentPrice),
                        confidenceScore = 95.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                if (activePosition.stopLossTrigger != null && currentPrice >= activePosition.stopLossTrigger) {
                    return Signal(
                        action = SignalAction.EXIT,
                        reason = "Stop Loss trigger hit @ %.4f".format(currentPrice),
                        confidenceScore = 95.0,
                        diagnostics = diag,
                        strategyId = id,
                        strategyName = name
                    )
                }
                return Signal(
                    action = SignalAction.HOLD,
                    reason = "Holding Short position. Fast EMA: %.4f, Slow EMA: %.4f (Spread: %+.2f%%)".format(currFast, currSlow, emaSpreadPct),
                    confidenceScore = 50.0,
                    diagnostics = diag,
                    strategyId = id,
                    strategyName = name
                )
            }
        }

        // 3. New Entry Signal Generation on Fresh Confirmed Crossover
        if (isBullishCrossover) {
            val tradeId = AppLogManager.TradeIdGenerator.generate(pair.ifEmpty { "EMA" })
            val riskDistance = currentPrice * (stopLossPercent / 100.0)
            val stopLossPrice = currentPrice - riskDistance
            val clampedTpDist = currentPrice * (targetPricePercent / 100.0)
            val takeProfitPrice = currentPrice + clampedTpDist
            val slDistPct = stopLossPercent
            val tpDistPct = targetPricePercent
            val actualRR = targetPricePercent / stopLossPercent

            AppLogManager.tradeLifecycle(
                event = "SIGNAL_GENERATED",
                tradeId = tradeId,
                symbol = pair.ifEmpty { "FUTURES" },
                mode = "EVAL",
                attributes = mapOf(
                    "side" to "LONG",
                    "entry_price" to "%.4f".format(currentPrice),
                    "fast_ema" to "%.4f".format(currFast),
                    "slow_ema" to "%.4f".format(currSlow),
                    "prev_fast_ema" to "%.4f".format(prevFast),
                    "prev_slow_ema" to "%.4f".format(prevSlow),
                    "crossover" to "BULLISH_CONFIRMED",
                    "atr_14" to "%.4f".format(atr),
                    "atr_mult" to "%.2fx".format(atrMultiplier),
                    "sl_dist" to "%.4f".format(riskDistance),
                    "sl_dist_pct" to "%.2f%%".format(slDistPct),
                    "stop_loss" to "%.4f".format(stopLossPrice),
                    "target" to "%.4f".format(takeProfitPrice),
                    "tp_dist_pct" to "%.2f%%".format(tpDistPct),
                    "rr_ratio" to "1:%.2f".format(actualRR),
                    "confidence" to 80.0
                ),
                narrative = "Bullish EMA Crossover detected: Fast(%.4f) crossed above Slow(%.4f) on confirmed bar (Prev: %.4f <= %.4f) -> Long signal -> Entry=%.4f, SL=%.4f (dist: %.4f), TP=%.4f (dist: %.4f), ATR=%.4f"
                    .format(currFast, currSlow, prevFast, prevSlow, currentPrice, stopLossPrice, riskDistance, takeProfitPrice, takeProfitPrice - currentPrice, atr)
            )

            return Signal(
                action = SignalAction.ENTER_LONG,
                tradeId = tradeId,
                entryPrice = currentPrice,
                fastEma = currFast,
                slowEma = currSlow,
                prevFastEma = prevFast,
                prevSlowEma = prevSlow,
                atr = atr,
                atrMultiplier = atrMultiplier,
                riskDistance = riskDistance,
                riskRewardRatio = actualRR,
                stopLossPrice = stopLossPrice,
                takeProfitPrice = takeProfitPrice,
                confidenceScore = 80.0,
                reason = "Bullish EMA Crossover: Fast ($fastPeriod) crossed above Slow ($slowPeriod) on confirmed bar",
                diagnostics = diag,
                strategyId = id,
                strategyName = name
            )
        }

        if (isBearishCrossover) {
            val tradeId = AppLogManager.TradeIdGenerator.generate(pair.ifEmpty { "EMA" })
            val riskDistance = currentPrice * (stopLossPercent / 100.0)
            val stopLossPrice = currentPrice + riskDistance
            val clampedTpDist = currentPrice * (targetPricePercent / 100.0)
            val takeProfitPrice = currentPrice - clampedTpDist
            val slDistPct = stopLossPercent
            val tpDistPct = targetPricePercent
            val actualRR = targetPricePercent / stopLossPercent

            AppLogManager.tradeLifecycle(
                event = "SIGNAL_GENERATED",
                tradeId = tradeId,
                symbol = pair.ifEmpty { "FUTURES" },
                mode = "EVAL",
                attributes = mapOf(
                    "side" to "SHORT",
                    "entry_price" to "%.4f".format(currentPrice),
                    "fast_ema" to "%.4f".format(currFast),
                    "slow_ema" to "%.4f".format(currSlow),
                    "prev_fast_ema" to "%.4f".format(prevFast),
                    "prev_slow_ema" to "%.4f".format(prevSlow),
                    "crossover" to "BEARISH_CONFIRMED",
                    "atr_14" to "%.4f".format(atr),
                    "atr_mult" to "%.2fx".format(atrMultiplier),
                    "sl_dist" to "%.4f".format(riskDistance),
                    "sl_dist_pct" to "%.2f%%".format(slDistPct),
                    "stop_loss" to "%.4f".format(stopLossPrice),
                    "target" to "%.4f".format(takeProfitPrice),
                    "tp_dist_pct" to "%.2f%%".format(tpDistPct),
                    "rr_ratio" to "1:%.2f".format(actualRR),
                    "confidence" to 80.0
                ),
                narrative = "Bearish EMA Crossover detected: Fast(%.4f) crossed below Slow(%.4f) on confirmed bar (Prev: %.4f >= %.4f) -> Short signal -> Entry=%.4f, SL=%.4f (dist: %.4f), TP=%.4f (dist: %.4f), ATR=%.4f"
                    .format(currFast, currSlow, prevFast, prevSlow, currentPrice, stopLossPrice, riskDistance, takeProfitPrice, currentPrice - takeProfitPrice, atr)
            )

            return Signal(
                action = SignalAction.ENTER_SHORT,
                tradeId = tradeId,
                entryPrice = currentPrice,
                fastEma = currFast,
                slowEma = currSlow,
                prevFastEma = prevFast,
                prevSlowEma = prevSlow,
                atr = atr,
                atrMultiplier = atrMultiplier,
                riskDistance = riskDistance,
                riskRewardRatio = actualRR,
                stopLossPrice = stopLossPrice,
                takeProfitPrice = takeProfitPrice,
                confidenceScore = 80.0,
                reason = "Bearish EMA Crossover: Fast ($fastPeriod) crossed below Slow ($slowPeriod) on confirmed bar",
                diagnostics = diag,
                strategyId = id,
                strategyName = name
            )
        }

        // 4. In-Trend / Flat Regime (Hold)
        val trendStatus = if (currFast > currSlow) "Bullish Trend" else "Bearish Trend"
        return Signal(
            action = SignalAction.HOLD,
            reason = "%s in progress: Fast=%.4f, Slow=%.4f (Spread: %+.2f%%). Awaiting fresh crossover.".format(trendStatus, currFast, currSlow, emaSpreadPct),
            confidenceScore = 30.0,
            diagnostics = diag,
            strategyId = id,
            strategyName = name
        )
    }
}
