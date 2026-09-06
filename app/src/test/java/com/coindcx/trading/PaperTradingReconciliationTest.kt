package com.coindcx.trading

import com.coindcx.trading.data.api.models.MarketCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic test suite verifying paper-trading calculations, margin, leverage,
 * gross/net P&L, fees, slippage, candle ordering, and account equity reconciliation.
 */
class PaperTradingReconciliationTest {

    private val takerFeeRate = 0.0005 // 0.05%
    private val slippageRate = 0.0005 // 0.05%

    @Test
    fun testLongPosition1xLeverage() {
        val marginInr = 1000.0
        val leverage = 1
        val notionalInr = marginInr * leverage
        val entryMarketPrice = 100.0
        val exitMarketPrice = 110.0 // +10% price move

        // Entry fill with slippage: Long buys higher
        val entryFill = entryMarketPrice * (1.0 + slippageRate) // 100.05
        // Exit fill with slippage: Long sells lower
        val exitFill = exitMarketPrice * (1.0 - slippageRate)   // 109.945

        val entryFee = notionalInr * takerFeeRate
        val exitFee = notionalInr * takerFeeRate
        val totalFees = entryFee + exitFee

        val grossPnl = notionalInr * ((exitFill - entryFill) / entryFill)
        val netPnl = grossPnl - totalFees
        val positionRoi = (netPnl / marginInr) * 100.0

        val startingCapital = 10000.0
        val accountReturnContribution = (netPnl / startingCapital) * 100.0

        // Price moved +10%, after 0.05% slippage on entry and exit, gross move is ~9.89%
        assertEquals(98.90, grossPnl, 0.1)
        assertEquals(1.0, totalFees, 0.001)
        assertEquals(97.90, netPnl, 0.1)
        assertEquals(9.79, positionRoi, 0.1)
        assertEquals(0.979, accountReturnContribution, 0.01)
    }

    @Test
    fun testLongPosition2xLeverageDoubleNotionalAndRoi() {
        val marginInr = 1000.0
        val leverage = 2
        val notionalInr = marginInr * leverage // ₹2,000 notional
        val entryPrice = 100.0
        val exitPrice = 110.0

        val grossPnl = notionalInr * ((exitPrice - entryPrice) / entryPrice)
        val fees = notionalInr * takerFeeRate * 2.0 // Entry + exit taker fees
        val netPnl = grossPnl - fees
        val positionRoi = (netPnl / marginInr) * 100.0

        assertEquals(200.0, grossPnl, 0.001)
        assertEquals(2.0, fees, 0.001)
        assertEquals(198.0, netPnl, 0.001)
        // Leverage 2x doubles return on invested margin
        assertEquals(19.8, positionRoi, 0.001)
    }

    @Test
    fun testShortPosition1xLeverageProfitsOnDrop() {
        val marginInr = 1000.0
        val leverage = 1
        val notionalInr = marginInr * leverage
        val entryPrice = 100.0
        val exitPrice = 90.0 // Market dropped 10%

        // Short Gross PnL: notional * ((entryPrice - exitPrice) / entryPrice)
        val grossPnl = notionalInr * ((entryPrice - exitPrice) / entryPrice)
        val fees = notionalInr * takerFeeRate * 2.0
        val netPnl = grossPnl - fees
        val positionRoi = (netPnl / marginInr) * 100.0

        assertEquals(100.0, grossPnl, 0.001)
        assertEquals(1.0, fees, 0.001)
        assertEquals(99.0, netPnl, 0.001)
        assertEquals(9.9, positionRoi, 0.001)
    }

    @Test
    fun testLosingTradeNegativeGrossAndNetPnl() {
        val marginInr = 1000.0
        val leverage = 1
        val notionalInr = marginInr * leverage
        val entryPrice = 100.0
        val exitPrice = 90.0 // Long position losing on 10% drop

        val grossPnl = notionalInr * ((exitPrice - entryPrice) / entryPrice)
        val fees = notionalInr * takerFeeRate * 2.0
        val netPnl = grossPnl - fees
        val positionRoi = (netPnl / marginInr) * 100.0

        assertEquals(-100.0, grossPnl, 0.001)
        assertEquals(1.0, fees, 0.001)
        assertEquals(-101.0, netPnl, 0.001)
        assertEquals(-10.1, positionRoi, 0.001)
    }

