package com.coindcx.trading.engine.strategies

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.telemetry.RejectionCode
import com.coindcx.trading.engine.time.TradingClock
import org.junit.Assert.*
import org.junit.Test

class FpxStrategyTest {

    private val fpx = FpxStrategy()

    @Test
    fun `test FpxStrategy metadata and registration`() {
        assertEquals("fpx", fpx.id)
        assertEquals(Interval.H1, fpx.primaryInterval)
        assertTrue(fpx.requiredIntervals.contains(Interval.H1))
        assertTrue(fpx.requiredIntervals.contains(Interval.H4))
        assertEquals(MarketRegimePreference.MEAN_REVERTING_RANGE, fpx.preferredRegime)

        val regStrat = StrategyRegistry.getStrategy("fpx")
        assertNotNull("FpxStrategy must be registered in StrategyRegistry", regStrat)
        assertEquals(StrategyMode.LIVE, StrategyRegistry.getStrategyMode("fpx"))
    }

    @Test
    fun `test FpxStrategy Short Fade on upside blowoff pump`() {
        val count = 80
        val openTime = LongArray(count)
        val open = DoubleArray(count)
        val high = DoubleArray(count)
        val low = DoubleArray(count)
        val close = DoubleArray(count)
        val volume = DoubleArray(count)
        val baseTime = 1_700_000_000_000L
        val stepMs = Interval.H1.durationMs

        // Bars 79..9: baseline consolidating around 100.0
        for (step in 0 until (count - 9)) {
            val idx = count - 1 - step
            openTime[idx] = baseTime + (step * stepMs)
            open[idx] = 100.0
            high[idx] = 101.0
            low[idx] = 99.0
            close[idx] = 100.0
            volume[idx] = 1000.0
        }

        // Bars 8..1: parabolic vertical pump (7 up-closes in last 8 bars), driving RSI > 75 and distanceATR > 3.0
        var price = 100.0
        for (b in 8 downTo 1) {
            val step = count - 1 - b
            openTime[b] = baseTime + (step * stepMs)
            val pOpen = price
            price += 2.5
            open[b] = pOpen
            high[b] = price + 0.5
            low[b] = pOpen - 0.2
            close[b] = price
            volume[b] = 1200.0
        }
        // At bar 1: price is around 120.0 (very far from EMA20)

        // Bar 0: Reversal rejection bar (opened at 120.5, pushed to 121.0, but closed sharply red at 117.5 < low[1]=117.3, high volume)
        val b0 = 0
        openTime[b0] = baseTime + ((count - 1) * stepMs)
        open[b0] = 120.5
        high[b0] = 121.0
        low[b0] = 117.0
        close[b0] = 117.2 // takes out low[1] which was 117.3
        volume[b0] = 2500.0

        val series = CandleSeries.fromArrays("B-SOL_USDT", Interval.H1, openTime, open, high, low, close, volume)
        val ctx = SymbolContext(
            symbol = "B-SOL_USDT",
            primarySeries = series,
            clock = TradingClock.SYSTEM
        )

        val result = fpx.evaluate(ctx, null)
        assertNotNull("Signal should be generated on extreme parabolic fade", result.signal)
        val sig = result.signal!!
        assertEquals(SignalDirection.SHORT, sig.direction)
        assertTrue(sig.stopLoss > sig.entryRef)
        assertTrue(sig.takeProfitPrice != null && sig.takeProfitPrice!! < sig.entryRef)
        assertTrue(sig.strengths.values.all { it in 0.0..1.0 })
    }

