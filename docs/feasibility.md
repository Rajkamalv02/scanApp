# CoinDCX API Feasibility Spike & Phase 0 Report

**Date:** September 18, 2026  
**Status:** COMPLETE (Phase 0 Go/No-Go Outcomes Documented)  

---

## 1. Executive Summary & Strategy Scope Decisions

| Investigation Item | CoinDCX API Reality | Architectural Impact / Decision |
|---|---|---|
| **Funding Rates & OI** | All funding and Open Interest endpoints (`/funding_rates`, `/funding_history`, `/open_interest`) return **HTTP 404 (Not Found)**. | **CUT Strategy 6 (FPX)** completely per Phase 0 exit criteria. No degraded proxy. |
| **Supported Intervals** | `/market_data/candles` strictly accepts: `1m`, `15m`, `1h`, `1d`. `4h` returns **HTTP 422: interval must be one of [1m, 15m, 1h, 1d]**. | `4h` candles are **synthesized by aggregating four 1h candles** aligned to UTC boundaries (00:00, 04:00, 08:00, 12:00, 16:00, 20:00 UTC). |
| **Candle Ordering & Forming Candle** | Candles are returned in descending order (newest first). The newest candle's timestamp is the **currently forming candle** (`openTime + interval > currentTime`). Max depth per request is 500 candles. | `CandleSeries.fromApi` must sort ascending by time and **structurally drop the newest forming candle**. |
| **Ticker BBO & Spread (G5)** | `/exchange/ticker` returns `bid`, `ask`, `last_price`, `volume`, `high`, `low` for all 502+ active derivatives instruments in a single HTTP call. | G5 spread check runs at zero extra network cost in `SymbolGate`. |
| **Backtest Dataset Venue** | CoinDCX provides max 500 bars per REST call (~5 days of 15m, ~20 days of 1h). For 24-month multi-interval datasets (≥30 symbols), public Binance Futures historical data (which CoinDCX derivatives mirror) is normalized and validated. | Historical data normalized to standard schema; venue differences (funding schedule, liquidation wicks) noted for replay. |

---

## 2. Candle Synthesizer Architecture for 4H Series

CoinDCX natively returns `1h` candles. S10 (EDTM), S5 (XRS), S1 (PBC bias), and S9 (SBOB bias) require 4H candles.
The 4H aggregator groups four 1h candles into one 4h candle:
- `open = h1[0].open`
- `high = max(h1[0..3].high)`
- `low = min(h1[0..3].low)`
- `close = h1[3].close`
- `volume = sum(h1[0..3].volume)`
- `openTime = h1[0].openTime` (where `openTime % (4 * 3600 * 1000) == 0` in UTC).
Any incomplete 4H block (less than 4 closed 1H candles or currently active 4H block) is dropped by `CandleSeries`.

---

## 3. Go / No-Go Decision on Strategy 6 (FPX)

**Outcome: NO-GO (CUT)**  
- CoinDCX does not expose 8h funding rate history or real-time open interest through public API tiers.
- Per §1.20 and §7.1 of the implementation plan, building the degraded price-crowding proxy was explicitly rejected because it is statistically weak and consumes development bandwidth for poor expectancy.
- S6 is removed from the active library. The library will focus on the remaining 9 robust, verified hypotheses.
