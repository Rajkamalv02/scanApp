import json
import time
import urllib.request
import urllib.error

def query(url):
    try:
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = resp.read().decode('utf-8')
            return resp.status, json.loads(data)
    except urllib.error.HTTPError as e:
        return e.code, str(e.read()[:500])
    except Exception as e:
        return 0, str(e)

print("--- 1. Testing Ticker BBO & Active Instruments ---")
st, instruments = query("https://api.coindcx.com/exchange/v1/derivatives/futures/data/active_instruments")
print(f"Active instruments status: {st}, total count: {len(instruments) if isinstance(instruments, list) else 0}")
sample_pair = "B-BTC_USDT" if isinstance(instruments, list) and "B-BTC_USDT" in instruments else "B-BTC_USDT"

st, ticker = query("https://api.coindcx.com/exchange/ticker")
print(f"Ticker status: {st}")
if isinstance(ticker, list):
    found = [t for t in ticker if t.get("market") in ["B-BTC_USDT", "BTCUSDT", "B-ETH_USDT", "ETHUSDT"]]
    print(f"Sample ticker entry: {json.dumps(found[:2], indent=2)}")

print("\n--- 2. Testing Candles (15m, 1h, 4h, 1d) on CoinDCX ---")
for interval in ["15m", "1h", "4h", "1d"]:
    url = f"https://public.coindcx.com/market_data/candles?pair={sample_pair}&interval={interval}"
    st, candles = query(url)
    if isinstance(candles, list):
        count = len(candles)
        last_candle = candles[-1] if count > 0 else None
        first_candle = candles[0] if count > 0 else None
        print(f"Interval {interval}: Status {st}, Count={count}")
        if count > 0:
            print(f"   First candle: {first_candle}")
            print(f"   Last candle:  {last_candle}")
            now_ms = int(time.time() * 1000)
            diff_ms = now_ms - last_candle.get("time", 0)
            print(f"   Now: {now_ms}, Last time: {last_candle.get('time')}, Delta: {diff_ms} ms ({diff_ms/1000/60:.1f} min)")
    else:
        print(f"Interval {interval}: Status {st}, Resp: {candles[:200]}")

print("\n--- 3. Testing Funding Rates & Open Interest Endpoints ---")
test_funding_endpoints = [
    "https://api.coindcx.com/exchange/v1/derivatives/futures/data/funding_rates",
    "https://public.coindcx.com/exchange/v1/derivatives/futures/data/funding_rates",
    "https://api.coindcx.com/exchange/v1/derivatives/futures/data/contract_details",
    "https://api.coindcx.com/exchange/v1/derivatives/futures/funding",
    "https://public.coindcx.com/market_data/futures/funding_rate",
    f"https://public.coindcx.com/market_data/candles?pair={sample_pair}&interval=8h",
    f"https://api.coindcx.com/exchange/v1/derivatives/futures/data/funding_history?pair={sample_pair}",
    f"https://api.coindcx.com/exchange/v1/derivatives/futures/data/open_interest?pair={sample_pair}",
]
for ep in test_funding_endpoints:
    st, resp = query(ep)
    print(f"Endpoint: {ep}\n   Status: {st}, Resp: {str(resp)[:150]}")
