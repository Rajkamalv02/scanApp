package com.coindcx.trading.engine.scanner

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of CoinDCX/Binance USD-M Futures Contract Specifications.
 *
 * Resolves the critical discrepancy where CoinDCX's public Spot /exchange/v1/markets_details
 * returns spot lot sizes (e.g. min_quantity: 0.001 for AAVE) while the underlying
 * CoinDCX/Binance Futures execution engine enforces strict derivatives lot sizes
 * (e.g. min_quantity: 0.1 for AAVE).
 */
object FuturesContractRegistry {

    data class ContractRule(
        val minQuantity: Double,
        val stepSize: Double,
        val quantityPrecision: Int,
        val pricePrecision: Int,
        val minNotionalUsdt: Double = 6.0
    )

    // High-fidelity pre-compiled specifications for CoinDCX B- (Binance USD-M) futures contracts
    private val KNOWN_FUTURES_RULES = mapOf(
        // Majors
        "BTCUSDT" to ContractRule(minQuantity = 0.001, stepSize = 0.001, quantityPrecision = 3, pricePrecision = 2),
        "ETHUSDT" to ContractRule(minQuantity = 0.001, stepSize = 0.001, quantityPrecision = 3, pricePrecision = 2),
        "SOLUSDT" to ContractRule(minQuantity = 0.01,  stepSize = 0.01,  quantityPrecision = 2, pricePrecision = 4),
        "BNBUSDT" to ContractRule(minQuantity = 0.01,  stepSize = 0.01,  quantityPrecision = 2, pricePrecision = 3),
        "XRPUSDT" to ContractRule(minQuantity = 0.1,   stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 4),
        "DOGEUSDT" to ContractRule(minQuantity = 1.0,  stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 6),
        "ADAUSDT" to ContractRule(minQuantity = 1.0,   stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 5),
        "AVAXUSDT" to ContractRule(minQuantity = 1.0,  stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 4),
        "LINKUSDT" to ContractRule(minQuantity = 0.01, stepSize = 0.01,  quantityPrecision = 2, pricePrecision = 3),
        "NEARUSDT" to ContractRule(minQuantity = 1.0,  stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 4),
        "SUIUSDT"  to ContractRule(minQuantity = 0.1,  stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 6),
        "LTCUSDT"  to ContractRule(minQuantity = 0.001, stepSize = 0.001, quantityPrecision = 3, pricePrecision = 2),
        "UNIUSDT"  to ContractRule(minQuantity = 1.0,  stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 4),
        "DOTUSDT"  to ContractRule(minQuantity = 0.1,  stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 6),
        "TRUMPUSDT" to ContractRule(minQuantity = 0.01, stepSize = 0.01, quantityPrecision = 2, pricePrecision = 6),
        "INJUSDT"  to ContractRule(minQuantity = 0.1,  stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 6),
        "FILUSDT"  to ContractRule(minQuantity = 0.1,  stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 6),
        "QNTUSDT"  to ContractRule(minQuantity = 0.1,  stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 6),
        "PENDLEUSDT" to ContractRule(minQuantity = 1.0, stepSize = 1.0,  quantityPrecision = 0, pricePrecision = 7),

        // DeFi & High-Priced Tier-1
        "AAVEUSDT" to ContractRule(minQuantity = 0.1,   stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 3),
        "MKRUSDT"  to ContractRule(minQuantity = 0.001, stepSize = 0.001, quantityPrecision = 3, pricePrecision = 2),
        "COMPUSDT" to ContractRule(minQuantity = 0.01,  stepSize = 0.01,  quantityPrecision = 2, pricePrecision = 3),
        "SNXUSDT"  to ContractRule(minQuantity = 0.1,   stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 4),
        "CRVUSDT"  to ContractRule(minQuantity = 1.0,   stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 4),
        "ZECUSDT"  to ContractRule(minQuantity = 0.01,  stepSize = 0.01,  quantityPrecision = 2, pricePrecision = 3),
        "DASHUSDT" to ContractRule(minQuantity = 0.01,  stepSize = 0.01,  quantityPrecision = 2, pricePrecision = 3),
        "ONDOUSDT" to ContractRule(minQuantity = 1.0,   stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 5),
        "ORDIUSDT" to ContractRule(minQuantity = 0.1,   stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 4),
        "MORPHOUSDT" to ContractRule(minQuantity = 0.1, stepSize = 0.1,  quantityPrecision = 1, pricePrecision = 5),
        "PROVEUSDT" to ContractRule(minQuantity = 1.0,  stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 5),
        "GIGGLEUSDT" to ContractRule(minQuantity = 0.1, stepSize = 0.1,  quantityPrecision = 1, pricePrecision = 4),
        "APTUSDT"  to ContractRule(minQuantity = 0.1,   stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 4),
        "ARBUSDT"  to ContractRule(minQuantity = 1.0,   stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 5),
        "OPUSDT"   to ContractRule(minQuantity = 0.1,   stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 4),
        "TIAUSDT"  to ContractRule(minQuantity = 0.1,   stepSize = 0.1,   quantityPrecision = 1, pricePrecision = 4),
        "RENDERUSDT" to ContractRule(minQuantity = 0.1, stepSize = 0.1,  quantityPrecision = 1, pricePrecision = 4),
        "RUNEUSDT" to ContractRule(minQuantity = 1.0,   stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 4),
        "SHIBUSDT" to ContractRule(minQuantity = 1000.0, stepSize = 1000.0, quantityPrecision = 0, pricePrecision = 8),
        "PEPEUSDT" to ContractRule(minQuantity = 100.0, stepSize = 100.0, quantityPrecision = 0, pricePrecision = 8),
        "BONKUSDT" to ContractRule(minQuantity = 1000.0, stepSize = 1000.0, quantityPrecision = 0, pricePrecision = 8),
        "FLOKIUSDT" to ContractRule(minQuantity = 100.0, stepSize = 100.0, quantityPrecision = 0, pricePrecision = 8),
        "POLUSDT"  to ContractRule(minQuantity = 1.0,   stepSize = 1.0,   quantityPrecision = 0, pricePrecision = 5)
    )

    private val dynamicRules = ConcurrentHashMap<String, ContractRule>()

    /**
     * Normalizes a CoinDCX pair identifier into standard futures symbol.
     * E.g. "B-AAVE_USDT" -> "AAVEUSDT", "B-BTC_USDT" -> "BTCUSDT".
     */
    fun normalizePairToFuturesSymbol(pair: String): String {
        return pair.removePrefix("B-")
            .replace("_", "")
            .uppercase(Locale.US)
    }

    /**
     * Retrieves the authoritative futures specification for a CoinDCX derivatives pair.
     */
    fun getFuturesSpec(pair: String): FuturesUniverseManager.InstrumentSpec? {
        val sym = normalizePairToFuturesSymbol(pair)
        val rule = dynamicRules[sym] ?: KNOWN_FUTURES_RULES[sym] ?: return null

        return FuturesUniverseManager.InstrumentSpec(
            pair = pair,
            step = rule.stepSize,
            minQuantity = rule.minQuantity,
            targetCurrencyPrecision = rule.quantityPrecision,
            minNotionalUsdt = rule.minNotionalUsdt,
            baseCurrencyPrecision = rule.pricePrecision
        )
    }

    /**
     * Ingests dynamic rule updates (e.g. from exchangeInfo cache or background updater).
     */
    fun registerDynamicRule(symbol: String, rule: ContractRule) {
        dynamicRules[symbol.uppercase(Locale.US)] = rule
    }
}
