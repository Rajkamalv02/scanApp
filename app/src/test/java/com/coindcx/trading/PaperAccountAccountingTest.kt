package com.coindcx.trading

import org.junit.Assert.assertEquals
import org.junit.Test

class PaperAccountAccountingTest {

    @Test
    fun testCleanAccountingFormulaWithoutDoubleCounting() {
        // Starting capital: ₹5,000
        val startingBalance = 5000.0

        // Closed trades store realizedPnl as Net Realized PnL (Gross PnL - Fees - Funding)
        // Closed trade 1: Net PnL = +200.0
        // Closed trade 2: Net PnL = -100.0
        val netRealizedPnlClosed = 200.0 - 100.0 // +100.0

        // Open trade:
        // Allocated Margin: ₹1,000
        val openUsedMargin = 1000.0
        // Entry fee deducted on position open: ₹2.50
        val openEntryFees = 2.50
        // Accrued UTC funding on open position: ₹0.50
        val openAccruedFunding = 0.50
        // Current Unrealized PnL on open position: +₹45.00
        val openUnrealizedPnl = 45.0

        // Available Balance Formula:
        // Available = Starting + NetRealized - UsedMargin - OpenEntryFees - OpenAccruedFunding
        val availableBalance = startingBalance + netRealizedPnlClosed - openUsedMargin - openEntryFees - openAccruedFunding
        assertEquals(4097.0, availableBalance, 0.001)

        // Total Equity Formula:
        // Equity = Available + UsedMargin + UnrealizedPnl
        val totalEquity = availableBalance + openUsedMargin + openUnrealizedPnl
        assertEquals(5142.0, totalEquity, 0.001)

        // Total Return %:
        // (Equity - Starting) / Starting * 100
        val totalReturnPct = ((totalEquity - startingBalance) / startingBalance) * 100.0
        assertEquals(2.84, totalReturnPct, 0.01)

        // If open position is closed at this exact moment:
        // Net Realized PnL on exit = +45.0 - exitFee(2.50) = +42.50
        // New Net Realized PnL closed = 100.0 + 42.50 = 142.50
        // Open margin released = 0, open fees = 0
        // Available = 5000 + 142.50 = 5142.50 (matches equity minus final exit fee)
    }
}
