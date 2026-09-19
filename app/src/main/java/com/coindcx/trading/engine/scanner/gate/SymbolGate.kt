package com.coindcx.trading.engine.scanner.gate

import com.coindcx.trading.engine.telemetry.RejectionCode

data class GateResult(
    val isAllowed: Boolean,
    val rejection: RejectionCode? = null,
    val reason: String = ""
) {
    companion object {
        val PASS = GateResult(true)
        fun reject(code: RejectionCode, reason: String) = GateResult(false, code, reason)
    }
}

/**
 * Symbol-level Tradeability Gate (§0.4 & §1.4).
 * Evaluated once per symbol per scan cycle before any candle data or strategy evaluation.
 */
object SymbolGate {

    const val MIN_24H_QUOTE_VOLUME_USDT = 5_000_000.0 // G2: $5,000,000
    const val MAX_SPREAD_PCT = 0.06 // G5: 0.06% = (bestAsk - bestBid) / mid * 100

    fun evaluate(
        pair: String,
        isInUniverse: Boolean,
        quoteVolume24h: Double,
        bid: Double,
        ask: Double,
        lastPrice: Double
    ): GateResult {
        // G1: Universe membership
        if (!isInUniverse) {
            return GateResult.reject(RejectionCode.GATE_G1_UNIVERSE, "G1: Symbol $pair not in active liquid universe")
        }

        // G2: 24h quote volume >= $5,000,000
        if (quoteVolume24h < MIN_24H_QUOTE_VOLUME_USDT) {
            return GateResult.reject(
                RejectionCode.GATE_G2_QUOTE_VOLUME,
                "G2: 24h quote volume $${"%.0f".format(quoteVolume24h)} < $${"%.0f".format(MIN_24H_QUOTE_VOLUME_USDT)}"
            )
        }

        // G5: Spread <= 0.06%
        if (lastPrice > 0.0 && bid > 0.0 && ask >= bid) {
            val mid = (bid + ask) / 2.0
            val spreadPct = ((ask - bid) / mid) * 100.0
            if (spreadPct > MAX_SPREAD_PCT) {
                return GateResult.reject(
                    RejectionCode.GATE_G5_SPREAD,
                    "G5: Spread ${"%.4f".format(spreadPct)}% > ${MAX_SPREAD_PCT}%"
                )
            }
        } else if (lastPrice <= 0.0 || bid <= 0.0) {
            return GateResult.reject(RejectionCode.GATE_G5_SPREAD, "G5: Invalid BBO quotes (bid=$bid, ask=$ask)")
        }

        return GateResult.PASS
    }
}