    @Test
    fun `test FpxStrategy Long Fade on downside capitulation dump`() {
        val count = 80
        val openTime = LongArray(count)
        val open = DoubleArray(count)
        val high = DoubleArray(count)
        val low = DoubleArray(count)
        val close = DoubleArray(count)
        val volume = DoubleArray(count)
        val baseTime = 1_700_000_000_000L
        val stepMs = Interval.H1.durationMs

        // Bars 79..9: baseline consolidating around 100.0
        for (step in 0 until (count - 9)) {
            val idx = count - 1 - step
            openTime[idx] = baseTime + (step * stepMs)
            open[idx] = 100.0
            high[idx] = 101.0
            low[idx] = 99.0
            close[idx] = 100.0
            volume[idx] = 1000.0
        }

        // Bars 8..1: vertical capitulation dump (7 down-closes), pushing RSI < 25 and distanceATR < -3.0
        var price = 100.0
        for (b in 8 downTo 1) {
            val step = count - 1 - b
            openTime[b] = baseTime + (step * stepMs)
            val pOpen = price
            price -= 2.5
            open[b] = pOpen
            high[b] = pOpen + 0.2
            low[b] = price - 0.5
            close[b] = price
            volume[b] = 1200.0
        }

        // Bar 0: Bullish hammer reversal bar closing green above high[1] on elevated volume
        val b0 = 0
        openTime[b0] = baseTime + ((count - 1) * stepMs)
        open[b0] = 79.5
        high[b0] = 83.5
        low[b0] = 79.0
        close[b0] = 83.0 // takes out high[1] which was 82.7
        volume[b0] = 2500.0

        val series = CandleSeries.fromArrays("B-ETH_USDT", Interval.H1, openTime, open, high, low, close, volume)
        val ctx = SymbolContext(
            symbol = "B-ETH_USDT",
            primarySeries = series,
            clock = TradingClock.SYSTEM
        )

        val result = fpx.evaluate(ctx, null)
        assertNotNull("Signal should be generated on capitulation dump fade", result.signal)
        val sig = result.signal!!
        assertEquals(SignalDirection.LONG, sig.direction)
        assertTrue(sig.stopLoss < sig.entryRef)
        assertTrue(sig.takeProfitPrice != null && sig.takeProfitPrice!! > sig.entryRef)
        assertTrue(sig.strengths.values.all { it in 0.0..1.0 })
    }

    @Test
    fun `test FpxStrategy HTF 4H trend guard rejection`() {
        val count = 80
        val openTime = LongArray(count)
        val open = DoubleArray(count)
        val high = DoubleArray(count)
        val low = DoubleArray(count)
        val close = DoubleArray(count)
        val volume = DoubleArray(count)
        val baseTime = 1_700_000_000_000L
        val stepMs = Interval.H1.durationMs

        for (i in 0 until count) {
            openTime[i] = baseTime + (i * stepMs)
            open[i] = 100.0
            high[i] = 101.0
            low[i] = 99.0
            close[i] = 100.0
            volume[i] = 1000.0
        }

        val primarySeries = CandleSeries.fromArrays("B-BTC_USDT", Interval.H1, openTime, open, high, low, close, volume)

        // Build 4H series with massive parabolic trend (ADX > 40)
        val htfCount = 50
        val htfOpenTime = LongArray(htfCount)
        val htfOpen = DoubleArray(htfCount)
        val htfHigh = DoubleArray(htfCount)
        val htfLow = DoubleArray(htfCount)
        val htfClose = DoubleArray(htfCount)
        val htfVolume = DoubleArray(htfCount)
        val htfStepMs = Interval.H4.durationMs

        var htfP = 50.0
        for (step in 0 until htfCount) {
            val idx = htfCount - 1 - step
            htfOpenTime[idx] = baseTime + (step * htfStepMs)
            htfOpen[idx] = htfP
            htfP += 3.0
            htfHigh[idx] = htfP + 1.0
            htfLow[idx] = htfP - 2.0
            htfClose[idx] = htfP
            htfVolume[idx] = 5000.0
        }
        val htfSeries = CandleSeries.fromArrays("B-BTC_USDT", Interval.H4, htfOpenTime, htfOpen, htfHigh, htfLow, htfClose, htfVolume)

        val ctx = SymbolContext(
            symbol = "B-BTC_USDT",
            primarySeries = primarySeries,
            htfSeries = mapOf(Interval.H4 to htfSeries),
            clock = TradingClock.SYSTEM
        )

        val result = fpx.evaluate(ctx, null)
        assertNull(result.signal)
        assertTrue(result.rejections.contains(RejectionCode.S6_REGIME_ADX_CAP))
    }
}
