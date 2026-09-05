import json
import time
import sys
import uuid
from coindcx_client import CoinDCXClient

def test_futures_order_lifecycle():
    client = CoinDCXClient()
    if not client.is_configured():
        print("[!] ERROR: CoinDCX API credentials not configured in .env.")
        sys.exit(1)

    print("[*] CoinDCX Futures Order Lifecycle Test (Create -> Status -> Cancel)")
    print("    Safety rule: Using a limit buy order far below market price to ensure it never fills.")

    client_oid = f"test_bot_{int(time.time())}_{uuid.uuid4().hex[:6]}"
    pair = "B-BTC_USDT"
    safety_limit_price = 15000.0  # Safe limit price (current BTC ~80k, will not fill)
    quantity = 0.001              # Minimal quantity
    leverage = 2

    order_payload = {
        "order": {
            "side": "buy",
            "pair": pair,
            "order_type": "limit_order",
            "price": safety_limit_price,
            "total_quantity": quantity,
            "leverage": leverage,
            "notification": "no_notification",
            "time_in_force": "good_till_cancel",
            "hidden": False,
            "post_only": False,
            "client_order_id": client_oid
        }
    }

    print(f"\n[1] Submitting Test Limit Order:")
    print(f"    Pair: {pair} | Price: {safety_limit_price} | Qty: {quantity} | ClientOrderId: {client_oid}")
    
    try:
        resp = client.send_signed_post("/exchange/v1/derivatives/futures/orders/create", order_payload)
        print(f"    HTTP Status: {resp.status_code}")
        order_data = resp.json()
        print("    Response:", json.dumps(order_data, indent=2))

        if resp.status_code != 200:
            print("[!] Order creation failed. Inspect error message above.")
            return

        order_id = order_data.get("id") or order_data.get("order_id")
        print(f"\n[2] Order successfully created with ID: {order_id}")

        # Query order list to verify client_order_id matching
        print("\n[3] Verifying order in Open Orders list...")
        time.sleep(1)
        list_resp = client.send_signed_post("/exchange/v1/derivatives/futures/orders", {"page": "1", "size": "10"})
        if list_resp.status_code == 200:
            open_orders = list_resp.json()
            matching = [o for o in open_orders if str(o.get("id")) == str(order_id) or o.get("client_order_id") == client_oid]
            if matching:
                print(f"    [VERIFIED] Found order in open orders matching client_order_id!")
                print("    Open order snippet:", json.dumps(matching[0], indent=2))
            else:
                print("    [?] Order not immediately found in open orders list.")

        # Immediate Cancellation
        print(f"\n[4] Canceling test order {order_id} immediately...")
        cancel_payload = {"id": order_id}
        cancel_resp = client.send_signed_post("/exchange/v1/derivatives/futures/orders/cancel", cancel_payload)
        print(f"    Cancel HTTP Status: {cancel_resp.status_code}")
        print("    Cancel Response:", cancel_resp.text)

    except Exception as e:
        print(f"    [ERROR] Exception occurred: {e}")

if __name__ == "__main__":
    test_futures_order_lifecycle()
