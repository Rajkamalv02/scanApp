package com.coindcx.trading.engine.indicators

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.data.CandleSeries
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BollingerBands(
    val basis: Double,
    val upper: Double,
    val lower: Double,
    val bbWidth: Double
)

data class DonchianChannel(
    val upper: Double,
    val lower: Double,
    val basis: Double
) {
    val height: Double get() = upper - lower
}

data class KeltnerChannel(
    val basis: Double,
    val upper: Double,
    val lower: Double
)

data class SwingPoint(
    val barIndex: Int,
    val price: Double,
    val barOpenTime: Long,
    val isHigh: Boolean
)

data class SwingPointsResult(
    val swingHighs: List<SwingPoint>,
    val swingLows: List<SwingPoint>
)

data class MacdPoint(
    val macd: Double,
    val signal: Double,
    val histogram: Double
)

/**
 * Pure Kotlin mathematical implementations of Technical Indicators.
 * Zero external libraries, highly optimized for low memory footprint on mobile devices.
 * Conforms strictly to §0.3 of the strategy specification.
 */
object TechnicalIndicators {

    // =========================================================================
    // 1. Exponential Moving Average (EMA)
    // =========================================================================

    /**
     * Standard EMA, seeded with SMA of initial [period] values.
     * Operates on chronological ascending array.
     */
    fun calculateEma(prices: List<Double>, period: Int): List<Double> {
        if (prices.size < period) return emptyList()

        val multiplier = 2.0 / (period + 1)
        val emaList = ArrayList<Double>(prices.size)

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

    fun calculateEmaArray(pricesAscending: DoubleArray, period: Int): DoubleArray {
        val n = pricesAscending.size
        if (n < period) return DoubleArray(0)

        val multiplier = 2.0 / (period + 1)
        val result = DoubleArray(n - period + 1)

        var sum = 0.0
        for (i in 0 until period) {
            sum += pricesAscending[i]
        }
        var currentEma = sum / period
        result[0] = currentEma

        for (i in period until n) {
            currentEma = (pricesAscending[i] - currentEma) * multiplier + currentEma
            result[i - period + 1] = currentEma
        }

        return result
    }

    /**
     * Evaluates EMA at bar[barIndex] on CandleSeries.
     * Uses ascending closes ending at barIndex.
     */
    fun calculateEmaAt(series: CandleSeries, period: Int, barIndex: Int = 0): Double {
        if (series.size <= barIndex + period) return 0.0
        // Extract ascending closes from oldest up to barIndex
        val subSeries = series.truncatedTo(barIndex)
        val closesAsc = subSeries.closesAscending()
        val emaList = calculateEmaArray(closesAsc, period)
        return if (emaList.isNotEmpty()) emaList.last() else 0.0
    }

    // =========================================================================
    // 2. Relative Strength Index (RSI) - Wilder's Smoothing
    // =========================================================================

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

    fun calculateRsi(series: CandleSeries, period: Int = 14, barIndex: Int = 0): Double {
        if (series.size <= barIndex + period + 1) return 50.0
        val sub = series.truncatedTo(barIndex)
        return calculateRsi(sub.closesAscending().toList(), period)
    }

    fun calculateRsiSeries(series: CandleSeries, period: Int = 14): DoubleArray {
        val n = series.size
        if (n <= period + 1) return DoubleArray(0)
        val closesAsc = series.closesAscending()
        val rsiAsc = DoubleArray(closesAsc.size - period)

        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val change = closesAsc[i] - closesAsc[i - 1]
            if (change >= 0) gains += change else losses += abs(change)
        }
        var avgGain = gains / period
        var avgLoss = losses / period
        rsiAsc[0] = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))

        for (i in (period + 1) until closesAsc.size) {
            val change = closesAsc[i] - closesAsc[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period

            val rsi = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
            rsiAsc[i - period] = rsi
        }

        // Convert back to CandleSeries indexing (index 0 = newest bar)
        val out = DoubleArray(rsiAsc.size)
        for (i in rsiAsc.indices) {
            out[i] = rsiAsc[rsiAsc.size - 1 - i]
        }
        return out
    }

    // =========================================================================
    // 3. Average True Range (ATR) - Wilder's RMA of True Range
    // =========================================================================

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

    fun calculateAtr(series: CandleSeries, period: Int = 14, barIndex: Int = 0): Double {
        if (series.size <= barIndex + period) return 0.0
        val sub = series.truncatedTo(barIndex)
        return calculateAtr(sub.toAscendingMarketCandles(), period)
    }

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

    fun calculateAtrSeries(series: CandleSeries, period: Int = 14): DoubleArray {
        val asc = calculateAtrSeries(series.toAscendingMarketCandles(), period)
        val out = DoubleArray(asc.size)
        for (i in asc.indices) {
            out[i] = asc[asc.size - 1 - i]
        }
        return out
    }

    fun calculateAtrPercent(atr: Double, close: Double): Double {
        return if (close > 0.0) (atr / close) * 100.0 else 0.0
    }

    // =========================================================================
    // 4. Average Directional Index (ADX) - Wilder's Smoothing
    // =========================================================================

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

    fun calculateAdx(series: CandleSeries, period: Int = 14, barIndex: Int = 0): Double {
        if (series.size <= barIndex + period * 2) return 0.0
        val sub = series.truncatedTo(barIndex)
        return calculateAdx(sub.toAscendingMarketCandles(), period)
    }

    // =========================================================================
    // 5. Volume SMA
    // =========================================================================

    fun calculateVolumeSma(series: CandleSeries, period: Int = 20, barIndex: Int = 0): Double {
        if (series.size < barIndex + period) return 0.0
        var sum = 0.0
        for (i in barIndex until (barIndex + period)) {
            sum += series.volume(i)
        }
        return sum / period
    }

    // =========================================================================
    // 6. Bollinger Bands & BBWidth (Population Stdev)
    // =========================================================================

    fun calculateBollingerBands(
        series: CandleSeries,
        period: Int = 20,
        k: Double = 2.0,
        barIndex: Int = 0
    ): BollingerBands {
        if (series.size < barIndex + period) {
            return BollingerBands(0.0, 0.0, 0.0, 0.0)
        }

        var sum = 0.0
        for (i in barIndex until (barIndex + period)) {
            sum += series.close(i)
        }
        val basis = sum / period

        var varSum = 0.0
        for (i in barIndex until (barIndex + period)) {
            val d = series.close(i) - basis
            varSum += d * d
        }
        val stdev = sqrt(varSum / period) // Population stdev per §0.3

        val upper = basis + k * stdev
        val lower = basis - k * stdev
        val bbWidth = if (basis > 0.0) (upper - lower) / basis else 0.0

        return BollingerBands(basis = basis, upper = upper, lower = lower, bbWidth = bbWidth)
    }

    // =========================================================================
    // 7. Percentile Rank
    // =========================================================================

    /**
     * Percentile rank: count(values in window <= value) / window_size * 100.
     * Window includes current bar per §0.3.
     */
    fun percentileRank(window: DoubleArray, value: Double): Double {
        if (window.isEmpty()) return 50.0
        var count = 0
        for (v in window) {
            if (v <= value) count++
        }
        return (count.toDouble() / window.size) * 100.0
    }

    // =========================================================================
    // 8. Kaufman Efficiency Ratio (ER)
    // =========================================================================

    /**
     * Kaufman ER(n) = abs(close[0] - close[n]) / sum(abs(close[i] - close[i+1]) for i in 0 until n)
     * Range: 0.0 to 1.0.
     */
    fun calculateEfficiencyRatio(series: CandleSeries, period: Int = 20, barIndex: Int = 0): Double {
        if (series.size <= barIndex + period) return 0.0

        val netChange = abs(series.close(barIndex) - series.close(barIndex + period))
        var totalPath = 0.0
        for (i in barIndex until (barIndex + period)) {
            totalPath += abs(series.close(i) - series.close(i + 1))
        }

        return if (totalPath > 0.0) (netChange / totalPath).coerceIn(0.0, 1.0) else 0.0
    }

    fun calculateEfficiencyRatioSeries(series: CandleSeries, period: Int = 20): DoubleArray {
        val n = series.size
        if (n <= period) return DoubleArray(0)
        val out = DoubleArray(n - period)
        for (i in out.indices) {
            out[i] = calculateEfficiencyRatio(series, period, barIndex = i)
        }
        return out
    }

    // =========================================================================
    // 9. Choppiness Index (CHOP)
    // =========================================================================

    /**
     * CHOP(n) = 100 * log10( sum(TR, n) / (highest(high, n) - lowest(low, n)) ) / log10(n)
     */
    fun calculateChoppiness(series: CandleSeries, period: Int = 14, barIndex: Int = 0): Double {
        if (series.size < barIndex + period) return 50.0

        var sumTr = 0.0
        var highestHigh = Double.MIN_VALUE
        var lowestLow = Double.MAX_VALUE

        for (i in barIndex until (barIndex + period)) {
            sumTr += series.tr(i)
            if (series.high(i) > highestHigh) highestHigh = series.high(i)
            if (series.low(i) < lowestLow) lowestLow = series.low(i)
        }

        val range = highestHigh - lowestLow
        if (range <= 0.0 || sumTr <= 0.0) return 100.0

        val logN = log10(period.toDouble())
        val chop = 100.0 * (log10(sumTr / range) / logN)
        return chop.coerceIn(0.0, 100.0)
    }

    fun calculateChoppinessSeries(series: CandleSeries, period: Int = 14): DoubleArray {
        val n = series.size
        if (n <= period) return DoubleArray(0)
        val out = DoubleArray(n - period)
        for (i in out.indices) {
            out[i] = calculateChoppiness(series, period, barIndex = i)
        }
        return out
    }

    // =========================================================================
    // 10. Donchian Channel
    // =========================================================================

    /**
     * Donchian Channel ending explicitly at [endIndex].
     * Looks back [period] bars starting from [endIndex]: endIndex until (endIndex + period).
     */
    fun donchian(series: CandleSeries, period: Int, endIndex: Int): DonchianChannel {
        if (series.size < endIndex + period) {
            return DonchianChannel(0.0, 0.0, 0.0)
        }

        var maxH = Double.MIN_VALUE
        var minL = Double.MAX_VALUE

        for (i in endIndex until (endIndex + period)) {
            val h = series.high(i)
            val l = series.low(i)
            if (h > maxH) maxH = h
            if (l < minL) minL = l
        }

        val basis = (maxH + minL) / 2.0
        return DonchianChannel(upper = maxH, lower = minL, basis = basis)
    }

    // =========================================================================
    // 11. Keltner Channel
    // =========================================================================

    fun calculateKeltnerChannel(
        series: CandleSeries,
        emaPeriod: Int = 20,
        atrMultiplier: Double = 1.5,
        atrPeriod: Int = 20,
        barIndex: Int = 0
    ): KeltnerChannel {
        val basis = calculateEmaAt(series, emaPeriod, barIndex)
        val atr = calculateAtr(series, atrPeriod, barIndex)
        val upper = basis + atrMultiplier * atr
        val lower = basis - atrMultiplier * atr
        return KeltnerChannel(basis = basis, upper = upper, lower = lower)
    }

    // =========================================================================
    // 12. 5-Bar Swing Point Fractals
    // =========================================================================

    /**
     * Confirms swing high at index i if:
     * high[i] > high[i+1] && high[i] > high[i+2] && high[i] > high[i-1] && high[i] > high[i-2]
     * (Confirmed 2 bars late: i >= 2).
     */
    fun calculateSwingPoints(
        series: CandleSeries,
        @Suppress("UNUSED_PARAMETER") window: Int = 5,
        maxCount: Int = 10
    ): SwingPointsResult {
        val highs = mutableListOf<SwingPoint>()
        val lows = mutableListOf<SwingPoint>()

        if (series.size < 5) return SwingPointsResult(highs, lows)

        // Confirmed swings start from bar[2] backwards
        for (i in 2 until (series.size - 2)) {
            val h = series.high(i)
            val l = series.low(i)

            val isSwingHigh = h > series.high(i + 1) &&
                    h > series.high(i + 2) &&
                    h > series.high(i - 1) &&
                    h > series.high(i - 2)

            val isSwingLow = l < series.low(i + 1) &&
                    l < series.low(i + 2) &&
                    l < series.low(i - 1) &&
                    l < series.low(i - 2)

            if (isSwingHigh && highs.size < maxCount) {
                highs.add(SwingPoint(i, h, series.openTime(i), isHigh = true))
            }
            if (isSwingLow && lows.size < maxCount) {
                lows.add(SwingPoint(i, l, series.openTime(i), isHigh = false))
            }

            if (highs.size >= maxCount && lows.size >= maxCount) break
        }

        return SwingPointsResult(highs, lows)
    }

    // =========================================================================
    // 13. Anchored VWAP
    // =========================================================================

    fun anchoredVwap(series: CandleSeries, anchorOpenTimeUtc: Long): Double {
        var cumVol = 0.0
        var cumVolPrice = 0.0

        for (i in 0 until series.size) {
            val t = series.openTime(i)
            if (t < anchorOpenTimeUtc) break

            val tp = (series.high(i) + series.low(i) + series.close(i)) / 3.0
            val vol = series.volume(i)
            cumVolPrice += tp * vol
            cumVol += vol
        }

        return if (cumVol > 0.0) cumVolPrice / cumVol else 0.0
    }

    // =========================================================================
    // 14. Z-Score (Population Stdev)
    // =========================================================================

    fun calculateZScore(series: CandleSeries, period: Int = 50, barIndex: Int = 0): Double {
        if (series.size < barIndex + period) return 0.0

        var sum = 0.0
        for (i in barIndex until (barIndex + period)) {
            sum += series.close(i)
        }
        val mean = sum / period

        var varSum = 0.0
        for (i in barIndex until (barIndex + period)) {
            val d = series.close(i) - mean
            varSum += d * d
        }
        val stdev = sqrt(varSum / period)

        return if (stdev > 0.0) (series.close(barIndex) - mean) / stdev else 0.0
    }

    // =========================================================================
    // 15. Timestamp-Aligned Beta vs BTC
    // =========================================================================

    /**
     * Mandatory timestamp alignment step before computing returns and beta.
     */
    fun alignReturns(seriesA: CandleSeries, seriesB: CandleSeries, period: Int = 30): Pair<DoubleArray, DoubleArray> {
        val mapB = HashMap<Long, Double>()
        for (i in 0 until seriesB.size) {
            mapB[seriesB.openTime(i)] = seriesB.close(i)
        }

        val alignedClosesA = mutableListOf<Double>()
        val alignedClosesB = mutableListOf<Double>()

        for (i in 0 until seriesA.size) {
            val t = seriesA.openTime(i)
            val closeB = mapB[t]
            if (closeB != null) {
                alignedClosesA.add(seriesA.close(i))
                alignedClosesB.add(closeB)
                if (alignedClosesA.size >= period + 1) break
            }
        }

        if (alignedClosesA.size < period + 1) {
            return Pair(DoubleArray(0), DoubleArray(0))
        }

        val returnsA = DoubleArray(period)
        val returnsB = DoubleArray(period)

        for (i in 0 until period) {
            val pA0 = alignedClosesA[i]
            val pA1 = alignedClosesA[i + 1]
            returnsA[i] = if (pA1 > 0) (pA0 - pA1) / pA1 else 0.0

            val pB0 = alignedClosesB[i]
            val pB1 = alignedClosesB[i + 1]
            returnsB[i] = if (pB1 > 0) (pB0 - pB1) / pB1 else 0.0
        }

        return Pair(returnsA, returnsB)
    }

    fun calculateBeta(alignedReturnsA: DoubleArray, alignedReturnsB: DoubleArray): Double {
        val n = min(alignedReturnsA.size, alignedReturnsB.size)
        if (n < 10) return 1.0

        val meanA = alignedReturnsA.take(n).average()
        val meanB = alignedReturnsB.take(n).average()

        var cov = 0.0
        var varB = 0.0

        for (i in 0 until n) {
            val da = alignedReturnsA[i] - meanA
            val db = alignedReturnsB[i] - meanB
            cov += da * db
            varB += db * db
        }

        return if (varB > 0.0) cov / varB else 1.0
    }
}
