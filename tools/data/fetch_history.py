#!/usr/bin/env python3
"""
fetch_history.py
================
Downloads historical kline data for crypto futures pairs across required intervals (15m, 1h, 4h, 1d).
Per Phase 0 feasibility spike, public Binance Futures API is used for deep >=24 month datasets,
with rate limiting, backoff, and pagination.

Usage:
  python fetch_history.py --symbols BTCUSDT,ETHUSDT,SOLUSDT --interval 15m --days 730 --out-dir tools/data/raw
"""

import os
import sys
import time
import json
import argparse
import urllib.request
import urllib.error

INTERVAL_MS = {
    "1m": 60 * 1000,
    "5m": 5 * 60 * 1000,
    "15m": 15 * 60 * 1000,
    "1h": 60 * 60 * 1000,
    "4h": 4 * 60 * 60 * 1000,
    "1d": 24 * 60 * 60 * 1000,
}

BASE_URL = "https://fapi.binance.com/fapi/v1/klines"

TOP_30_LIQUID_SYMBOLS = [
    "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT",
    "DOGEUSDT", "ADAUSDT", "AVAXUSDT", "LINKUSDT", "SUIUSDT",
    "NEARUSDT", "APTUSDT", "DOTUSDT", "LTCUSDT", "BCHUSDT",
    "TRXUSDT", "UNIUSDT", "ICPUSDT", "FETUSDT", "RENDERUSDT",
    "OPUSDT", "ARBUSDT", "FILUSDT", "INJUSDT", "AAVEUSDT",
    "TIAUSDT", "RUNEUSDT", "KASUSDT", "PEPEUSDT", "WIFUSDT"
]

def fetch_klines_batch(symbol: str, interval: str, start_time: int, end_time: int, limit: int = 1500):
    url = f"{BASE_URL}?symbol={symbol}&interval={interval}&startTime={start_time}&endTime={end_time}&limit={limit}"
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) StrategyBacktest/2.0"}
    )
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                if resp.status == 200:
                    return json.loads(resp.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code == 418:
                wait_s = int(e.headers.get("Retry-After", 10 * (attempt + 1)))
                print(f"[WARN] Rate limited (HTTP {e.code}). Backing off for {wait_s}s...")
                time.sleep(wait_s)
            else:
                print(f"[ERROR] HTTP {e.code} for {symbol}: {e.reason}")
                time.sleep(2)
        except Exception as e:
            print(f"[WARN] Request error on attempt {attempt+1}: {e}")
            time.sleep(2)
    return None

def download_symbol_history(symbol: str, interval: str, days: int, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    out_file = os.path.join(out_dir, f"{symbol}_{interval}_raw.json")

    now_ms = int(time.time() * 1000)
    # Exclude currently forming candle
    dur_ms = INTERVAL_MS.get(interval, 15 * 60 * 1000)
    end_time = (now_ms // dur_ms) * dur_ms
    start_time = end_time - (days * 24 * 60 * 60 * 1000)

    print(f"\n[*] Fetching {symbol} [{interval}] from {start_time} to {end_time} (~{days} days)...")
    all_candles = []
    curr_start = start_time

    while curr_start < end_time:
        batch = fetch_klines_batch(symbol, interval, curr_start, end_time, limit=1500)
        if not batch:
            print(f"[!] No more data returned or failure at timestamp {curr_start}")
            break
        all_candles.extend(batch)
        last_close_time = batch[-1][6] # Kline close time
        print(f"    Downloaded {len(batch)} candles, total: {len(all_candles)} (up to {last_close_time})", end="\r")
        if len(batch) < 1500 or last_close_time >= end_time:
            break
        curr_start = last_close_time + 1
        time.sleep(0.2) # Polite delay to stay well within 2400 req/min limit

    print(f"\n[+] Completed {symbol} [{interval}]: {len(all_candles)} total candles.")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(all_candles, f)
    print(f"[✓] Saved to {out_file}")

def main():
    parser = argparse.ArgumentParser(description="Fetch deep historical klines for Crypto Futures Strategy Library")
    parser.add_argument("--symbols", type=str, default=",".join(TOP_30_LIQUID_SYMBOLS[:5]),
                        help="Comma-separated symbols or 'top30'")
    parser.add_argument("--interval", type=str, default="15m", choices=list(INTERVAL_MS.keys()))
    parser.add_argument("--days", type=int, default=730, help="Days of history (default 730 = 24 months)")
    parser.add_argument("--out-dir", type=str, default="tools/data/raw", help="Output directory")
    args = parser.parse_args()

    symbols = TOP_30_LIQUID_SYMBOLS if args.symbols.lower() == "top30" else args.symbols.split(",")
    for sym in symbols:
        sym = sym.strip().upper()
        if sym:
            download_symbol_history(sym, args.interval, args.days, args.out_dir)

if __name__ == "__main__":
    main()
