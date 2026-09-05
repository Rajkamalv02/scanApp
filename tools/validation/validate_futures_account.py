import json
import sys
from coindcx_client import CoinDCXClient

def test_futures_account():
    client = CoinDCXClient()
    if not client.is_configured():
        print("[!] ERROR: CoinDCX API credentials not configured in .env.")
        sys.exit(1)

    print("[*] Testing CoinDCX Futures Account & Positions...")

    # 1. Test Positions endpoint
    print("\n[1] Querying Futures Positions (/exchange/v1/derivatives/futures/positions)...")
    payload = {
        "page": "1",
        "size": "50",
        "margin_currency_short_name": ["USDT"]
    }
    try:
        resp = client.send_signed_post("/exchange/v1/derivatives/futures/positions", payload)
        print(f"    HTTP Status: {resp.status_code}")
        if resp.status_code == 200:
            positions = resp.json()
            print(f"    [SUCCESS] Positions retrieved! Total positions: {len(positions)}")
            if positions:
                print("    Sample Position Schema:")
                print(json.dumps(positions[0], indent=2))
        else:
            print(f"    [FAILED] {resp.text}")
    except Exception as e:
        print(f"    Error: {e}")

    # 2. Test Open Futures Orders
    print("\n[2] Querying Open Futures Orders (/exchange/v1/derivatives/futures/orders)...")
    orders_payload = {
        "page": "1",
        "size": "50"
    }
    try:
        resp = client.send_signed_post("/exchange/v1/derivatives/futures/orders", orders_payload)
        print(f"    HTTP Status: {resp.status_code}")
        if resp.status_code == 200:
            orders = resp.json()
            print(f"    [SUCCESS] Orders retrieved! Total open orders: {len(orders)}")
            if orders:
                print("    Sample Order Schema:")
                print(json.dumps(orders[0], indent=2))
        else:
            print(f"    [FAILED] {resp.text}")
    except Exception as e:
        print(f"    Error: {e}")

    # 3. Test Futures Wallet Balance (Verified endpoint)
    print("\n[3] Querying Futures Wallets (/exchange/v1/derivatives/futures/wallets)...")
    try:
        resp = client.send_signed_get("/exchange/v1/derivatives/futures/wallets")
        print(f"    HTTP Status: {resp.status_code}")
        if resp.status_code == 200:
            wallets = resp.json()
            print("    [SUCCESS] Futures Wallets retrieved:")
            print(json.dumps(wallets, indent=2))
        else:
            print(f"    [FAILED] {resp.text}")
    except Exception as e:
        print(f"    Error: {e}")

    # 4. Test Cross Margin Details (Verified endpoint)
    print("\n[4] Querying Cross Margin Details (/exchange/v1/derivatives/futures/positions/cross_margin_details)...")
    try:
        resp = client.send_signed_get("/exchange/v1/derivatives/futures/positions/cross_margin_details")
        print(f"    HTTP Status: {resp.status_code}")
        if resp.status_code == 200:
            print("    [SUCCESS] Cross margin details response:", resp.json())
    except Exception as e:
        print(f"    Error: {e}")

if __name__ == "__main__":
    test_futures_account()
