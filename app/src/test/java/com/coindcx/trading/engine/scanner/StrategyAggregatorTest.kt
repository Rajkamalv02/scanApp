package com.coindcx.trading.engine.scanner

import com.coindcx.trading.engine.Signal
import com.coindcx.trading.engine.SignalAction
import com.coindcx.trading.engine.SignalDirection
import org.junit.Assert.*
import org.junit.Test

class StrategyAggregatorTest {

    @Test
    fun `test Case A - strong multi-strategy consensus arithmetic and reconciliation`() {
        // PBC: Conf 81.0%, Entry 60000, SL 59100, TP 62000
        val pbc = StrategyEvaluation(
            strategyId = "pbc",
            strategyName = "PBC Strategy",
            family = StrategyFamily.TREND,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 81.0,
            entryPrice = 60000.0,
            stopLossPrice = 59100.0,
            takeProfitPrice = 62000.0
        )

        // EDTM: Conf 76.0%, Entry 60050, SL 59200, TP 62200
        val edtm = StrategyEvaluation(
            strategyId = "edtm",
            strategyName = "EDTM Strategy",
            family = StrategyFamily.TREND,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 76.0,
            entryPrice = 60050.0,
            stopLossPrice = 59200.0,
            takeProfitPrice = 62200.0
        )

        // IRC: Conf 72.0%, Entry 59950, SL 58900, TP 62500
        val irc = StrategyEvaluation(
            strategyId = "irc",
            strategyName = "IRC Strategy",
            family = StrategyFamily.STRUCTURE,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 72.0,
            entryPrice = 59950.0,
            stopLossPrice = 58900.0,
            takeProfitPrice = 62500.0
        )

        val candidate = StrategyAggregator.aggregate(
            symbol = "B-BTC_USDT",
            evaluations = listOf(pbc, edtm, irc),
            currentMarketPrice = 60000.0,
            stopLossPercent = (59950.0 - 58900.0) / 59950.0 * 100.0,
            targetPricePercent = 2.2
        )

        assertNotNull("Case A should be approved", candidate)
        candidate!!

        // Verified arithmetic: 83.3019%
        assertEquals(83.30, candidate.aggregatedConfidence, 0.05)

        // Reconciled Entry = min(60000, 60050, 59950, current 60000) = 59950.0
        assertEquals(59950.0, candidate.reconciledEntry, 0.001)

        // Reconciled SL = min(59100, 59200, 58900) = 58900.0
        assertEquals(58900.0, candidate.reconciledStopLoss, 0.001)

        // Reconciled TP: raw was 62000.0 (dist 2050.0 = 3.42%), clamped to 2.2% scalping ceiling = 59950.0 * 1.022 = 61268.9
        assertEquals(61268.9, candidate.reconciledTakeProfit, 0.01)

        // Net R:R: raw = 1318.9 / 1050 = 1.2561
        // feeFriction = 0.14 / (1050/59950 * 100) = 0.14 / 1.75146 = 0.07993
        // netRR = 1.2561 - 0.07993 = ~1.18
        assertTrue(candidate.netRiskReward >= 0.55)
        assertEquals(1.18, candidate.netRiskReward, 0.05)
        assertEquals(3, candidate.consensusCount)
    }

    @Test
    fun `test Case B - directional conflict with high confidence discards both`() {
        // Long Pool: PBC (82.0%, F1) + Confluence (74.0%, F1)
        val pbc = StrategyEvaluation(
            strategyId = "pbc",
            strategyName = "PBC Strategy",
            family = StrategyFamily.TREND,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 82.0,
            entryPrice = 3000.0,
            stopLossPrice = 2950.0,
            takeProfitPrice = 3150.0
        )
        val confluence = StrategyEvaluation(
            strategyId = "confluence",
            strategyName = "Confluence Strategy",
            family = StrategyFamily.TREND,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 74.0,
            entryPrice = 3005.0,
            stopLossPrice = 2955.0,
            takeProfitPrice = 3140.0
        )

        // Short Pool: LSR (79.0%, F3)
        val lsr = StrategyEvaluation(
            strategyId = "lsr",
            strategyName = "LSR Strategy",
            family = StrategyFamily.MEANREV,
            action = SignalAction.ENTER_SHORT,
            direction = SignalDirection.SHORT,
            confidence = 79.0,
            entryPrice = 3010.0,
            stopLossPrice = 3060.0,
            takeProfitPrice = 2900.0
        )

        val candidate = StrategyAggregator.aggregate(
            symbol = "B-ETH_USDT",
            evaluations = listOf(pbc, confluence, lsr),
            currentMarketPrice = 3000.0
        )

        // Sub-case 4A: Both >= 70.0% -> Severe ambiguity -> Must discard both!
        assertNull("Case B severe high-confidence conflict must discard both", candidate)
    }

