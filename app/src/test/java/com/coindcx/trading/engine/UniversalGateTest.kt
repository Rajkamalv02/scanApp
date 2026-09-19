package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.scanner.SignalDedupRegistry
import com.coindcx.trading.engine.scanner.StrategyFamily
import com.coindcx.trading.engine.scanner.gate.SymbolGate
import com.coindcx.trading.engine.scanner.gate.TimeframeGate
import com.coindcx.trading.engine.telemetry.RejectionCode
import com.coindcx.trading.engine.time.FixedClock
import org.junit.Assert.*
import org.junit.Test

class UniversalGateTest {

    private val clock = FixedClock(10_000_000_000L)

    @Test
    fun `test SymbolGate G1, G2, G5 validation`() {
        // G1: Not in universe
        val resG1 = SymbolGate.evaluate(
            pair = "B-SHITCOIN_USDT",
            isInUniverse = false,
            quoteVolume24h = 10_000_000.0,
            bid = 100.0,
            ask = 100.04,
            lastPrice = 100.02
        )
        assertFalse(resG1.isAllowed)
        assertEquals(RejectionCode.GATE_G1_UNIVERSE, resG1.rejection)

        // G2: Quote volume < $5M
        val resG2 = SymbolGate.evaluate(
            pair = "B-LOWVOL_USDT",
            isInUniverse = true,
            quoteVolume24h = 4_500_000.0,
            bid = 100.0,
            ask = 100.04,
            lastPrice = 100.02
        )
        assertFalse(resG2.isAllowed)
        assertEquals(RejectionCode.GATE_G2_QUOTE_VOLUME, resG2.rejection)

        // G5: Spread > 0.06% (e.g. bid=100.0, ask=100.10 -> mid=100.05, spread = 0.10/100.05 = 0.10%)
        val resG5 = SymbolGate.evaluate(
            pair = "B-WIDESPREAD_USDT",
            isInUniverse = true,
            quoteVolume24h = 10_000_000.0,
            bid = 100.0,
            ask = 100.10,
            lastPrice = 100.05
        )
        assertFalse(resG5.isAllowed)
        assertEquals(RejectionCode.GATE_G5_SPREAD, resG5.rejection)

        // Clean Pass: quote volume = $8M, spread = 0.02%
        val resPass = SymbolGate.evaluate(
            pair = "B-BTC_USDT",
            isInUniverse = true,
            quoteVolume24h = 8_000_000.0,
            bid = 100.0,
            ask = 100.02,
            lastPrice = 100.01
        )
        assertTrue(resPass.isAllowed)
        assertNull(resPass.rejection)
    }

    @Test
    fun `test TimeframeGate G3, G4, G6 validation`() {
        val candles = (1..350).map { i ->
            MarketCandle(open = 100.0, high = 102.0, low = 98.0, close = 100.0, volume = 50.0, time = i * 60_000L)
        }
        val series = CandleSeries.fromApi(candles, Interval.M15, clock, "B-BTC_USDT")

        // G6: Insufficient history (< 300 bars)
        val shortSeries = series.truncatedTo(100) // 250 bars
        val resG6 = TimeframeGate.evaluate(shortSeries, atr14 = 0.5)
        assertFalse(resG6.isAllowed)
        assertEquals(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY, resG6.rejection)

        // G3: ATR% < 0.45% on 15m (e.g., price = 100.0, ATR = 0.30 -> ATR% = 0.30%)
        val resG3 = TimeframeGate.evaluate(series, atr14 = 0.30)
        assertFalse(resG3.isAllowed)
        assertEquals(RejectionCode.GATE_G3_ATR_FLOOR, resG3.rejection)

        // G4: ATR% > 6.0% (e.g., price = 100.0, ATR = 7.0 -> ATR% = 7.0%)
        val resG4 = TimeframeGate.evaluate(series, atr14 = 7.0)
        assertFalse(resG4.isAllowed)
        assertEquals(RejectionCode.GATE_G4_ATR_CEILING, resG4.rejection)

        // Clean Pass: price = 100.0, ATR = 1.0 -> ATR% = 1.0% (between 0.45 and 6.0)
        val resPass = TimeframeGate.evaluate(series, atr14 = 1.0)
        assertTrue(resPass.isAllowed)
        assertNull(resPass.rejection)
    }

    @Test
    fun `test SignalDedupRegistry cooldown and 3-bar signal window`() {
        val registry = SignalDedupRegistry(familyCooldownMs = 90 * 60_000L)
        val testClock = FixedClock(1_000_000L)

        val signalS2 = Signal(
            symbol = "B-SOL_USDT",
            strategyId = "vceb",
            direction = SignalDirection.LONG,
            barOpenTimeUtc = 1_000_000L,
            entryRef = 150.0,
            stopLoss = 148.0,
            target = Target.Fixed(154.0, null, 2.0),
            riskDistance = 2.0,
            riskPct = 1.33,
            regimeTag = RegimeTag.COMPRESSION,
            strengths = mapOf("breakoutStrength" to 0.8),
            expiryBars = 5,
            primaryInterval = Interval.M15
        )

        // 1. Initially allowed
        val initialCheck = registry.evaluate("B-SOL_USDT", "vceb", testClock)
        assertTrue(initialCheck.isAllowed)

        // 2. Record S2 (family = BREAKOUT)
        registry.recordSignal(signalS2, testClock)

        // 3. Immediately checking another BREAKOUT strategy (e.g. sorm) must be REJECTED by cooldown
        val cooldownCheck = registry.evaluate("B-SOL_USDT", "sorm", testClock)
        assertFalse(cooldownCheck.isAllowed)
        assertEquals(RejectionCode.GATE_G7_COOLDOWN, cooldownCheck.rejection)

        // 4. Different family (e.g. TREND s10 or MEANREV s7) must be ALLOWED
        val trendCheck = registry.evaluate("B-SOL_USDT", "edtm", testClock)
        assertTrue(trendCheck.isAllowed)

        // 5. Test hasSignalWithinBars for S2 ↔ S3 conflict
        // Current bar = 1_000_000L + 2 * 15m bars = 1_000_000 + 1_800_000 = 2_800_000L (2 bars later)
        val within2Bars = registry.hasSignalWithinBars(
            symbol = "B-SOL_USDT",
            targetStrategyId = "vceb",
            interval = Interval.M15,
            maxBars = 3,
            currentBarTime = 2_800_000L
        )
        assertTrue("Must detect S2 signal within 3 bars window", within2Bars)

        // 5 bars later: 1_000_000 + 5 * 15m = 5_500_000L
        val after5Bars = registry.hasSignalWithinBars(
            symbol = "B-SOL_USDT",
            targetStrategyId = "vceb",
            interval = Interval.M15,
            maxBars = 3,
            currentBarTime = 5_500_000L
        )
        assertFalse("Must NOT detect S2 signal outside 3 bars window", after5Bars)
    }
}
