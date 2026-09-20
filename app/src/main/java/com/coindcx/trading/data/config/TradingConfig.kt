package com.coindcx.trading.data.config

/**
 * User-configurable trading settings with strict INR denomination.
 */
data class TradingConfig(
    val riskProfile: String = "BALANCED", // "CONSERVATIVE" (0.5%), "BALANCED" (1.0%), "GROWTH" (1.5%)
    val riskPerTradePercent: Double = 1.0,
    val safetyReservePercent: Double = 0.0,
    val maxSingleExposurePercent: Double = 30.0,
    val minMarginPerTradeInr: Double = 500.0, // Legacy fallback
    val leverage: Int = 2,
    val timeframe: String = "15m", // "1m", "15m", "1h", "1d"
    val scanIntervalMinutes: Int = 2, // 1, 2, 5, 15, 30, 60
    val isMarketWideScan: Boolean = true,
    val selectedPairs: List<String> = listOf(
        "B-BTC_USDT",
        "B-ETH_USDT",
        "B-SOL_USDT",
        "B-XRP_USDT",
        "B-DOGE_USDT",
        "B-ADA_USDT",
        "B-BNB_USDT",
        "B-AVAX_USDT",
        "B-LINK_USDT",
        "B-NEAR_USDT"
    ),
    val paperInitialBalanceInr: Double = 10000.0,
    val maxDailyLossInr: Double = 2000.0,
    // Strategy tuning
    val fastEmaPeriod: Int = 9,
    val slowEmaPeriod: Int = 21,
    val atrMultiplier: Double = 1.5,
    val rsiPeriod: Int = 14,
    val rsiOversold: Double = 30.0,
    val rsiOverbought: Double = 70.0,
    // Canary guardrail toggle
    val allowTier2AltcoinsLive: Boolean = true
)
