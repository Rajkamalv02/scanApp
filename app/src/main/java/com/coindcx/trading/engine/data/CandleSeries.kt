package com.coindcx.trading.engine.data

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.time.TradingClock
import kotlin.math.abs
import kotlin.math.max

enum class Interval(val label: String, val durationMs: Long) {
    M1("1m", 60_000L),
    M15("15m", 15 * 60_000L),
    H1("1h", 60 * 60_000L),
    H4("4h", 4 * 60 * 60_000L),
    D1("1d", 24 * 60 * 60_000L);

    companion object {
        fun fromLabel(label: String): Interval = when (label.lowercase()) {
            "1m" -> M1
            "15m" -> M15
            "1h" -> H1
            "4h" -> H4
            "1d" -> D1
            else -> throw IllegalArgumentException("Unsupported interval: $label")
        }
    }
}

/**
 * Primitive-backed, columnar time-series storage for complete, closed market candles.
 *
 * Index Convention:
 * - index 0 = bar[0] = most recently CLOSED candle.
 * - index 1 = bar[1] = one before it.
 * - index k = bar[k] = k bars before bar[0].
 *
 * Structural Invariant:
 * Incomplete / currently forming candles are excluded at construction.
 */
class CandleSeries private constructor(
    val symbol: String,
    val interval: Interval,
    private val openTime: LongArray,
    private val open: DoubleArray,
    private val high: DoubleArray,
    private val low: DoubleArray,
    private val close: DoubleArray,
    private val volume: DoubleArray
) {
    val size: Int = openTime.size

    fun isEmpty(): Boolean = size == 0
    fun isNotEmpty(): Boolean = size > 0

    fun openTime(i: Int): Long {
        checkIndex(i)
        return openTime[i]
    }

    fun open(i: Int): Double {
        checkIndex(i)
        return open[i]
    }

    fun high(i: Int): Double {
        checkIndex(i)
        return high[i]
    }

    fun low(i: Int): Double {
        checkIndex(i)
        return low[i]
    }

    fun close(i: Int): Double {
        checkIndex(i)
        return close[i]
    }

    fun volume(i: Int): Double {
        checkIndex(i)
        return volume[i]
    }

    /**
     * True Range at bar[i] relative to close[i+1].
     * For the oldest bar (i == size - 1), TR is high - low.
     */
    fun tr(i: Int): Double {
        checkIndex(i)
        val h = high[i]
        val l = low[i]
        if (i == size - 1) return h - l
        val prevClose = close[i + 1]
        return max(h - l, max(abs(h - prevClose), abs(l - prevClose)))
    }

    /**
     * Extracts close prices in chronological order (oldest to newest)
     * suitable for indicators requiring ascending time series.
     */
    fun closesAscending(): DoubleArray {
        val res = DoubleArray(size)
        for (i in 0 until size) {
            res[i] = close[size - 1 - i]
        }
        return res
    }

    /**
     * Returns a new CandleSeries truncated such that bar[i] becomes the new bar[0].
     * Essential for the look-ahead property test to evaluate historical states without future leakage.
     */
    fun truncatedTo(i: Int): CandleSeries {
        checkIndex(i)
        val newOpenTime = openTime.copyOfRange(i, size)
        val newOpen = open.copyOfRange(i, size)
        val newHigh = high.copyOfRange(i, size)
        val newLow = low.copyOfRange(i, size)
        val newClose = close.copyOfRange(i, size)
        val newVolume = volume.copyOfRange(i, size)

        return CandleSeries(
            symbol = symbol,
            interval = interval,
            openTime = newOpenTime,
            open = newOpen,
            high = newHigh,
            low = newLow,
            close = newClose,
            volume = newVolume
        )
    }

    /**
     * Returns a sub-series starting at [offsetNewest] of length [count].
     * Used for walk-forward Train/Validation/Test segmentation.
     */
    fun subSeries(offsetNewest: Int, count: Int): CandleSeries {
        checkIndex(offsetNewest)
        require(count > 0 && offsetNewest + count <= size) {
            "Invalid count $count for offset $offsetNewest in size $size"
        }
        val end = offsetNewest + count
        return CandleSeries(
            symbol = symbol,
            interval = interval,
            openTime = openTime.copyOfRange(offsetNewest, end),
            open = open.copyOfRange(offsetNewest, end),
            high = high.copyOfRange(offsetNewest, end),
            low = low.copyOfRange(offsetNewest, end),
            close = close.copyOfRange(offsetNewest, end),
            volume = volume.copyOfRange(offsetNewest, end)
        )
    }

    /**
     * Helper to map to MarketCandle list (for backward-compatible component evaluation).
     * Output is in ascending chronological order.
     */
    fun toAscendingMarketCandles(): List<MarketCandle> {
        val list = ArrayList<MarketCandle>(size)
        for (i in (size - 1) downTo 0) {
            list.add(
                MarketCandle(
                    open = open[i],
                    high = high[i],
                    low = low[i],
                    close = close[i],
                    volume = volume[i],
                    time = openTime[i]
                )
            )
        }
        return list
    }

    private fun checkIndex(i: Int) {
        if (i < 0 || i >= size) {
            throw IndexOutOfBoundsException("Bar index $i out of bounds for series of size $size (valid: 0..${size - 1})")
        }
    }

    companion object {

        /**
         * Constructs a clean CandleSeries from raw API candles.
         * Structurally drops any candle that is not yet completed according to clock.
         */
        fun fromApi(
            raw: List<MarketCandle>,
            interval: Interval,
            clock: TradingClock,
            symbol: String = ""
        ): CandleSeries {
            if (raw.isEmpty()) {
                return empty(symbol, interval)
            }

            // 1. Sort chronological ascending
            val sorted = raw.sortedBy { it.time }
            val nowMs = clock.nowUtcMillis()

            // 2. Drop incomplete / forming candles: openTime + intervalDuration > nowMs
            val completed = sorted.filter { candle ->
                candle.time + interval.durationMs <= nowMs
            }

            if (completed.isEmpty()) {
                return empty(symbol, interval)
            }

            val n = completed.size
            val openTime = LongArray(n)
            val open = DoubleArray(n)
            val high = DoubleArray(n)
            val low = DoubleArray(n)
            val close = DoubleArray(n)
            val volume = DoubleArray(n)

            // Index 0 = newest completed candle (last element of ascending list)
            for (i in 0 until n) {
                val src = completed[n - 1 - i]
                openTime[i] = src.time
                open[i] = src.open
                high[i] = src.high
                low[i] = src.low
                close[i] = src.close
                volume[i] = src.volume
            }

            return CandleSeries(
                symbol = symbol,
                interval = interval,
                openTime = openTime,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )
        }

        /**
         * Directly constructs CandleSeries from completed arrays (used in backtests and tests).
         * Arrays are expected in bar[0], bar[1], ... descending order.
         */
        fun fromArrays(
            symbol: String,
            interval: Interval,
            openTime: LongArray,
            open: DoubleArray,
            high: DoubleArray,
            low: DoubleArray,
            close: DoubleArray,
            volume: DoubleArray
        ): CandleSeries {
            require(openTime.size == open.size && open.size == high.size && high.size == low.size && low.size == close.size && close.size == volume.size) {
                "Array sizes must match exactly"
            }
            return CandleSeries(
                symbol = symbol,
                interval = interval,
                openTime = openTime.clone(),
                open = open.clone(),
                high = high.clone(),
                low = low.clone(),
                close = close.clone(),
                volume = volume.clone()
            )
        }

        fun empty(symbol: String, interval: Interval): CandleSeries = CandleSeries(
            symbol = symbol,
            interval = interval,
            openTime = LongArray(0),
            open = DoubleArray(0),
            high = DoubleArray(0),
            low = DoubleArray(0),
            close = DoubleArray(0),
            volume = DoubleArray(0)
        )

        /**
         * Synthesizes higher timeframe candles from a lower timeframe series (e.g. 15m -> 1H, 15m -> 4H, 1H -> 4H).
         * Aggregates completed lower timeframe candles aligned to UTC boundaries of targetInterval.
         */
        fun synthesize(sourceSeries: CandleSeries, targetInterval: Interval, clock: TradingClock): CandleSeries {
            if (targetInterval.durationMs <= sourceSeries.interval.durationMs) {
                return empty(sourceSeries.symbol, targetInterval)
            }
            val expectedCount = (targetInterval.durationMs / sourceSeries.interval.durationMs).toInt()
            if (sourceSeries.size < expectedCount) return empty(sourceSeries.symbol, targetInterval)

            val ascendingCandles = sourceSeries.toAscendingMarketCandles()
            val targetBuckets = mutableListOf<MarketCandle>()
            val targetMs = targetInterval.durationMs

            var currentBucket: MutableList<MarketCandle>? = null
            var currentBucketStart = -1L

            for (c in ascendingCandles) {
                val bucketStart = c.time - (c.time % targetMs)
                if (bucketStart != currentBucketStart) {
                    if (currentBucket != null && currentBucket.size == expectedCount) {
                        targetBuckets.add(aggregateBucket(currentBucketStart, currentBucket))
                    }
                    currentBucket = mutableListOf(c)
                    currentBucketStart = bucketStart
                } else {
                    currentBucket?.add(c)
                }
            }

            if (currentBucket != null && currentBucket.size == expectedCount) {
                targetBuckets.add(aggregateBucket(currentBucketStart, currentBucket))
            }

            return fromApi(targetBuckets, targetInterval, clock, sourceSeries.symbol)
        }

        /**
         * Synthesizes 4H candles from 1H series.
         */
        fun synthesize4H(series1H: CandleSeries, clock: TradingClock): CandleSeries {
            return synthesize(series1H, Interval.H4, clock)
        }

        private fun aggregateBucket(bucketStart: Long, candles: List<MarketCandle>): MarketCandle {
            val o = candles.first().open
            var h = Double.MIN_VALUE
            var l = Double.MAX_VALUE
            var vol = 0.0
            for (c in candles) {
                if (c.high > h) h = c.high
                if (c.low < l) l = c.low
                vol += c.volume
            }
            val cl = candles.last().close
            return MarketCandle(
                open = o,
                high = h,
                low = l,
                close = cl,
                volume = vol,
                time = bucketStart
            )
        }
    }
}
