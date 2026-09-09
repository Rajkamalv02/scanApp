#!/usr/bin/env python3
"""
Empirical Timing & Efficacy Diagnostic Engine:
Measures the temporal compatibility and predictive efficacy between Supply/Demand Zones,
Bearish Engulfing Reversals, and MACD across Large-Sample Historical Market Data (50,000+ candles).

Key Research Questions Investigated:
1. Large-Sample Empirical Distribution (n >= 100+):
   - What is the true confirmation rate with 95% Wilson score confidence intervals?
   - What is the true lag distribution when pseudoreplication (repeated zone visits) is controlled?
2. Recall vs. Precision of Faster MACD Variants:
   - How many total crossovers do MACD(12,26,9), MACD(8,17,9), and MACD(5,13,6) produce across 50,000 bars?
   - What is their signal-to-noise ratio and precision?
3. Mechanical Origin of "Before" Crossovers (lag < 0):
   - Did the crossover originate at the zone's birth displacement or at a fresh pre-reversal swing?
4. Outcome Efficacy (The Critical Question):
   - Does requiring MACD confirmation actually improve trade outcomes (Win Rate to 1.5R, MFE, MAE)
     compared to taking the Zone + Engulfing reversal alone?
"""

import os
import json
import time
import math
import urllib.request
from dataclasses import dataclass
from typing import List, Optional, Dict, Tuple
import numpy as np


@dataclass
class Bar:
    index: int
    time: int
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
class TimingEvent:
    event_id: int
    symbol: str
    timeframe: str
    engulf_bar_idx: int
    engulf_time: int
    engulf_open: float
    engulf_high: float
    engulf_low: float
    engulf_close: float
    atr: float
    supply_zone_id: int
    supply_zone_high: float
    supply_zone_low: float
    supply_zone_created_bar: int
    supply_zone_age: int
    supply_touch_count: int
    is_first_touch_on_zone: bool
    macd_at_engulf: float
    signal_at_engulf: float
    hist_at_engulf: float
    is_bearish_state: bool
    nearest_crossover_bar: Optional[int] = None
    lag: Optional[int] = None  # J - T
    classification: str = "NO_CONFIRMATION"
    crossover_origin: str = "NONE"  # ZONE_BIRTH, REACTION_SWING, POST_ENGULFING, NONE

    # Forward Outcome Tracking (over next 20 bars)
    risk: float = 0.0
    stop_loss: float = 0.0
    target_1_5r: float = 0.0
    mfe_r: float = 0.0  # Max favorable excursion in R
    mae_r: float = 0.0  # Max adverse excursion in R
    hit_1_5r_first: bool = False
    hit_sl_first: bool = False
    fwd_ret_5_pct: float = 0.0
    fwd_ret_10_pct: float = 0.0
    fwd_ret_15_pct: float = 0.0


# =============================================================================
# 1. STATISTICAL CONFIDENCE INTERVALS (Wilson Score Interval)
# =============================================================================
def wilson_score_interval(successes: int, trials: int, confidence: float = 0.95) -> Tuple[float, float]:
    """
    Computes Wilson score confidence interval for a binomial proportion.
    Accurate and robust even for small n or extreme proportions (near 0 or 1).
    """
    if trials == 0:
        return 0.0, 0.0
    z = 1.95996  # 95% standard normal quantile
    p = successes / trials
    denominator = 1.0 + (z**2) / trials
    centre_adjusted_probability = p + (z**2) / (2 * trials)
    adjusted_std_dev = math.sqrt((p * (1.0 - p) + (z**2) / (4 * trials)) / trials)
    lower = (centre_adjusted_probability - z * adjusted_std_dev) / denominator
    upper = (centre_adjusted_probability + z * adjusted_std_dev) / denominator
    return max(0.0, lower), min(1.0, upper)


