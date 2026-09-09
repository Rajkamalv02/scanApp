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

# RSI 14
gains = [0.0]
losses = [0.0]
for i in range(1, n):
    diff = closes[i] - closes[i-1]
    gains.append(max(0.0, diff))
    losses.append(max(0.0, -diff))

avg_gain = sum(gains[:14]) / 14
avg_loss = sum(losses[:14]) / 14
rsi = [50.0] * 14
for i in range(14, n):
    avg_gain = (avg_gain * 13 + gains[i]) / 14
    avg_loss = (avg_loss * 13 + losses[i]) / 14
    rs = avg_gain / (avg_loss + 1e-9)
    rsi.append(100.0 - (100.0 / (1.0 + rs)))

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

    # Zone creation
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
            disp_size = (c_open - c_close) / c_atr
            zones.append({
                'id': len(zones) + 1,
                'high': zh,
                'low': zl,
                'created_bar': i,
                'disp_size': disp_size,
                'touch_count': 0,
                'invalidated': False
            })

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
            act_z['touch_count'] += 1
            entry_p = opens[i+1] if i+1 < n else c_close
            sl_p = max(c_high, act_z['high']) + 0.3 * c_atr
            risk = sl_p - entry_p
            if risk <= 0: continue

            # Track 1.5R outcome
            target_p = entry_p - 1.5 * risk
            hit_tp = False
            hit_sl = False
            for k in range(1, 25):
                if i + 1 + k >= n: break
                bh = highs[i + 1 + k]
                bl = lows[i + 1 + k]
                if bh >= sl_p:
                    hit_sl = True
                    break
                if bl <= target_p:
                    hit_tp = True
                    break

            # Features
            is_virgin = (act_z['touch_count'] == 1)
            zone_age = i - act_z['created_bar']
            vol_ratio = c_vol / (c_vol_sma + 1e-9)
            body_ratio = (c_open - c_close) / (c_high - c_low)
            rsi_val = rsi[i]
            swept_zone_high = (c_high >= act_z['high']) # False breakout / liquidity sweep!
            disp_size = act_z['disp_size']

            events.append({
                'hit_tp': hit_tp,
                'hit_sl': hit_sl,
                'is_virgin': is_virgin,
                'zone_age': zone_age,
                'vol_ratio': vol_ratio,
                'body_ratio': body_ratio,
                'rsi': rsi_val,
                'swept_high': swept_zone_high,
                'disp_size': disp_size
            })

print(f"Total events analyzed: {len(events)}")
wins = [e for e in events if e['hit_tp']]
losses = [e for e in events if e['hit_sl']]

print(f"Overall 1.5R Win Rate: {len(wins)/len(events)*100:.1f}%\n")

def compare_feature(name, feat_fn):
    val_wins = [feat_fn(e) for e in wins]
    val_losses = [feat_fn(e) for e in losses]
    print(f"Feature: {name}")
    print(f"  Avg in Winners : {np.mean(val_wins):.3f}")
    print(f"  Avg in Losers  : {np.mean(val_losses):.3f}")

compare_feature("Displacement Size (ATR)", lambda e: e['disp_size'])
compare_feature("Zone Age (Bars)", lambda e: e['zone_age'])
compare_feature("Engulfing Volume Ratio", lambda e: e['vol_ratio'])
compare_feature("Engulfing Body Ratio", lambda e: e['body_ratio'])
compare_feature("RSI at Signal", lambda e: e['rsi'])
compare_feature("Virgin Zone Rate (%)", lambda e: 100.0 if e['is_virgin'] else 0.0)
compare_feature("Swept Zone High Rate (%)", lambda e: 100.0 if e['swept_high'] else 0.0)

# Check subgroup win rates:
print("\n--- SUBGROUP 1.5R WIN RATES ---")
def subgroup(name, cond_fn):
    sub = [e for e in events if cond_fn(e)]
    if not sub: return
    w = sum(1 for e in sub if e['hit_tp'])
    print(f"{name:<45} | N={len(sub):<3} | Win Rate: {w/len(sub)*100:.1f}%")

subgroup("Baseline (All Events)", lambda e: True)
subgroup("Displacement >= 2.0 ATR (Strong Imbalance)", lambda e: e['disp_size'] >= 2.0)
subgroup("Displacement < 2.0 ATR (Weak Imbalance)", lambda e: e['disp_size'] < 2.0)
subgroup("Fresh Zone (Age <= 60 bars / 15 hrs)", lambda e: e['zone_age'] <= 60)
subgroup("Old Zone (Age > 60 bars)", lambda e: e['zone_age'] > 60)
subgroup("High Volume (Vol >= 1.2x SMA)", lambda e: e['vol_ratio'] >= 1.2)
subgroup("Low Volume (Vol < 1.0x SMA)", lambda e: e['vol_ratio'] < 1.0)
subgroup("Overbought RSI >= 60 at Supply", lambda e: e['rsi'] >= 60.0)
subgroup("Oversold RSI < 45 at Supply", lambda e: e['rsi'] < 45.0)
subgroup("Swept Zone High (Liquidity Sweep)", lambda e: e['swept_high'])
subgroup("Did Not Sweep Zone High", lambda e: not e['swept_high'])
subgroup("Virgin Zone Only", lambda e: e['is_virgin'])
subgroup("Tested Zone (Touch >= 2)", lambda e: not e['is_virgin'])