    @Test
    fun `test Case C - candidate approved under scalping net RR threshold or rejected when below 0_55`() {
        // VCEB: Entry 140, SL 135 (risk 5.0 -> clamped to 3.0% = 4.2 -> SL 135.8), TP 144 (reward 4.0 -> clamped to 2.2% = 3.08 -> TP 143.08)
        // Raw RR = 3.08 / 4.2 = 0.733. Fee friction = 0.14 / 3.0 = 0.0467. Net RR = 0.687 >= 0.55 -> Approved!
        val vceb = StrategyEvaluation(
            strategyId = "vceb",
            strategyName = "VCEB Strategy",
            family = StrategyFamily.BREAKOUT,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 85.0,
            entryPrice = 140.0,
            stopLossPrice = 135.0,
            takeProfitPrice = 144.0
        )

        val candidate = StrategyAggregator.aggregate(
            symbol = "B-SOL_USDT",
            evaluations = listOf(vceb),
            currentMarketPrice = 140.0,
            stopLossPercent = 3.0,
            targetPricePercent = 2.2
        )

        assertNotNull("Case C qualifies under scalping Net R:R threshold", candidate)
        assertEquals(0.69, candidate!!.netRiskReward, 0.05)

        // Candidate with Net R:R <= 0 (fee friction exceeds profit target)
        val rejectedCandidate = StrategyAggregator.aggregate(
            symbol = "B-SOL_USDT",
            evaluations = listOf(vceb),
            currentMarketPrice = 140.0,
            stopLossPercent = 3.0,
            targetPricePercent = 0.10
        )
        assertNull("Candidate with Net R:R <= 0 must be rejected", rejectedCandidate)
    }

    @Test
    fun `test Case E - level reconciliation and hard geometric invariant check`() {
        // Strategy A: Entry 30.0, SL 29.1, TP 32.0
        val stratA = StrategyEvaluation(
            strategyId = "stratA",
            strategyName = "Strategy A",
            family = StrategyFamily.TREND,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 78.0,
            entryPrice = 30.0,
            stopLossPrice = 29.1,
            takeProfitPrice = 32.0
        )

        // Strategy B: Entry 30.2, SL 29.4, TP 32.5
        val stratB = StrategyEvaluation(
            strategyId = "stratB",
            strategyName = "Strategy B",
            family = StrategyFamily.BREAKOUT,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 72.0,
            entryPrice = 30.2,
            stopLossPrice = 29.4,
            takeProfitPrice = 32.5
        )

        val candidate = StrategyAggregator.aggregate(
            symbol = "B-AVAX_USDT",
            evaluations = listOf(stratA, stratB),
            currentMarketPrice = 30.1,
            stopLossPercent = 3.0,
            targetPricePercent = 2.2
        )

        assertNotNull(candidate)
        candidate!!

        assertEquals(30.0, candidate.reconciledEntry, 0.001)
        assertEquals(29.1, candidate.reconciledStopLoss, 0.001)
        // Scalping TP clamped to 2.2% ceiling: 30.0 + (30.0 * 0.022) = 30.66
        assertEquals(30.66, candidate.reconciledTakeProfit, 0.01)

        // Invariant check: SL < Entry < TP
        assertTrue(candidate.reconciledStopLoss < candidate.reconciledEntry)
        assertTrue(candidate.reconciledEntry < candidate.reconciledTakeProfit)
        assertTrue(candidate.netRiskReward >= 0.55)
    }

    @Test
    fun `test Case J - highly correlated strategies receive minimal marginal boost`() {
        // EMA Crossover: 75.0% (F1)
        val ema = StrategyEvaluation(
            strategyId = "ema_crossover",
            strategyName = "EMA Crossover",
            family = StrategyFamily.TREND,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 75.0,
            entryPrice = 100.0,
            stopLossPrice = 98.0,
            takeProfitPrice = 106.0
        )

        // PBC: 74.0% (F1), rho(F1, F1) = 0.80
        val pbc = StrategyEvaluation(
            strategyId = "pbc",
            strategyName = "PBC",
            family = StrategyFamily.TREND,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 74.0,
            entryPrice = 100.0,
            stopLossPrice = 98.0,
            takeProfitPrice = 106.0
        )

        val aggConf = StrategyAggregator.aggregateConfidence(listOf(ema, pbc))

        // Marginal: (74/100) * (100 - 75) * 0.20 * (1 - 0.80) = 0.74 * 25 * 0.04 = 0.74%
        // Final: 75.0 + 0.74 = 75.74%
        assertEquals(75.74, aggConf, 0.01)
    }