# =============================================================================
# 2. DATA ACQUISITION & CACHING (50,000+ Candles)
# =============================================================================
def fetch_historical_candles(symbol: str = "BTCUSDT", interval: str = "15m", total_candles: int = 50000) -> List[Bar]:
    cache_dir = os.path.join(os.path.dirname(__file__), "data")
    os.makedirs(cache_dir, exist_ok=True)
    cache_file = os.path.join(cache_dir, f"{symbol}_{interval}_{total_candles}.json")

    if os.path.exists(cache_file):
        try:
            with open(cache_file, "r") as f:
                raw_data = json.load(f)
            bars = [
                Bar(
                    index=i,
                    time=int(r[0]),
                    open=float(r[1]),
                    high=float(r[2]),
                    low=float(r[3]),
                    close=float(r[4]),
                    volume=float(r[5]),
                    symbol=symbol
                )
                for i, r in enumerate(raw_data)
            ]
            print(f"[CACHE] Loaded {len(bars)} {interval} candles for {symbol} from disk.")
            return bars
        except Exception as e:
            print(f"[WARN] Failed to read cache: {e}. Re-fetching...")

    print(f"[FETCH] Downloading {total_candles} {interval} candles for {symbol} from Binance API...")
    all_candles = []
    end_time = None
    batches = math.ceil(total_candles / 1000)

    for b in range(batches):
        url = f"https://api.binance.com/api/v3/klines?symbol={symbol}&interval={interval}&limit=1000"
        if end_time:
            url += f"&endTime={end_time}"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            with urllib.request.urlopen(req, timeout=12) as resp:
                data = json.loads(resp.read().decode())
            if not data:
                break
            all_candles = data + all_candles
            end_time = data[0][0] - 1
            if (b + 1) % 10 == 0:
                print(f"  Downloaded {len(all_candles)} / {total_candles} candles...")
            time.sleep(0.08)
        except Exception as e:
            print(f"[ERROR] Fetch failed at batch {b}: {e}")
            time.sleep(1.0)

    seen = set()
    unique = []
    for c in all_candles:
        if c[0] not in seen:
            seen.add(c[0])
            unique.append(c)
    unique.sort(key=lambda x: x[0])
    selected = unique[-total_candles:]

    try:
        with open(cache_file, "w") as f:
            json.dump(selected, f)
        print(f"[CACHE] Saved {len(selected)} candles to {cache_file}.")
    except Exception as e:
        print(f"[WARN] Failed to write cache: {e}")

    bars = [
        Bar(
            index=i,
            time=int(r[0]),
            open=float(r[1]),
            high=float(r[2]),
            low=float(r[3]),
            close=float(r[4]),
            volume=float(r[5]),
            symbol=symbol
        )
        for i, r in enumerate(selected)
    ]
    print(f"[DONE] Prepared {len(bars)} {interval} candles for analysis.")
    return bars


# =============================================================================
# 3. TECHNICAL INDICATORS & ZONE ENGINE
# =============================================================================
def calculate_atr_series(bars: List[Bar], period: int = 14) -> List[float]:
    atr_series = []
    for idx, curr in enumerate(bars):
        if idx == 0:
            tr = curr.high - curr.low
            atr_series.append(tr)
        else:
            p_close = bars[idx - 1].close
            tr = max(curr.high - curr.low, abs(curr.high - p_close), abs(curr.low - p_close))
            atr = (atr_series[-1] * (period - 1) + tr) / period
            atr_series.append(atr)
    return atr_series


def calculate_vol_sma_series(bars: List[Bar], period: int = 20) -> List[float]:
    vol_sma = []
    window = []
    for b in bars:
        window.append(b.volume)
        if len(window) > period:
            window.pop(0)
        vol_sma.append(sum(window) / len(window))
    return vol_sma


def calculate_macd(bars: List[Bar], fast: int = 12, slow: int = 26, signal: int = 9) -> Tuple[List[float], List[float], List[float]]:
    alpha_fast = 2.0 / (fast + 1.0)
    alpha_slow = 2.0 / (slow + 1.0)
    alpha_signal = 2.0 / (signal + 1.0)

    fast_series = []
    slow_series = []
    macd_series = []
    signal_series = []
    hist_series = []

    for idx, b in enumerate(bars):
        if idx == 0:
            fast_series.append(b.close)
            slow_series.append(b.close)
            macd_series.append(0.0)
            signal_series.append(0.0)
            hist_series.append(0.0)
        else:
            fast_val = alpha_fast * b.close + (1.0 - alpha_fast) * fast_series[-1]
            slow_val = alpha_slow * b.close + (1.0 - alpha_slow) * slow_series[-1]
            fast_series.append(fast_val)
            slow_series.append(slow_val)

            macd_val = fast_val - slow_val
            macd_series.append(macd_val)

            sig_val = alpha_signal * macd_val + (1.0 - alpha_signal) * signal_series[-1]
            signal_series.append(sig_val)
            hist_series.append(macd_val - sig_val)

    return macd_series, signal_series, hist_series


