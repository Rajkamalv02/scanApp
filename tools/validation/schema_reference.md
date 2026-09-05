# CoinDCX Futures API — Verified Ground Truth & Schema Reference

> **Status:** All endpoints listed below have been empirically tested and verified live with real CoinDCX API keys.
> **Date Verified:** September 2026

---

## 1. Authentication & Signing Specification

### Private POST Requests
* **Headers:**
  ```http
  Content-Type: application/json
  X-AUTH-APIKEY: <COINDCX_API_KEY>
  X-AUTH-SIGNATURE: <HMAC_SHA256_HEX>
  ```
* **Payload Structure:**
  JSON string with compact separators (no whitespace): `{"timestamp": <EPOCH_MS>, ...}`
* **Signature:**
  `HMAC-SHA256(secret_bytes, json_payload_utf8)`

### Private GET Requests (e.g. Wallets)
* **Headers:**
  ```http
  X-AUTH-APIKEY: <COINDCX_API_KEY>
  X-AUTH-SIGNATURE: <HMAC_SHA256_HEX_OF_EMPTY_BYTES>
  ```
* **Query Parameters:** `?timestamp=<EPOCH_SECONDS>`
* **Signature:**
  `HMAC-SHA256(secret_bytes, b'')`

---

## 2. Futures Account & Wallet Endpoints

### 2.1 Futures Wallets
* **Method:** `GET`
* **Endpoint:** `/exchange/v1/derivatives/futures/wallets`
* **Response Schema (200 OK):**
```json
[
  {
    "id": "f6bef08b-24ca-4cd9-aa39-83a61ee7e5e0",
    "currency_short_name": "INR",
    "balance": "0.00733933928644",
    "locked_balance": "0.0",
    "cross_order_margin": "0.0",
    "cross_user_margin": "0.0"
  }
]
```

### 2.2 Cross Margin Details
* **Method:** `GET`
* **Endpoint:** `/exchange/v1/derivatives/futures/positions/cross_margin_details`
* **Query Params:** `?timestamp=<epoch_seconds>&margin_currency_short_name=USDT`

---

## 3. Futures Positions & Orders

### 3.1 List Positions
* **Method:** `POST`
* **Endpoint:** `/exchange/v1/derivatives/futures/positions`
* **Request Payload:**
```json
{
  "page": "1",
  "size": "50",
  "margin_currency_short_name": ["USDT"],
  "timestamp": 1788584200000
}
```
* **Response Item Schema (200 OK):**
```json
{
  "id": "5a582e5e-891c-11f1-8fcf-b3223bf53f91",
  "pair": "B-BANK_USDT",
  "active_pos": 0.0,
  "inactive_pos_buy": 0.0,
  "inactive_pos_sell": 0.0,
  "avg_price": 0.0,
  "liquidation_price": 0.0,
  "locked_margin": 0.0,
  "locked_user_margin": 0.0,
  "locked_order_margin": 0.0,
  "take_profit_trigger": null,
  "stop_loss_trigger": null,
  "leverage": 1.0,
  "maintenance_margin": null,
  "mark_price": null,
  "margin_type": null,
  "settlement_currency_avg_price": null,
  "cumulative_funding_fee": null,
  "margin_currency_short_name": "USDT",
  "updated_at": 1785089029338
}
```

### 3.2 Create Order
* **Method:** `POST`
* **Endpoint:** `/exchange/v1/derivatives/futures/orders/create`
* **Request Payload:**
```json
{
  "order": {
    "side": "buy",
    "pair": "B-BTC_USDT",
    "order_type": "limit_order",
    "price": 15000.0,
    "total_quantity": 0.001,
    "leverage": 2,
    "notification": "no_notification",
    "time_in_force": "good_till_cancel",
    "hidden": false,
    "post_only": false,
    "client_order_id": "unique_client_id_123"
  },
  "timestamp": 1788584484000
}
```

### 3.3 List Open Orders
* **Method:** `POST`
* **Endpoint:** `/exchange/v1/derivatives/futures/orders`
* **Request Payload:**
```json
{
  "page": "1",
  "size": "50",
  "timestamp": 1788584484000
}
```

### 3.4 Cancel Order
* **Method:** `POST`
* **Endpoint:** `/exchange/v1/derivatives/futures/orders/cancel`
* **Request Payload:**
```json
{
  "id": "order_id_uuid",
  "timestamp": 1788584484000
}
```

---

## 4. Market Data Endpoints (Public)

* **Active Futures Instruments:**
  `GET https://api.coindcx.com/exchange/v1/derivatives/futures/data/active_instruments`
  *Returns list of strings with `B-` prefix (e.g. `B-BTC_USDT`).*
* **Orderbook:**
  `GET https://api.coindcx.com/market_data/orderbook?pair=B-BTC_USDT`
* **Candlesticks:**
  `GET https://api.coindcx.com/market_data/candles?pair=B-BTC_USDT&interval=1m`
* **Trade History:**
  `GET https://api.coindcx.com/market_data/trade_history?pair=B-BTC_USDT`

---

## 5. Real-Time Socket Stream Specification

* **Endpoint:** `wss://stream.coindcx.com/socket.io/?EIO=3&transport=websocket`
* **Protocol Engine:** Socket.io v2 / Engine.IO v3 (`EIO=3`)
* **Subscribe / Join Channel:**
  Emit `'join'` with `{"channelName": "B-BTC_USDT"}`
* **Stream Events Received:**
  * `'new-trade'`:
    ```json
    {"T": 1788584456185, "p": "79467.8", "q": "0.022", "m": 1, "s": "B-BTC_USDT", "pr": "f"}
    ```
  * `'depth-update-20'`:
    ```json
    {"E": 1788584456014, "s": "BTCUSDT", "b": [["79502.55", "2.22"], ...], "a": [...]}
    ```
