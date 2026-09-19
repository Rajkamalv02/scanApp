package com.coindcx.trading.engine.scanner.gate

import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.telemetry.RejectionCode

/**
 * Timeframe-level Tradeability Gate (§0.4 & §1.4).
 * Evaluated per (symbol, interval) and cached per scan cycle.
 */
object TimeframeGate {

    const val MIN_HISTORY_BARS = 300 // G6

    fun getMinAtrPct(interval: Interval): Double = when (interval) {
        Interval.M1 -> 0.25
        Interval.M15 -> 0.45
        Interval.H1 -> 0.80
        Interval.H4 -> 1.60
        Interval.D1 -> 2.50
    }

    const val DEFAULT_MAX_ATR_PCT = 6.0 // G4

    fun evaluate(
        series: CandleSeries,
        atr14: Double,
        customMinAtrPct: Double? = null,
        customMaxAtrPct: Double? = null
    ): GateResult {
        // G6: Candle history >= 300 bars
        if (series.size < MIN_HISTORY_BARS) {
            return GateResult.reject(
                RejectionCode.GATE_G6_INSUFFICIENT_HISTORY,
                "G6: History ${series.size} bars < $MIN_HISTORY_BARS required"
            )
        }

        if (series.isEmpty()) {
            return GateResult.reject(RejectionCode.GATE_G6_INSUFFICIENT_HISTORY, "G6: Empty candle series")
        }

        val lastClose = series.close(0)
        if (lastClose <= 0.0 || atr14 <= 0.0) {
            return GateResult.reject(RejectionCode.GATE_G3_ATR_FLOOR, "G3: Invalid price ($lastClose) or ATR ($atr14)")
        }

        val atrPct = (atr14 / lastClose) * 100.0
        val minAtr = customMinAtrPct ?: getMinAtrPct(series.interval)
        val maxAtr = customMaxAtrPct ?: DEFAULT_MAX_ATR_PCT

        // G3: Minimum ATR%
        if (atrPct < minAtr) {
            return GateResult.reject(
                RejectionCode.GATE_G3_ATR_FLOOR,
                "G3: ATR% ${"%.3f".format(atrPct)}% < min ${"%.3f".format(minAtr)}% for ${series.interval.label}"
            )
        }

        // G4: Maximum ATR%
        if (atrPct > maxAtr) {
            return GateResult.reject(
                RejectionCode.GATE_G4_ATR_CEILING,
                "G4: ATR% ${"%.3f".format(atrPct)}% > max ${"%.3f".format(maxAtr)}% (liquidation/blowoff chaos)"
            )
        }

        return GateResult.PASS
    }
}
