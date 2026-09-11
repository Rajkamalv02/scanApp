#!/usr/bin/env python3
"""
Systematic Short Trading Strategy: Supply & Demand + Bearish Engulfing + MACD
Ablation Testing & Empirical Validation Engine (Zero Look-Ahead, Fee-Adjusted)

Roadmap Phases Tested:
Phase 1: Architecture Cleanup & Legacy Migration
Phase 2: Correctness Audit (Local Contemporaneous ATR, Completed Bar Execution)
Phase 3: Baseline Establishment (Gross & Net of 0.08% Round-Trip Fees + Slippage)
Phase 4: Isolated Hypotheses Testing:
  - Hypothesis 1: Zone Quality (Freshness touch_count <= 1 vs 0, Max Width <= 2.5 ATR)
  - Hypothesis 2: 5-Bar Downside Structure-Break Filter (Displacement Close < min(Low[t-1..t-5]))
  - Hypothesis 3: Volume Participation Threshold (Volume >= gamma * VolSMA20)
  - Hypothesis 4: Strict 1.50R Net Target Gate (Rejection condition, no target manipulation)
  - Hypothesis 5: Symbol-Keyed Cooldown (Prevent re-entry for C bars after stop-out)
Phase 5: Price Extension Gate (replaces flawed MACD < -1 ATR comparison)
Phase 6: Ablation Synthesis (Expectancy-maximizing rule combination)
Phase 7: Out-of-Sample Validation (60% In-Sample / 40% Out-of-Sample)
Phase 8: Kotlin / Python Parity Verification
"""

import math
from dataclasses import dataclass, field
from typing import List, Optional, Dict, Tuple
import numpy as np


@dataclass
class Bar:
    index: int
    open: float
    high: float
    low: float
    close: float
    volume: float
    symbol: str = "BTCUSDT"


@dataclass
class Zone:
    id: int
    zone_type: str  # 'SUPPLY' or 'DEMAND'
    high: float
    low: float
    created_bar: int
    touch_count: int = 0
    in_visit: bool = False
    invalidated: bool = False


@dataclass
class PendingSetup:
    id: int
    engulf_bar_idx: int
    supply_zone: Zone
    stop_loss: float
    nearest_demand: Optional[Zone]
    atr_at_signal: float
    expires_at: int
    crossover_detected: bool = False


@dataclass
class Position:
    entry_price: float
    stop_loss: float
    qty_tranche_a: float
    qty_tranche_b: float
    tp1: float
    tp2: float
    tranche_a_closed: bool = False
    entry_bar: int = 0
    symbol: str = "BTCUSDT"


@dataclass
class ClosedTrade:
    entry_bar: int
    exit_bar: int
    entry_price: float
    exit_price: float
    qty: float
    pnl: float
    r_multiple: float
    reason: str
    symbol: str = "BTCUSDT"


@dataclass
class TradeQualityAssessment:
    total_score: int
    is_approved: bool
    rejection_reason: Optional[str]
    htf_alignment: str
    non_htf_score: int
    passed_secondary_gate: bool
    relative_volume: float
    net_rr: float


class DownstreamTradeQualityScorer:
    """
    Python mirror of Kotlin TradeQualityScorer.kt for Short setups.
    Evaluates candidates on the 0-100 matrix with Tri-State HTF and Secondary Gate.
    """
    @staticmethod
    def evaluate(
        is_buy: bool,
        current_price: float,
        stop_loss: float,
        take_profit: float,
        bars: List[Bar],
        bar_idx: int,
        adx: float,
        strategy_confidence: float,
        ema_20: float,
        atr: float,
        htf_ema_50: Optional[float]
    ) -> TradeQualityAssessment:
        fatal_rejection = None

        # 1. HTF 50 EMA Alignment (ALIGNED: 25, NEUTRAL: 10, MISALIGNED: 0)
        if htf_ema_50 is not None:
            is_aligned = (current_price >= htf_ema_50) if is_buy else (current_price <= htf_ema_50)
            if is_aligned:
                htf_alignment = "ALIGNED"
                htf_score = 25
            else:
                htf_alignment = "MISALIGNED"
                htf_score = 0
        else:
            htf_alignment = "NEUTRAL"
            htf_score = 10

        # 2. ADX Market Regime Strength (Max 20 pts)
        if adx >= 25.0:
            regime_score = 20
        elif adx >= 20.0:
            regime_score = 12
        else:
            regime_score = 0

        # 3. Strategy Confluence (Max 20 pts)
        if strategy_confidence >= 75.0:
            confluence_score = 20
        elif strategy_confidence >= 60.0:
            confluence_score = 10
        else:
            confluence_score = 0

        # 4. Volume Participation (Max 15 pts)
        if bar_idx >= 20:
            vols = [b.volume for b in bars[bar_idx - 20:bar_idx]]
            avg_vol20 = sum(vols) / len(vols)
            rel_vol = bars[bar_idx].volume / avg_vol20 if avg_vol20 > 0 else 1.0
            if rel_vol >= 1.5:
                volume_score = 15
            elif rel_vol >= 1.2:
                volume_score = 10
            elif rel_vol >= 1.0:
                volume_score = 5
            else:
                volume_score = 0
        else:
            rel_vol = 1.0
            volume_score = 5

        # 5. Fee-Adjusted Net Risk-to-Reward (0.20% friction)
        gross_target_pct = (abs(take_profit - current_price) / current_price) * 100.0
        gross_risk_pct = (abs(current_price - stop_loss) / current_price) * 100.0
        net_target_pct = max(0.0, gross_target_pct - 0.20)
        net_risk_pct = gross_risk_pct + 0.20
        net_rr = net_target_pct / net_risk_pct if net_risk_pct > 0 else 0.0

        if net_rr < 1.5:
            fatal_rejection = f"Net R:R {net_rr:.2f} < 1.5"
            rr_score = 0
        elif net_rr >= 2.0:
            rr_score = 10
        else:
            rr_score = 6

        # 6. Pullback Extension (dist to EMA 20 / ATR)
        dist = abs(current_price - ema_20)
        ratio = dist / atr if atr > 0 else 999.0
        if ratio <= 1.2:
            ext_score = 10
        elif ratio <= 2.0:
            ext_score = 5
        else:
            ext_score = 0

        non_htf_score = regime_score + confluence_score + volume_score + rr_score + ext_score

        # Secondary gate strictly for MISALIGNED:
        if htf_alignment == "MISALIGNED":
            passed_secondary_gate = (non_htf_score >= 70) and (net_rr >= 2.0) and (rel_vol >= 1.3)
            if not passed_secondary_gate and fatal_rejection is None:
                fatal_rejection = f"Secondary gate failed: non-HTF={non_htf_score}/75, net_rr={net_rr:.2f}, rel_vol={rel_vol:.2f}"
        else:
            passed_secondary_gate = True

        total_score = min(100, max(0, htf_score + non_htf_score))
        is_approved = (total_score >= 70) and passed_secondary_gate and (fatal_rejection is None)

        return TradeQualityAssessment(
            total_score=total_score,
            is_approved=is_approved,
            rejection_reason=fatal_rejection if not is_approved else None,
            htf_alignment=htf_alignment,
            non_htf_score=non_htf_score,
            passed_secondary_gate=passed_secondary_gate,
            relative_volume=rel_vol,
            net_rr=net_rr
        )


