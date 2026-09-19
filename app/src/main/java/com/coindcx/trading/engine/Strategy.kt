package com.coindcx.trading.engine

import com.coindcx.trading.data.api.models.FuturesPosition
import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.state.StrategyState
import com.coindcx.trading.engine.telemetry.RejectionCode
import com.coindcx.trading.engine.time.TradingClock

enum class SignalDirection {
    LONG,
    SHORT
}

enum class SignalAction {
    ENTER_LONG,
    ENTER_SHORT,
    EXIT,
    HOLD
}

enum class RegimeTag {
    TREND_UP,
    TREND_DOWN,
    RANGE,
    COMPRESSION,
    EXPANSION,
    SHOCK,
    UNKNOWN
}

data class TrailSpec(
    val atrMultiplier: Double,
    val atrPeriod: Int = 14
)

sealed interface Target {
    data class Fixed(
        val tp1: Double,
        val tp2: Double? = null,
        val plannedRR: Double
    ) : Target

    data class OpenEnded(
        val trailSpec: TrailSpec
    ) : Target
}

data class StrategyDiagnostics(
    val stage: String,
    val failedFilter: String? = null,
    val indicators: Map<String, Double> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val mathDetails: Map<String, String> = emptyMap()
)

/**
 * Standardized Signal Object Contract (v2.0).
 * Conforms to §0.5 and §1.3 of the strategy specification.
 * Validates strengths normalization [0.0, 1.0] and sealed Target types.
 */
data class Signal(
    val symbol: String = "",
    val strategyId: String = "",
    val direction: SignalDirection = SignalDirection.LONG,
    val barOpenTimeUtc: Long = 0L,
    val entryRef: Double = 0.0,
    val stopLoss: Double = 0.0,
    val target: Target = Target.Fixed(0.0, null, 0.0),
    val riskDistance: Double = 0.0,
    val riskPct: Double = 0.0,
    val regimeTag: RegimeTag = RegimeTag.UNKNOWN,
    val strengths: Map<String, Double> = emptyMap(),
    val expiryBars: Int = 0,
    val primaryInterval: Interval = Interval.M15,
    val strategyName: String = "",
    val reason: String = "",
    val confidenceScore: Double = 0.0,
    val diagnostics: StrategyDiagnostics? = null,
    val tradeId: String? = null,
    val stopLossPrice: Double? = stopLoss,
    val takeProfitPrice: Double? = when (target) {
        is Target.Fixed -> target.tp1
        is Target.OpenEnded -> null
    },
    val suggestedQuantity: Double = 0.0,
    val suggestedLeverage: Int = 1,
    val fastEma: Double = 0.0,
    val slowEma: Double = 0.0,
    val prevFastEma: Double = 0.0,
    val prevSlowEma: Double = 0.0,
    val atr: Double = 0.0,
    val atrMultiplier: Double = 0.0,
    val explicitAction: SignalAction? = null
) {
    init {
        require(strengths.values.all { it in 0.0..1.0 }) {
            "All strengths values must be strictly normalized 0.0..1.0. Found: $strengths"
        }
    }

    val action: SignalAction get() = explicitAction ?: when (direction) {
        SignalDirection.LONG -> SignalAction.ENTER_LONG
        SignalDirection.SHORT -> SignalAction.ENTER_SHORT
    }

    val entryPrice: Double get() = entryRef

    val riskRewardRatio: Double get() = when (target) {
        is Target.Fixed -> target.plannedRR
        is Target.OpenEnded -> 0.0
    }

    val plannedRR: Double? get() = when (target) {
        is Target.Fixed -> target.plannedRR
        is Target.OpenEnded -> null
    }

    val openEndedTarget: Boolean get() = target is Target.OpenEnded

    /**
     * Backward-compatible secondary constructor for existing legacy strategies and tests.
     */
    constructor(
        action: SignalAction,
        entryPrice: Double = 0.0,
        stopLossPrice: Double? = null,
        takeProfitPrice: Double? = null,
        confidenceScore: Double = 0.0,
        reason: String = "",
        fastEma: Double = 0.0,
        slowEma: Double = 0.0,
        prevFastEma: Double = 0.0,
        prevSlowEma: Double = 0.0,
        atr: Double = 0.0,
        atrMultiplier: Double = 0.0,
        riskDistance: Double = 0.0,
        riskRewardRatio: Double = 0.0,
        diagnostics: StrategyDiagnostics? = null,
        suggestedQuantity: Double = 0.0,
        suggestedLeverage: Int = 1,
        tradeId: String? = null,
        strategyId: String = "",
        strategyName: String = "",
        symbol: String = "",
        barOpenTimeUtc: Long = 0L,
        regimeTag: RegimeTag = RegimeTag.UNKNOWN,
        strengths: Map<String, Double> = emptyMap(),
        expiryBars: Int = 0,
        primaryInterval: Interval = Interval.M15
    ) : this(
        symbol = symbol,
        strategyId = strategyId,
        direction = if (action == SignalAction.ENTER_SHORT) SignalDirection.SHORT else SignalDirection.LONG,
        barOpenTimeUtc = barOpenTimeUtc,
        entryRef = entryPrice,
        stopLoss = stopLossPrice ?: 0.0,
        target = Target.Fixed(takeProfitPrice ?: 0.0, null, riskRewardRatio),
        riskDistance = riskDistance,
        riskPct = if (entryPrice > 0.0) (riskDistance / entryPrice) * 100.0 else 0.0,
        regimeTag = regimeTag,
        strengths = strengths,
        expiryBars = expiryBars,
        primaryInterval = primaryInterval,
        strategyName = strategyName,
        reason = reason,
        confidenceScore = confidenceScore,
        diagnostics = diagnostics,
        tradeId = tradeId,
        stopLossPrice = stopLossPrice,
        takeProfitPrice = takeProfitPrice,
        suggestedQuantity = suggestedQuantity,
        suggestedLeverage = suggestedLeverage,
        fastEma = fastEma,
        slowEma = slowEma,
        prevFastEma = prevFastEma,
        prevSlowEma = prevSlowEma,
        atr = atr,
        atrMultiplier = atrMultiplier,
        explicitAction = action
    )
}

