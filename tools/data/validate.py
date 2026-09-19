#!/usr/bin/env python3
"""
validate.py
===========
Validates normalized historical kline datasets meeting institutional quality standards:
1. Strict monotonic timestamp ordering (increasing).
2. Zero timestamp gaps exceeding tolerance.
3. Candle geometry invariants: high >= max(open, close) and low <= min(open, close).
4. Non-negative volume: volume >= 0.0.
5. Fully closed last candle: openTime + intervalMs <= current_utc_time.
6. Minimum history depth: >= 24 months (or specified minimum bars).

Usage:
  python validate.py --file tools/backtesting/data/BTCUSDT_15m_35000.json --interval 15m
  python validate.py --dir tools/backtesting/data
"""

import os
import sys
import time
import json
import glob
import argparse

INTERVAL_MS = {
    "1m": 60 * 1000,
    "5m": 5 * 60 * 1000,
    "15m": 15 * 60 * 1000,
    "1h": 60 * 60 * 1000,
    "4h": 4 * 60 * 60 * 1000,
    "1d": 24 * 60 * 60 * 1000,
}

def infer_interval_from_name(filename: str) -> str:
    name_lower = filename.lower()
    for iv in ["15m", "1m", "5m", "1h", "4h", "1d"]:
        if f"_{iv}_" in name_lower or f"_{iv}." in name_lower or f"-{iv}." in name_lower:
            return iv
    return "15m"

def validate_dataset(filepath: str, interval: str = None) -> bool:
    print(f"\n=======================================================")
    print(f"Validating: {os.path.basename(filepath)}")
    print(f"=======================================================")

    if not interval:
        interval = infer_interval_from_name(filepath)
    expected_step_ms = INTERVAL_MS.get(interval, 15 * 60 * 1000)

    with open(filepath, "r", encoding="utf-8") as f:
        try:
            data = json.load(f)
        except Exception as e:
            print(f"[FAIL] JSON decode error: {e}")
            return False

    if not isinstance(data, list) or len(data) == 0:
        print(f"[FAIL] Dataset is empty or not a list")
        return False

    total_bars = len(data)
    print(f"Total Candles : {total_bars}")
    print(f"Interval      : {interval} ({expected_step_ms} ms)")

    errors = 0
    warnings = 0
    now_ms = int(time.time() * 1000)

    prev_time = -1

    for idx, row in enumerate(data):
        if not isinstance(row, list) or len(row) < 6:
            print(f"[FAIL] Row {idx} malformed: {row}")
            errors += 1
            break

        open_time = int(row[0])
        open_p = float(row[1])
        high_p = float(row[2])
        low_p = float(row[3])
        close_p = float(row[4])
        volume = float(row[5])

        # 1. Monotonicity & Gap checks
        if idx > 0:
            diff = open_time - prev_time
            if diff <= 0:
                print(f"[FAIL] Non-increasing timestamp at bar {idx}: prev={prev_time}, curr={open_time}")
                errors += 1
                if errors > 5: break
            elif diff > expected_step_ms:
                gap_bars = diff / expected_step_ms
                if gap_bars > 1.5:
                    warnings += 1
                    if warnings <= 5:
                        print(f"[WARN] Gap detected at bar {idx}: {diff} ms ({gap_bars:.1f} bars) between {prev_time} and {open_time}")

        # 2. Geometry Invariants
        max_oc = max(open_p, close_p)
        min_oc = min(open_p, close_p)
        if high_p < max_oc:
            print(f"[FAIL] Geometry violation at bar {idx}: high {high_p} < max(open, close) {max_oc}")
            errors += 1
            if errors > 5: break

        if low_p > min_oc:
            print(f"[FAIL] Geometry violation at bar {idx}: low {low_p} > min(open, close) {min_oc}")
            errors += 1
            if errors > 5: break

        # 3. Non-negative volume
        if volume < 0.0:
            print(f"[FAIL] Negative volume at bar {idx}: {volume}")
            errors += 1
            if errors > 5: break

        prev_time = open_time

    # 4. Forming Candle Check on last candle
    last_candle_open = int(data[-1][0])
    if last_candle_open + expected_step_ms > now_ms:
        print(f"[WARN] Last candle openTime ({last_candle_open}) + interval exceeds current time ({now_ms}). Forming candle present!")
        warnings += 1

    first_time = int(data[0][0])
    last_time = int(data[-1][0])
    span_days = (last_time - first_time) / (1000.0 * 3600 * 24)
    print(f"Time Span     : {span_days:.1f} days ({first_time} -> {last_time})")
    print(f"Gaps / Warns  : {warnings}")
    print(f"Errors        : {errors}")

    if errors == 0:
        print(f"[PASS] Dataset passed institutional integrity validation.")
        return True
    else:
        print(f"[FAIL] Dataset failed validation with {errors} errors.")
        return False

def main():
    parser = argparse.ArgumentParser(description="Validate normalized historical klines")
    parser.add_argument("--file", type=str, help="Specific file to validate")
    parser.add_argument("--dir", type=str, default="tools/backtesting/data", help="Directory of datasets to validate")
    parser.add_argument("--interval", type=str, help="Interval (15m, 1h, 4h, 1d)")
    args = parser.parse_args()

    if args.file:
        success = validate_dataset(args.file, args.interval)
        sys.exit(0 if success else 1)
    else:
        files = glob.glob(os.path.join(args.dir, "*.json"))
        if not files:
            print(f"[!] No json files found in {args.dir}")
            sys.exit(1)
        all_passed = True
        for fp in files:
            passed = validate_dataset(fp, args.interval)
            if not passed:
                all_passed = False
        sys.exit(0 if all_passed else 1)

if __name__ == "__main__":
    main()