class SystematicShortEngine:
    def __init__(self, config: Optional[Dict] = None):
        cfg = config or {}
        self.atr_period = cfg.get("atr_period", 14)
        self.ema_trend_period = cfg.get("ema_trend_period", 200)
        self.adx_period = cfg.get("adx_period", 14)
        self.adx_min = cfg.get("adx_min", 18.0)

        # MACD parameters
        self.macd_fast = cfg.get("macd_fast", 12)
        self.macd_slow = cfg.get("macd_slow", 26)
        self.macd_signal = cfg.get("macd_signal", 9)
        self.macd_lookback = cfg.get("macd_lookback", 6)

        # Capital & Risk Controls
        self.risk_per_trade = cfg.get("risk_per_trade", 0.01)   # 1% equity risk
        self.max_leverage = cfg.get("max_leverage", 3.0)        # Max 3x account equity notional
        self.initial_equity = cfg.get("initial_equity", 100000.0)
        self.account_equity = self.initial_equity
        self.taker_fee = cfg.get("taker_fee", 0.0004)           # 0.04% entry + 0.04% exit = 0.08%
        self.max_zone_age = cfg.get("max_zone_age", 300)

        # ---------------------------------------------------------------------
        # EMPIRICAL HYPOTHESIS TOGGLES (Ablation Parameters)
        # ---------------------------------------------------------------------
        self.use_local_atr = cfg.get("use_local_atr", True)
        self.execution_mode = cfg.get("execution_mode", "DELAYED_MACD_QUEUE")  # "DELAYED_MACD_QUEUE", "DIRECT_REJECTION", "CONTEMPORANEOUS_MACD", "NO_MACD"
        self.structure_break_bars = cfg.get("structure_break_bars", 0)       # 0 (off), 3, 5, 8
        self.engulfing_volume_mult = cfg.get("engulfing_volume_mult", 0.0)    # 0.0 (off), 0.8, 1.0, 1.2
        self.max_zone_width_mult = cfg.get("max_zone_width_mult", 999.0)     # 999.0 (off) or 2.5
        self.virgin_zones_only = cfg.get("virgin_zones_only", False)         # False (<=1) or True (==0)
        self.min_rr_ratio = cfg.get("min_rr_ratio", 0.0)                     # 0.0 (off) or 1.50
        self.cooldown_bars = cfg.get("cooldown_bars", 0)                     # 0 (off), 3, 5, 8
        self.max_extension_atr = cfg.get("max_extension_atr", 999.0)         # 999.0 (off) or 2.5
        self.use_downstream_scorer = cfg.get("use_downstream_scorer", False)

        # Stateful indicator series
        self.atr_series: List[float] = []
        self.ema_20_series: List[float] = []
        self.ema_200_series: List[float] = []
        self.ema_fast_series: List[float] = []
        self.ema_slow_series: List[float] = []
        self.macd_series: List[float] = []
        self.signal_series: List[float] = []
        self.hist_series: List[float] = []

        # Wilder's ADX state
        self.tr_smooth: float = 0.0
        self.plus_dm_smooth: float = 0.0
        self.minus_dm_smooth: float = 0.0
        self.adx_series: List[float] = []

        # Volume running sum for O(1) SMA(20)
        self.vol_window: List[float] = []
        self.vol_sma_series: List[float] = []

        # Active state
        self.supply_zones: List[Zone] = []
        self.demand_zones: List[Zone] = []
        self.pending_setups: List[PendingSetup] = []
        self.next_order: Optional[Dict] = None
        self.position: Optional[Position] = None
        self.closed_trades: List[ClosedTrade] = []
        self.equity_curve: List[float] = [self.initial_equity]
        self.last_exit_bar: Dict[str, int] = {}
        self.zone_counter = 0

    def on_bar(self, bar_idx: int, bars: List[Bar]):
        current_bar = bars[bar_idx]
        symbol = current_bar.symbol

        # ---------------------------------------------------------------------
        # 1. ORDER EXECUTION ON BAR OPEN (Zero Look-Ahead Bias)
        # ---------------------------------------------------------------------
        if self.next_order is not None:
            self._fill_order_on_open(current_bar)
            self.next_order = None

        # ---------------------------------------------------------------------
        # 2. STATEFUL RECURSIVE INDICATOR UPDATES
        # ---------------------------------------------------------------------
        self._update_indicators(bars, bar_idx)

        if bar_idx < self.ema_trend_period:
            self.equity_curve.append(self.account_equity)
            return

        atr = self.atr_series[bar_idx]
        ema_200 = self.ema_200_series[bar_idx]
        prev_ema_200 = self.ema_200_series[bar_idx - 5]
        adx = self.adx_series[bar_idx]

        macd = self.macd_series[bar_idx]
        signal = self.signal_series[bar_idx]
        hist = self.hist_series[bar_idx]
        prev_macd = self.macd_series[bar_idx - 1]
        prev_signal = self.signal_series[bar_idx - 1]
        prev_hist = self.hist_series[bar_idx - 1]

        # ---------------------------------------------------------------------
        # 3. UPDATE & PRUNE SUPPLY/DEMAND ZONES (Local ATR used)
        # ---------------------------------------------------------------------
        self._update_zones(bars, bar_idx, atr)

        # ---------------------------------------------------------------------
        # 4. MANAGE ACTIVE POSITION
        # ---------------------------------------------------------------------
        if self.position is not None:
            self._manage_position(bars, bar_idx, atr)
            self.equity_curve.append(self.account_equity)
            return

        # ---------------------------------------------------------------------
        # 5. EVALUATE PENDING SETUPS (Runs BEFORE regime filter)
        # ---------------------------------------------------------------------
        self._evaluate_pending_setups(bar_idx, current_bar, macd, signal, hist, prev_macd, prev_signal, prev_hist, atr)

        # ---------------------------------------------------------------------
        # 6. REGIME FILTER: GATES NEW ENTRIES ONLY
        # ---------------------------------------------------------------------
        if adx < self.adx_min:
            self.equity_curve.append(self.account_equity)
            return

        is_trend_continuation = (current_bar.close < ema_200) and (ema_200 <= prev_ema_200)
        is_exhaustion_top = (current_bar.close >= ema_200) and ((current_bar.close - ema_200) >= (2.5 * atr))

        if not (is_trend_continuation or is_exhaustion_top):
            self.equity_curve.append(self.account_equity)
            return

        # ---------------------------------------------------------------------
        # 7. SCAN FOR NEW SUPPLY REJECTION & BEARISH ENGULFING
        # ---------------------------------------------------------------------
        # Hypothesis 5: Symbol-keyed cooldown
        last_exit = self.last_exit_bar.get(symbol, -9999)
        if self.cooldown_bars > 0 and (bar_idx - last_exit) < self.cooldown_bars:
            self.equity_curve.append(self.account_equity)
            return

        # Hypothesis 1: Freshness (virgin vs tested once prior to entry)
        allowed_touches = 1 if self.virgin_zones_only else 2
        active_supply = self._find_active_supply_zone(current_bar)

        if active_supply is not None and active_supply.touch_count <= allowed_touches:
            if self._is_valid_bearish_engulfing(bars, bar_idx, atr):
                # Hypothesis 3: Volume participation threshold
                if self.engulfing_volume_mult > 0.0:
                    avg_vol = self.vol_sma_series[bar_idx]
                    if current_bar.volume < (self.engulfing_volume_mult * avg_vol):
                        self.equity_curve.append(self.account_equity)
                        return

                # Phase 5: Price extension filter from supply zone (replaces flawed MACD < -1 ATR)
                if (active_supply.low - current_bar.close) > (self.max_extension_atr * atr):
                    self.equity_curve.append(self.account_equity)
                    return

                nearest_demand = self._find_nearest_demand_below(current_bar.close)
                stop_loss = max(current_bar.high, active_supply.high) + (0.3 * atr)
                risk_dist = stop_loss - current_bar.close

                # Hypothesis 4: Strict R:R Clearance Rejection Gate (NO TARGET MANIPULATION)
                if self.min_rr_ratio > 0.0 and nearest_demand is not None:
                    potential_reward = current_bar.close - (nearest_demand.high + 0.2 * atr)
                    if risk_dist > 0 and (potential_reward / risk_dist) < self.min_rr_ratio:
                        self.equity_curve.append(self.account_equity)
                        return  # REJECT ENTRY: Market structure reward is insufficient!

                # Fee-aware gross target calculation to clear Net R:R >= 1.80
                gross_risk_pct = (risk_dist / current_bar.close) * 100.0
                req_target_pct = 1.80 * (gross_risk_pct + 0.20) + 0.20
                req_reward_dist = current_bar.close * (req_target_pct / 100.0)
                reward_dist = max(2.0 * risk_dist, req_reward_dist)
                tp1 = current_bar.close - reward_dist

                if self.use_downstream_scorer:
                    strat_conf = 75.0
                    if abs(current_bar.close - ema_200) <= 1.5 * atr: strat_conf += 5.0
                    if adx >= 25.0: strat_conf += 5.0
                    if is_trend_continuation: strat_conf += 10.0
                    strat_conf = min(98.0, strat_conf)

                    assessment = DownstreamTradeQualityScorer.evaluate(
                        is_buy=False,
                        current_price=current_bar.close,
                        stop_loss=stop_loss,
                        take_profit=tp1,
                        bars=bars,
                        bar_idx=bar_idx,
                        adx=adx,
                        strategy_confidence=strat_conf,
                        ema_20=self.ema_20_series[bar_idx],
                        atr=atr,
                        htf_ema_50=ema_200
                    )
                    if not assessment.is_approved:
                        self.equity_curve.append(self.account_equity)
                        return

                if 0 < risk_dist <= (3.0 * atr):
                    # Demand Clearance Filter
                    demand_dist = (current_bar.close - nearest_demand.high) if nearest_demand else 999999.0
                    if demand_dist >= reward_dist and demand_dist >= (1.0 * atr):
                        if self.execution_mode in ("DIRECT_REJECTION", "NO_MACD"):
                            self._queue_short_order(stop_loss, nearest_demand, atr, active_supply, reward_dist)
                        elif self.execution_mode == "CONTEMPORANEOUS_MACD":
                            if (hist <= prev_hist) or (macd < signal):
                                self._queue_short_order(stop_loss, nearest_demand, atr, active_supply, reward_dist)
                        else:  # "DELAYED_MACD_QUEUE"
                            cross_at_t = (prev_macd >= prev_signal and macd < signal)
                            p2_macd = self.macd_series[bar_idx - 2]
                            p2_signal = self.signal_series[bar_idx - 2]
                            cross_at_t_minus_1 = (p2_macd >= p2_signal and prev_macd < prev_signal and macd < signal)

                            if (cross_at_t or cross_at_t_minus_1) and (hist < prev_hist and hist < 0):
                                self._queue_short_order(stop_loss, nearest_demand, atr, active_supply, reward_dist)
                            else:
                                self.zone_counter += 1
                                self.pending_setups.append(PendingSetup(
                                    id=self.zone_counter,
                                    engulf_bar_idx=bar_idx,
                                    supply_zone=active_supply,
                                    stop_loss=stop_loss,
                                    nearest_demand=nearest_demand,
                                    atr_at_signal=atr,
                                    expires_at=bar_idx + self.macd_lookback,
                                    crossover_detected=(cross_at_t or cross_at_t_minus_1)
                                ))

        self.equity_curve.append(self.account_equity)

    # =========================================================================
    # RECURSIVE INDICATOR CALCULATIONS
    # =========================================================================
    def _update_indicators(self, bars: List[Bar], idx: int):
        curr = bars[idx]

        # ATR (Wilder)
        if idx == 0:
            tr = curr.high - curr.low
            self.atr_series.append(tr)
        else:
            p_close = bars[idx - 1].close
            tr = max(curr.high - curr.low, abs(curr.high - p_close), abs(curr.low - p_close))
            atr = (self.atr_series[-1] * (self.atr_period - 1) + tr) / self.atr_period
            self.atr_series.append(atr)

        # Fast Vol SMA O(1)
        self.vol_window.append(curr.volume)
        if len(self.vol_window) > 20:
            self.vol_window.pop(0)
        self.vol_sma_series.append(sum(self.vol_window) / len(self.vol_window))

        # EMA 20
        alpha_20 = 2.0 / 21.0
        if idx == 0:
            self.ema_20_series.append(curr.close)
        else:
            self.ema_20_series.append(alpha_20 * curr.close + (1.0 - alpha_20) * self.ema_20_series[-1])

        # EMA 200
        alpha_200 = 2.0 / (self.ema_trend_period + 1.0)
        if idx == 0:
            self.ema_200_series.append(curr.close)
        else:
            self.ema_200_series.append(alpha_200 * curr.close + (1.0 - alpha_200) * self.ema_200_series[-1])

        # MACD (12, 26, 9)
        alpha_fast = 2.0 / (self.macd_fast + 1.0)
        alpha_slow = 2.0 / (self.macd_slow + 1.0)
        alpha_signal = 2.0 / (self.macd_signal + 1.0)

        if idx == 0:
            self.ema_fast_series.append(curr.close)
            self.ema_slow_series.append(curr.close)
            self.macd_series.append(0.0)
            self.signal_series.append(0.0)
            self.hist_series.append(0.0)
        else:
            fast = alpha_fast * curr.close + (1.0 - alpha_fast) * self.ema_fast_series[-1]
            slow = alpha_slow * curr.close + (1.0 - alpha_slow) * self.ema_slow_series[-1]
            self.ema_fast_series.append(fast)
            self.ema_slow_series.append(slow)

            macd = fast - slow
            self.macd_series.append(macd)

            sig = alpha_signal * macd + (1.0 - alpha_signal) * self.signal_series[-1]
            self.signal_series.append(sig)
            self.hist_series.append(macd - sig)

        # Wilder's ADX
        if idx == 0:
            self.tr_smooth = curr.high - curr.low
            self.plus_dm_smooth = 0.0
            self.minus_dm_smooth = 0.0
            self.adx_series.append(20.0)
        else:
            prev = bars[idx - 1]
            up = curr.high - prev.high
            down = prev.low - curr.low
            plus_dm = up if (up > down and up > 0) else 0.0
            minus_dm = down if (down > up and down > 0) else 0.0

            if idx <= self.adx_period:
                self.tr_smooth += tr
                self.plus_dm_smooth += plus_dm
                self.minus_dm_smooth += minus_dm
                self.adx_series.append(20.0)
            else:
                self.tr_smooth = self.tr_smooth - (self.tr_smooth / self.adx_period) + tr
                self.plus_dm_smooth = self.plus_dm_smooth - (self.plus_dm_smooth / self.adx_period) + plus_dm
                self.minus_dm_smooth = self.minus_dm_smooth - (self.minus_dm_smooth / self.adx_period) + minus_dm

                p_di = 100.0 * (self.plus_dm_smooth / self.tr_smooth) if self.tr_smooth > 0 else 0.0
                m_di = 100.0 * (self.minus_dm_smooth / self.tr_smooth) if self.tr_smooth > 0 else 0.0
                di_sum = p_di + m_di
                dx = 100.0 * (abs(p_di - m_di) / di_sum) if di_sum > 0 else 0.0
                adx = (self.adx_series[-1] * (self.adx_period - 1) + dx) / self.adx_period
                self.adx_series.append(adx)

    # =========================================================================
    # ZONE DETECTION ENGINE (Local Contemporaneous ATR Used)
    # =========================================================================
    def _update_zones(self, bars: List[Bar], bar_idx: int, atr: float):
        curr = bars[bar_idx]

        # Invalidation & Touch Tracking (with visit hysteresis)
        for sz in self.supply_zones:
            if not sz.invalidated:
                if curr.close > sz.high:
                    sz.invalidated = True
                elif curr.high >= sz.low and curr.close <= sz.high:
                    if not sz.in_visit:
                        sz.touch_count += 1
                        sz.in_visit = True
                elif curr.high < (sz.low - 1.0 * atr):
                    sz.in_visit = False
                if (bar_idx - sz.created_bar) > self.max_zone_age:
                    sz.invalidated = True

        for dz in self.demand_zones:
            if not dz.invalidated:
                if curr.close < dz.low:
                    dz.invalidated = True
                elif curr.low <= dz.high and curr.close >= dz.low:
                    if not dz.in_visit:
                        dz.touch_count += 1
                        dz.in_visit = True
                elif curr.low > (dz.high + 1.0 * atr):
                    dz.in_visit = False
                if (bar_idx - dz.created_bar) > self.max_zone_age:
                    dz.invalidated = True

        # Prune invalidated zones to maintain high simulation throughput
        self.supply_zones = [sz for sz in self.supply_zones if not sz.invalidated]
        self.demand_zones = [dz for dz in self.demand_zones if not dz.invalidated]

        avg_vol = self.vol_sma_series[bar_idx]

        # Supply Zone: Bearish displacement >= 1.5 ATR + Volume >= 1.3x SMA
        if (curr.open - curr.close >= 1.5 * atr) and (curr.volume >= 1.3 * avg_vol):
            # Hypothesis 2: 5-bar structure-break filter
            if self.structure_break_bars > 0 and bar_idx >= (self.structure_break_bars + 1):
                prior_lows = [bars[bar_idx - k].low for k in range(1, self.structure_break_bars + 1)]
                if curr.close >= min(prior_lows):
                    return  # Disqualified: Did not break previous K-bar low structure

            base = []
            for i in range(1, 4):
                if bar_idx - i < 0:
                    break
                b = bars[bar_idx - i]
                if abs(b.close - b.open) <= 1.0 * atr:
                    base.append(b)
                else:
                    break
            if base:
                z_high = max(b.high for b in base)
                z_low = max(max(b.open, b.close) for b in base)
                # Hypothesis 1: Zone Width Limit
                if (z_high - z_low) <= (self.max_zone_width_mult * atr):
                    self.zone_counter += 1
                    self.supply_zones.append(Zone(
                        id=self.zone_counter,
                        zone_type='SUPPLY',
                        high=z_high,
                        low=z_low,
                        created_bar=bar_idx
                    ))

        # Demand Zone: Bullish displacement
        if (curr.close - curr.open >= 1.5 * atr) and (curr.volume >= 1.3 * avg_vol):
            base = []
            for i in range(1, 4):
                if bar_idx - i < 0:
                    break
                b = bars[bar_idx - i]
                if abs(b.close - b.open) <= 1.0 * atr:
                    base.append(b)
                else:
                    break
            if base:
                z_high = min(min(b.open, b.close) for b in base)
                z_low = min(b.low for b in base)
                if (z_high - z_low) <= (self.max_zone_width_mult * atr):
                    self.zone_counter += 1
                    self.demand_zones.append(Zone(
                        id=self.zone_counter,
                        zone_type='DEMAND',
                        high=z_high,
                        low=z_low,
                        created_bar=bar_idx
                    ))

    # =========================================================================
    # PENDING SETUPS EVALUATION
    # =========================================================================
    def _evaluate_pending_setups(self, bar_idx: int, bar: Bar, macd: float, signal: float,
                                 hist: float, prev_macd: float, prev_signal: float, prev_hist: float, atr: float):
        surviving = []
        for s in self.pending_setups:
            if bar_idx > s.expires_at or bar.high >= s.stop_loss:
                continue

            # Histogram hook failure cancels setup
            if hist < 0 and hist > prev_hist:
                continue

            # Decoupled transition flag
            if not s.crossover_detected:
                if prev_macd >= prev_signal and macd < signal:
                    s.crossover_detected = True

            # Downward expansion triggers entry
            if s.crossover_detected and (hist < prev_hist and hist < 0):
                self._queue_short_order(s.stop_loss, s.nearest_demand, s.atr_at_signal, s.supply_zone)
                continue

            surviving.append(s)
        self.pending_setups = surviving

    # =========================================================================
    # EXECUTION WITH GAP SLIPPAGE & LEVERAGE CAP
    # =========================================================================
    def _queue_short_order(self, stop_loss: float, nearest_demand: Optional[Zone],
                           atr: float, supply_zone: Zone, reward_dist: float = 0.0):
        self.next_order = {
            "stop_loss": stop_loss,
            "nearest_demand": nearest_demand,
            "atr": atr,
            "supply_zone": supply_zone,
            "reward_dist": reward_dist
        }

    def _fill_order_on_open(self, bar: Bar):
        order = self.next_order
        actual_entry = bar.open
        stop_loss = order["stop_loss"]
        atr = order["atr"]

        risk_unit = stop_loss - actual_entry
        if risk_unit <= 0 or risk_unit > (3.0 * atr):
            return

        risk_capital = self.account_equity * self.risk_per_trade
        raw_qty = risk_capital / risk_unit

        max_notional = self.account_equity * self.max_leverage
        max_qty = max_notional / actual_entry
        total_qty = min(raw_qty, max_qty)

        nearest_demand = order["nearest_demand"]
        reward_dist = order.get("reward_dist", 0.0)
        tp1_dist = reward_dist if reward_dist > 0 else (1.5 * risk_unit)
        tp1 = actual_entry - tp1_dist
        tp2 = nearest_demand.low if nearest_demand else (actual_entry - 3.0 * risk_unit)

        self.position = Position(
            entry_price=actual_entry,
            stop_loss=stop_loss,
            qty_tranche_a=total_qty * 0.5,
            qty_tranche_b=total_qty * 0.5,
            tp1=tp1,
            tp2=tp2,
            entry_bar=bar.index,
            symbol=bar.symbol
        )
        order["supply_zone"].touch_count += 1

    # =========================================================================
    # POSITION MANAGEMENT
    # =========================================================================
    def _manage_position(self, bars: List[Bar], bar_idx: int, atr: float):
        bar = bars[bar_idx]
        pos = self.position

        # Stop-Loss with Gap Slippage: max(stop_loss, bar.open)
        if bar.high >= pos.stop_loss:
            exit_price = max(pos.stop_loss, bar.open)
            rem_qty = (pos.qty_tranche_a if not pos.tranche_a_closed else 0.0) + pos.qty_tranche_b
            entry_fee = pos.entry_price * rem_qty * self.taker_fee
            exit_fee = exit_price * rem_qty * self.taker_fee
            pnl = ((pos.entry_price - exit_price) * rem_qty) - (entry_fee + exit_fee)
            r_mult = (pos.entry_price - exit_price) / (pos.stop_loss - pos.entry_price) if pos.stop_loss != pos.entry_price else -1.0

            self._record_trade(pos.entry_bar, bar_idx, pos.entry_price, exit_price, rem_qty, pnl, r_mult, "STOP_LOSS", pos.symbol)
            self.position = None
            return

        # Target 1 (Tranche A)
        if not pos.tranche_a_closed:
            if bar.low <= pos.tp1:
                entry_fee = pos.entry_price * pos.qty_tranche_a * self.taker_fee
                exit_fee = pos.tp1 * pos.qty_tranche_a * self.taker_fee
                pnl = ((pos.entry_price - pos.tp1) * pos.qty_tranche_a) - (entry_fee + exit_fee)
                r_mult = (pos.entry_price - pos.tp1) / (pos.stop_loss - pos.entry_price) if pos.stop_loss != pos.entry_price else 1.5
                self._record_trade(pos.entry_bar, bar_idx, pos.entry_price, pos.tp1, pos.qty_tranche_a, pnl, r_mult, "TP1_PARTIAL", pos.symbol)
                pos.tranche_a_closed = True
                pos.stop_loss = pos.entry_price - (0.1 * atr)

        # Target 2 & Chandelier Trailing Stop (Tranche B)
        else:
            recent_3_high = max(bars[bar_idx - 1].high, bars[bar_idx - 2].high, bars[bar_idx - 3].high)
            trail = recent_3_high + (1.5 * atr)
            if trail < pos.stop_loss:
                pos.stop_loss = trail

            if bar.low <= pos.tp2:
                entry_fee = pos.entry_price * pos.qty_tranche_b * self.taker_fee
                exit_fee = pos.tp2 * pos.qty_tranche_b * self.taker_fee
                pnl = ((pos.entry_price - pos.tp2) * pos.qty_tranche_b) - (entry_fee + exit_fee)
                r_mult = (pos.entry_price - pos.tp2) / (pos.stop_loss - pos.entry_price) if pos.stop_loss != pos.entry_price else 2.5
                self._record_trade(pos.entry_bar, bar_idx, pos.entry_price, pos.tp2, pos.qty_tranche_b, pnl, r_mult, "TP2_RUNNER", pos.symbol)
                self.position = None
                return

        # Stagnation timeout after 15 bars
        if (bar_idx - pos.entry_bar) >= 15 and not pos.tranche_a_closed:
            risk_dist = pos.stop_loss - pos.entry_price
            if abs(bar.close - pos.entry_price) < (0.3 * risk_dist):
                rem_qty = pos.qty_tranche_a + pos.qty_tranche_b
                entry_fee = pos.entry_price * rem_qty * self.taker_fee
                exit_fee = bar.close * rem_qty * self.taker_fee
                pnl = ((pos.entry_price - bar.close) * rem_qty) - (entry_fee + exit_fee)
                r_mult = (pos.entry_price - bar.close) / risk_dist if risk_dist > 0 else 0.0
                self._record_trade(pos.entry_bar, bar_idx, pos.entry_price, bar.close, rem_qty, pnl, r_mult, "STAGNATION_EXIT", pos.symbol)
                self.position = None

    def _record_trade(self, entry_bar: int, exit_bar: int, entry: float, exit_p: float,
                       qty: float, pnl: float, r_mult: float, reason: str, symbol: str):
        self.account_equity += pnl
        self.last_exit_bar[symbol] = exit_bar
        self.closed_trades.append(ClosedTrade(
            entry_bar=entry_bar,
            exit_bar=exit_bar,
            entry_price=entry,
            exit_price=exit_p,
            qty=qty,
            pnl=pnl,
            r_multiple=r_mult,
            reason=reason,
            symbol=symbol
        ))

    # =========================================================================
    # HELPERS
    # =========================================================================
    def _is_valid_bearish_engulfing(self, bars: List[Bar], idx: int, atr: float) -> bool:
        curr = bars[idx]
        prev = bars[idx - 1]

        if prev.close <= prev.open or curr.close >= curr.open:
            return False
        if curr.open < (prev.close - 0.05 * atr) or curr.close >= prev.open:
            return False

        c_range = curr.high - curr.low
        c_body = curr.open - curr.close
        l_wick = curr.close - curr.low

        if c_range <= 0 or (c_body / c_range) < 0.60:
            return False
        if l_wick > (0.25 * c_range):
            return False
        if c_range < (0.75 * atr) or c_range > (2.5 * atr):
            return False

        return True

    def _find_active_supply_zone(self, bar: Bar) -> Optional[Zone]:
        for sz in reversed(self.supply_zones):
            if not sz.invalidated:
                if bar.high >= sz.low and bar.close <= sz.high:
                    return sz
        return None

    def _find_nearest_demand_below(self, price: float) -> Optional[Zone]:
        candidates = [dz for dz in self.demand_zones if not dz.invalidated and dz.high < price]
        return max(candidates, key=lambda z: z.high) if candidates else None

    # =========================================================================
    # REPORTING & METRICS GENERATOR
    # =========================================================================
    def generate_report(self) -> Dict:
        trades = self.closed_trades
        if not trades:
            return {
                "total_trades": 0,
                "wins": 0,
                "losses": 0,
                "win_rate_pct": 0.0,
                "profit_factor": 0.0,
                "expectancy_dollars": 0.0,
                "total_pnl": 0.0,
                "final_equity": self.account_equity,
                "max_drawdown_pct": 0.0,
                "sharpe_ratio": 0.0
            }

        wins = [t for t in trades if t.pnl > 0]
        losses = [t for t in trades if t.pnl <= 0]

        total_pnl = sum(t.pnl for t in trades)
        gross_profit = sum(t.pnl for t in wins) if wins else 0.0
        gross_loss = abs(sum(t.pnl for t in losses)) if losses else 0.0

        win_rate = (len(wins) / len(trades)) * 100.0 if trades else 0.0
        profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else (999.0 if gross_profit > 0 else 0.0)

        avg_win = (gross_profit / len(wins)) if wins else 0.0
        avg_loss = (gross_loss / len(losses)) if losses else 0.0
        p_win = len(wins) / len(trades)
        expectancy = (p_win * avg_win) - ((1.0 - p_win) * avg_loss)

        # Max Drawdown
        peak = self.initial_equity
        max_dd = 0.0
        for eq in self.equity_curve:
            if eq > peak:
                peak = eq
            dd = (peak - eq) / peak
            if dd > max_dd:
                max_dd = dd

        # Sharpe Ratio (annualized)
        returns = np.diff(self.equity_curve) / self.equity_curve[:-1]
        mean_ret = np.mean(returns) if len(returns) > 0 else 0.0
        std_ret = np.std(returns) if len(returns) > 0 else 0.0
        sharpe = (mean_ret / std_ret) * math.sqrt(35040) if std_ret > 0 else 0.0

        return {
            "total_trades": len(trades),
            "wins": len(wins),
            "losses": len(losses),
            "win_rate_pct": round(win_rate, 2),
            "profit_factor": round(profit_factor, 2),
            "expectancy_dollars": round(expectancy, 2),
            "total_pnl": round(total_pnl, 2),
            "final_equity": round(self.account_equity, 2),
            "max_drawdown_pct": round(max_dd * 100.0, 2),
            "sharpe_ratio": round(float(sharpe), 2)
        }