data class SymbolContext(
    val symbol: String,
    val primarySeries: CandleSeries,
    val htfSeries: Map<Interval, CandleSeries> = emptyMap(),
    val activePosition: FuturesPosition? = null,
    val clock: TradingClock = TradingClock.SYSTEM,
    val tickerLastPrice: Double = 0.0,
    val tickerBid: Double = 0.0,
    val tickerAsk: Double = 0.0,
    val quoteVolume24h: Double = 0.0
)

data class StrategyResult(
    val signal: Signal?,
    val newState: StrategyState? = null,
    val rejections: List<RejectionCode> = emptyList()
)

enum class MarketRegimePreference {
    ANY,
    TRENDING_MOMENTUM,
    MEAN_REVERTING_RANGE
}

/**
 * Pure, deterministic strategy interface.
 * Signal = f(SymbolContext, StrategyState?) -> StrategyResult
 */
interface Strategy {
    val id: String
    val name: String
    val description: String
    val parametersSummary: String
    val requiredCandleCount: Int
    val primaryInterval: Interval get() = Interval.fromLabel(defaultTimeframe)
    val requiredIntervals: Set<Interval> get() = setOf(primaryInterval)
    val defaultTimeframe: String get() = primaryInterval.label
    val preferredRegime: MarketRegimePreference get() = MarketRegimePreference.ANY

    /**
     * Pure institutional evaluation method.
     */
    fun evaluate(ctx: SymbolContext, state: StrategyState? = null): StrategyResult {
        // Fallback adapter for legacy strategies implementing evaluate(candles, activePosition, pair)
        val lookback = kotlin.math.min(ctx.primarySeries.size, kotlin.math.max(requiredCandleCount * 3, 300))
        val candles = (lookback - 1 downTo 0).map { i ->
            MarketCandle(
                open = ctx.primarySeries.open(i),
                high = ctx.primarySeries.high(i),
                low = ctx.primarySeries.low(i),
                close = ctx.primarySeries.close(i),
                volume = ctx.primarySeries.volume(i),
                time = ctx.primarySeries.openTime(i)
            )
        }
        val sig = evaluate(candles, ctx.activePosition, ctx.symbol)
        return StrategyResult(
            signal = if (sig.action == SignalAction.HOLD) null else sig,
            newState = state,
            rejections = if (sig.action == SignalAction.HOLD) listOf(RejectionCode.STRATEGY_SPECIFIC) else emptyList()
        )
    }

    /**
     * Backward-compatible adapter overload for existing components and tests.
     */
    fun evaluate(candles: List<MarketCandle>, activePosition: FuturesPosition?, pair: String = ""): Signal {
        val series = CandleSeries.fromApi(candles, primaryInterval, TradingClock.SYSTEM, pair)
        val ctx = SymbolContext(
            symbol = pair,
            primarySeries = series,
            activePosition = activePosition
        )
        val res = evaluate(ctx, null)
        return res.signal ?: Signal(
            symbol = pair,
            strategyId = id,
            direction = SignalDirection.LONG, // placeholder for HOLD
            barOpenTimeUtc = if (series.isNotEmpty()) series.openTime(0) else 0L,
            entryRef = if (series.isNotEmpty()) series.close(0) else 0.0,
            stopLoss = 0.0,
            target = Target.Fixed(0.0, null, 0.0),
            riskDistance = 0.0,
            riskPct = 0.0,
            regimeTag = RegimeTag.UNKNOWN,
            strengths = emptyMap(),
            expiryBars = 0,
            primaryInterval = primaryInterval,
            strategyName = name,
            reason = if (res.rejections.isNotEmpty()) "HOLD: ${res.rejections.first()}" else "HOLD: No signal",
            explicitAction = SignalAction.HOLD
        )
    }
}