def is_valid_bearish_engulfing(curr: Bar, prev: Bar, atr: float) -> bool:
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


# =============================================================================
# 4. LARGE-SAMPLE DIAGNOSTIC HARNESS WITH EFFICACY EVALUATION
# =============================================================================
class MacdTimingDiagnostic:
    def __init__(self, bars: List[Bar], fast: int = 12, slow: int = 26, signal: int = 9, search_window: int = 10):
        self.bars = bars
        self.fast = fast
        self.slow = slow
        self.signal = signal
        self.search_window = search_window
        self.max_zone_age = 300

        self.atr_series = calculate_atr_series(bars, 14)
        self.vol_sma_series = calculate_vol_sma_series(bars, 20)
        self.macd_series, self.signal_series, self.hist_series = calculate_macd(bars, fast, slow, signal)

        # Identify all bearish MACD crossovers across entire dataset
        self.bearish_crossovers = []
        for j in range(1, len(bars)):
            if self.macd_series[j - 1] >= self.signal_series[j - 1] and self.macd_series[j] < self.signal_series[j]:
                self.bearish_crossovers.append(j)

        self.supply_zones: List[Zone] = []
        self.zone_counter = 0
        self.events: List[TimingEvent] = []

    def run_diagnostic(self) -> List[TimingEvent]:
        self.events = []
        self.supply_zones = []
        self.zone_counter = 0
        seen_zones_in_events = set()

        for idx in range(25, len(self.bars) - 25):
            curr = self.bars[idx]
            local_atr = self.atr_series[idx]
            avg_vol = self.vol_sma_series[idx]

            # 1. Update existing zones (invalidation & hysteresis)
            for sz in self.supply_zones:
                if not sz.invalidated:
                    if curr.close > sz.high:
                        sz.invalidated = True
                    elif curr.high >= sz.low and curr.close <= sz.high:
                        if not sz.in_visit:
                            sz.touch_count += 1
                            sz.in_visit = True
                    elif curr.high < (sz.low - 1.0 * local_atr):
                        sz.in_visit = False
                    if (idx - sz.created_bar) > self.max_zone_age:
                        sz.invalidated = True

            # Prune invalidated zones to maintain optimal performance
            self.supply_zones = [sz for sz in self.supply_zones if not sz.invalidated]

            # 2. Detect new supply zones (displacement drop + 5-bar structure break)
            is_displacement = (curr.open - curr.close >= 1.5 * local_atr) and (curr.volume >= 1.3 * avg_vol)
            if is_displacement:
                if idx >= 5:
                    min_prior_low = min(self.bars[idx - k].low for k in range(1, 6))
                    if curr.close < min_prior_low:
                        base = []
                        for b_idx in range(1, 4):
                            if idx - b_idx < 0:
                                break
                            b = self.bars[idx - b_idx]
                            if abs(b.close - b.open) <= 1.0 * local_atr:
                                base.append(b)
                            else:
                                break
                        if base:
                            z_high = max(b.high for b in base)
                            z_low = max(max(b.open, b.close) for b in base)
                            if (z_high - z_low) <= 2.5 * local_atr:
                                self.zone_counter += 1
                                self.supply_zones.append(Zone(
                                    id=self.zone_counter,
                                    zone_type="SUPPLY",
                                    high=z_high,
                                    low=z_low,
                                    created_bar=idx
                                ))

            # 3. Check for valid Bearish Engulfing Candle probing active supply
            prev = self.bars[idx - 1]
            if is_valid_bearish_engulfing(curr, prev, local_atr):
                active_sz = None
                for sz in reversed(self.supply_zones):
                    if not sz.invalidated:
                        if curr.high >= sz.low and curr.close <= sz.high:
                            active_sz = sz
                            break

                if active_sz is not None and active_sz.touch_count <= 2:
                    T = idx
                    event_id = len(self.events) + 1
                    macd_val = self.macd_series[T]
                    sig_val = self.signal_series[T]
                    hist_val = self.hist_series[T]
                    is_bearish_state = (macd_val < sig_val)

                    # Check independence: Is this the first event on this supply zone?
                    is_first_touch = (active_sz.id not in seen_zones_in_events)
                    seen_zones_in_events.add(active_sz.id)

                    # Search for nearest crossover in [T - W, T + W]
                    W = self.search_window
                    window_crossovers = [j for j in self.bearish_crossovers if (T - W) <= j <= (T + W)]

                    nearest_j = None
                    min_dist = 999999
                    for j in window_crossovers:
                        dist = abs(j - T)
                        if dist < min_dist:
                            min_dist = dist
                            nearest_j = j

                    lag = (nearest_j - T) if nearest_j is not None else None

                    # Classification
                    if lag is not None:
                        if lag < 0:
                            classification = "A. FRESH_BEFORE"
                        elif lag == 0:
                            classification = "B. SAME_BAR"
                        else:
                            classification = "C. AFTER"
                    elif is_bearish_state:
                        classification = "D. STALE_BEARISH"
                    else:
                        classification = "E. NO_CONFIRMATION"

                    # Mechanical origin analysis for lag < 0
                    origin = "NONE"
                    if lag is not None:
                        if lag > 0:
                            origin = "POST_ENGULFING"
                        elif lag == 0:
                            origin = "SAME_BAR"
                        else:
                            # Crossover occurred before engulfing
                            bars_from_zone_birth = abs(nearest_j - active_sz.created_bar)
                            if bars_from_zone_birth <= 2:
                                origin = "ZONE_BIRTH"  # Tied to the original zone creation drop!
                            else:
                                origin = "REACTION_SWING"  # A fresh crossover during the rally

                    # 4. Measure Subsequent Forward Outcome (over next 20 bars)
                    entry_price = curr.close
                    stop_loss = max(curr.high, active_sz.high) + (0.3 * local_atr)
                    risk = stop_loss - entry_price
                    target_1_5r = entry_price - (1.5 * risk)

                    mfe_dist = 0.0
                    mae_dist = 0.0
                    hit_tp = False
                    hit_sl = False

                    if risk > 0:
                        for f_idx in range(1, 21):
                            if T + f_idx >= len(self.bars):
                                break
                            f_bar = self.bars[T + f_idx]
                            downside_move = entry_price - f_bar.low
                            upside_move = f_bar.high - entry_price

                            if downside_move > mfe_dist:
                                mfe_dist = downside_move
                            if upside_move > mae_dist:
                                mae_dist = upside_move

                            # Check TP / SL hit sequence
                            if not hit_tp and not hit_sl:
                                if f_bar.low <= target_1_5r and f_bar.high < stop_loss:
                                    hit_tp = True
                                elif f_bar.high >= stop_loss:
                                    hit_sl = True

                    mfe_r = (mfe_dist / risk) if risk > 0 else 0.0
                    mae_r = (mae_dist / risk) if risk > 0 else 0.0

                    fwd_5 = (entry_price - self.bars[T + 5].close) / entry_price * 100.0 if (T + 5) < len(self.bars) else 0.0
                    fwd_10 = (entry_price - self.bars[T + 10].close) / entry_price * 100.0 if (T + 10) < len(self.bars) else 0.0
                    fwd_15 = (entry_price - self.bars[T + 15].close) / entry_price * 100.0 if (T + 15) < len(self.bars) else 0.0

                    self.events.append(TimingEvent(
                        event_id=event_id,
                        symbol=curr.symbol,
                        timeframe="15m",
                        engulf_bar_idx=T,
                        engulf_time=curr.time,
                        engulf_open=curr.open,
                        engulf_high=curr.high,
                        engulf_low=curr.low,
                        engulf_close=curr.close,
                        atr=local_atr,
                        supply_zone_id=active_sz.id,
                        supply_zone_high=active_sz.high,
                        supply_zone_low=active_sz.low,
                        supply_zone_created_bar=active_sz.created_bar,
                        supply_zone_age=T - active_sz.created_bar,
                        supply_touch_count=active_sz.touch_count,
                        is_first_touch_on_zone=is_first_touch,
                        macd_at_engulf=macd_val,
                        signal_at_engulf=sig_val,
                        hist_at_engulf=hist_val,
                        is_bearish_state=is_bearish_state,
                        nearest_crossover_bar=nearest_j,
                        lag=lag,
                        classification=classification,
                        crossover_origin=origin,
                        risk=risk,
                        stop_loss=stop_loss,
                        target_1_5r=target_1_5r,
                        mfe_r=mfe_r,
                        mae_r=mae_r,
                        hit_1_5r_first=hit_tp,
                        hit_sl_first=hit_sl,
                        fwd_ret_5_pct=fwd_5,
                        fwd_ret_10_pct=fwd_10,
                        fwd_ret_15_pct=fwd_15
                    ))

        return self.events