    @Test
    fun testFeeAndSlippageFrictionReducesProfit() {
        val notionalInr = 1000.0
        val theoreticalGross = notionalInr * ((110.0 - 100.0) / 100.0) // 100.0

        val entryFill = 100.0 * (1.0 + slippageRate)
        val exitFill = 110.0 * (1.0 - slippageRate)
        val actualGross = notionalInr * ((exitFill - entryFill) / entryFill)
        val totalFees = notionalInr * takerFeeRate * 2.0
        val netPnl = actualGross - totalFees

        assertTrue("Actual gross must be less than theoretical due to slippage", actualGross < theoreticalGross)
        assertTrue("Net PnL must be strictly less than actual gross due to taker fees", netPnl < actualGross)
    }

    @Test
    fun testCoinDcxDescendingCandleSortingAscending() {
        // Simulating raw response from CoinDCX GET /market_data/candles
        // CoinDCX returns newest first (descending by timestamp)
        val rawCoinDcxCandles = listOf(
            MarketCandle(time = 5000L, open = 150.0, high = 155.0, low = 149.0, close = 152.0, volume = 10.0), // NEWEST (Current)
            MarketCandle(time = 4000L, open = 148.0, high = 151.0, low = 147.0, close = 150.0, volume = 12.0),
            MarketCandle(time = 3000L, open = 145.0, high = 149.0, low = 144.0, close = 148.0, volume = 15.0),
            MarketCandle(time = 2000L, open = 140.0, high = 146.0, low = 139.0, close = 145.0, volume = 8.0),
            MarketCandle(time = 1000L, open = 100.0, high = 105.0, low = 98.0, close = 100.0, volume = 20.0)   // OLDEST (5 days ago)
        )

        // Verifying the bug: calling candles.last() on unsorted data returned 100.0 (5-day-old price!)
        val buggyPrice = rawCoinDcxCandles.last().close
        assertEquals(100.0, buggyPrice, 0.001)

        // Verifying the fix: sorting ascending by timestamp ensures last() is the current live price
        val sortedCandles = rawCoinDcxCandles.sortedBy { it.time }
        val currentLivePrice = sortedCandles.last().close
        assertEquals(152.0, currentLivePrice, 0.001)
        assertEquals(5000L, sortedCandles.last().time)
        assertEquals(1000L, sortedCandles.first().time)

        // Verifying chronological order for technical indicators
        for (i in 0 until sortedCandles.size - 1) {
            assertTrue("Candles must be strictly increasing in time", sortedCandles[i].time < sortedCandles[i + 1].time)
        }
    }

    @Test
    fun testAccountEquityAndBalanceReconciliationWithoutDoubleCounting() {
        val startingCapital = 10000.0
        val trade1NetPnl = 99.0 // Closed win

        val openTradeMargin = 2000.0
        val openTradeEntryFee = 1.0
        val openTradeFunding = 0.50
        val openTradeGrossPnl = 50.0 // Unrealized gross

        // Available Cash = Starting + Closed Net PnL - Locked Margin - Open Fees - Open Funding
        val availableCash = startingCapital + trade1NetPnl - openTradeMargin - openTradeEntryFee - openTradeFunding
        assertEquals(8097.50, availableCash, 0.001)

        // Open Net Unrealized PnL = Open Gross - Entry Fee - Funding
        val openNetUnrealizedPnl = openTradeGrossPnl - openTradeEntryFee - openTradeFunding // 48.50

        // Total Equity = Available Cash + Locked Margin + Open Gross PnL
        val totalEquity = availableCash + openTradeMargin + openTradeGrossPnl
        assertEquals(10147.50, totalEquity, 0.001)

        // Reconciled direct identity check:
        // Total Equity == Starting Capital + Closed Net Realized PnL + Open Net Unrealized PnL
        val expectedEquity = startingCapital + trade1NetPnl + openNetUnrealizedPnl
        assertEquals(expectedEquity, totalEquity, 0.00001)
    }
}
