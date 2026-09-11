#!/usr/bin/env python3
"""
Systematic Long Trading Strategy: Supply & Demand + Bullish Engulfing + MACD
Ablation Testing & Empirical Validation Engine (Zero Look-Ahead, Fee-Adjusted)
Tested across 35,000 bars of real 15m historical data.
"""

import math
import os
import json
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
    zone_type: str  # 'DEMAND' or 'SUPPLY'
    high: float
    low: float
    created_bar: int
    touch_count: int = 0
    in_visit: bool = False
    invalidated: bool = False


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
    Python mirror of Kotlin TradeQualityScorer.kt.
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


class SystematicLongEngine:
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

        # Capital & Risk Controls
        self.risk_per_trade = cfg.get("risk_per_trade", 0.01)   # 1% equity risk
        self.max_leverage = cfg.get("max_leverage", 3.0)        # Max 3x notional
        self.initial_equity = cfg.get("initial_equity", 100000.0)
        self.account_equity = self.initial_equity
        self.taker_fee = cfg.get("taker_fee", 0.0004)           # 0.04% entry + 0.04% exit = 0.08%
        self.max_zone_age = cfg.get("max_zone_age", 300)

        # Ablation Toggles
        self.structure_break_bars = cfg.get("structure_break_bars", 5)       # 5 bars high break
        self.engulfing_volume_mult = cfg.get("engulfing_volume_mult", 1.0)    # Volume >= 1.0x SMA20
        self.max_zone_width_mult = cfg.get("max_zone_width_mult", 2.5)       # Width <= 2.5 ATR
        self.virgin_zones_only = cfg.get("virgin_zones_only", False)         # Touch count <= 1 vs 0
        self.min_rr_ratio = cfg.get("min_rr_ratio", 1.50)                    # Minimum 1.50R
        self.cooldown_bars = cfg.get("cooldown_bars", 5)                     # 5 bars cooldown after SL
        self.max_extension_atr = cfg.get("max_extension_atr", 2.5)
        self.displacement_mult = cfg.get("displacement_mult", 1.5)          # Displacement threshold
        self.use_downstream_scorer = cfg.get("use_downstream_scorer", True)
        self.verbose = cfg.get("verbose", False)

        # Indicator Series
        self.atr_series: List[float] = []
        self.ema_20_series: List[float] = []
        self.ema_200_series: List[float] = []
        self.macd_series: List[float] = []
        self.signal_series: List[float] = []
        self.hist_series: List[float] = []
        self.adx_series: List[float] = []
        self.vol_sma_series: List[float] = []

        # Wilder ADX state
        self.tr_smooth: float = 0.0
        self.plus_dm_smooth: float = 0.0
        self.minus_dm_smooth: float = 0.0

        # State
        self.demand_zones: List[Zone] = []
        self.supply_zones: List[Zone] = []
        self.next_order: Optional[Dict] = None
        self.position: Optional[Position] = None
        self.closed_trades: List[ClosedTrade] = []
        self.equity_curve: List[float] = [self.initial_equity]
        self.last_exit_bar: Dict[str, int] = {}
        self.zone_counter = 0

    def on_bar(self, bar_idx: int, bars: List[Bar]):
        current_bar = bars[bar_idx]
        symbol = current_bar.symbol

        # 1. Order execution on bar open (zero look-ahead)
        if self.next_order is not None:
            self._fill_order_on_open(current_bar)
            self.next_order = None

        # 2. Indicators
        self._update_indicators(bars, bar_idx)

        if bar_idx < self.ema_trend_period:
            self.equity_curve.append(self.account_equity)
            return

        atr = self.atr_series[bar_idx]
        ema_20 = self.ema_20_series[bar_idx]
        ema_200 = self.ema_200_series[bar_idx]
        prev_ema_200 = self.ema_200_series[bar_idx - 5]
        adx = self.adx_series[bar_idx]

        macd = self.macd_series[bar_idx]
        signal = self.signal_series[bar_idx]
        hist = self.hist_series[bar_idx]
        prev_hist = self.hist_series[bar_idx - 1]

        # 3. Update Zones
        self._update_zones(bars, bar_idx, atr)

        # 4. Manage active position
        if self.position is not None:
            self._manage_position(bars, bar_idx, atr, hist, prev_hist)
            self.equity_curve.append(self.account_equity)
            return

        # 5. Regime Filter
        if adx < self.adx_min:
            self.equity_curve.append(self.account_equity)
            return

        is_trend_continuation = (current_bar.close > ema_200) and (ema_200 >= prev_ema_200)
        is_exhaustion_bottom = (current_bar.close <= ema_200) and ((ema_200 - current_bar.close) >= (2.5 * atr))

        if not (is_trend_continuation or is_exhaustion_bottom):
            self.equity_curve.append(self.account_equity)
            return

        # 6. Cooldown Check
        last_exit = self.last_exit_bar.get(symbol, -9999)
        if self.cooldown_bars > 0 and (bar_idx - last_exit) < self.cooldown_bars:
            self.equity_curve.append(self.account_equity)
            return

        # 7. Scan for Demand Zone Rejection & Bullish Engulfing
        allowed_touches = 1 if self.virgin_zones_only else 2
        active_demand = self._find_active_demand_zone(current_bar)

        if active_demand is not None and active_demand.touch_count <= allowed_touches:
            if self._is_valid_bullish_engulfing(bars, bar_idx, atr):
                # Volume participation
                if self.engulfing_volume_mult > 0.0:
                    avg_vol = self.vol_sma_series[bar_idx]
                    if current_bar.volume < (self.engulfing_volume_mult * avg_vol):
                        self.equity_curve.append(self.account_equity)
                        return

                # Price extension from demand zone
                if (current_bar.close - active_demand.high) > (self.max_extension_atr * atr):
                    self.equity_curve.append(self.account_equity)
                    return

                # Contemporaneous MACD confirmation: expanding histogram or bullish state
                if not (hist >= prev_hist or macd > signal):
                    self.equity_curve.append(self.account_equity)
                    return

                nearest_supply = self._find_nearest_supply_above(current_bar.close)
                stop_loss = min(current_bar.low, active_demand.low) - (0.3 * atr)
                risk_dist = current_bar.close - stop_loss

                if risk_dist <= 0 or risk_dist > (3.0 * atr):
                    self.equity_curve.append(self.account_equity)
                    return

                # Fee-aware gross target calculation to clear Net R:R >= 1.80
                gross_risk_pct = (risk_dist / current_bar.close) * 100.0
                req_target_pct = 1.80 * (gross_risk_pct + 0.20) + 0.20
                req_reward_dist = current_bar.close * (req_target_pct / 100.0)
                reward_dist = max(2.0 * risk_dist, req_reward_dist)
                tp1 = current_bar.close + reward_dist

                # Supply clearance filter: require >= reward_dist to nearest supply ceiling
                supply_dist = (nearest_supply.low - current_bar.close) if nearest_supply else 999999.0
                if supply_dist < reward_dist or supply_dist < (1.0 * atr):
                    self.equity_curve.append(self.account_equity)
                    return

                # Downstream Quality Scorer
                if self.use_downstream_scorer:
                    # HTF 50 EMA is mirrored by 200 EMA of 15m (50 hours)
                    strat_conf = 75.0
                    if abs(current_bar.close - ema_200) <= 1.5 * atr: strat_conf += 5.0
                    if adx >= 25.0: strat_conf += 5.0
                    if is_trend_continuation: strat_conf += 10.0
                    strat_conf = min(98.0, strat_conf)

                    assessment = DownstreamTradeQualityScorer.evaluate(
                        is_buy=True,
                        current_price=current_bar.close,
                        stop_loss=stop_loss,
                        take_profit=tp1,
                        bars=bars,
                        bar_idx=bar_idx,
                        adx=adx,
                        strategy_confidence=strat_conf,
                        ema_20=ema_20,
                        atr=atr,
                        htf_ema_50=ema_200
                    )

                    if not assessment.is_approved:
                        if getattr(self, "verbose", False):
                            print(f"[REJECT @ bar {bar_idx}] Score={assessment.total_score} (HTF={assessment.htf_alignment}) nonHTF={assessment.non_htf_score}/75 netRR={assessment.net_rr:.2f} relVol={assessment.relative_volume:.2f} Reason={assessment.rejection_reason}")
                        self.equity_curve.append(self.account_equity)
                        return

                self._queue_long_order(stop_loss, nearest_supply, atr, active_demand, reward_dist)

        self.equity_curve.append(self.account_equity)

    def _queue_long_order(self, stop_loss: float, nearest_supply: Optional[Zone],
                          atr: float, demand_zone: Zone, reward_dist: float):
        self.next_order = {
            "stop_loss": stop_loss,
            "nearest_supply": nearest_supply,
            "atr": atr,
            "demand_zone": demand_zone,
            "reward_dist": reward_dist
        }

    def _fill_order_on_open(self, bar: Bar):
        order = self.next_order
        actual_entry = bar.open
        stop_loss = order["stop_loss"]
        atr = order["atr"]

        risk_unit = actual_entry - stop_loss
        if risk_unit <= 0 or risk_unit > (3.0 * atr):
            return

        risk_capital = self.account_equity * self.risk_per_trade
        raw_qty = risk_capital / risk_unit

        max_notional = self.account_equity * self.max_leverage
        max_qty = max_notional / actual_entry
        total_qty = min(raw_qty, max_qty)

        nearest_supply = order["nearest_supply"]
        tp1 = actual_entry + order["reward_dist"]
        tp2 = nearest_supply.high if nearest_supply else (actual_entry + 3.0 * risk_unit)

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
        order["demand_zone"].touch_count += 1

    def _manage_position(self, bars: List[Bar], bar_idx: int, atr: float, hist: float, prev_hist: float):
        bar = bars[bar_idx]
        pos = self.position

        # Stop-Loss with Gap Slippage: min(stop_loss, bar.open)
        if bar.low <= pos.stop_loss:
            exit_price = min(pos.stop_loss, bar.open)
            rem_qty = (pos.qty_tranche_a if not pos.tranche_a_closed else 0.0) + pos.qty_tranche_b
            entry_fee = pos.entry_price * rem_qty * self.taker_fee
            exit_fee = exit_price * rem_qty * self.taker_fee
            pnl = ((exit_price - pos.entry_price) * rem_qty) - (entry_fee + exit_fee)
            risk_unit = pos.entry_price - pos.stop_loss
            r_mult = (exit_price - pos.entry_price) / risk_unit if risk_unit > 0 else -1.0

            self._record_trade(pos.entry_bar, bar_idx, pos.entry_price, exit_price, rem_qty, pnl, r_mult, "STOP_LOSS", pos.symbol)
            self.position = None
            return

        # Target 1 (Tranche A)
        if not pos.tranche_a_closed:
            if bar.high >= pos.tp1:
                entry_fee = pos.entry_price * pos.qty_tranche_a * self.taker_fee
                exit_fee = pos.tp1 * pos.qty_tranche_a * self.taker_fee
                pnl = ((pos.tp1 - pos.entry_price) * pos.qty_tranche_a) - (entry_fee + exit_fee)
                risk_unit = pos.entry_price - pos.stop_loss
                r_mult = (pos.tp1 - pos.entry_price) / risk_unit if risk_unit > 0 else 1.5

                self._record_trade(pos.entry_bar, bar_idx, pos.entry_price, pos.tp1, pos.qty_tranche_a, pnl, r_mult, "TP1_HIT", pos.symbol)
                pos.tranche_a_closed = True
                # Move Stop Loss to Breakeven
                pos.stop_loss = pos.entry_price

        # Target 2 (Tranche B) or Bearish Momentum Reversal Exit
        if pos.tranche_a_closed:
            if bar.high >= pos.tp2:
                entry_fee = pos.entry_price * pos.qty_tranche_b * self.taker_fee
                exit_fee = pos.tp2 * pos.qty_tranche_b * self.taker_fee
                pnl = ((pos.tp2 - pos.entry_price) * pos.qty_tranche_b) - (entry_fee + exit_fee)
                risk_unit = pos.entry_price - pos.stop_loss
                r_mult = (pos.tp2 - pos.entry_price) / risk_unit if risk_unit > 0 else 3.0
                self._record_trade(pos.entry_bar, bar_idx, pos.entry_price, pos.tp2, pos.qty_tranche_b, pnl, r_mult, "TP2_HIT", pos.symbol)
                self.position = None
                return

            if prev_hist >= 0.0 and hist < 0.0:
                entry_fee = pos.entry_price * pos.qty_tranche_b * self.taker_fee
                exit_fee = bar.close * pos.qty_tranche_b * self.taker_fee
                pnl = ((bar.close - pos.entry_price) * pos.qty_tranche_b) - (entry_fee + exit_fee)
                risk_unit = pos.entry_price - pos.stop_loss
                r_mult = (bar.close - pos.entry_price) / risk_unit if risk_unit > 0 else 0.0
                self._record_trade(pos.entry_bar, bar_idx, pos.entry_price, bar.close, pos.qty_tranche_b, pnl, r_mult, "BEARISH_MACD_EXIT", pos.symbol)
                self.position = None
                return

        # Stagnation exit after 96 bars (24 hours)
        if (bar_idx - pos.entry_bar) >= 96:
            rem_qty = (pos.qty_tranche_a if not pos.tranche_a_closed else 0.0) + pos.qty_tranche_b
            entry_fee = pos.entry_price * rem_qty * self.taker_fee
            exit_fee = bar.close * rem_qty * self.taker_fee
            pnl = ((bar.close - pos.entry_price) * rem_qty) - (entry_fee + exit_fee)
            risk_unit = pos.entry_price - pos.stop_loss
            r_mult = (bar.close - pos.entry_price) / risk_unit if risk_unit > 0 else 0.0
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

    def _update_indicators(self, bars: List[Bar], idx: int):
        curr = bars[idx]

        # ATR (Wilder)
        if idx == 0:
            tr = curr.high - curr.low
            self.atr_series.append(tr)
        else:
            p_close = bars[idx - 1].close
            tr = max(curr.high - curr.low, abs(curr.high - p_close), abs(curr.low - p_close))
            prev_atr = self.atr_series[-1]
            self.atr_series.append((prev_atr * (self.atr_period - 1) + tr) / self.atr_period)

        # EMA 20
        if idx == 0:
            self.ema_20_series.append(curr.close)
        else:
            alpha = 2.0 / (20.0 + 1.0)
            self.ema_20_series.append(alpha * curr.close + (1.0 - alpha) * self.ema_20_series[-1])

        # EMA 200
        if idx == 0:
            self.ema_200_series.append(curr.close)
        else:
            alpha = 2.0 / (self.ema_trend_period + 1.0)
            self.ema_200_series.append(alpha * curr.close + (1.0 - alpha) * self.ema_200_series[-1])

        # Volume SMA 20
        if idx < 20:
            avg_v = sum(b.volume for b in bars[:idx + 1]) / (idx + 1)
        else:
            avg_v = sum(b.volume for b in bars[idx - 19:idx + 1]) / 20.0
        self.vol_sma_series.append(avg_v)

        # MACD
        if idx == 0:
            self.macd_series.append(0.0)
            self.signal_series.append(0.0)
            self.hist_series.append(0.0)
        else:
            fast_alpha = 2.0 / (self.macd_fast + 1)
            slow_alpha = 2.0 / (self.macd_slow + 1)
            signal_alpha = 2.0 / (self.macd_signal + 1)

            if not hasattr(self, '_fast_ema'):
                self._fast_ema = curr.close
                self._slow_ema = curr.close
                self._sig_ema = 0.0

            self._fast_ema = fast_alpha * curr.close + (1.0 - fast_alpha) * self._fast_ema
            self._slow_ema = slow_alpha * curr.close + (1.0 - slow_alpha) * self._slow_ema
            macd_val = self._fast_ema - self._slow_ema
            self._sig_ema = signal_alpha * macd_val + (1.0 - signal_alpha) * self._sig_ema

            self.macd_series.append(macd_val)
            self.signal_series.append(self._sig_ema)
            self.hist_series.append(macd_val - self._sig_ema)

        # ADX (Wilder)
        if idx == 0:
            self.adx_series.append(0.0)
        else:
            p_bar = bars[idx - 1]
            tr = max(curr.high - curr.low, abs(curr.high - p_bar.close), abs(curr.low - p_bar.close))
            up = curr.high - p_bar.high
            down = p_bar.low - curr.low

            plus_dm = up if (up > down and up > 0) else 0.0
            minus_dm = down if (down > up and down > 0) else 0.0

            if idx == 1:
                self.tr_smooth = tr
                self.plus_dm_smooth = plus_dm
                self.minus_dm_smooth = minus_dm
                self.adx_series.append(0.0)
            else:
                p = self.adx_period
                self.tr_smooth = self.tr_smooth - (self.tr_smooth / p) + tr
                self.plus_dm_smooth = self.plus_dm_smooth - (self.plus_dm_smooth / p) + plus_dm
                self.minus_dm_smooth = self.minus_dm_smooth - (self.minus_dm_smooth / p) + minus_dm

                if self.tr_smooth > 0:
                    plus_di = 100.0 * (self.plus_dm_smooth / self.tr_smooth)
                    minus_di = 100.0 * (self.minus_dm_smooth / self.tr_smooth)
                    di_sum = plus_di + minus_di
                    dx = 100.0 * (abs(plus_di - minus_di) / di_sum) if di_sum > 0 else 0.0
                else:
                    dx = 0.0

                if not hasattr(self, '_adx_val'):
                    self._adx_val = dx
                else:
                    self._adx_val = ((self._adx_val * (p - 1)) + dx) / p

                self.adx_series.append(self._adx_val)

    def _update_zones(self, bars: List[Bar], bar_idx: int, atr: float):
        curr = bars[bar_idx]
        avg_vol = self.vol_sma_series[bar_idx]

        # Prune / Invalidate broken zones
        for dz in self.demand_zones:
            if not dz.invalidated:
                if curr.close < dz.low:
                    dz.invalidated = True
                elif (bar_idx - dz.created_bar) > self.max_zone_age:
                    dz.invalidated = True

        for sz in self.supply_zones:
            if not sz.invalidated:
                if curr.close > sz.high:
                    sz.invalidated = True
                elif (bar_idx - sz.created_bar) > self.max_zone_age:
                    sz.invalidated = True

        # Demand Zone Creation: Bullish displacement breaking structure
        if (curr.close - curr.open >= self.displacement_mult * atr) and (curr.volume >= 1.3 * avg_vol):
            if bar_idx >= 5:
                prev_highs = [b.high for b in bars[bar_idx - 5:bar_idx]]
                if curr.close > max(prev_highs):
                    base = []
                    for i in range(1, 4):
                        if bar_idx - i < 0: break
                        b = bars[bar_idx - i]
                        if abs(b.close - b.open) <= 1.0 * atr:
                            base.append(b)
                        else: break
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

        # Supply Zone Creation: Bearish displacement
        if (curr.open - curr.close >= 1.5 * atr) and (curr.volume >= 1.3 * avg_vol):
            base = []
            for i in range(1, 4):
                if bar_idx - i < 0: break
                b = bars[bar_idx - i]
                if abs(b.close - b.open) <= 1.0 * atr: base.append(b)
                else: break
            if base:
                z_high = max(b.high for b in base)
                z_low = max(max(b.open, b.close) for b in base)
                if (z_high - z_low) <= (self.max_zone_width_mult * atr):
                    self.zone_counter += 1
                    self.supply_zones.append(Zone(
                        id=self.zone_counter,
                        zone_type='SUPPLY',
                        high=z_high,
                        low=z_low,
                        created_bar=bar_idx
                    ))

    def _is_valid_bullish_engulfing(self, bars: List[Bar], idx: int, atr: float) -> bool:
        curr = bars[idx]
        prev = bars[idx - 1]

        if prev.close >= prev.open or curr.close <= curr.open:
            return False
        if curr.open > (prev.close + 0.05 * atr) or curr.close <= prev.open:
            return False

        c_range = curr.high - curr.low
        c_body = curr.close - curr.open
        u_wick = curr.high - curr.close

        if c_range <= 0 or (c_body / c_range) < 0.60:
            return False
        if u_wick > (0.25 * c_range):
            return False
        if c_range < (0.75 * atr) or c_range > (2.5 * atr):
            return False

        return True

    def _find_active_demand_zone(self, bar: Bar) -> Optional[Zone]:
        for dz in reversed(self.demand_zones):
            if not dz.invalidated:
                if bar.low <= dz.high and bar.close >= dz.low:
                    return dz
        return None

    def _find_nearest_supply_above(self, price: float) -> Optional[Zone]:
        candidates = [sz for sz in self.supply_zones if not sz.invalidated and sz.low > price]
        return min(candidates, key=lambda z: z.low) if candidates else None

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
                "mean_r": 0.0,
                "se_r": 0.0,
                "t_stat": 0.0,
                "p_value": 1.0,
                "total_pnl": 0.0,
                "max_drawdown_pct": 0.0
            }

        wins = [t for t in trades if t.pnl > 0]
        losses = [t for t in trades if t.pnl <= 0]
        total_pnl = sum(t.pnl for t in trades)
        gross_profit = sum(t.pnl for t in wins)
        gross_loss = abs(sum(t.pnl for t in losses))
        profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else 99.0

        r_multiples = [t.r_multiple for t in trades]
        mean_r = float(np.mean(r_multiples))
        std_r = float(np.std(r_multiples, ddof=1)) if len(r_multiples) > 1 else 0.0
        se_r = std_r / math.sqrt(len(r_multiples)) if len(r_multiples) > 0 else 0.0
        t_stat = (mean_r / se_r) if se_r > 0 else 0.0
        # Normal approximation p-value
        p_val = 2.0 * (1.0 - 0.5 * (1.0 + math.erf(abs(t_stat) / math.sqrt(2.0))))

        # Max drawdown
        peak = self.initial_equity
        max_dd = 0.0
        for eq in self.equity_curve:
            if eq > peak: peak = eq
            dd = (peak - eq) / peak * 100.0
            if dd > max_dd: max_dd = dd

        return {
            "total_trades": len(trades),
            "wins": len(wins),
            "losses": len(losses),
            "win_rate_pct": (len(wins) / len(trades)) * 100.0,
            "profit_factor": profit_factor,
            "expectancy_dollars": total_pnl / len(trades),
            "mean_r": mean_r,
            "se_r": se_r,
            "t_stat": t_stat,
            "p_value": p_val,
            "total_pnl": total_pnl,
            "max_drawdown_pct": max_dd
        }