    @Test
    fun `test hard invariant check discards inverted levels`() {
        // Malformed or inverted strategy levels: TP below Entry
        val invertedStrat = StrategyEvaluation(
            strategyId = "bad_strat",
            strategyName = "Bad Strategy",
            family = StrategyFamily.TREND,
            action = SignalAction.ENTER_LONG,
            direction = SignalDirection.LONG,
            confidence = 85.0,
            entryPrice = 100.0,
            stopLossPrice = 95.0,
            takeProfitPrice = 98.0 // Inverted! TP < Entry for Long
        )

        val candidate = StrategyAggregator.aggregate(
            symbol = "B-TEST_USDT",
            evaluations = listOf(invertedStrat),
            currentMarketPrice = 100.0,
            stopLossPercent = -2.0
        )

        assertNull("Inverted level must fail invariant check and return null", candidate)
    }

    @Test
    fun `test TradeCandidateSelector Case D capacity K and priority score derivation`() {
        val selector = TradeCandidateSelector()

        val dogeSignal = Signal(
            action = SignalAction.ENTER_LONG,
            confidenceScore = 80.0,
            entryPrice = 0.15,
            stopLossPrice = 0.146,
            takeProfitPrice = 0.160,
            riskRewardRatio = 2.0,
            strategyId = "pbc",
            strategyName = "PBC"
        )
        val dogeOpp = MarketOpportunity(
            pair = "B-DOGE_USDT",
            signal = dogeSignal,
            currentPrice = 0.15,
            confidenceScore = 80.0,
            qualityScore = 80,
            netRiskRewardRatio = 2.0,
            isApproved = true,
            contributingStrategies = listOf(
                StrategyContribution("pbc", "PBC", SignalAction.ENTER_LONG, 80, 80.0, 2.0),
                StrategyContribution("edtm", "EDTM", SignalAction.ENTER_LONG, 75, 75.0, 2.0)
            ) // n = 2
        )

        val suiSignal = Signal(
            action = SignalAction.ENTER_LONG,
            confidenceScore = 72.0,
            entryPrice = 1.50,
            stopLossPrice = 1.45,
            takeProfitPrice = 1.62,
            riskRewardRatio = 1.8,
            strategyId = "vceb",
            strategyName = "VCEB"
        )
        val suiOpp = MarketOpportunity(
            pair = "B-SUI_USDT",
            signal = suiSignal,
            currentPrice = 1.50,
            confidenceScore = 72.0,
            qualityScore = 72,
            netRiskRewardRatio = 1.8,
            isApproved = true,
            contributingStrategies = listOf(
                StrategyContribution("vceb", "VCEB", SignalAction.ENTER_LONG, 72, 72.0, 1.8)
            ) // n = 1
        )

        // Account params from Case D (Reserve removed):
        // Cash = 550.0, MinMarginRequired = 572.89 / 2 = 286.45
        // K_margin = floor(550.0 / 286.45) = 1, K_slots = 3, K_risk = 4 -> K = 1
        val result = selector.selectCandidates(
            candidates = listOf(dogeOpp, suiOpp),
            accountEquityInr = 650.0,
            availableCashInr = 550.0,
            activePositionsCount = 0,
            maxConcurrentPositions = 3,
            leverage = 2,
            minExchangeNotionalInr = 572.89,
            riskPerTradePercent = 1.0,
            maxPortfolioRiskPercent = 4.0
        )

        assertEquals(1, result.capacityK)
        assertEquals(1, result.approvedTrades.size)
        assertEquals(1, result.deferredTrades.size)

        // DOGE has higher PriorityScore (60.8 vs 49.1) and must be approved
        assertEquals("B-DOGE_USDT", result.approvedTrades.first().pair)
        assertEquals(1, result.approvedTrades.first().rank)

        // SUI must be deferred
        assertEquals("B-SUI_USDT", result.deferredTrades.first().pair)
        assertTrue(result.deferredTrades.first().statusMessage.contains("Deferred: Exceeds dynamic capacity K=1"))
    }

    @Test
    fun `test TradeCandidateSelector outputs zero trades when no candidates qualify`() {
        val selector = TradeCandidateSelector()

        val holdOpp = MarketOpportunity(
            pair = "B-ADA_USDT",
            signal = Signal(action = SignalAction.HOLD, strategyId = "pbc", strategyName = "PBC"),
            currentPrice = 0.50,
            confidenceScore = 40.0,
            qualityScore = 40,
            isApproved = false
        )

        val result = selector.selectCandidates(
            candidates = listOf(holdOpp),
            accountEquityInr = 1000.0,
            availableCashInr = 1000.0,
            activePositionsCount = 0,
            maxConcurrentPositions = 3,
            leverage = 2,
            minExchangeNotionalInr = 600.0
        )

        assertEquals(0, result.approvedTrades.size)
        assertEquals(0, result.deferredTrades.size)
        assertTrue(result.statusMessage.contains("0 valid actionable setups"))
    }
}
