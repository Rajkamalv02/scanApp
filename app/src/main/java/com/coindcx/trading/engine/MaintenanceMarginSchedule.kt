package com.coindcx.trading.engine

/**
 * Single canonical source of truth for tiered Maintenance Margin Rates (MMR)
 * and estimated liquidation calculations across the application.
 */
object MaintenanceMarginSchedule {

    /**
     * Tiered Maintenance Margin schedule based on leverage:
     * <= 5x   : 1.0% (0.010)
     * 6x-10x  : 1.5% (0.015)
     * 11x-20x : 2.5% (0.025)
     */
    fun getMaintenanceMarginRate(leverage: Int): Double {
        val lev = leverage.coerceAtLeast(1)
        return when {
            lev <= 5 -> 0.010
            lev <= 10 -> 0.015
            else -> 0.025
        }
    }

    /**
     * Estimated distance to liquidation as a percentage of entry price.
     * Formula: (1.0 / leverage) - MMR(leverage)
     */
    fun getEstimatedLiquidationDistancePct(leverage: Int): Double {
        val lev = leverage.coerceAtLeast(1)
        return (1.0 / lev) - getMaintenanceMarginRate(lev)
    }

    /**
     * Estimates liquidation price for Long and Short positions.
     */
    fun calculateEstimatedLiquidationPrice(
        side: String,
        entryPrice: Double,
        leverage: Int
    ): Double {
        val lev = leverage.coerceAtLeast(1)
        val mmr = getMaintenanceMarginRate(lev)
        val levInv = 1.0 / lev
        return if (side.equals("LONG", ignoreCase = true) || side.equals("BUY", ignoreCase = true)) {
            (entryPrice * (1.0 - levInv + mmr)).coerceAtLeast(0.0)
        } else {
            entryPrice * (1.0 + levInv - mmr)
        }
    }
}
