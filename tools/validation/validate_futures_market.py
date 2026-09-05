import json
from coindcx_client import CoinDCXClient

def test_futures_market_data():
    client = CoinDCXClient()
    print("[*] Probing CoinDCX Futures Market Data Endpoints...")

    # List of candidate futures market endpoints to test
    candidates = [
        # Instruments / Market details
        ("/exchange/v1/derivatives/futures/data/active_instruments", "GET"),
        ("/exchange/v1/derivatives/futures/instruments", "GET"),
        ("/exchange/v1/derivatives/futures/data/market_details", "GET"),
        ("/exchange/v1/markets_details", "GET"),
        ("/exchange/v1/derivatives/futures/data/current_prices", "GET"),
        # Orderbook
        ("/exchange/v1/derivatives/futures/data/orderbook?pair=B-BTC_USDT", "GET"),
        ("/market_data/orderbook?pair=B-BTC_USDT", "GET"),
        # Candles
        ("/exchange/v1/derivatives/futures/data/candles?pair=B-BTC_USDT&interval=1m", "GET"),
        ("/market_data/candles?pair=B-BTC_USDT&interval=1m", "GET")
    ]

    valid_endpoints = []

    for path, method in candidates:
        try:
            url = f"https://api.coindcx.com{path}"
            resp = client.session.get(url, timeout=10)
            print(f"\n[?] {method} {path} -> HTTP {resp.status_code}")
            if resp.status_code == 200:
                data = resp.json()
                valid_endpoints.append(path)
                if isinstance(data, list):
                    print(f"    [SUCCESS] Received list with {len(data)} items.")
                    if data:
                        print("    Sample item:", json.dumps(data[0], indent=2)[:300])
                elif isinstance(data, dict):
                    print(f"    [SUCCESS] Received dict with keys: {list(data.keys())}")
                    print("    Snippet:", json.dumps(data, indent=2)[:300])
            else:
                print(f"    Response snippet: {resp.text[:120]}")
        except Exception as e:
            print(f"    Exception: {e}")

    print("\n" + "="*50)
    print(f"Summary: {len(valid_endpoints)} of {len(candidates)} endpoints responded with 200 OK.")
    for ep in valid_endpoints:
        print(f" - {ep}")

if __name__ == "__main__":
    test_futures_market_data()
