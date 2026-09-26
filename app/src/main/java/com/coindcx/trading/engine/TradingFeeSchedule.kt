package com.coindcx.trading.engine

/**
 * Institutional Trading Fee & Risk Level Configuration.
 *
 * Implements exact user formulas:
 * 1. Target Price Calculation:
 *    Final Target % = UI Target % + Total Fees % (Buy + Sell) + 0.5%
 *    - UI Target % = percentage selected by user in UI
 *    - Buy Fee % = 0.05%
 *    - Sell Fee % = 0.05%
 *    - Total Fees % = Buy Fee % + Sell Fee % = 0.10%
 *    - Additional Buffer = 0.50%
 *    - CRUCIAL: Leverage MUST NOT multiply or modify UI Target %.
 *      Target calculation starts with UI Target %, adds fees (0.10%) and 0.50% buffer.
 *
 * 2. Stop-Loss Calculation:
 *    Stop-Loss % = (UI Stop-Loss % * Leverage) - Total Fees %
 *    - UI Stop-Loss % = percentage selected in UI
 *    - Leverage = selected leverage
 *    - Total Fees % = Buy Fee % + Sell Fee % = 0.10%
 */
object TradingFeeSchedule {
    const val BUY_FEE_PERCENT = 0.05
    const val SELL_FEE_PERCENT = 0.05
    const val TOTAL_FEES_PERCENT = BUY_FEE_PERCENT + SELL_FEE_PERCENT // 0.10%
    const val ADDITIONAL_BUFFER_PERCENT = 0.50 // 0.50% buffer

    /**
     * Final Target % = UI Target % + Total Fees % (Buy + Sell) + 0.5%
     *
     * Example: UI Target 1% -> 1.0% + 0.05% + 0.05% + 0.5% = 1.60%.
     * IMPORTANT: Leverage does NOT scale or multiply UI Target %.
     */
    fun calculateFinalTargetPercent(uiTargetPercent: Double): Double {
        return uiTargetPercent + TOTAL_FEES_PERCENT + ADDITIONAL_BUFFER_PERCENT
    }

    /**
     * Stop-Loss % = (UI Stop-Loss % * Leverage) - Total Fees %
     *
     * Example: UI SL 1%, Leverage 5x -> (1% * 5) - 0.10% = 4.90%.
     * Clamped to safe range [0.10%, 85.0%] to protect against negative SL or immediate liquidation.
     */
    fun calculateFinalStopLossPercent(uiStopLossPercent: Double, leverage: Int): Double {
        val lev = leverage.coerceAtLeast(1)
        val rawSl = (uiStopLossPercent * lev) - TOTAL_FEES_PERCENT
        return rawSl.coerceIn(0.10, 85.0)
    }

    /**
     * Calculates absolute Take Profit Price given Entry Price and UI Target %.
     * Long: entry * (1.0 + finalTargetPct / 100.0)
     * Short: entry * (1.0 - finalTargetPct / 100.0)
     */
    fun calculateTakeProfitPrice(entryPrice: Double, isBuy: Boolean, uiTargetPercent: Double): Double {
        val finalTargetPct = calculateFinalTargetPercent(uiTargetPercent)
        return if (isBuy) {
            entryPrice * (1.0 + finalTargetPct / 100.0)
        } else {
            entryPrice * (1.0 - finalTargetPct / 100.0)
        }
    }

    /**
     * Calculates absolute Stop Loss Price given Entry Price, UI SL %, and Leverage.
     * Long: entry * (1.0 - finalSlPct / 100.0)
     * Short: entry * (1.0 + finalSlPct / 100.0)
     */
    fun calculateStopLossPrice(entryPrice: Double, isBuy: Boolean, uiStopLossPercent: Double, leverage: Int): Double {
        val finalSlPct = calculateFinalStopLossPercent(uiStopLossPercent, leverage)
        return if (isBuy) {
            entryPrice * (1.0 - finalSlPct / 100.0)
        } else {
            entryPrice * (1.0 + finalSlPct / 100.0)
        }
    }
}
