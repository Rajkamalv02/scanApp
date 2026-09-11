#!/usr/bin/env python3
import os
import json

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

ema200 = [closes[0]]
a200 = 2.0 / 201.0
for i in range(1, n):
    ema200.append(a200 * closes[i] + (1 - a200) * ema200[-1])

# ADX
tr_s = highs[0] - lows[0]
pdm_s = 0.0
mdm_s = 0.0
adx = [20.0]
for i in range(1, n):
    tr = max(highs[i] - lows[i], abs(highs[i] - closes[i-1]), abs(lows[i] - closes[i-1]))
    up = highs[i] - highs[i-1]
    dn = lows[i-1] - lows[i]
    pdm = up if up > dn and up > 0 else 0.0
    mdm = dn if dn > up and dn > 0 else 0.0
    if i <= 14:
        tr_s += tr
        pdm_s += pdm
        mdm_s += mdm
        adx.append(20.0)
    else:
        tr_s = tr_s - (tr_s / 14) + tr
        pdm_s = pdm_s - (pdm_s / 14) + pdm
        mdm_s = mdm_s - (mdm_s / 14) + mdm
        pdi = 100.0 * pdm_s / tr_s if tr_s > 0 else 0.0
        mdi = 100.0 * mdm_s / tr_s if tr_s > 0 else 0.0
        dx = 100.0 * abs(pdi - mdi) / (pdi + mdi) if (pdi + mdi) > 0 else 0.0
        adx.append((adx[-1] * 13 + dx) / 14)

demand_zones = []
total_demand_zones = 0
engulfing_at_demand = 0
pass_adx = 0
pass_ema = 0

events = []

for i in range(25, n - 25):
    c_open, c_high, c_low, c_close, c_vol = opens[i], highs[i], lows[i], closes[i], volumes[i]
    c_atr = atr[i]
    c_vol_sma = vol_sma[i]

    # Invalidate demand zones
    for z in demand_zones:
        if not z['invalidated']:
            if c_close < z['low']:
                z['invalidated'] = True
            elif (i - z['created_bar']) > 300:
                z['invalidated'] = True
    demand_zones = [z for z in demand_zones if not z['invalidated']]

    # New demand zone
    if (c_close - c_open >= 1.5 * c_atr) and (c_vol >= 1.3 * c_vol_sma):
        # 5-bar high break
        if i >= 5 and c_close > max(highs[i-5:i]):
            base = []
            for k in range(1, 4):
                if i - k < 0: break
                if abs(closes[i-k] - opens[i-k]) <= 1.0 * c_atr:
                    base.append(k)
                else: break
            if base:
                zh = min(min(opens[i-k], closes[i-k]) for k in base)
                zl = min(lows[i-k] for k in base)
                demand_zones.append({'high': zh, 'low': zl, 'created_bar': i, 'touch_count': 0, 'invalidated': False})
                total_demand_zones += 1

    # Bullish Engulfing check
    p_open, p_close = opens[i-1], closes[i-1]
    is_engulf = (p_close < p_open and c_close > c_open and
                 c_open <= (p_close + 0.05 * c_atr) and c_close > p_open and
                 (c_high - c_low) > 0 and ((c_close - c_open) / (c_high - c_low)) >= 0.60 and
                 (c_high - c_close) <= 0.25 * (c_high - c_low) and
                 0.75 * c_atr <= (c_high - c_low) <= 2.5 * c_atr)

    if is_engulf:
        act_z = None
        for z in reversed(demand_zones):
            if not z['invalidated'] and c_low <= z['high'] and c_close >= z['low']:
                act_z = z
                break
        if act_z:
            engulfing_at_demand += 1
            c_adx = adx[i]
            c_pass_adx = c_adx >= 18.0
            if c_pass_adx: pass_adx += 1

            c_ema = ema200[i]
            p_ema = ema200[i-5] if i >= 5 else c_ema
            is_cont = (c_close > c_ema) and (c_ema >= p_ema)
            is_exh = (c_close <= c_ema) and (c_ema - c_close >= 2.5 * c_atr)
            c_pass_ema = (is_cont or is_exh)
            if c_pass_adx and c_pass_ema: pass_ema += 1

            events.append({
                'bar': i,
                'price': c_close,
                'atr': c_atr,
                'zone_low': act_z['low'],
                'adx_pass': c_pass_adx,
                'ema_pass': c_pass_ema
            })

print(f"Total Demand Zones Formed : {total_demand_zones}")
print(f"Bullish Engulfing in Zone : {engulfing_at_demand}")
print(f"Passed ADX >= 18          : {pass_adx} ({pass_adx/max(1, engulfing_at_demand)*100:.1f}%)")
print(f"Passed ADX + EMA 200      : {pass_ema} ({pass_ema/max(1, engulfing_at_demand)*100:.1f}%)")

def eval_cohort(name, cohort):
    if not cohort: return
    win_1r = 0
    win_1_5r = 0
    win_2r = 0
    mfes = []
    maes = []

    for ev in cohort:
        idx = ev['bar']
        entry = opens[idx + 1] if idx + 1 < n else ev['price']
        sl = ev['zone_low'] - 0.3 * ev['atr']
        risk = entry - sl
        if risk <= 0: continue

        mfe = 0.0
        mae = 0.0
        hit_sl = False

        for f in range(1, 96):
            if idx + f >= n: break
            f_high = highs[idx + f]
            f_low = lows[idx + f]

            if f_low <= sl:
                hit_sl = True
                mae = max(mae, (entry - f_low) / risk)
                break
            
            mae = max(mae, (entry - f_low) / risk)
            mfe = max(mfe, (f_high - entry) / risk)

        mfes.append(mfe)
        maes.append(mae)
        if mfe >= 1.0: win_1r += 1
        if mfe >= 1.5: win_1_5r += 1
        if mfe >= 2.0: win_2r += 1

    cnt = len(cohort)
    print(f"\n--- Cohort: {name} (N={cnt}) ---")
    print(f"  Win Rate to 1.0R : {win_1r/cnt*100:.1f}%")
    print(f"  Win Rate to 1.5R : {win_1_5r/cnt*100:.1f}%")
    print(f"  Win Rate to 2.0R : {win_2r/cnt*100:.1f}%")
    print(f"  Average MFE      : {sum(mfes)/len(mfes):.2f}R")
    print(f"  Average MAE      : {sum(maes)/len(maes):.2f}R")

eval_cohort("All Zone + Bullish Engulfings (Unfiltered)", events)
eval_cohort("Passed ADX + EMA 200 Trend Filter", [e for e in events if e['adx_pass'] and e['ema_pass']])