# =============================================================================
# 5. COMPREHENSIVE STATISTICAL REPORT GENERATOR
# =============================================================================
def generate_statistical_report(events: List[TimingEvent], title: str, total_bars_searched: int, total_crossovers_in_data: int, bars: List[Bar]) -> Dict:
    total_events = len(events)
    if total_events == 0:
        return {"title": title, "total_events": 0}

    # Partition by independence
    independent_events = [e for e in events if e.is_first_touch_on_zone]

    confirmed_all = [e for e in events if e.lag is not None]
    confirmed_indep = [e for e in independent_events if e.lag is not None]

    all_lags = [e.lag for e in confirmed_all]
    indep_lags = [e.lag for e in confirmed_indep]

    pos_lags_all = [e.lag for e in confirmed_all if e.lag > 0]
    pos_lags_indep = [e.lag for e in confirmed_indep if e.lag > 0]

    # Confidence intervals
    conf_rate, (conf_ci_low, conf_ci_high) = (len(confirmed_all) / total_events), wilson_score_interval(len(confirmed_all), total_events)
    stale_count = sum(1 for e in events if e.classification == "D. STALE_BEARISH")
    stale_rate, (stale_ci_low, stale_ci_high) = (stale_count / total_events), wilson_score_interval(stale_count, total_events)
    no_conf_count = sum(1 for e in events if e.classification == "E. NO_CONFIRMATION")
    no_conf_rate, (no_conf_ci_low, no_conf_ci_high) = (no_conf_count / total_events), wilson_score_interval(no_conf_count, total_events)

    # Categories
    cat_a = [e for e in events if e.classification == "A. FRESH_BEFORE"]
    cat_b = [e for e in events if e.classification == "B. SAME_BAR"]
    cat_c = [e for e in events if e.classification == "C. AFTER"]

    # Origin breakdown of lag < 0
    origin_birth = sum(1 for e in cat_a if e.crossover_origin == "ZONE_BIRTH")
    origin_reaction = sum(1 for e in cat_a if e.crossover_origin == "REACTION_SWING")

    # Percentiles on independent dataset
    target_lags = indep_lags if len(indep_lags) >= 20 else all_lags
    p25 = np.percentile(target_lags, 25) if target_lags else 0.0
    p50 = np.median(target_lags) if target_lags else 0.0
    p75 = np.percentile(target_lags, 75) if target_lags else 0.0
    p80 = np.percentile(target_lags, 80) if target_lags else 0.0
    p90 = np.percentile(target_lags, 90) if target_lags else 0.0

    # Positive lag catch-up distribution
    pos_lag_dist = {}
    cum_count = 0
    pos_lag_cum = {}
    target_pos = pos_lags_indep if len(pos_lags_indep) >= 15 else pos_lags_all
    for k in range(1, 11):
        cnt = sum(1 for lag in target_pos if lag == k)
        pos_lag_dist[k] = cnt
        cum_count += cnt
        pos_lag_cum[k] = (cum_count / len(target_pos) * 100.0) if target_pos else 0.0

    # Macro Precision / Frequency Analysis
    crossover_frequency = (total_bars_searched / total_crossovers_in_data) if total_crossovers_in_data > 0 else 0.0
    # Precision: proportion of total crossovers that coincide within +/- 2 bars of a valid structural setup
    crossovers_near_setups = set()
    for e in events:
        if e.nearest_crossover_bar is not None and abs(e.lag or 999) <= 2:
            crossovers_near_setups.add(e.nearest_crossover_bar)
    structural_precision = (len(crossovers_near_setups) / total_crossovers_in_data * 100.0) if total_crossovers_in_data > 0 else 0.0

    # OUTCOME EFFICACY ANALYSIS: Confirmed vs Unconfirmed
    # Group 1: All Events (Baseline: No MACD filter)
    grp_all = events
    # Group 2: MACD Confirmed in window [-3, +5]
    grp_win_all = [e for e in events if e.lag is not None and -3 <= e.lag <= 5]
    # Group 3: Strict MACD Confirmed [0, +5] (same-bar or post-engulfing)
    grp_strict_pos = [e for e in events if e.lag is not None and 0 <= e.lag <= 5]
    # Group 4: Unconfirmed / Rejected by MACD (no cross in window)
    grp_unconfirmed = [e for e in events if e.lag is None or e.lag > 5 or e.lag < -3]

    def calc_group_stats(grp: List[TimingEvent]) -> Dict:
        if not grp:
            return {"count": 0, "win_rate_1_5r": 0.0, "avg_mfe": 0.0, "avg_mae": 0.0, "fwd_ret_5": 0.0, "fwd_ret_10": 0.0}
        wins = sum(1 for e in grp if e.hit_1_5r_first)
        return {
            "count": len(grp),
            "win_rate_1_5r": round(wins / len(grp) * 100.0, 1),
            "avg_mfe": round(float(np.mean([e.mfe_r for e in grp])), 2),
            "avg_mae": round(float(np.mean([e.mae_r for e in grp])), 2),
            "fwd_ret_5": round(float(np.mean([e.fwd_ret_5_pct for e in grp])), 2),
            "fwd_ret_10": round(float(np.mean([e.fwd_ret_10_pct for e in grp])), 2)
        }

    # Price degradation on waiting for crossover:
    # If lag > 0, compare entry at Engulfing close vs entry at Crossover bar close
    delayed_slippage_r = []
    for e in events:
        if e.lag is not None and e.lag > 0 and e.nearest_crossover_bar is not None and e.risk > 0:
            cross_close = bars[e.nearest_crossover_bar].close
            # Slippage = (Engulfing Close - Crossover Close) / Risk.
            # If price dropped before crossover, slippage is positive (you enter at a worse lower price for a short)
            slip_r = (e.engulf_close - cross_close) / e.risk
            delayed_slippage_r.append(slip_r)
    avg_slippage_r = round(float(np.mean(delayed_slippage_r)), 2) if delayed_slippage_r else 0.0

    stats_all = calc_group_stats(grp_all)
    stats_win_all = calc_group_stats(grp_win_all)
    stats_strict_pos = calc_group_stats(grp_strict_pos)
    stats_unconfirmed = calc_group_stats(grp_unconfirmed)

    return {
        "title": title,
        "total_events": total_events,
        "independent_events_count": len(independent_events),
        "repeated_events_count": total_events - len(independent_events),
        "total_crossovers_in_data": total_crossovers_in_data,
        "crossover_frequency_bars": round(crossover_frequency, 1),
        "structural_precision_pct": round(structural_precision, 2),
        "confirmed_count": len(confirmed_all),
        "confirmation_rate_pct": round(conf_rate * 100.0, 1),
        "conf_ci_95": (round(conf_ci_low * 100.0, 1), round(conf_ci_high * 100.0, 1)),
        "cat_a_count": len(cat_a),
        "origin_birth_count": origin_birth,
        "origin_reaction_count": origin_reaction,
        "cat_b_count": len(cat_b),
        "cat_c_count": len(cat_c),
        "stale_count": stale_count,
        "stale_rate_pct": round(stale_rate * 100.0, 1),
        "stale_ci_95": (round(stale_ci_low * 100.0, 1), round(stale_ci_high * 100.0, 1)),
        "no_conf_count": no_conf_count,
        "no_conf_rate_pct": round(no_conf_rate * 100.0, 1),
        "no_conf_ci_95": (round(no_conf_ci_low * 100.0, 1), round(no_conf_ci_high * 100.0, 1)),
        "mean_lag": round(float(np.mean(target_lags)), 2) if target_lags else 0.0,
        "median_lag": round(float(p50), 2),
        "std_lag": round(float(np.std(target_lags)), 2) if target_lags else 0.0,
        "p25_lag": round(float(p25), 2),
        "p75_lag": round(float(p75), 2),
        "p80_lag": round(float(p80), 2),
        "p90_lag": round(float(p90), 2),
        "pos_lag_dist": pos_lag_dist,
        "pos_lag_cum": pos_lag_cum,
        "avg_slippage_r": avg_slippage_r,
        "stats_all": stats_all,
        "stats_win_all": stats_win_all,
        "stats_strict_pos": stats_strict_pos,
        "stats_unconfirmed": stats_unconfirmed
    }


