package com.coindcx.trading.engine.backtest

import com.coindcx.trading.engine.*
import com.coindcx.trading.engine.Target
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.indicators.TechnicalIndicators
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.time.FixedClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ReplayEngine(
    val takerFeePct: Double = 0.0005, // 0.05% taker fee per fill
    val slippagePct: Double = 0.0002  // 0.02% slippage per market order
) {

    private data class ActiveTrade(
        val symbol: String,
        val strategyId: String,
        val direction: SignalDirection,
        val entryBarTime: Long,
        val entryPrice: Double,
        var currentStopLoss: Double,
        val target: Target,
        val expiryBars: Int,
        val initialRisk: Double,
        var barsHeld: Int = 0
    )

    fun replay(
        series: CandleSeries,
        strategy: Strategy,
        warmupBars: Int = strategy.requiredCandleCount.coerceAtLeast(60),
        htf1HSeries: CandleSeries? = null,
        htf4HSeries: CandleSeries? = null
    ): BacktestSummary {
        val totalBars = series.size
        require(totalBars > warmupBars + 10) {
            "Series size ($totalBars) must be > warmupBars ($warmupBars) + 10"
        }

        val completedTrades = mutableListOf<BacktestTrade>()
        var activeTrade: ActiveTrade? = null
        var pendingSignal: Signal? = null
        var currentState: StrategyState? = null

        // Chronological stepping: k runs from warmupBars to totalBars - 1
        for (k in warmupBars until totalBars) {
            val offset = (totalBars - 1) - k
            val currentBarTime = series.openTime(offset)
            val currentHigh = series.high(offset)
            val currentLow = series.low(offset)
            val currentOpen = series.open(offset)
            val currentClose = series.close(offset)

            // 1. Process Pending Entry Order at Open of current bar (bar k)
            if (pendingSignal != null && activeTrade == null) {
                val sig = pendingSignal!!
                pendingSignal = null

                val fillPrice = when (sig.direction) {
                    SignalDirection.LONG -> currentOpen * (1.0 + slippagePct)
                    SignalDirection.SHORT -> currentOpen * (1.0 - slippagePct)
                }
                val risk = abs(fillPrice - sig.stopLoss)

                activeTrade = ActiveTrade(
                    symbol = sig.symbol,
                    strategyId = sig.strategyId,
                    direction = sig.direction,
                    entryBarTime = currentBarTime,
                    entryPrice = fillPrice,
                    currentStopLoss = sig.stopLoss,
                    target = sig.target,
                    expiryBars = sig.expiryBars,
                    initialRisk = if (risk > 0.0) risk else fillPrice * 0.01
                )
            }

            // 2. Manage Active Trade on current bar
            if (activeTrade != null) {
                val trade = activeTrade!!
                trade.barsHeld++

                var exitOccurred = false
                var exitPrice = 0.0
                var exitReason = ExitReason.STOP_LOSS

                when (trade.direction) {
                    SignalDirection.LONG -> {
                        // Check Stop Loss
                        if (currentLow <= trade.currentStopLoss) {
                            exitOccurred = true
                            exitPrice = min(currentOpen, trade.currentStopLoss) * (1.0 - slippagePct)
                            exitReason = ExitReason.STOP_LOSS
                        }
                        // Check Take Profit (Fixed Target)
                        else if (trade.target is Target.Fixed && currentHigh >= trade.target.tp1) {
                            exitOccurred = true
                            exitPrice = max(currentOpen, trade.target.tp1)
                            exitReason = ExitReason.TAKE_PROFIT
                        }
                        // Check Trailing Stop (OpenEnded Target)
                        else if (trade.target is Target.OpenEnded) {
                            val atr = TechnicalIndicators.calculateAtr(series, trade.target.trailSpec.atrPeriod, barIndex = offset)
                            val trailDist = trade.target.trailSpec.atrMultiplier * atr
                            val candidateStop = currentClose - trailDist
                            if (candidateStop > trade.currentStopLoss) {
                                trade.currentStopLoss = candidateStop
                            }
                            if (currentLow <= trade.currentStopLoss) {
                                exitOccurred = true
                                exitPrice = min(currentOpen, trade.currentStopLoss) * (1.0 - slippagePct)
                                exitReason = ExitReason.TRAILING_STOP
                            }
                        }
                        // Check Expiry Bars
                        if (!exitOccurred && trade.expiryBars > 0 && trade.barsHeld >= trade.expiryBars) {
                            exitOccurred = true
                            exitPrice = currentClose * (1.0 - slippagePct)
                            exitReason = ExitReason.EXPIRY_BARS
                        }
                    }
                    SignalDirection.SHORT -> {
                        // Check Stop Loss
                        if (currentHigh >= trade.currentStopLoss) {
                            exitOccurred = true
                            exitPrice = max(currentOpen, trade.currentStopLoss) * (1.0 + slippagePct)
                            exitReason = ExitReason.STOP_LOSS
                        }
                        // Check Take Profit (Fixed Target)
                        else if (trade.target is Target.Fixed && currentLow <= trade.target.tp1) {
                            exitOccurred = true
                            exitPrice = min(currentOpen, trade.target.tp1)
                            exitReason = ExitReason.TAKE_PROFIT
                        }
                        // Check Trailing Stop (OpenEnded Target)
                        else if (trade.target is Target.OpenEnded) {
                            val atr = TechnicalIndicators.calculateAtr(series, trade.target.trailSpec.atrPeriod, barIndex = offset)
                            val trailDist = trade.target.trailSpec.atrMultiplier * atr
                            val candidateStop = currentClose + trailDist
                            if (candidateStop < trade.currentStopLoss) {
                                trade.currentStopLoss = candidateStop
                            }
                            if (currentHigh >= trade.currentStopLoss) {
                                exitOccurred = true
                                exitPrice = max(currentOpen, trade.currentStopLoss) * (1.0 + slippagePct)
                                exitReason = ExitReason.TRAILING_STOP
                            }
                        }
                        // Check Expiry Bars
                        if (!exitOccurred && trade.expiryBars > 0 && trade.barsHeld >= trade.expiryBars) {
                            exitOccurred = true
                            exitPrice = currentClose * (1.0 + slippagePct)
                            exitReason = ExitReason.EXPIRY_BARS
                        }
                    }
                }

                if (exitOccurred) {
                    val rawPnlPct = when (trade.direction) {
                        SignalDirection.LONG -> ((exitPrice - trade.entryPrice) / trade.entryPrice) * 100.0
                        SignalDirection.SHORT -> ((trade.entryPrice - exitPrice) / trade.entryPrice) * 100.0
                    }
                    val roundTripFeePct = (takerFeePct * 2.0) * 100.0
                    val netPnlPct = rawPnlPct - roundTripFeePct
                    val initialRiskPct = (trade.initialRisk / trade.entryPrice) * 100.0
                    val rMultiple = if (initialRiskPct > 0.0) netPnlPct / initialRiskPct else 0.0

                    completedTrades.add(
                        BacktestTrade(
                            symbol = trade.symbol,
                            strategyId = trade.strategyId,
                            direction = trade.direction,
                            entryBarTime = trade.entryBarTime,
                            entryPrice = trade.entryPrice,
                            exitBarTime = currentBarTime,
                            exitPrice = exitPrice,
                            exitReason = exitReason,
                            initialRisk = trade.initialRisk,
                            rawPnlPct = rawPnlPct,
                            netPnlPct = netPnlPct,
                            rMultiple = rMultiple,
                            barsHeld = trade.barsHeld
                        )
                    )
                    activeTrade = null
                }
            }

            // 3. Evaluate Strategy on Closed Bar k (if no active position and not at last bar)
            if (activeTrade == null && pendingSignal == null && k < totalBars - 1) {
                val lookback = min(series.size - offset, 400)
                val subPrimary = series.subSeries(offset, lookback)
                val barCloseTimeUtc = currentBarTime + series.interval.durationMs
                val clock = FixedClock(barCloseTimeUtc + 1000L)

                val htfMap = mutableMapOf<Interval, CandleSeries>()
                if (htf1HSeries != null) {
                    val htfIdx = findClosedIndex(htf1HSeries, barCloseTimeUtc)
                    if (htfIdx >= 0) {
                        val htfLookback = min(htf1HSeries.size - htfIdx, 200)
                        htfMap[Interval.H1] = htf1HSeries.subSeries(htfIdx, htfLookback)
                    }
                }
                if (htf4HSeries != null) {
                    val htfIdx = findClosedIndex(htf4HSeries, barCloseTimeUtc)
                    if (htfIdx >= 0) {
                        val htfLookback = min(htf4HSeries.size - htfIdx, 200)
                        htfMap[Interval.H4] = htf4HSeries.subSeries(htfIdx, htfLookback)
                    }
                }

                val ctx = SymbolContext(
                    symbol = series.symbol,
                    primarySeries = subPrimary,
                    htfSeries = htfMap,
                    clock = clock,
                    tickerLastPrice = currentClose
                )

                val evalResult = strategy.evaluate(ctx, currentState)
                currentState = evalResult.newState

                if (evalResult.signal != null) {
                    pendingSignal = evalResult.signal
                }
            }
        }

        // Close any lingering position at final bar close
        if (activeTrade != null) {
            val trade = activeTrade!!
            val finalClose = series.close(0)
            val finalTime = series.openTime(0)
            val exitPrice = when (trade.direction) {
                SignalDirection.LONG -> finalClose * (1.0 - slippagePct)
                SignalDirection.SHORT -> finalClose * (1.0 + slippagePct)
            }
            val rawPnlPct = when (trade.direction) {
                SignalDirection.LONG -> ((exitPrice - trade.entryPrice) / trade.entryPrice) * 100.0
                SignalDirection.SHORT -> ((trade.entryPrice - exitPrice) / trade.entryPrice) * 100.0
            }
            val roundTripFeePct = (takerFeePct * 2.0) * 100.0
            val netPnlPct = rawPnlPct - roundTripFeePct
            val initialRiskPct = (trade.initialRisk / trade.entryPrice) * 100.0
            val rMultiple = if (initialRiskPct > 0.0) netPnlPct / initialRiskPct else 0.0

            completedTrades.add(
                BacktestTrade(
                    symbol = trade.symbol,
                    strategyId = trade.strategyId,
                    direction = trade.direction,
                    entryBarTime = trade.entryBarTime,
                    entryPrice = trade.entryPrice,
                    exitBarTime = finalTime,
                    exitPrice = exitPrice,
                    exitReason = ExitReason.END_OF_DATA,
                    initialRisk = trade.initialRisk,
                    rawPnlPct = rawPnlPct,
                    netPnlPct = netPnlPct,
                    rMultiple = rMultiple,
                    barsHeld = trade.barsHeld
                )
            )
        }

        return BacktestSummary.calculate(
            strategyId = strategy.id,
            totalBars = totalBars - warmupBars,
            trades = completedTrades,
            barsPerYear = when (series.interval) {
                Interval.M1 -> 365.25 * 24 * 60
                Interval.M15 -> 365.25 * 24 * 4
                Interval.H1 -> 365.25 * 24
                Interval.H4 -> 365.25 * 6
                Interval.D1 -> 365.25
            }
        )
    }

    private fun findClosedIndex(htfSeries: CandleSeries, asOfTimeUtc: Long): Int {
        for (i in 0 until htfSeries.size) {
            val closeTime = htfSeries.openTime(i) + htfSeries.interval.durationMs
            if (closeTime <= asOfTimeUtc) return i
        }
        return -1
    }
}