def load_real_market_data() -> Tuple[List[Bar], List[Bar]]:
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

    split_idx = int(len(bars) * 0.60)
    in_sample = bars[:split_idx]
    out_of_sample = bars[split_idx:]
    return in_sample, out_of_sample


def run_long_ablation():
    print("================================================================================")
    print("RUNNING QUANTITATIVE ABLATION STUDY: SYSTEMATIC LONG STRATEGY")
    print("Real Historical Market Data: 35,000 Bars of BTCUSDT 15m")
    print("Fee-Adjusted (0.08% round-trip), Realistic Fill at Open(t+1)")
    print("================================================================================")

    in_sample, out_of_sample = load_real_market_data()
    print(f"Data Partition: In-Sample (60%) = {len(in_sample)} bars | Out-of-Sample (40%) = {len(out_of_sample)} bars\n")

    experiments = [
        ("1. Pure Baseline (No downstream scorer)", {
            "use_downstream_scorer": False
        }),
        ("2. Downstream Scorer Enabled (HTF + Secondary Gate)", {
            "use_downstream_scorer": True
        }),
        ("3. Scorer + Strict Zone Width <= 2.0 ATR", {
            "use_downstream_scorer": True,
            "max_zone_width_mult": 2.0
        }),
        ("4. Scorer + Fresh Demand Only (touch <= 0)", {
            "use_downstream_scorer": True,
            "virgin_zones_only": True
        }),
        ("5. Scorer + Elevated Volume Participation (gamma=1.2x)", {
            "use_downstream_scorer": True,
            "engulfing_volume_mult": 1.2
        }),
        ("6. Scorer + Displacement=1.2 ATR (Accumulation tuning)", {
            "use_downstream_scorer": True,
            "displacement_mult": 1.2
        }),
        ("7. Scorer + Displacement=1.0 ATR (Accumulation tuning)", {
            "use_downstream_scorer": True,
            "displacement_mult": 1.0
        })
    ]

    print(f"{'Experiment':<52} | {'Trades':<6} | {'Win %':<6} | {'Profit Factor':<13} | {'Mean R':<8} | {'SE (R)':<8} | {'p-val':<7}")
    print("-" * 115)

    for name, cfg in experiments:
        engine = SystematicLongEngine(cfg)
        for idx in range(len(in_sample)):
            engine.on_bar(idx, in_sample)
        r = engine.generate_report()
        print(f"{name:<52} | {r['total_trades']:<6} | {r['win_rate_pct']:<6.1f} | {r['profit_factor']:<13.2f} | {r['mean_r']:<8.2f} | {r['se_r']:<8.2f} | {r['p_value']:<7.4f}")

    # Out of Sample
    print("\n================================================================================")
    print(f"OUT-OF-SAMPLE VALIDATION (Untouched Holdout: {len(out_of_sample)} Bars)")
    print("================================================================================")
    opt_cfg = experiments[1][1]
    oos_engine = SystematicLongEngine(opt_cfg)
    for idx in range(len(out_of_sample)):
        oos_engine.on_bar(idx, out_of_sample)
    oos_r = oos_engine.generate_report()

    print(f"OOS Total Trades : {oos_r['total_trades']}")
    print(f"OOS Win Rate     : {oos_r['win_rate_pct']:.1f}%")
    print(f"OOS Profit Factor: {oos_r['profit_factor']:.2f}")
    print(f"OOS Mean R       : {oos_r['mean_r']:.2f} (SE: {oos_r['se_r']:.2f})")
    print(f"OOS Net PnL      : ${oos_r['total_pnl']:.2f}")
    print(f"OOS Max DD       : {oos_r['max_drawdown_pct']:.2f}%")
    print("================================================================================")


if __name__ == "__main__":
    run_long_ablation()
