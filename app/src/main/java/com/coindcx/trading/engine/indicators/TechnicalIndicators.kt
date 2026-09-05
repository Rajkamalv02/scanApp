package com.coindcx.trading.engine.indicators

import com.coindcx.trading.data.api.models.MarketCandle
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure Kotlin mathematical implementations of Technical Indicators.
 * Zero external libraries, highly optimized for low memory footprint on mobile devices.
 */
object TechnicalIndicators {

    /**
     * Exponential Moving Average (EMA)
     * Returns list of EMA values aligned with input prices.
     */
    fun calculateEma(prices: List<Double>, period: Int): List<Double> {
        if (prices.size < period) return emptyList()

        val multiplier = 2.0 / (period + 1)
        val emaList = ArrayList<Double>(prices.size)

        // First EMA value is SMA of initial period
        var sum = 0.0
        for (i in 0 until period) {
            sum += prices[i]
        }
        var currentEma = sum / period
        emaList.add(currentEma)

        for (i in period until prices.size) {
            currentEma = (prices[i] - currentEma) * multiplier + currentEma
            emaList.add(currentEma)
        }

        return emaList
    }

    /**
     * Relative Strength Index (RSI) using Wilder's smoothing.
     * Returns the latest RSI value (0 to 100).
     */
    fun calculateRsi(prices: List<Double>, period: Int = 14): Double {
        if (prices.size <= period) return 50.0

        var gains = 0.0
        var losses = 0.0

        for (i in 1..period) {
            val change = prices[i] - prices[i - 1]
            if (change >= 0) gains += change else losses += abs(change)
        }

        var avgGain = gains / period
        var avgLoss = losses / period

        for (i in (period + 1) until prices.size) {
            val change = prices[i] - prices[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /**
     * Average True Range (ATR)
     * Used for dynamic volatility-based Stop Loss.
     */
    fun calculateAtr(candles: List<MarketCandle>, period: Int = 14): Double {
        if (candles.size <= period) return 0.0

        val trueRanges = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            val current = candles[i]
            val prevClose = candles[i - 1].close
            val tr = max(
                current.high - current.low,
                max(abs(current.high - prevClose), abs(current.low - prevClose))
            )
            trueRanges.add(tr)
        }

        if (trueRanges.size < period) return 0.0
        var atr = trueRanges.take(period).sum() / period

        for (i in period until trueRanges.size) {
            atr = (atr * (period - 1) + trueRanges[i]) / period
        }

        return atr
    }
}
