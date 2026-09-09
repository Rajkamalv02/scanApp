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

    /**
     * Rolling Average True Range (ATR) series aligned 1-to-1 with input candles.
     * Guarantees that historical zone evaluation uses contemporaneous volatility at bar t.
     */
    fun calculateAtrSeries(candles: List<MarketCandle>, period: Int = 14): List<Double> {
        if (candles.isEmpty()) return emptyList()
        val result = ArrayList<Double>(candles.size)

        var prevClose = candles[0].close
        val trList = ArrayList<Double>(candles.size)
        trList.add(candles[0].high - candles[0].low)

        for (i in 1 until candles.size) {
            val c = candles[i]
            val tr = max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            trList.add(tr)
            prevClose = c.close
        }

        var runningAtr = trList[0]
        result.add(runningAtr)

        for (i in 1 until candles.size) {
            if (i < period) {
                runningAtr = (runningAtr * i + trList[i]) / (i + 1)
            } else {
                runningAtr = (runningAtr * (period - 1) + trList[i]) / period
            }
            result.add(runningAtr)
        }

        return result
    }

    /**
     * Average Directional Index (ADX) using Wilder's exact smoothing.
     * Evaluates true directional movement (+DM, -DM mutual exclusivity) and trend strength.
     * Returns latest ADX value (0 to 100).
     */
    fun calculateAdx(candles: List<MarketCandle>, period: Int = 14): Double {
        if (candles.size <= period * 2) return 0.0

        val trList = ArrayList<Double>(candles.size - 1)
        val plusDmList = ArrayList<Double>(candles.size - 1)
        val minusDmList = ArrayList<Double>(candles.size - 1)

        for (i in 1 until candles.size) {
            val current = candles[i]
            val prev = candles[i - 1]

            val tr = max(
                current.high - current.low,
                max(abs(current.high - prev.close), abs(current.low - prev.close))
            )
            trList.add(tr)

            val upMove = current.high - prev.high
            val downMove = prev.low - current.low

            // Wilder's Mutual Exclusivity Rule:
            if (upMove > downMove && upMove > 0.0) {
                plusDmList.add(upMove)
            } else {
                plusDmList.add(0.0)
            }

            if (downMove > upMove && downMove > 0.0) {
                minusDmList.add(downMove)
            } else {
                minusDmList.add(0.0)
            }
        }

        if (trList.size < period * 2) return 0.0

        // Wilder's smoothing for TR, +DM, -DM
        var smoothedTr = trList.take(period).sum()
        var smoothedPlusDm = plusDmList.take(period).sum()
        var smoothedMinusDm = minusDmList.take(period).sum()

        val dxList = ArrayList<Double>()

        for (i in period until trList.size) {
            smoothedTr = smoothedTr - (smoothedTr / period) + trList[i]
            smoothedPlusDm = smoothedPlusDm - (smoothedPlusDm / period) + plusDmList[i]
            smoothedMinusDm = smoothedMinusDm - (smoothedMinusDm / period) + minusDmList[i]

            val plusDi = if (smoothedTr > 0) (smoothedPlusDm / smoothedTr) * 100.0 else 0.0
            val minusDi = if (smoothedTr > 0) (smoothedMinusDm / smoothedTr) * 100.0 else 0.0

            val diSum = plusDi + minusDi
            val dx = if (diSum > 0) (abs(plusDi - minusDi) / diSum) * 100.0 else 0.0
            dxList.add(dx)
        }

        if (dxList.size < period) return 0.0
        var adx = dxList.take(period).average()
        for (i in period until dxList.size) {
            adx = (adx * (period - 1) + dxList[i]) / period
        }

        return adx
    }

    /**
     * Moving Average Convergence Divergence (MACD)
     * Computes MACD line (Fast EMA - Slow EMA), Signal line (EMA of MACD), and Histogram.
     */
    fun calculateMacd(
        prices: List<Double>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): List<MacdPoint> {
        if (prices.size < slowPeriod + signalPeriod) return emptyList()

        val fastEma = calculateEma(prices, fastPeriod)
        val slowEma = calculateEma(prices, slowPeriod)

        if (fastEma.isEmpty() || slowEma.isEmpty()) return emptyList()

        val offset = fastEma.size - slowEma.size
        val macdLine = ArrayList<Double>(slowEma.size)
        for (i in slowEma.indices) {
            macdLine.add(fastEma[i + offset] - slowEma[i])
        }

        val signalEma = calculateEma(macdLine, signalPeriod)
        if (signalEma.isEmpty()) return emptyList()

        val signalOffset = macdLine.size - signalEma.size
        val result = ArrayList<MacdPoint>(signalEma.size)
        for (i in signalEma.indices) {
            val macd = macdLine[i + signalOffset]
            val signal = signalEma[i]
            val hist = macd - signal
            result.add(MacdPoint(macd = macd, signal = signal, histogram = hist))
        }

        return result
    }
}

data class MacdPoint(
    val macd: Double,
    val signal: Double,
    val histogram: Double
)