def print_statistical_report(rep: Dict):
    print("\n" + "=" * 95)
    print(f" {rep['title']}")
    print("=" * 95)
    print(f"1. SAMPLE SIZE & INDEPENDENCE CONTROL:")
    print(f"  Total Valid Events Detected      : {rep['total_events']}")
    print(f"  - Independent Unique-Zone Events : {rep['independent_events_count']} ({rep['independent_events_count'] / max(1, rep['total_events']) * 100.0:.1f}%)")
    print(f"  - Repeated Tests on Same Zone    : {rep['repeated_events_count']}")
    print("-" * 95)
    print(f"2. CONFIRMATION RATES WITH 95% WILSON SCORE CONFIDENCE INTERVALS:")
    print(f"  Confirmation Rate (in window)    : {rep['confirmation_rate_pct']}%  [95% CI: {rep['conf_ci_95'][0]}% - {rep['conf_ci_95'][1]}%]")
    print(f"  Stale Rate (Category D)          : {rep['stale_rate_pct']}%  [95% CI: {rep['stale_ci_95'][0]}% - {rep['stale_ci_95'][1]}%]")
    print(f"  No Confirmation Rate (Category E): {rep['no_conf_rate_pct']}%  [95% CI: {rep['no_conf_ci_95'][0]}% - {rep['no_conf_ci_95'][1]}%]")
    print("-" * 95)
    print(f"3. CLASSIFICATION BREAKDOWN & MECHANICAL ORIGIN ANALYSIS:")
    print(f"  - Cat A. Confirmation Before (lag < 0) : {rep['cat_a_count']} events")
    print(f"      * Tied to Zone Birth Drop          : {rep['origin_birth_count']} ({rep['origin_birth_count']/max(1, rep['cat_a_count'])*100:.1f}%) [Mechanical origin!]")
    print(f"      * Fresh Pre-Reversal Swing         : {rep['origin_reaction_count']} ({rep['origin_reaction_count']/max(1, rep['cat_a_count'])*100:.1f}%)")
    print(f"  - Cat B. Same-Bar Confirmation (lag = 0): {rep['cat_b_count']} events")
    print(f"  - Cat C. Confirmation After (lag > 0)  : {rep['cat_c_count']} events")
    print("-" * 95)
    print(f"4. EMPIRICAL LAG DISTRIBUTION (Independent Sample):")
    print(f"  Mean Lag       : {rep['mean_lag']:+.2f} bars | Median Lag: {rep['median_lag']:+.2f} bars | Std: {rep['std_lag']:.2f}")
    print(f"  25th Percentile: {rep['p25_lag']:+.2f} bars | 75th Percentile: {rep['p75_lag']:+.2f} bars")
    print(f"  80th Percentile: {rep['p80_lag']:+.2f} bars | 90th Percentile: {rep['p90_lag']:+.2f} bars")
    print(f"  Positive Lag (Cat C) Cumulative Catch-Up:")
    for k, cum in rep['pos_lag_cum'].items():
        bar_count = rep['pos_lag_dist'].get(k, 0)
        print(f"    lag <= +{k:<2} bars: {cum:>5.1f}% of Cat C events ({bar_count} events at +{k})")
    print("-" * 95)
    print(f"5. MACRO PRECISION VS. RECALL (Signal-to-Noise across all 35,000 bars):")
    print(f"  Total Bearish Crossovers in Data : {rep['total_crossovers_in_data']} across 35,000 bars")
    print(f"  Average Crossover Frequency      : Every {rep['crossover_frequency_bars']} bars (~{rep['crossover_frequency_bars']*15/60:.1f} hours)")
    print(f"  Structural Precision             : {rep['structural_precision_pct']}% of crossovers coincide with a zone reversal")
    print("-" * 95)
    print(f"6. OUTCOME EFFICACY: DOES REQUIRING MACD CONFIRMATION IMPROVE RESULTS?")
    s_all = rep['stats_all']
    s_win = rep['stats_win_all']
    s_strict = rep['stats_strict_pos']
    s_unconf = rep['stats_unconfirmed']
    print(f"{'Metric':<30} | {'(A) All Setups':<15} | {'(B) Conf [-3,+5]':<16} | {'(C) Conf [0,+5]':<16} | {'(D) Unconfirmed':<15}")
    print("-" * 105)
    print(f"{'Event Count':<30} | {s_all['count']:<15} | {s_win['count']:<16} | {s_strict['count']:<16} | {s_unconf['count']:<15}")
    print(f"{'Win Rate to 1.5R':<30} | {s_all['win_rate_1_5r']:>14.1f}% | {s_win['win_rate_1_5r']:>15.1f}% | {s_strict['win_rate_1_5r']:>15.1f}% | {s_unconf['win_rate_1_5r']:>14.1f}%")
    print(f"{'Avg Max Favorable (MFE)':<30} | {s_all['avg_mfe']:>14.2f}R | {s_win['avg_mfe']:>15.2f}R | {s_strict['avg_mfe']:>15.2f}R | {s_unconf['avg_mfe']:>14.2f}R")
    print(f"{'Avg Max Adverse (MAE)':<30} | {s_all['avg_mae']:>14.2f}R | {s_win['avg_mae']:>15.2f}R | {s_strict['avg_mae']:>15.2f}R | {s_unconf['avg_mae']:>14.2f}R")
    print(f"{'Forward 5-Bar Return':<30} | {s_all['fwd_ret_5']:>14.2f}% | {s_win['fwd_ret_5']:>15.2f}% | {s_strict['fwd_ret_5']:>15.2f}% | {s_unconf['fwd_ret_5']:>14.2f}%")
    print(f"{'Forward 10-Bar Return':<30} | {s_all['fwd_ret_10']:>14.2f}% | {s_win['fwd_ret_10']:>15.2f}% | {s_strict['fwd_ret_10']:>15.2f}% | {s_unconf['fwd_ret_10']:>14.2f}%")
    print("-" * 105)
    print(f"  Price Deterioration / Cost of Waiting (Cat C):")
    print(f"  Average R lost if waiting for crossover to enter: {rep['avg_slippage_r']:+.2f}R")
    print("=" * 95)


