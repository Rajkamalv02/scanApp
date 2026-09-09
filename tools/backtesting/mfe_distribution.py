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
            entry_p = opens[i+1] if i+1 < n else c_close
            sl_p = max(c_high, act_z['high']) + 0.3 * c_atr
            risk = sl_p - entry_p
            if risk <= 0: continue

            # Track bar-by-bar excursion over next 25 bars
            mfes = []
            maes = []
            stopped = False
            for k in range(1, 26):
                if i + 1 + k >= n: break
                bh = highs[i + 1 + k]
                bl = lows[i + 1 + k]
                mfe = (entry_p - bl) / risk
                mae = (bh - entry_p) / risk
                mfes.append(mfe)
                maes.append(mae)

            events.append({
                'risk': risk,
                'risk_atr': risk / c_atr,
                'max_mfe': max(mfes) if mfes else 0.0,
                'max_mae': max(maes) if maes else 0.0,
                'mfe_series': mfes,
                'mae_series': maes
            })

print(f"Total events: {len(events)}")
risk_atrs = [e['risk_atr'] for e in events]
print(f"Average Risk Distance : {np.mean(risk_atrs):.2f}x ATR (Min: {min(risk_atrs):.2f}, Max: {max(risk_atrs):.2f})")

# Percentile distribution of MFE
all_mfes = [e['max_mfe'] for e in events]
print(f"MFE 25th Percentile   : {np.percentile(all_mfes, 25):.2f}R")
print(f"MFE 50th (Median)     : {np.percentile(all_mfes, 50):.2f}R")
print(f"MFE 75th Percentile   : {np.percentile(all_mfes, 75):.2f}R")
print(f"MFE 90th Percentile   : {np.percentile(all_mfes, 90):.2f}R")

# Threshold hit rates (before being stopped at 1.0R SL)
print("\n--- FIRST TOUCH: REACHING TARGET BEFORE HITTING STOP LOSS (SL = 1.0R) ---")
for t_r in [0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5]:
    hits = 0
    for e in events:
        hit = False
        stopped = False
        for mfe, mae in zip(e['mfe_series'], e['mae_series']):
            if mae >= 1.0:
                stopped = True
                break
            if mfe >= t_r:
                hit = True
                break
        if hit and not stopped:
            hits += 1
    pct = hits / len(events) * 100.0
    ev = (pct / 100.0 * t_r) - ((100 - pct) / 100.0 * 1.0) - 0.05
    print(f"Target: {t_r:>4.2f}R | Hit Rate: {pct:>5.1f}% | Net Expectancy (after 0.08% fees): {ev:>+6.3f}R / trade")