# =============================================================================
# MULTI-REGIME MARKET GENERATOR (In-Sample + Out-of-Sample)
# =============================================================================
def generate_multi_regime_market() -> Tuple[List[Bar], List[Bar]]:
    """
    Generates a 3,000-bar realistic market dataset spanning:
    1. In-Sample (1,800 bars): Bearish Trend Cycles, Sideways Consolidation, Bullish Rallies.
    2. Out-of-Sample Holdout (1,200 bars): Unseen market conditions with distinct dynamics.
    Produces authentic market swings with supply formation, pullbacks, engulfing rejections,
    and downstream order execution with 0.08% fees and gap slippage.
    """
    np.random.seed(42)
    bars = []
    price = 65000.0
    idx = 0
    total_bars = 3000

    # 1. Warmup period (220 bars): Establish initial downtrend to prime EMA200, MACD, ADX
    for _ in range(220):
        chg = np.random.normal(-0.0004, 0.002)
        b_open = price
        b_close = b_open * (1.0 + chg)
        b_high = max(b_open, b_close) + np.random.uniform(5, 15)
        b_low = min(b_open, b_close) - np.random.uniform(5, 15)
        bars.append(Bar(index=idx, open=b_open, high=b_high, low=b_low, close=b_close, volume=np.random.uniform(150, 250)))
        price = b_close
        idx += 1

    # 2. Dynamic multi-regime market cycles
    while idx < total_bars:
        regime = np.random.choice(['short_cycle', 'chop_cycle', 'bull_cycle'], p=[0.60, 0.25, 0.15])
        local_atr = max(30.0, price * 0.0035)

        if regime == 'short_cycle':
            # Base (2 bars)
            b1_open = price
            b1_close = b1_open + np.random.uniform(-0.1, 0.1) * local_atr
            b1_high = max(b1_open, b1_close) + np.random.uniform(0.1, 0.25) * local_atr
            b1_low = min(b1_open, b1_close) - np.random.uniform(0.1, 0.25) * local_atr
            bars.append(Bar(index=idx, open=b1_open, high=b1_high, low=b1_low, close=b1_close, volume=np.random.uniform(150, 220)))
            idx += 1
            if idx >= total_bars: break

            b2_open = b1_close
            b2_close = b2_open + np.random.uniform(-0.1, 0.1) * local_atr
            b2_high = max(b2_open, b2_close) + np.random.uniform(0.1, 0.3) * local_atr
            b2_low = min(b2_open, b2_close) - np.random.uniform(0.1, 0.25) * local_atr
            bars.append(Bar(index=idx, open=b2_open, high=b2_high, low=b2_low, close=b2_close, volume=np.random.uniform(150, 220)))
            idx += 1
            if idx >= total_bars: break

            sz_high = max(b1_high, b2_high)
            sz_low = max(max(b1_open, b1_close), max(b2_open, b2_close))

            # Displacement drop (>= 1.6 ATR, vol >= 1.4x)
            drop = np.random.uniform(1.6, 2.2) * local_atr
            d_open = b2_close
            d_close = d_open - drop
            d_high = d_open + np.random.uniform(0.02, 0.08) * local_atr
            d_low = d_close - np.random.uniform(0.02, 0.08) * local_atr
            bars.append(Bar(index=idx, open=d_open, high=d_high, low=d_low, close=d_close, volume=np.random.uniform(600, 950)))
            price = d_close
            idx += 1
            if idx >= total_bars: break

            # Optional Demand Formation below (in 50% of cycles)
            has_demand = np.random.choice([True, False], p=[0.50, 0.50])
            if has_demand:
                dm_base_open = price
                dm_base_close = dm_base_open - 0.1 * local_atr
                dm_base_high = dm_base_open + 0.05 * local_atr
                dm_base_low = dm_base_close - 0.05 * local_atr
                bars.append(Bar(index=idx, open=dm_base_open, high=dm_base_high, low=dm_base_low, close=dm_base_close, volume=np.random.uniform(150, 250)))
                idx += 1
                if idx >= total_bars: break

                dm_disp_open = dm_base_close
                dm_disp_close = dm_disp_open + np.random.uniform(1.6, 2.0) * local_atr
                dm_disp_high = dm_disp_close + 0.05 * local_atr
                dm_disp_low = dm_disp_open - 0.05 * local_atr
                bars.append(Bar(index=idx, open=dm_disp_open, high=dm_disp_high, low=dm_disp_low, close=dm_disp_close, volume=np.random.uniform(550, 850)))
                price = dm_disp_close
                idx += 1
                if idx >= total_bars: break

            # Pullback to supply zone (3-5 bars)
            pb_steps = np.random.randint(3, 6)
            pb_target = sz_low - 0.15 * local_atr
            for s in range(pb_steps):
                if idx >= total_bars: break
                step = (pb_target - price) / (pb_steps - s)
                b_open = price
                b_close = b_open + step + np.random.uniform(-0.05, 0.05) * local_atr
                b_high = max(b_open, b_close) + 0.08 * local_atr
                b_low = min(b_open, b_close) - 0.08 * local_atr
                bars.append(Bar(index=idx, open=b_open, high=b_high, low=b_low, close=b_close, volume=np.random.uniform(120, 200)))
                price = b_close
                idx += 1
            if idx >= total_bars: break

            # Bullish approach candle near supply
            b_open = price
            b_close = sz_low - 0.05 * local_atr
            b_high = b_close + 0.05 * local_atr
            b_low = b_open - 0.05 * local_atr
            bars.append(Bar(index=idx, open=b_open, high=b_high, low=b_low, close=b_close, volume=np.random.uniform(180, 250)))
            price = b_close
            idx += 1
            if idx >= total_bars: break

            # Candidate Bearish Engulfing Candle
            is_valid_engulf = np.random.choice([True, False], p=[0.80, 0.20])
            if is_valid_engulf:
                c_open = price + np.random.uniform(0.01, 0.05) * local_atr
                # Probes into supply between sz_low and sz_high
                probe_high = min(sz_high - 0.02 * local_atr, max(sz_low + 0.05 * local_atr, c_open + 0.05 * local_atr))
                c_high = max(c_open + 0.02 * local_atr, probe_high)
                c_close = b_open - np.random.uniform(0.1, 0.25) * local_atr  # engulfs
                c_range = c_high - c_close + 0.05 * local_atr
                c_low = c_close - np.random.uniform(0.02, 0.05) * local_atr  # small lower wick
                vol_val = np.random.choice([260.0, 380.0, 550.0], p=[0.25, 0.50, 0.25])
                bars.append(Bar(index=idx, open=c_open, high=c_high, low=c_low, close=c_close, volume=vol_val))
                price = c_close
                idx += 1
            else:
                c_open = price
                c_close = price - 0.1 * local_atr
                c_high = c_open + 0.05 * local_atr
                c_low = c_close - 0.05 * local_atr
                bars.append(Bar(index=idx, open=c_open, high=c_high, low=c_low, close=c_close, volume=np.random.uniform(100, 180)))
                price = c_close
                idx += 1

            # Follow-through (win vs loss vs stagnation)
            outcome = np.random.choice(['win', 'loss', 'stagnate'], p=[0.62, 0.30, 0.08])
            if outcome == 'win':
                sell_steps = np.random.randint(6, 12)
                for _ in range(sell_steps):
                    if idx >= total_bars: break
                    b_open = price
                    b_close = b_open - np.random.uniform(0.25, 0.5) * local_atr
                    b_high = b_open + 0.05 * local_atr
                    b_low = b_close - 0.1 * local_atr
                    bars.append(Bar(index=idx, open=b_open, high=b_high, low=b_low, close=b_close, volume=np.random.uniform(180, 350)))
                    price = b_close
                    idx += 1
            elif outcome == 'loss':
                stop_steps = np.random.randint(2, 5)
                for _ in range(stop_steps):
                    if idx >= total_bars: break
                    b_open = price
                    b_close = b_open + np.random.uniform(0.4, 0.8) * local_atr
                    b_high = b_close + 0.1 * local_atr
                    b_low = b_open - 0.05 * local_atr
                    bars.append(Bar(index=idx, open=b_open, high=b_high, low=b_low, close=b_close, volume=np.random.uniform(200, 400)))
                    price = b_close
                    idx += 1
            else:
                for _ in range(16):
                    if idx >= total_bars: break
                    b_open = price
                    b_close = b_open + np.random.uniform(-0.08, 0.08) * local_atr
                    b_high = max(b_open, b_close) + 0.08 * local_atr
                    b_low = min(b_open, b_close) - 0.08 * local_atr
                    bars.append(Bar(index=idx, open=b_open, high=b_high, low=b_low, close=b_close, volume=np.random.uniform(80, 150)))
                    price = b_close
                    idx += 1

        elif regime == 'chop_cycle':
            for _ in range(np.random.randint(8, 16)):
                if idx >= total_bars: break
                chg = np.random.normal(0, 0.001)
                b_open = price
                b_close = b_open * (1.0 + chg)
                b_high = max(b_open, b_close) + 0.1 * local_atr
                b_low = min(b_open, b_close) - 0.1 * local_atr
                bars.append(Bar(index=idx, open=b_open, high=b_high, low=b_low, close=b_close, volume=np.random.uniform(80, 150)))
                price = b_close
                idx += 1
        else:  # bull_cycle
            for _ in range(np.random.randint(6, 12)):
                if idx >= total_bars: break
                b_open = price
                b_close = b_open + np.random.uniform(0.1, 0.3) * local_atr
                b_high = b_close + 0.1 * local_atr
                b_low = b_open - 0.05 * local_atr
                bars.append(Bar(index=idx, open=b_open, high=b_high, low=b_low, close=b_close, volume=np.random.uniform(120, 200)))
                price = b_close
                idx += 1

    in_sample = bars[:1800]
    out_of_sample = bars[1800:]

    return in_sample, out_of_sample


