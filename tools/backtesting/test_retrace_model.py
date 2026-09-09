#!/usr/bin/env python3
import os
import json
import numpy as np

cache_file = os.path.join(os.path.dirname(__file__), "data", "BTCUSDT_15m_35000.json")
with open(cache_file, "r") as f:
    raw = json.load(f)

closes = [float(r[4]) for r in raw]
highs = [float(r[2]) for r in raw]
lows = [float(r[3]) for r in raw]
opens = [float(r[1]) for r in raw]
volumes = [float(r[5]) for r in raw]
n = len(raw)

atr = [highs[0] - lows[0]]
for i in range(1, n):
    tr = max(highs[i] - lows[i], abs(highs[i] - closes[i-1]), abs(lows[i] - closes[i-1]))
    atr.append((atr[-1] * 13 + tr) / 14)

vol_sma = []
win = []
for v in volumes:
    win.append(v)
    if len(win) > 20: win.pop(0)
    vol_sma.append(sum(win) / len(win))

zones = []
events = []

for i in range(25, n - 25):
    c_open, c_high, c_low, c_close, c_vol = opens[i], highs[i], lows[i], closes[i], volumes[i]
    c_atr = atr[i]
    c_vol_sma = vol_sma[i]

    for z in zones:
        if not z['invalidated']:
            if c_close > z['high'] or (i - z['created_bar']) > 300:
                z['invalidated'] = True
    zones = [z for z in zones if not z['invalidated']]

    if (c_open - c_close >= 1.5 * c_atr) and (c_vol >= 1.3 * c_vol_sma):
        base = []
        for k in range(1, 4):
            if i - k < 0: break
            if abs(closes[i-k] - opens[i-k]) <= 1.0 * c_atr:
                base.append(k)
            else: break
        if base:
            zh = max(highs[i-k] for k in base)
            zl = max(max(opens[i-k], closes[i-k]) for k in base)
            zones.append({'high': zh, 'low': zl, 'created_bar': i, 'invalidated': False})

    p_open, p_close = opens[i-1], closes[i-1]
    is_engulf = (p_close > p_open and c_close < c_open and
                 c_open >= (p_close - 0.05 * c_atr) and c_close < p_open and
                 (c_high - c_low) > 0 and ((c_open - c_close) / (c_high - c_low)) >= 0.60 and
                 (c_close - c_low) <= 0.25 * (c_high - c_low) and
                 0.75 * c_atr <= (c_high - c_low) <= 2.5 * c_atr)

    if is_engulf:
        act_z = None
        for z in reversed(zones):
            if not z['invalidated'] and c_high >= z['low'] and c_close <= z['high']:
                act_z = z
                break
        if act_z:
            events.append({
                'idx': i,
                'open': c_open,
                'high': c_high,
                'low': c_low,
                'close': c_close,
                'atr': c_atr,
                'zone_high': act_z['high'],
                'zone_low': act_z['low']
            })

print(f"Total events: {len(events)}")

# Test Retracement Entry + Tight Invalidation
def test_retrace_tight_sl(retrace_pct=0.50, sl_buffer=0.15, target_r=1.5):
    filled = 0
    wins = 0
    losses = 0
    total_r = 0.0
    fee_rate = 0.0008

    for ev in events:
        idx = ev['idx']
        c_open, c_high, c_close, c_atr = ev['open'], ev['high'], ev['close'], ev['atr']
        zh = ev['zone_high']

        limit_entry = c_close + retrace_pct * (c_open - c_close)
        fill_bar = None
        for k in range(1, 5):
            if idx + k >= n: break
            if highs[idx + k] >= limit_entry:
                fill_bar = idx + k
                break
        if fill_bar is None: continue

        sl = c_high + sl_buffer * c_atr
        risk = sl - limit_entry
        if risk <= 0 or risk > 2.0 * c_atr: continue

        target_p = limit_entry - target_r * risk
        filled += 1

        hit_tp = False
        hit_sl = False

        for k in range(1, 30):
            if fill_bar + k >= n: break
            bh = highs[fill_bar + k]
            bl = lows[fill_bar + k]

            if bh >= sl:
                hit_sl = True
                break
            if bl <= target_p:
                hit_tp = True
                break

        if hit_tp:
            wins += 1
            total_r += target_r - (fee_rate * limit_entry / risk)
        elif hit_sl:
            losses += 1
            total_r -= 1.0 + (fee_rate * limit_entry / risk)
        else:
            final_p = closes[min(n - 1, fill_bar + 29)]
            realized_r = (limit_entry - final_p) / risk
            total_r += realized_r - (fee_rate * limit_entry / risk)
            if realized_r > 0: wins += 1
            else: losses += 1

    wr = (wins / filled * 100.0) if filled > 0 else 0.0
    ev_r = (total_r / filled) if filled > 0 else 0.0
    print(f"[Retrace {retrace_pct*100:.0f}% | SL +{sl_buffer:.2f} ATR | Target {target_r:.1f}R] Filled: {filled:<3} | Win Rate: {wr:>5.1f}% | Total Net R: {total_r:>+6.2f}R | Expectancy: {ev_r:>+5.3f}R")

print("\n--- RETRACEMENT ENTRY & TIGHT STOP MATRIX ---")
for r_pct in [0.382, 0.50, 0.618]:
    for buf in [0.10, 0.15, 0.25]:
        for tr in [1.5, 2.0, 2.5]:
            test_retrace_tight_sl(r_pct, buf, tr)
