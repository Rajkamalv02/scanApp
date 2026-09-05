import json
import sys
from coindcx_client import CoinDCXClient

def test_authentication():
    client = CoinDCXClient()
    if not client.is_configured():
        print("[!] ERROR: CoinDCX API credentials not configured.")
        print("    Please set COINDCX_API_KEY and COINDCX_API_SECRET in the .env file.")
        sys.exit(1)

    print("[*] Testing CoinDCX Authentication with configured API Key...")

    # 1. Test Spot balances (Verified benchmark)
    print("\n[1] Testing Spot Balances (Benchmark: /exchange/v1/users/balances)...")
    try:
        resp = client.send_signed_post("/exchange/v1/users/balances")
        print(f"    Status: {resp.status_code}")
        if resp.status_code == 200:
            balances = resp.json()
            non_zero = [b for b in balances if float(b.get("balance", 0)) > 0 or float(b.get("locked_balance", 0)) > 0]
            print(f"    [SUCCESS] Authentication verified! Total spot assets: {len(balances)}, Non-zero: {len(non_zero)}")
            if non_zero:
                print(f"    Sample balance: {non_zero[0]}")
        else:
            print(f"    [FAILED] Body: {resp.text}")
            return
    except Exception as e:
        print(f"    [ERROR] Exception during request: {e}")
        return

    # 2. Test Futures Wallet Endpoints (Needs Verification in §9)
    print("\n[2] Probing Futures Wallet Endpoints...")
    futures_wallet_candidates = [
        "/exchange/v1/derivatives/futures/wallets",
        "/exchange/v1/derivatives/futures/wallet_details",
        "/exchange/v1/derivatives/futures/positions",
        "/exchange/v1/wallets/details"
    ]

    for endpoint in futures_wallet_candidates:
        try:
            resp = client.send_signed_post(endpoint, {})
            print(f"    Endpoint: {endpoint} -> Status: {resp.status_code}")
            if resp.status_code == 200:
                print(f"    [SUCCESS] Found valid endpoint: {endpoint}")
                data = resp.json()
                print("    Sample response:", json.dumps(data, indent=2)[:500])
            else:
                print(f"    Response snippet: {resp.text[:150]}")
        except Exception as e:
            print(f"    Error hitting {endpoint}: {e}")

if __name__ == "__main__":
    test_authentication()