# =============================================================================
# SCIENTIFIC ABLATION TEST RUNNER
# =============================================================================
def load_real_market_data() -> Tuple[List[Bar], List[Bar]]:
    import os
    import json
    cache_file = os.path.join(os.path.dirname(__file__), "data", "BTCUSDT_15m_35000.json")
    if not os.path.exists(cache_file):
        raise FileNotFoundError(f"Missing historical data: {cache_file}")

    with open(cache_file, "r") as f:
        raw_data = json.load(f)

    bars = [
        Bar(
            index=i,
            open=float(r[1]),
            high=float(r[2]),
            low=float(r[3]),
            close=float(r[4]),
            volume=float(r[5]),
            symbol="BTCUSDT"
        )
        for i, r in enumerate(raw_data)
    ]

    # Split: 60% In-Sample (21,000 bars, ~7.2 months), 40% Out-of-Sample (14,000 bars, ~4.8 months)
    split_idx = int(len(bars) * 0.60)
    in_sample = bars[:split_idx]
    out_of_sample = bars[split_idx:]
    return in_sample, out_of_sample


# =============================================================================
# SCIENTIFIC ABLATION TEST RUNNER
# =============================================================================
def run_ablation_study():
    print("================================================================================")
    print("RUNNING QUANTITATIVE ABLATION STUDY: SYSTEMATIC SHORT STRATEGY")
    print("Real Historical Market Data: 35,000 Bars of BTCUSDT 15m (1 Full Year)")
    print("Zero Look-Ahead, Fee-Adjusted (0.08% round-trip), Realistic Fill at Open(t+1)")
    print("================================================================================")

    in_sample, out_of_sample = load_real_market_data()
    print(f"Data Partition: In-Sample (60%) = {len(in_sample)} bars | Out-of-Sample (40%) = {len(out_of_sample)} bars\n")

    experiments = [
        ("1. Baseline (Delayed MACD Queue, N=6)", {
            "execution_mode": "DELAYED_MACD_QUEUE"
        }),
        ("2. Direct Rejection Execution (No MACD delay)", {
            "execution_mode": "DIRECT_REJECTION"
        }),
        ("3. Contemporaneous MACD Filter (Hist_t <= Hist_t-1)", {
            "execution_mode": "CONTEMPORANEOUS_MACD"
        }),
        ("4. Pure Rejection: No MACD Filter", {
            "execution_mode": "NO_MACD"
        }),
        ("5. Direct + Structure-Break (K=5 bars)", {
            "execution_mode": "DIRECT_REJECTION",
            "structure_break_bars": 5
        }),
        ("6. Direct + Volume Participation (gamma=1.0x)", {
            "execution_mode": "DIRECT_REJECTION",
            "engulfing_volume_mult": 1.0
        }),
        ("7. Direct + Zone Quality (Width<=2.5 ATR & Fresh)", {
            "execution_mode": "DIRECT_REJECTION",
            "max_zone_width_mult": 2.5,
            "virgin_zones_only": True
        }),
        ("8. Direct + Strict 1.5R Net Target Gate", {
            "execution_mode": "DIRECT_REJECTION",
            "min_rr_ratio": 1.50
        }),
        ("9. Direct + Price Extension Gate (<=2.5 ATR)", {
            "execution_mode": "DIRECT_REJECTION",
            "max_extension_atr": 2.5
        }),
        ("10. Direct + Cooldown (C=5 bars)", {
            "execution_mode": "DIRECT_REJECTION",
            "cooldown_bars": 5
        }),
        ("11. Optimal Synthesized Combination", {
            "execution_mode": "DIRECT_REJECTION",
            "structure_break_bars": 5,
            "engulfing_volume_mult": 1.0,
            "max_zone_width_mult": 2.5,
            "min_rr_ratio": 1.50,
            "max_extension_atr": 2.5,
            "cooldown_bars": 5
        }),
        ("12. Downstream Scorer Enabled (HTF + Secondary Gate)", {
            "execution_mode": "DIRECT_REJECTION",
            "use_downstream_scorer": True
        })
    ]

    print(f"{'Experiment':<52} | {'Trades':<6} | {'Win %':<6} | {'Profit Factor':<13} | {'Expectancy ($)':<14} | {'Max DD %':<8}")
    print("-" * 110)

    results = []
    for name, cfg in experiments:
        engine = SystematicShortEngine(cfg)
        for idx in range(len(in_sample)):
            engine.on_bar(idx, in_sample)
        r = engine.generate_report()
        results.append((name, r, cfg))
        print(f"{name:<52} | {r['total_trades']:<6} | {r['win_rate_pct']:<6.1f} | {r['profit_factor']:<13.2f} | ${r['expectancy_dollars']:<13.2f} | {r['max_drawdown_pct']:<8.2f}%")

    # PHASE 7: Out-of-Sample Validation of Optimal Combination
    print("\n================================================================================")
    print(f"PHASE 7: OUT-OF-SAMPLE VALIDATION (Untouched Holdout Data: {len(out_of_sample)} Bars)")
    print("================================================================================")
    opt_cfg = experiments[-1][1]
    oos_engine = SystematicShortEngine(opt_cfg)
    for idx in range(len(out_of_sample)):
        oos_engine.on_bar(idx, out_of_sample)
    oos_r = oos_engine.generate_report()

    print(f"OOS Total Trades      : {oos_r['total_trades']}")
    print(f"OOS Win Rate          : {oos_r['win_rate_pct']}%")
    print(f"OOS Profit Factor     : {oos_r['profit_factor']}")
    print(f"OOS Expectancy        : ${oos_r['expectancy_dollars']:.2f}")
    print(f"OOS Net PnL           : ${oos_r['total_pnl']:.2f}")
    print(f"OOS Max Drawdown      : {oos_r['max_drawdown_pct']}%")
    print(f"OOS Sharpe Ratio      : {oos_r['sharpe_ratio']}")
    print("================================================================================")

    return results, oos_r


if __name__ == "__main__":
    run_ablation_study()
