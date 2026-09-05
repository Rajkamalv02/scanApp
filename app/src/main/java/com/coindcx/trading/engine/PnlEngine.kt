package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.FuturesPosition

/**
 * P&L Engine
 * Computes exact realized & unrealized P&L taking into account fees, leverage, and funding.
 */
object PnlEngine {

    fun calculateRealizedPnl(
        side: String,
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
        totalFees: Double = 0.0,
        fundingFees: Double = 0.0
    ): Double {
        val grossPnl = if (side.equals("LONG", ignoreCase = true) || side.equals("BUY", ignoreCase = true)) {
            (exitPrice - entryPrice) * quantity
        } else {
            (entryPrice - exitPrice) * quantity
        }
        return grossPnl - totalFees - fundingFees
    }

    fun calculateUnrealizedPnl(position: FuturesPosition): Double {
        val mark = position.markPrice ?: position.avgPrice
        val funding = position.cumulativeFundingFee ?: 0.0

        val gross = if (position.isLong) {
            (mark - position.avgPrice) * position.activePos
        } else if (position.isShort) {
            (position.avgPrice - mark) * kotlin.math.abs(position.activePos)
        } else {
            0.0
        }
        return gross - funding
    }
}
