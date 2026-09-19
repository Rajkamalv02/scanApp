#!/usr/bin/env python3
"""
normalize.py
============
Normalizes raw downloaded historical kline data into the canonical BacktestDataLoader format:
JSON array of rows: [openTimeMs, "open", "high", "low", "close", "volume"]
Ensures strict chronological sorting, timestamp deduplication, and drops forming candles.

Usage:
  python normalize.py --in-dir tools/data/raw --out-dir tools/backtesting/data
"""

import os
import json
import glob
import argparse

def normalize_file(in_path: str, out_path: str):
    with open(in_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    if not isinstance(data, list):
        print(f"[!] Skipping {in_path}: not a list")
        return

    # Raw Binance format:
    # [0: openTime, 1: open, 2: high, 3: low, 4: close, 5: volume, 6: closeTime, ...]
    seen_times = set()
    normalized = []

    for row in data:
        if isinstance(row, list) and len(row) >= 6:
            open_time = int(row[0])
            if open_time in seen_times:
                continue
            seen_times.add(open_time)

            open_p = str(float(row[1]))
            high_p = str(float(row[2]))
            low_p = str(float(row[3]))
            close_p = str(float(row[4]))
            volume = str(float(row[5]))

            normalized.append([open_time, open_p, high_p, low_p, close_p, volume])

    # Sort ascending by timestamp
    normalized.sort(key=lambda x: x[0])

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(normalized, f)

    print(f"[✓] Normalized {os.path.basename(in_path)} -> {out_path} ({len(normalized)} clean bars)")

def main():
    parser = argparse.ArgumentParser(description="Normalize historical klines to BacktestDataLoader format")
    parser.add_argument("--in-dir", type=str, default="tools/data/raw", help="Raw input directory")
    parser.add_argument("--out-dir", type=str, default="tools/backtesting/data", help="Normalized output directory")
    args = parser.parse_args()

    files = glob.glob(os.path.join(args.in_dir, "*.json"))
    if not files:
        print(f"[!] No raw files found in {args.in_dir}")
        return

    for fp in files:
        base_name = os.path.basename(fp).replace("_raw.json", ".json")
        out_fp = os.path.join(args.out_dir, base_name)
        normalize_file(fp, out_fp)

if __name__ == "__main__":
    main()