# =============================================================================
# 6. SUITE EXECUTION
# =============================================================================
def run_rigorous_diagnostic_suite():
    print("=" * 90)
    print("STARTING LARGE-SAMPLE EMPIRICAL MACD TIMING & EFFICACY SUITE")
    print("Dataset: 35,000 bars of BTCUSDT 15m (1.0 year of continuous 15m candles)")
    print("=" * 90)

    bars_btc_15m = fetch_historical_candles("BTCUSDT", "15m", 35000)
    total_bars = len(bars_btc_15m)

    # 1. Standard MACD(12,26,9)
    diag_std = MacdTimingDiagnostic(bars_btc_15m, fast=12, slow=26, signal=9, search_window=10)
    events_std = diag_std.run_diagnostic()
    rep_std = generate_statistical_report(events_std, "BTCUSDT 15m — Standard MACD(12,26,9)", total_bars, len(diag_std.bearish_crossovers), bars_btc_15m)
    print_statistical_report(rep_std)

    # 2. Faster MACD(8,17,9)
    diag_fast1 = MacdTimingDiagnostic(bars_btc_15m, fast=8, slow=17, signal=9, search_window=10)
    events_fast1 = diag_fast1.run_diagnostic()
    rep_fast1 = generate_statistical_report(events_fast1, "BTCUSDT 15m — Faster MACD(8,17,9)", total_bars, len(diag_fast1.bearish_crossovers), bars_btc_15m)
    print_statistical_report(rep_fast1)

    # 3. Ultra-Fast MACD(5,13,6)
    diag_fast2 = MacdTimingDiagnostic(bars_btc_15m, fast=5, slow=13, signal=6, search_window=10)
    events_fast2 = diag_fast2.run_diagnostic()
    rep_fast2 = generate_statistical_report(events_fast2, "BTCUSDT 15m — Ultra-Fast MACD(5,13,6)", total_bars, len(diag_fast2.bearish_crossovers), bars_btc_15m)
    print_statistical_report(rep_fast2)

    # Export large-sample event dataset to JSON
    export_path = os.path.join(os.path.dirname(__file__), "data", "large_sample_timing_dataset_50k.json")
    try:
        with open(export_path, "w") as f:
            json.dump([e.__dict__ for e in events_std], f, indent=2)
        print(f"\n[SAVED] Exported {len(events_std)} events to {export_path}")
    except Exception as e:
        print(f"[WARN] Failed to export: {e}")

    return rep_std, rep_fast1, rep_fast2, events_std


if __name__ == "__main__":
    run_rigorous_diagnostic_suite()
