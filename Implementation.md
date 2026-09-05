# IMPLEMENTATION_PLAN.md
## CoinDCX Futures Auto-Trading App — V1 Feasibility & Implementation Plan

**Status:** Planning only. No application code has been written.
**Author context:** Personal-use project, real capital involved, target device Samsung Galaxy A30s.
**Primary source:** `https://docs.coindcx.com/` (official CoinDCX API reference, fetched directly during this planning pass), cross-checked only where noted against community libraries (`coindcx-futures`, `svamja/coindcx-python`) — never treated as authoritative over the official docs.

> **Reading key used throughout this document:**
> - **Verified** — confirmed directly from `docs.coindcx.com` content retrieved during this planning session.
> - **Needs Verification** — the official docs reference the endpoint/feature by name (confirmed present in the doc's own table of contents) but the exact request/response schema was not captured in this pass and must be pulled and tested before coding against it.
> - **Assumption** — a reasonable inference, not stated outright in the docs.
> - **Not Supported** — not found anywhere in official docs; treat as unavailable until proven otherwise.

---

## 1. Project Overview

A personal-use Android application that connects to a single CoinDCX account and runs an automated crypto **futures** trading strategy, with monitoring, safety controls, and a kill switch. Strategy logic itself is out of scope for this architecture plan — it is a replaceable module. This plan does not choose a strategy; your existing UCE / crypto-futures-bot work is the eventual strategy source, kept fully decoupled from execution plumbing.

**Primary design priority, in order:** reliability + safety + functionality + ₹0 cost + maintainability. UI polish is explicitly last.

**Non-goals for V1:** polished UI, multi-exchange support, multi-account support, cloud dashboards, social/sharing features, in-app charting, in-app backtesting.

---

## 2. Core V1 Requirements — Feasibility Classification

| Requirement | Feasibility | Notes |
|---|---|---|
| Connect to CoinDCX account (API key/secret) | **Verified** | HMAC-SHA256 signed REST, `X-AUTH-APIKEY` + `X-AUTH-SIGNATURE` headers, confirmed on `/exchange/v1/...` endpoints. |
| Read account/balance info | **Verified** (spot) / **Needs Verification** (futures wallet) | Spot: `POST /exchange/v1/users/balances` confirmed. Futures uses a separate wallet; funds move via `POST /exchange/v1/wallets/transfer` (verified, `source_wallet_type`/`destination_wallet_type` = spot/futures). A dedicated futures "Wallet Details" endpoint is listed in the docs TOC but its schema needs a direct pull before coding. |
| Monitor futures markets | **Verified** (spot equivalents) / **Needs Verification** (futures-specific paths) | Spot market data (`/market_data/candles`, `/market_data/orderbook`, `/market_data/trade_history`) confirmed with parameters. Futures Endpoints TOC lists parallel calls ("Get instrument candlesticks", "Get instrument orderbook", "Get Current Prices RT") — same shape expected, must be pulled and diffed against spot before coding. |
| Automated trading strategy | **Verified as architecturally possible** | No API blocker; gated only by CoinDCX's Algorithmic Trading terms (§9.1). |
| Place futures orders | **Needs Verification** | TOC confirms "Create Order" exists under Futures Endpoints. A community-documented (unofficial) schema shows `POST /exchange/v1/derivatives/futures/orders/create` with `pair`, `side`, `order_type`, `price`, `total_quantity`, `leverage` — this is **Assumption-grade** until confirmed against the official page directly in Phase 2. Do not code against the community schema without that confirmation. |
| Cancel/monitor open orders | **Needs Verification** | TOC lists "Cancel Order", "List Orders", "Edit Order" under Futures Endpoints — names confirmed, schemas pending. Community docs suggest `POST /exchange/v1/derivatives/futures/orders/cancel` with `id` — same Assumption-grade caveat applies. |
| Monitor active positions | **Needs Verification** | TOC lists "List Positions", "Get Positions By pairs or positionid" — confirmed to exist. Community docs suggest `POST /exchange/v1/derivatives/futures/positions` with `page`/`size` pagination — Assumption-grade, pending official confirmation. |
| Detect completed trades / trade history | **Needs Verification** | TOC lists "Get Trades" under Futures Endpoints (distinct from spot's Account Trade History). Spot equivalent is verified and gives an expected shape template (trade id, order id, side, price, quantity, fee, timestamp). |
| Track historical trades | **Needs Verification** | Same endpoint as above; local persistence required regardless (§12). |
| Realized / Unrealized P&L | **Assumption — must be computed app-side** | No single documented "give me P&L" field was found in the official docs retrieved. Treat CoinDCX as a source of raw fills/positions only, and build a dedicated P&L engine from entry price, exit price, quantity, leverage, and fees (§10). |
| Start/stop trading, emergency stop | **Verified as buildable** | App-side state machine, not an API feature. CoinDCX's "Cancel All Open Orders" and "Exit Position" (both in the Futures Endpoints TOC) are the underlying primitives a kill switch calls. |
| Recover from network/API/app failure | **Verified as buildable, non-trivial** | Entirely your reconciliation logic (§6); no API feature does this for you. |

**Bottom line:** nothing in your requirement list is *Not Supported*. But the requirements that matter most for real-money safety — futures order placement schema, position schema, exact P&L fields — are **Needs Verification**, not Verified. §19 Phase 2 is a mandatory, dedicated pass hitting every futures endpoint with a live key and a minimal test order before any strategy code is written. "The docs mention this endpoint" is not the same as "I have seen its actual JSON shape" — this plan does not conflate the two, and neither should implementation.

---

## 3. ZERO-COST REQUIREMENT — Component Classification

| Component | Category | Why |
|---|---|---|
| CoinDCX REST/WebSocket API | **Free** | CoinDCX's own API Terms (§3.1) state no fees are presently levied for API use, though they reserve the right to change this. |
| Android device (Galaxy A30s) | **Free** | Already-owned hardware. |
| Kotlin + Android SDK + Gradle | **Free** | Fully open/free tooling. |
| Room (SQLite wrapper) | **Free** | Local, no server, no license cost. |
| Android Studio | **Free** | Free IDE from Google. |
| Git / GitHub (private repo) | **Free** | Free tier sufficient for a solo personal project. |
| WorkManager / Foreground Service | **Free** | Part of the Android SDK; free but see §5 — free does not automatically mean sufficiently reliable. |
| Local logging (files/DB on device) | **Free** | No SaaS logging needed at this scale. |
| Any cloud VPS (AWS/GCP/Azure/DigitalOcean) | **Required Paid**, if used | Smallest reliable always-on VPS realistically runs roughly ₹300–700/month once free-tier credits expire. |
| Oracle Cloud "Always Free" ARM VM | **Free Tier**, genuinely free but operationally fragile | Real always-free compute (not a trial) — but India-region capacity availability is inconsistent and it carries no SLA. See §15. |
| Telegram Bot API (notifications/remote kill-switch trigger) | **Free** | No cost, no meaningful rate-limit concern at personal-use volume. |
| Domain name | **Not required for V1** | No public-facing service is needed; skip entirely. |
| SSL certificate | **Not required for V1** | Only relevant if a backend is exposed publicly — avoided by design (§4). |
| CI/CD (GitHub Actions) | **Free Tier** | Free minutes/month sufficient for a solo repo's build checks. |

**Target: ₹0 initial, ₹0/month ongoing — achievable, on the condition that no backend server is used (Option A, §4).** If a backend is added later, the honest floor is either the Oracle Free Tier (genuinely ₹0 but operationally fragile) or roughly ₹300–500/month for a small reliable VPS. Stated plainly rather than glossed over.

---

## 4. Critical Architecture Decision: Where Does the Trading Engine Run?

### Option A — Everything on Android (Samsung A30s)

| Factor | Assessment |
|---|---|
| Cost | ₹0. No server. |
| Reliability | Moderate risk, manageable with discipline. The A30s is not a server; Android actively tries to kill background work to save battery. |
| Background restrictions | Samsung's "Sleeping apps"/battery management (One UI, based on Android 10 on the A30s) is more aggressive than stock Android and known to kill foreground services if the user doesn't manually whitelist the app. Real, documented risk, not hypothetical. |
| Battery | A foreground service + WebSocket + periodic reconciliation is a moderate but sustainable drain if optimized; for genuine 24/7 operation the phone should stay on charger continuously. |
| App termination | Mitigated via a Foreground Service (not a plain background Service) — far less likely to be killed than a background service, though not immune under memory pressure on a low-RAM device. |
| Phone restart | Requires a `BOOT_COMPLETED` receiver to restart the service. Trading must never auto-resume blind after this — startup reconciliation (§6) is mandatory. |
| Internet interruptions | Must be designed for from day one (Wi-Fi/mobile-data switching is normal on a phone) — see §6. |
| Security / API-key protection | Key/secret stored on-device only, in Android Keystore-backed encrypted storage (§8). Never transits a third-party server — an actual security advantage of this option. |
| Latency | Home Wi-Fi/4G latency to CoinDCX from India is likely 50–200ms — acceptable for a swing/intraday strategy, not acceptable for HFT-style strategies (which this project should not attempt regardless — see §9.1). |
| Maintenance | Single codebase, single deployment target — lowest overhead of the three options. |
| Complexity | Lowest of the three options. |
| Failure recovery | Achievable, but must be deliberately built (Foreground Service + WorkManager watchdog + startup reconciliation). |
| Suitability for real-money trading | Acceptable for a non-latency-sensitive strategy, with the mandatory safety nets in §6–§7. Not acceptable if left ungoverned. |

### Option B — Backend-Hosted Trading Engine

| Factor | Assessment |
|---|---|
| Cost | Required Paid for a reliable always-on VPS, or Free Tier with real operational risk (Oracle Always Free — §15). |
| Reliability | Highest, if a paid always-on VPS is used — no Doze mode, no app-swap risk. |
| Background restrictions | N/A — no Android lifecycle concerns for the engine itself. |
| Battery | N/A for the engine; only the monitoring app on the phone has a battery concern. |
| App termination | Irrelevant — engine survives independent of the Android app's lifecycle. Biggest reliability win of Option B. |
| Phone restart | Irrelevant to trading continuity; phone becomes a pure monitoring client. |
| Internet interruptions | Still relevant between phone and backend for monitoring, but does not affect the engine's connection to CoinDCX, which is the critical path. |
| Server availability | Depends on the chosen host; a paid VPS from a reputable provider has real uptime SLAs, free tiers do not. |
| Security / API-key protection | Worse than Option A unless done carefully — the key/secret now lives on a server that needs its own hardening (firewall, SSH lockdown, patching, encrypted secrets at rest). More attack surface than a phone that never listens for inbound connections. |
| Latency | Marginally better with good datacenter peering, but the difference is not meaningful at the speeds this project should run. |
| Maintenance | Significantly higher — OS patching, dependency updates, process supervision (systemd/pm2), monitoring the monitor. |
| Complexity | Highest — two deployable units with a sync/API contract between them. |
| Failure recovery | Needs the same reconciliation logic as Option A, plus infra-level recovery. |
| Suitability for real-money trading | Best-in-class if paid and properly hardened; overkill and cost-inefficient for this project's actual latency needs. |

### Option C — Hybrid

In practice this collapses to Option B for a solo zero-cost project — "engine elsewhere" still needs to be always-on, meaning either a server (cost) or the phone itself (Option A again). The only genuinely distinct hybrid worth naming — a second dedicated old Android device running only the bot, acting as its own always-on host — is a variant of Option A, not a true third architecture. Noted as an optional resilience upgrade in §18/§15, not a separate path.

### Recommendation: Use Option A (Android-only) for V1

Reasoning:
1. Your existing strategy work (UCE, crypto-futures-bot) is swing/intraday, not HFT — server-grade responsiveness is not required.
2. The zero-cost constraint is a hard, stated requirement. Option B cannot honestly satisfy it without either recurring cost or accepting the real operational fragility of a free-tier VM for a system trading real money — a worse trade-off than a well-built Android foreground service.
3. API-key custody is safer on Option A — there is no server to breach.
4. The main historical failure mode of Option A (Android killing the process) is known and mitigable via foreground service + battery-optimization whitelist + BOOT_COMPLETED receiver + startup reconciliation — not an unsolvable problem.

**Condition attached to this recommendation:** treat "the phone is not a server" as a first-class design constraint. Aggressive position-size and daily-loss limits (§7), mandatory startup reconciliation after every restart (§6), and — critically — during any period the phone is genuinely offline (dead battery, no signal, OS killed it anyway), the system defaults to flat/paused and places no new orders. It never assumes the last known state and keeps trading blind. This is a safety-over-uptime choice, and it is the correct one for real money on this hardware.

---

## 5. Samsung A30s Compatibility

**Hardware (public specs):** Exynos 7904 octa-core SoC, 3GB/4GB RAM variants, 32GB/64GB storage, 4000mAh battery, shipped with Android 9/10 (One UI 2), officially upgradeable to Android 11 (One UI 3.1) as its final version.

| Constraint | Implication |
|---|---|
| CPU (2018-era, mid-low tier) | Fine for REST calls, JSON parsing, SQLite I/O, and a lightweight strategy loop. Not fine for heavy on-device computation — keep backtesting and any CPU-intensive analysis on a PC; ship only the finalized live-decision logic to the phone. |
| RAM (3–4GB, shared with One UI overhead) | Small memory footprint required. Don't load full historical candle datasets into memory — page from Room. Prefer a lighter UI layer (XML or minimal Compose) over recomposition-heavy patterns. |
| Storage (32/64GB) | Trivial concern — a personal bot's trade logs and SQLite DB will be megabytes, not gigabytes, for years. |
| Max official Android version: 11 (API 30) | This app is sideloaded for personal use, not Play-Store distributed, so Play's targetSdk mandate doesn't apply. Recommendation: **minSdk 29** (matches the A30s's shipped OS, Android 10), **compileSdk/targetSdk = latest stable at build time** for library compatibility. |
| Doze mode / App Standby Buckets | Real risk, stock Android behavior. A Foreground Service with an active notification is exempt from Doze's harshest restrictions, but the device can still apply "Rare"/"Restricted" standby buckets if the app looks unused. Mitigate by opening the app periodically and requesting `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. |
| One UI's extra battery layer ("Sleeping apps", "Adaptive Battery") | More aggressive than stock Doze in practice. Must be manually disabled for this app in Settings → Battery → Background usage limits. This is a **mandatory manual setup step** — the app cannot silently guarantee this for itself. |
| Background execution limits (Android 8+) | Sidestepped by using a Foreground Service (exempt from these limits) rather than a plain Service or JobScheduler for the always-on loop. |
| WebSocket connections | Fine to hold from a Foreground Service; must handle `ConnectivityManager.NetworkCallback` for Wi-Fi↔mobile-data handoffs and proactively reconnect rather than waiting for a dead-socket timeout. |
| Local database performance | Room/SQLite handles thousands of rows without perceptible lag at personal-use scale — not a bottleneck. |

**Design constraints this implies:** no animations beyond default system transitions; no image-heavy UI or large third-party UI libraries; strategy computation kept lightweight on-device (heavy analysis stays on a PC); minSdk 29.

---

## 6. Failure Scenarios

For each scenario: what can go wrong → detection → system response → continue trading? → manual intervention? → recovery.

**Internet connection lost.** Orders in flight may not confirm; market data goes stale. Detected via `ConnectivityManager` callback and consecutive request timeouts. Response: pause new order placement immediately, keep the UI showing "disconnected," continue attempting reconnect on backoff. Trading does not continue. No manual intervention required unless the outage is prolonged. Recovers automatically on reconnect, followed by a full reconciliation pass — never a blind resume.

**Internet connection returns.** Detected via the connectivity callback firing "available" plus a successful lightweight API ping (e.g., a balance check). Response: run full reconciliation (see below) before allowing the strategy to place any new order. Trading resumes only after reconciliation confirms local state matches CoinDCX's actual state.

**CoinDCX API is unavailable (5xx, outage).** Detected via repeated 5xx or connection-refused responses distinguishable from local network loss (local connectivity is up, but calls to CoinDCX specifically fail). Response: pause new entries, do not touch existing positions blindly, alert via Telegram/notification, retry on exponential backoff. Manual intervention only if the outage exceeds a configurable threshold (e.g., 15+ minutes), at which point the user is prompted to manually check the CoinDCX app/website.

**API rate limits reached.** Detected via 429 responses (spot rate limits are documented — e.g., 2000/60s for order creation, 300/60s for active orders; futures limits are **not published anywhere in official CoinDCX docs found during this research and must not be assumed to match spot** — see §9). Response: back off requests globally, not per-call; a token-bucket limiter local to the app should keep well under a conservatively-assumed ceiling proactively, rather than treating 429s as the normal way limits are discovered.

**API returns an error on order placement.** Detected via a non-success response body/status. Response: log the full request/response pair, and do not assume the order didn't happen (see "order submission succeeds but response is lost" below) — always follow an ambiguous order outcome with an explicit order-status query before deciding what happened.

**WebSocket disconnects.** Detected via socket close/error event. Response: mark market data as stale immediately (never silently keep using the last price), attempt reconnect with backoff, fall back to REST polling for critical data (position/price) while the socket is down.

**WebSocket reconnects.** Response: re-subscribe to all required channels, then explicitly re-fetch a fresh snapshot (positions, orderbook) via REST rather than trusting the socket to backfill everything missed while disconnected.

**App is closed (by user or OS).** The Foreground Service is designed to keep running independent of the Activity/UI being open — closing the visible app screen must not stop the trading service. If the user explicitly force-stops the app from Android settings, the service does stop, and by design no auto-restart happens without the user reopening the app (a force-stop is a deliberate signal, not treated as a crash).

**Android kills the app despite the foreground service (rare, but possible under extreme memory pressure).** Detected on next launch via a heartbeat timestamp written to local storage on every loop iteration — if the last heartbeat is older than an expected threshold, the app knows it was killed. Response on relaunch: full reconciliation before resuming, and the failure is logged as a risk event, not silently absorbed.

**Phone restarts.** A `BOOT_COMPLETED` receiver restarts the foreground service, but the service starts in a **paused** state, not an active-trading state, and immediately runs full reconciliation. Trading only resumes after reconciliation passes and (recommended) requires the user to explicitly re-arm trading after a restart, rather than the app silently self-resuming with real money at stake.

**Phone battery dies.** Undetectable in real time by definition (no process running). Detected retroactively on next boot via the heartbeat-gap check above. Any position that existed before battery death remains open on CoinDCX's side regardless — this is why exchange-side stop-loss orders (§7) are non-negotiable, not just app-side risk logic: the app cannot protect a position it isn't running to see.

**Application crashes.** Caught via a global uncaught-exception handler that logs the crash with full context before the process dies, and a WorkManager-scheduled periodic health check (separate from the foreground service) that can detect "the service should be running but isn't" and prompt the user via notification — WorkManager should not be relied on to silently restart trading automatically, only to alert.

**Order request times out.** This is the single most dangerous ambiguous state: the order may or may not have reached CoinDCX. Response: never retry blindly. Always follow up with an order-status query using the `client_order_id` generated locally (CoinDCX's spot order endpoints support `client_order_id` lookups, verified; futures support for the same needs confirmation in Phase 2) before deciding whether to retry — a unique `client_order_id` per attempt is what makes "did my specific order go through" answerable rather than a guess from a fresh order list.

**Order submission succeeds but response is lost (network drops after send, before response received).** Functionally identical to a timeout from the app's perspective and resolved the same way: query by `client_order_id`, never assume success or failure.

**Order status is unknown.** Treated as "must resolve before doing anything else" — the strategy engine is blocked from acting on that symbol/position until the ambiguous order is resolved via an explicit status query, with a maximum retry count before escalating to a user alert requiring manual check.

**Order partially filled.** Detected via the documented `partially_filled` status (confirmed as a valid status value in the docs' Terminology section). Response: position/quantity tracking treats partial fills as first-class, not an edge case — the local position record updates incrementally, and every risk calculation (§7) uses actual filled quantity, never the originally requested quantity.

**Order rejected.** Detected via the documented `rejected` status. Response: log the rejection reason if provided, alert, and never silently retry with the same parameters — a rejection often signals something structural (insufficient margin, an invalid quantity/price step, a leverage mismatch against an already-open position on that pair), and blind retry could compound the problem.

**Position information becomes stale.** Defined as: no successful position-sync call within a configurable freshness window (e.g., 60 seconds during active trading). Response: the strategy engine is blocked from opening new positions on stale data; existing exchange-side stop-losses remain the safety net regardless of app freshness.

**Market data becomes stale.** Same treatment — a freshness timestamp on every price/candle update; the strategy engine refuses to act on data older than its configured threshold.

**Strategy crashes (an exception inside the pluggable strategy module).** Isolated via a try/catch boundary around the entire strategy-evaluation call, so a bug in the strategy module cannot crash the risk manager, order manager, or reconciliation logic. On strategy exception: log it, disable further new entries from that strategy, and alert — existing positions remain protected by exchange-side stops, and the app keeps running (monitoring, reconciliation) even with the strategy disabled.

**Database becomes unavailable/corrupted.** Rare but not impossible (e.g., after an unclean shutdown). Response: wrap DB access in try/catch, and on corruption detection, fail safe — disable new trading, alert the user, and treat CoinDCX's live account state (fetched fresh via API) as the source of truth for manual recovery, since the local DB is a cache/audit trail, not the primary record of what's actually open.

**Device time is incorrect.** CoinDCX's authentication requires a timestamp and rejects requests that deviate too much from server time (confirmed in the docs' Authentication section). Response: on every app start (and periodically), compare device time drift against a trusted source; if drift is significant, alert the user that authenticated calls will fail until device time is corrected — enabling Android's "Automatic date & time" is the standard remedy to recommend, since this is a system-level issue the app cannot silently fix on its own.

---

## 7. Trading Safety

**Hard limits (enforced app-side before every order, not just designed once and assumed):**
- Maximum position size (₹ or quantity, per symbol and in aggregate).
- Maximum leverage per position. **Confirmed exchange-level constraint:** once a position is opened at a given leverage, all further orders on that pair must use the same leverage until the position is fully closed — this must be respected by the order manager to avoid rejected orders, not treated as a soft UI suggestion.
- Maximum daily loss — a hard circuit breaker; once hit, trading auto-disables for the day with no silent override.
- Maximum number of concurrently open trades.
- Maximum total exposure across all open positions.
- Duplicate-order prevention via `client_order_id` idempotency — every order the app generates gets a unique, deterministic `client_order_id` so a retried request can never accidentally double-execute.
- Startup reconciliation (mandatory, §6): on every app/service start, before any new order is allowed, fetch live positions/orders/balance from CoinDCX and reconcile against local DB state; any mismatch blocks new trading until resolved or acknowledged.
- Ongoing position reconciliation on a regular cadence during operation, not only at startup.
- Ongoing order reconciliation the same way.
- Stale-data protection (§6).
- API timeout handling with explicit ambiguous-state resolution via status queries, never blind retry (§6).
- Emergency stop / kill switch: a single control (in-app button, and ideally also reachable via a Telegram command since the phone screen might not be accessible in the moment) that immediately calls Cancel All Open Orders and Exit Position for every open position, then disables the trading loop until manually re-armed.
- Trading enable/disable as a persisted, explicit state — never implied merely by whether the app happens to be open.
- Crash recovery (§6).
- Logging and audit trail (§17) — every order attempt, fill, and risk-limit trigger logged with enough detail to reconstruct exactly what happened and why, after the fact.

**Additional safety mechanisms:**
- **An exchange-side stop-loss on every position, always, placed immediately after entry confirms.** This is the single most important safety mechanism given Option A's architecture, because it protects the position even if the phone is completely offline (dead battery, killed app, no signal). App-side risk logic is a second layer, not a substitute for this.
- **A cooldown/circuit-breaker after N consecutive losses**, independent of the daily-loss limit, to catch a malfunctioning strategy before it burns through the daily limit in one bad sequence.
- **A "confirm before first real-money order" manual gate.** Even after paper trading passes, the very first live order should require an explicit manual confirmation tap, not a fully silent auto-start.
- **Sanity bounds on every order before submission** — reject (app-side, before even calling the API) any order whose size, leverage, or price deviates implausibly from expectations (e.g., a strategy bug computing a 100x-too-large quantity), as a last line of defense independent of exchange-side limits.

**On the "API returned success = trade definitely happened" fallacy:** the order manager's state machine explicitly models an `UNKNOWN` intermediate state for any order whose outcome wasn't cleanly confirmed (timeout, dropped response, ambiguous error). No downstream logic — P&L calculation, position count, risk exposure — is allowed to treat an `UNKNOWN`-state order as either filled or not-filled until a status query resolves it. This is a first-class state in the order lifecycle, not an exception path bolted on afterward.

---

## 8. Security

**API key/secret handling:**
- **Never hardcoded** in source or committed to any repository, including private ones — loaded at runtime from encrypted storage only.
- Stored on-device (Option A architecture, §4) using **Android Keystore-backed encryption** (e.g., `EncryptedSharedPreferences` from Jetpack Security, which wraps a Keystore-generated key) — the raw secret is never in plaintext in SharedPreferences, a file, or a database column.
- No backend exists to relay through in the recommended architecture, so there is no "secret transits a server" risk to design around at all.
- Backups: **exclude the credential store from Android auto-backup** (`android:allowBackup="false"` or explicit backup-rules exclusion) so encrypted key material never leaves the device via Google's backup service either.
- Application logs must **never** contain the API secret, and should avoid logging the full API key too — log a masked/truncated identifier if debugging ever needs one.

**API key permissions:** CoinDCX's API dashboard allows creating a key with an optional IP binding (confirmed in the docs' Setup section). Recommendations:
- **Bind the key to your home/mobile IP if it is stable enough to be practical** — this meaningfully limits blast radius if the key is ever exfiltrated, at the cost of needing to update the binding if your IP changes (a real friction point on mobile data with no static IP; weigh this against your actual network setup before committing to it).
- Use the **minimum permission scope CoinDCX's dashboard exposes** — if the key-creation UI allows scoping to trading-only with no withdrawal permission, always exclude withdrawal permission from a key used by an automated bot. (This is a dashboard-level setting to check directly at key-creation time — **Needs Verification**, not visible in the docs pages retrieved.)

**Where should credentials live? On the Android phone, in encrypted local storage — not on a backend, because there is no backend in this architecture.** If a future version ever adds a backend (§15 explains why this plan recommends against it for V1), the credential-custody question would need to be revisited entirely, since a server-side secret introduces a new class of risk — server compromise — that doesn't exist in the recommended architecture at all.

**Biggest security concern for this project overall:** a stolen, lost, or rooted phone. The API key lives encrypted on-device, but decrypted key material is used in memory during signing operations, so physical device compromise (theft, malware with root access) is the realistic worst case — mitigated by device-level security (screen lock, disk encryption, default on modern Android) but not something the app itself can fully prevent. Stated plainly: don't leave a rooted or unlocked phone that also runs this bot lying around.

---

## 9. CoinDCX API Research Summary

| Capability | Status | Detail |
|---|---|---|
| Authentication | **Verified** | HMAC-SHA256 over the JSON-encoded request body, secret as HMAC key, sent as `X-AUTH-SIGNATURE` alongside `X-AUTH-APIKEY`. All authenticated calls are POST with a mandatory `timestamp` field; server rejects requests with excessive clock drift. |
| API key requirements | **Verified** | Generated from CoinDCX's API dashboard (`coindcx.com/api-dashboard`); optional IP binding at creation time. |
| API permissions/scoping | **Needs Verification** | Whether granular scopes (e.g., trade-only vs. trade+withdraw) exist must be checked directly in the dashboard UI at key-creation time. |
| Futures support | **Verified (exists)** / **Needs Verification (full schema)** | A dedicated "Futures End Points" and "Futures Sockets" section exists in the official docs with roughly 25 named endpoints (Create/Cancel/Edit Order, List/Get Positions, Exit Position, Create TP/SL, Get Trades, Get Transactions, Wallet Details/Transactions, leverage/margin management, etc.) — names confirmed via the table of contents; full schemas need a dedicated fetch pass. |
| Market data (spot) | **Verified** | Ticker, Markets, Markets Details, Trades, Order Book, Candles — all confirmed with parameters and response shapes. |
| Market data (futures) | **Needs Verification** | Parallel futures-specific endpoints named in the TOC ("Get instrument details", "Get instrument Real-time trade history", "Get instrument orderbook", "Get instrument candlesticks", "Get Current Prices RT", "Get Pair Stats") — expected to mirror spot's shape, not yet confirmed. |
| WebSockets | **Verified (exist)** / **Needs Verification (futures schema)** | Both Spot Sockets and a separate Futures Sockets section exist, covering position updates, order updates, balance updates, candlesticks, orderbook, current prices, new trades, LTP. Spot socket setup requires `socket.io` **version 2.4.0 specifically** — a real, easy-to-miss dependency-pinning constraint confirmed in the docs. |
| Order placement | **Needs Verification (futures)** | Spot order placement is fully confirmed (`POST /exchange/v1/orders/create`, max 25 open orders per market). Futures "Create Order" exists per the TOC; a community (unofficial) schema for `POST /exchange/v1/derivatives/futures/orders/create` was found but is **Assumption-grade** — confirm against the official page directly before coding. |
| Order cancellation | **Needs Verification (futures)** / **Verified (spot)** | Spot cancel / cancel-all / cancel-by-ids fully confirmed. Futures equivalents ("Cancel Order", "Cancel All Open Orders", "Cancel All Open Orders for Position") exist per the TOC, schema pending. |
| Order status | **Needs Verification (futures)** / **Verified (spot)** | Spot order-status and multiple-order-status are confirmed, including that both numeric `id` and `client_order_id` lookups are supported — this is the exact mechanism §6/§7 rely on for resolving ambiguous order outcomes. Futures "List Orders" exists per the TOC; confirm the same `client_order_id` lookup capability exists there before relying on it. |
| Position information | **Needs Verification** | "List Positions" and "Get Positions By pairs or positionid" exist per the TOC; schema (unrealized-P&L field presence, margin, liquidation price, etc.) needs direct verification. |
| Balance | **Verified (spot)** / **Needs Verification (futures)** | Spot balances endpoint fully confirmed. Futures wallet is separate (confirmed via the Wallet Transfer endpoint's spot/futures enum); a dedicated futures balance/margin endpoint is named in the TOC ("Wallet Details") but its schema is pending. |
| Margin | **Needs Verification** | "Add Margin"/"Remove Margin" endpoints exist for both Margin Orders and Futures per the TOC; schemas pending for the futures variant. |
| Leverage | **Verified (constraint)** / **Needs Verification (mechanics)** | Confirmed: leverage is fixed per open position on a pair until that position is fully closed (all further orders on that pair must match). "Update position leverage" exists per the TOC for setting it; exact request shape pending. |
| Stop-loss / take-profit | **Needs Verification** | A dedicated "Create Take Profit and Stop Loss Orders" endpoint exists per the TOC for futures — good news structurally, since native exchange-side SL/TP is what §7's safety design depends on — but the schema needs direct verification before that design can be finalized. |
| Trade history | **Needs Verification (futures)** / **Verified (spot)** | Spot Account Trade History is fully confirmed with pagination-style filters (`from_id`, timestamps, symbol). Futures "Get Trades" exists per the TOC, schema pending. |
| Order history | **Needs Verification** | Covered by the same "List Orders"/status endpoints noted above for futures. |
| P&L information | **Not confirmed as a direct field — Assumption: must be computed app-side** | No standalone "P&L" endpoint was found. Positions may carry an unrealized-P&L-style field once the Position schema is pulled (Needs Verification), but realized P&L across a trade's full lifecycle (entry fees + exit fees + any funding) should be assumed to require your own computation, not a single trusted API number. |
| Funding rate (perpetual futures) | **Needs Verification** | If CoinDCX's futures product includes perpetual contracts with periodic funding, this affects P&L and must be pulled into the P&L engine; unconfirmed whether a dedicated funding-rate endpoint exists in what was retrieved. |
| Fees | **Needs Verification (futures)** / **Verified (spot, structurally)** | Spot order responses include `fee`, `maker_fee`, `taker_fee`, `fee_amount` fields (confirmed). Futures fee fields need direct verification — reconcile your existing fee-tier research (from your bot's prior work) against whatever the futures order/trade response actually returns. |
| Rate limits | **Verified (spot only)** / **Not found (futures)** | Spot rate-limit table fully confirmed (e.g., 2000/60s create-order, 300/60s active-orders, 30/60s cancel-all). No equivalent futures rate-limit table exists anywhere in official CoinDCX documentation found during this research (checked specifically) — **do not assume futures shares spot's limits or invent a number; confirm empirically in Phase 2 by deliberately probing until a 429 occurs.** |
| Error handling | **Needs Verification** | The docs TOC lists a dedicated "Errors" section; its content was not retrieved in this pass and must be pulled before finalizing the error-classification logic in §6. |
| Futures-specific restrictions | **Needs Verification** | Beyond the confirmed same-pair-same-leverage constraint, other futures-specific limits (max leverage per pair, minimum order size per instrument — likely exposed via a "Get instrument details" call, per community docs' `min_trade_size` field, official schema still pending) should be pulled per-instrument at runtime rather than hardcoded, since these vary by pair and can change. |

### 9.1 A note on CoinDCX's own Terms regarding algorithmic trading

CoinDCX's API Terms and Conditions (confirmed, retrieved directly) define both "Algorithmic Trading" and "High-Frequency Trading" as defined terms, and §6.4 of those terms has the User represent and warrant that **"It has the relevant licenses to conduct any High Frequency Trading or Algorithmic Trading and shall ensure that relevant licenses/approvals/consents are obtained if the same is required under any Applicable Law(s)."** This is a real contractual representation made by using the API for automated trading, not boilerplate to skim past. This plan is not legal advice — flagging it explicitly as something to independently satisfy yourself about. A personal-use retail bot trading your own single account is a very different regulatory posture than a commercial HFT operation, but the terms don't carve that distinction out explicitly, so this is worth your own diligence rather than an assumption that it's automatically fine.

---

## 10. Trading Strategy Architecture

Refined pipeline (adds an explicit reconciliation stage and a data-freshness gate the original brief's sketch left implicit):

```
Market Data Feed (WebSocket + REST fallback)
        |
        v
Data Freshness Gate  (blocks stale data from reaching the strategy)
        |
        v
Strategy Engine  (pluggable, stateless w.r.t. execution — emits signals only)
        |
        v
Risk Manager  (position size, leverage, daily loss, exposure, sanity bounds — §7)
        |
        v
Order Manager  (client_order_id generation, idempotency, ambiguous-state resolution)
        |
        v
CoinDCX API  (REST for orders, WebSocket for fills/position updates)
        |
        v
Reconciliation Engine  (continuous, not just at startup — diffs local vs. exchange state)
        |
        v
P&L Engine  (computes realized/unrealized P&L from fills — CoinDCX likely doesn't hand this over as a single field, §9)
        |
        v
Local Database (Room/SQLite)  (audit-trail source of truth; exchange remains source of position truth)
        |
        v
Android Dashboard  (read-only view over the database + live snapshot calls)
```

**Component responsibilities:**

- **Market Data Feed** — owns the WebSocket connection lifecycle (connect, subscribe, reconnect on drop) and the REST polling fallback when the socket is down. Publishes price/candle updates with a timestamp. Has no knowledge of strategy or risk logic.
- **Data Freshness Gate** — a thin checkpoint that strategy/risk queries must pass through; refuses to hand out data older than a configured threshold, forcing callers to handle "no fresh data available" explicitly rather than silently getting stale numbers.
- **Strategy Engine** — the only component meant to be swapped out. Consumes market data, produces a signal (enter long/short, exit, no action) with a suggested size/SL/TP. **Never touches the API directly** and never holds authoritative state about what's actually open — it asks the Risk Manager what's currently open rather than remembering state itself, so restarting or swapping strategies mid-operation can't desync it from reality.
- **Risk Manager** — the safety layer from §7. Takes a strategy's proposed signal and either approves it (possibly resizing it down to fit limits) or rejects it outright with a reason. The only component allowed to veto a trade, checked on every single signal, not just at startup.
- **Order Manager** — translates an approved signal into an actual API call, owns `client_order_id` generation and the idempotency/ambiguous-state-resolution logic from §6–§7. This is where "did the order actually happen" gets resolved before anything downstream trusts it.
- **Reconciliation Engine** — runs on a schedule, and on every startup/reconnect, independent of the trading loop; compares local DB state against a fresh pull of CoinDCX's actual positions/orders/balance and flags discrepancies. Auto-corrects only read-state drift (e.g., "position size differs") by trusting the exchange as ground truth; never auto-corrects by silently placing corrective orders without user visibility.
- **P&L Engine** — computes realized P&L per closed trade (entry/exit price, quantity, leverage, fees) and aggregates for daily/overall views; computes unrealized P&L for open positions from live mark price. Pure computation, no side effects.
- **Local Database** — the audit trail and UI data source. Explicitly **not** the source of truth for "what's currently open" — that's always re-derived from the exchange during reconciliation. If the DB and the exchange ever disagree, the exchange wins, and the disagreement itself gets logged as a risk event.
- **Android Dashboard** — purely presentational; reads from the local DB (fast, offline-friendly), supplemented by a live snapshot pull on open/refresh for balance and position data that needs to be current-second accurate.

The main improvement over the originally sketched pipeline: separating the Reconciliation Engine out as its own always-running component rather than folding it into a single box between Order Manager and Database, and inserting the Data Freshness Gate explicitly, since §6 treats stale-data protection as a first-class failure mode rather than something implicit in the Market Data component.

---

## 11. V1 Application Screens

**Dashboard** — bot status (running/paused/stopped, with the reason if not running), CoinDCX connection status (API reachable? WebSocket connected?), account balance, available margin, active position count, today's P&L, overall P&L, total trade count, last successful sync timestamp, and a prominent warnings/errors area — impossible to miss, a visible banner rather than a small icon, when something needs attention.

**Active Trades** — symbol, side (long/short), entry price, current price, quantity, leverage, margin used, unrealized P&L, stop-loss price, take-profit price, position status.

**Past Trades** — symbol, direction, entry price, exit price, quantity, fees, realized P&L, duration, entry time, exit time, and a "reason" field if the strategy engine tags signals with a reason string (cheap to add now, useful for later strategy debugging).

**Controls:**
- Start/Stop trading — **Must Have.**
- Emergency Stop / kill switch — **Must Have**, the single most important control in the app.
- Strategy enable/disable — **Must Have** if more than one strategy exists; otherwise folds into Start/Stop.
- Risk settings (position size/leverage/daily-loss limits, viewable and editable) — **Must Have**, since these numbers need to be tunable without a rebuild.

**Postponed to later versions:** multiple simultaneous strategies, in-app strategy backtesting UI, charting (use a companion tool like TradingView instead — do not rebuild charting inside this app), detailed analytics/reporting beyond basic P&L aggregation, any multi-account or multi-exchange support.

---

## 12. Data Architecture

**Persisted locally (Room/SQLite) — trade data:** symbol, side, order_type, quantity (requested and filled), entry_price, exit_price, leverage, margin, stop_loss, take_profit, fees (entry + exit), realized_pnl, unrealized_pnl (last computed snapshot, not authoritative live), order_id (exchange), client_order_id (locally generated), position_id (exchange, once the futures schema confirms this field name), order_status, created_at/updated_at/closed_at timestamps, strategy_name + reason tag.

**Persisted locally — system data:** trading engine status (running/paused/stopped + reason), API connectivity status history (useful for diagnosing intermittent issues after the fact), last successful sync timestamp per data type (positions/orders/balance/market data — tracked separately since they can go stale independently), error log entries, risk event log (every time a limit blocked or resized a trade), strategy status/version, application version (useful once builds iterate and you need to know which build produced which trades).

**Retrieved from CoinDCX on demand, not persisted as primary truth** (though a snapshot is cached locally for offline dashboard viewing): current balance/margin, current open positions, current open orders, current market price. All of these are re-fetched during reconciliation and treated as authoritative; the local DB only ever holds the *last known snapshot* for display, never as the thing reconciliation checks itself against.

**Why this split:** the exchange is the only party that actually knows what's truly open and settled; the local DB's job is to be a fast, offline-capable audit trail and UI data source, not a competing source of truth. This directly supports the reconciliation design in §10 — there's no ambiguity about which side wins a conflict.

---

## 13. Technology Selection

**Android:**
- **Kotlin** — the only reasonable choice for new Android development today; mature, official, no reason to consider Java.
- **UI layer: XML views over Jetpack Compose for V1.** A deliberate choice against the more fashionable default: Compose's recomposition model carries a real, if often small, CPU/memory overhead, and on a 3–4GB RAM device where UI is explicitly not the priority, a simpler XML-based UI with ViewBinding is the lower-risk, lower-maintenance-surface choice for a function-over-form app. If you're already comfortable with Compose and prefer it, it's not disqualifying — just noted as the more resource-conscious alternative for this specific device.
- **Room** — local persistence per §12; well-suited to this structured trade/order data shape versus raw SQLite or file-based storage.
- **WorkManager** — for periodic, deferrable work (health-check pings, non-time-critical reconciliation sweeps) — not for the core always-on trading loop, which needs a Foreground Service.
- **Foreground Service** — the backbone of the always-on trading engine on Option A; exempt from Doze/background-execution limits, and the piece needing the persistent notification and battery-optimization exemption discussed in §5.
- **Coroutines** — for all async work (network calls, DB access); a natural fit with Room and Retrofit/OkHttp, lighter-weight than RxJava for this scope.
- **Retrofit + OkHttp** (or plain OkHttp) — for REST calls; a well-trodden, free, open-source combination with good coroutine support.
- **OkHttp's WebSocket support** (or a dedicated lightweight WS client) — for the CoinDCX socket connections, keeping the dependency surface small rather than pulling in a heavier real-time framework.

**Backend:** **None recommended for V1**, per §4. If this changes later, Python (fast iteration, matches your existing bot codebase's language, richest ecosystem for trading-adjacent libraries) would be the natural choice over Kotlin/Java/Node/Go — but this plan does not recommend building one for V1.

**Database:** **Room (SQLite) on-device.** PostgreSQL or any server-hosted DB is unnecessary and directly contradicts both the zero-cost goal and the no-backend architecture decision — there's no second process that needs to read this data concurrently.

**Rationale tied back to requirements:** every selection above is free/open-source (zero-cost goal), has a small footprint appropriate to a 3–4GB RAM device (A30s compatibility), and avoids introducing a second deployable/maintainable surface (maintainability) — none were picked for popularity; Compose vs. XML in particular was picked *against* the currently more popular option specifically because of this device's constraints.

---

## 14. Free Development Environment

- **IDE:** Android Studio (free, official).
- **SDK/build tools:** Android SDK + Gradle, bundled with Android Studio (free).
- **Version control:** Git + a private GitHub repository (free for personal/private repos at this scale).
- **Testing tools:** JUnit (unit tests for strategy/risk/P&L logic — pure Kotlin, no Android dependency needed for most of this, which makes it fast to run), Android Instrumented Tests/Espresso only where actual Android-framework behavior needs testing (foreground service behavior, DB migrations) — free, bundled with the Android toolchain.
- **Debugging tools:** Android Studio's built-in debugger, Logcat, and the free `adb` CLI (already part of platform-tools) for on-device log inspection and manual API testing during Phase 2 (endpoint verification) — e.g., pairing `adb logcat` with a small standalone Kotlin/Python script that hits CoinDCX endpoints directly before wiring them into the app.
- **Deployment:** direct install via USB/ADB to the A30s (`adb install`) — no Play Store listing needed or wanted for a personal-use sideloaded app, which also avoids Play's review process and target-SDK mandates entirely.

**What could eventually require payment:** none of the above, at this project's scale. The only realistic future cost driver is if you ever choose to distribute this beyond personal use (a Play Store developer account is a one-time roughly $25 fee) or add server infrastructure (§15) — neither is in scope for V1.

---

## 15. Free Deployment / Hosting Strategy

Given the Option A recommendation (§4), **V1 requires no hosting at all** — "deployment" is installing the APK onto your own A30s via ADB. This section evaluates the alternatives anyway, per the requirement to do so, and is direct about where each one falls short.

| Approach | Actually free? | Limitations | Reliable enough for automated real-money trading? |
|---|---|---|---|
| Android-only (recommended) | Yes, fully | Subject to §5's Android-lifecycle risks, mitigated but not eliminated | Yes, **with** exchange-side stop-losses and the safety nets in §6–§7 as non-negotiable companions, not optional extras |
| Local network / home PC left running | Yes, if you already leave a PC on | Home power outages, home ISP outages, no remote visibility if something goes wrong while you're out, PC sleep/update-reboot behavior needs the same hardening as the phone | Only as reliable as your home power+internet — for many households this is *worse* than a phone on mobile data as a backup path, not better |
| Existing PC/laptop as a dedicated always-on host | Yes | Same as above, plus the PC becomes a single point of failure with no offline fallback (a phone at least has mobile data as a second path if home Wi-Fi drops) | Comparable to Android-only, arguably worse for a single-device zero-redundancy setup unless it has a UPS and both wired+backup internet |
| Oracle Cloud "Always Free" ARM VM | Genuinely yes, not a trial | India-region capacity for the free ARM shapes is inconsistent (you may not be able to provision one, or Oracle could reclaim/change the offer), no SLA, and you take on full server-hardening responsibility (§8) for a machine now holding your API credentials | Technically the most reliable option *if* you can get and keep one, but the "if" carries real risk for a system trading real money — a free-tier cloud offer with no contractual guarantee is not the same reliability class as a paid VPS |
| Other free-tier hosting (Render, Railway, Fly.io free tiers, etc.) | Free tier, with real usage-limit ceilings | Free tiers on these platforms are typically designed for lightweight web apps, often sleep on inactivity or cap always-on compute hours — directly incompatible with a 24/7 trading engine's requirements | No — not built for always-on processes; would introduce exactly the same "did my process get suspended" uncertainty as the free VM option, without even Oracle's genuinely-always-on compute model |
| Free serverless (AWS Lambda free tier, etc.) | Free tier, with limits | Serverless functions are not designed to hold persistent WebSocket connections or run a continuous loop — architecturally the wrong tool regardless of cost | No — wrong tool for this job, not just a cost question |

**Direct answer to the core question here:** reliable 24/7 automated trading *can* be achieved at genuinely zero ongoing cost — but only via Option A (your own phone), accepted with its real, named limitations and mitigated by exchange-side safety nets. Every zero-cost *server* alternative examined above either isn't truly free (needs a paid tier eventually) or isn't actually suitable for a persistent always-on trading process regardless of cost. If the Android-only risk profile ever proves unacceptable to you personally after real use, the honest next step up is a small paid VPS (~₹300–500/month) — not a "free" cloud workaround, since every free cloud path here carries a materially different risk profile than what most people mean by "reliable."

---

## 16. Testing Plan

**Unit testing (pure Kotlin, no device needed — fast iteration):** strategy signal logic, risk-limit calculations (position sizing, leverage caps, daily-loss circuit breaker), P&L computation (realized and unrealized, including fee handling), position/order state-machine transitions (especially the `UNKNOWN`-state resolution logic from §6–§7).

**Integration testing (against CoinDCX, ideally against a small non-production-critical balance):** authentication/signing correctness, market data retrieval (spot and futures once schemas are confirmed in Phase 2), order placement/cancellation/status lookup round-trips, position/balance retrieval, WebSocket subscribe/reconnect behavior.

**Failure testing:** simulate network loss (airplane mode toggling on the device), simulate API failure (deliberately point the app at a wrong endpoint/invalid key temporarily to exercise error paths), simulate timeout (a local proxy or deliberately slow endpoint), manually disconnect/reconnect the WebSocket, force-crash the app to test crash recovery, and actually reboot the phone to test the `BOOT_COMPLETED` path end-to-end rather than assuming it works from code review alone.

**Paper trading:** whether CoinDCX provides an official sandbox/testnet was **not confirmed** in the docs retrieved during this research — **Needs Verification**, check directly with CoinDCX support or the dashboard before assuming one exists. If no official sandbox exists (the more likely case based on what was found), **design a local simulation mode**: the app runs its full pipeline (market data → strategy → risk → order manager) against **real live market data** but routes the "order placement" step to a simulated fill engine instead of the real CoinDCX order-create endpoint, using the live orderbook/last-trade price to simulate realistic fills including slippage assumptions. This gives a genuine paper-trading mode without needing an exchange-provided sandbox.

**Live testing — staged rollout:**
1. **Simulation** (as above) — run long enough to see the strategy behave across varied market conditions, not just a lucky short window.
2. **Paper trading** — same simulation mode, run continuously alongside normal phone use for at least several days, validating the *app's* reliability (battery behavior, reconnect behavior, does the foreground service actually survive a full day) as much as the strategy's.
3. **Very small real-money amount** — the smallest quantity CoinDCX's futures markets allow per-instrument (confirm `min_trade_size` per instrument via the instrument-details endpoint, §9), specifically to validate the *real* order-placement, fill, and reconciliation pipeline end-to-end with minimal financial stakes.
4. **Extended monitoring** at that minimal size — enough live trades and real time to see at least one instance of most of the §6 failure scenarios actually occur naturally (a dropped connection, a stale-data moment) and confirm the app handled it as designed, not just as intended.
5. **Gradual increase** only after Stage 4 has run without a single instance of an unresolved `UNKNOWN` order state, an unhandled crash, or an undetected risk-limit breach — and only in deliberate, small increments, never a jump straight to intended full position sizing.

---

## 17. Logging & Monitoring

**What to log:** every strategy decision (including "no action" decisions with the reason — useful for later "why didn't it trade here" debugging), every order attempt (request payload minus secrets, response, resolved final status), every position change detected during reconciliation, every API error with enough context to diagnose later, every connection/disconnection event (WebSocket and general connectivity), every risk-manager event (a trade blocked, resized, or a circuit breaker triggered), every crash/recovery event, every sync/reconciliation pass with its outcome (clean vs. discrepancy found).

**What to never log:** the API secret, under any circumstance, in any log level, including debug builds. The API key itself should also be excluded or masked (log only a short identifying prefix if needed) — no reason to leave a working credential sitting in a log file even if it's "only" the key half of the pair.

**Implementation:** local file-based logging, or a dedicated `logs` table in the Room database for structured queryability from the dashboard — no third-party logging SaaS needed at this scale, keeping with the zero-cost goal, and avoiding ever transmitting log content (which will contain trade details) to a third party at all.

**In-app log inspection:** a simple, low-priority-for-V1 log viewer screen (filterable by severity/category) is enough — accessible without needing `adb logcat` plugged into a PC when trying to understand something that happened while away from a computer.

---

## 18. V1 Scope Control

**Must Have:** CoinDCX auth + balance read; futures market data (price/candles); futures order placement/cancellation/status with `client_order_id` idempotency; position monitoring; exchange-side stop-loss on every position; risk manager with position size/leverage/daily-loss/exposure limits; startup + periodic reconciliation; emergency stop/kill switch; start/stop trading control; crash recovery (foreground service + BOOT_COMPLETED + heartbeat detection); local trade/order persistence; basic dashboard, active trades, and past trades screens; logging with audit trail; the pluggable strategy interface (even with only one strategy initially).

**Should Have:** Telegram-based remote notifications and a remote kill-switch trigger; a settings screen for tuning risk limits without a rebuild; a basic in-app log viewer; per-strategy enable/disable if more than one strategy is planned soon.

**Later:** multiple concurrent strategies running side-by-side; richer analytics/reporting beyond basic P&L aggregation; a second dedicated always-on device as a resilience upgrade (§4's Option C variant); any exploration of a backend, if the Android-only reliability profile proves genuinely insufficient after real-world use.

**Do Not Build:** in-app charting (use TradingView or similar externally); multi-exchange support; multi-account support; any cloud dashboard/remote web UI (adds a public-facing surface and real security/cost tradeoffs for no clear benefit at personal-use scale); social/sharing features; a from-scratch backtesting engine inside the Android app (keep backtesting on a PC where compute isn't constrained).

---

## 19. Detailed Implementation Roadmap

**Phase 1 — Requirements & Feasibility**
Objective: confirm this document's conclusions are sound before writing code. Tasks: review this plan; resolve any open questions from §21. Dependencies: none. Deliverables: this document, finalized/approved. Risks: none at this stage. Acceptance criteria: agreement with the architecture recommendation (§4) and the safety non-negotiables (§7).

**Phase 2 — CoinDCX API Validation (mandatory before any app code)**
Objective: convert every "Needs Verification" item in §9 into "Verified" or "Not Supported" using a real API key against real, minimal-scale requests. Tasks: write small standalone Python/Kotlin scripts (not the app itself) to hit every futures endpoint listed in §9 — instrument details, create/cancel/status order, list positions, TP/SL creation, get trades, wallet details — and deliberately probe rate limits until a 429 occurs, once, to confirm the actual futures limit rather than assuming spot's. Dependencies: a CoinDCX account with a small amount transferred to the futures wallet. Deliverables: a schema reference document (your own, not CoinDCX's) capturing every field name/type actually observed. Risks: real small amounts of money are at stake even in this validation phase — use minimal test order sizes throughout. Acceptance criteria: every row in §9's table is either Verified or Not Supported; none remain Needs Verification.

**Phase 3 — Architecture**
Objective: finalize the module boundaries from §10 as actual package structure in the Android project. Tasks: set up the project skeleton with a layered structure (market-data, strategy, risk, order-manager, reconciliation, pnl, persistence, ui) as separate Kotlin packages so "the strategy is replaceable" is enforced by the module boundary, not just convention. Dependencies: Phase 2 complete. Deliverables: an empty-but-structured Android project that compiles. Risks: over-engineering module boundaries prematurely — plain Kotlin packages are enough; full Gradle multi-module isn't necessary at this scale. Acceptance criteria: project compiles, module boundaries match §10.

**Phase 4 — Exchange Integration**
Objective: implement the authenticated API client (signing, headers, timestamp handling) and confirm it against Phase 2's findings. Tasks: build the HMAC-signing request wrapper; implement balance/wallet-transfer calls first (lowest-risk, read-mostly), then order placement using the confirmed Phase 2 schema. Dependencies: Phase 2, 3. Deliverables: a working API client module with unit tests against recorded (not live) response fixtures from Phase 2. Risks: signature generation bugs are silent until they hit a real endpoint — test against Phase 2's captured real responses first. Acceptance criteria: client authenticates and fetches balances successfully against the live account.

**Phase 5 — Market Data**
Objective: implement the Market Data Feed and Data Freshness Gate from §10. Tasks: REST polling first (simpler, ships faster), then WebSocket for real-time updates with reconnect logic, wiring in the freshness-timestamp gate. Dependencies: Phase 4. Deliverables: a live price/candle feed visible in a debug log or minimal test screen. Risks: the `socket.io` v2.4.0 version-pinning constraint (§9) — verify the WebSocket library choice against this early rather than discovering it late. Acceptance criteria: live price updates flow reliably for a multi-hour test run, surviving at least one manual Wi-Fi/mobile-data toggle.

**Phase 6 — Order Management**
Objective: implement the Order Manager from §10, including `client_order_id` generation and the ambiguous-state resolution logic from §6. Tasks: build order placement wrapped with idempotent client IDs; implement the status-query-on-ambiguity logic; implement TP/SL order creation immediately following any entry fill. Dependencies: Phase 4, Phase 2 (confirmed futures order schema). Deliverables: can place, monitor, and cancel a real minimal-size test order end-to-end, including a deliberately-simulated timeout to confirm the resolution path works. Risks: this is the highest-stakes phase to get wrong — allocate real testing time here, not just a quick pass. Acceptance criteria: a full test order lifecycle (place → fill → SL/TP attached → close) completes correctly, and a simulated ambiguous state resolves correctly without duplicate orders.

**Phase 7 — Risk Management**
Objective: implement the Risk Manager from §7/§10. Tasks: position size/leverage/daily-loss/exposure limit checks; the sanity-bounds last-line-of-defense check; the cooldown-after-losses circuit breaker. Dependencies: Phase 6. Deliverables: risk manager unit-tested against a wide range of scenarios including edge cases (exactly-at-limit, one-over-limit, negative/zero/malformed inputs). Risks: an under-tested risk manager is worse than none, since it creates false confidence — prioritize test coverage over feature completeness here. Acceptance criteria: every hard limit from §7 is enforced and unit-tested, including the daily-loss circuit breaker actually blocking further trades once triggered.

**Phase 8 — Strategy Engine**
Objective: wire in your existing strategy logic (from your UCE/crypto-futures-bot work) behind the pluggable interface from §10. Tasks: port/adapt the strategy's decision logic into the interface's expected input/output shape (market data in, signal out, no direct API access). Dependencies: Phase 5 (market data), Phase 3 (interface defined). Deliverables: strategy engine producing signals against live market data in dry-run mode (no real orders yet). Risks: porting strategy logic can introduce subtle behavioral differences from the original — cross-check signals against your existing backtested/paper-traded behavior where possible. Acceptance criteria: strategy produces signals in dry-run that qualitatively match expectations from prior work on the same strategy.

**Phase 9 — Database**
Objective: implement the Room schema from §12. Tasks: define entities for trades, orders, and system/log events; implement the Reconciliation Engine's diff logic against Phase 4's API client. Dependencies: Phase 4, Phase 6. Deliverables: full persistence layer with migrations tested. Risks: schema changes later require migrations — get the core trade/order shape close to right early, since retrofitting is more painful than getting it close now. Acceptance criteria: a full trade lifecycle persists correctly and survives an app restart.

**Phase 10 — Android Application (UI, Foreground Service, lifecycle)**
Objective: build the actual app shell — Foreground Service hosting the trading loop, `BOOT_COMPLETED` receiver, the dashboard/active-trades/past-trades/controls screens from §11. Tasks: implement per §5 and §11; wire the battery-optimization-exemption request flow into first-run setup explicitly, not as a silent assumption. Dependencies: all prior phases feeding into a working pipeline. Deliverables: an installable APK with full UI. Risks: this is where the A30s-specific concerns from §5 get tested for real — budget time for on-device battery/lifecycle testing, not just emulator testing (the emulator won't reproduce One UI's battery management behavior). Acceptance criteria: the app runs as a foreground service, survives phone lock/unlock and backgrounding, and the app survives a real device restart, a real force-stop-and-reopen cycle, and 24+ hours of continuous operation on the actual A30s with battery optimization correctly disabled, without an unexplained service death.

**Phase 11 — Monitoring & Recovery**
Objective: implement the heartbeat/crash-detection, WorkManager health-check, and full logging system from §6/§17, plus the Reconciliation Engine's live wiring. Tasks: wire in the crash handler, heartbeat-gap detection on startup, the periodic health-check WorkManager job, the Reconciliation Engine's live schedule, and (Should-Have) Telegram notifications for critical events. Dependencies: Phase 9 (needs the DB), Phase 10 (needs the running service to monitor). Deliverables: the app can detect and surface its own prior failures on next launch, and a deliberately-introduced local/exchange state mismatch (e.g., manually placing an order via CoinDCX's own app) is correctly detected. Risks: testing this well requires deliberately inducing failures (force-stop, battery pull, manual out-of-band orders) — don't skip this because it's awkward to test; reconciliation logic that's too aggressive will constantly and unnecessarily halt trading, too lenient defeats the purpose — tune against Phase 2's real observed API behavior. Acceptance criteria: a deliberately force-killed service is detected and surfaced on next app open with the correct "was last active at X, now it's Y" gap reported, and a deliberately-introduced state mismatch is correctly detected and correctly halts new trading.

**Phase 12 — Testing**
Objective: execute the full test plan from §16 (unit, integration, failure) as a dedicated phase, not just ad hoc testing during development. Dependencies: all prior phases. Deliverables: a documented test pass with results covering every scenario in §6 and every limit in §7. Risks: skipping failure-injection testing under time pressure is the single most likely way this project produces a real financial loss from a software bug rather than a legitimate strategy loss. Acceptance criteria: every failure scenario in §6 has been deliberately triggered at least once during testing and observed to behave as designed.

**Phase 13 — Paper Trading (Simulation)**
Objective: run the simulation mode from §16 against live market data for a meaningful period. Dependencies: Phase 12. Deliverables: a log of simulated trading decisions/outcomes and, just as importantly, app reliability (uptime, reconnects handled, any crashes) over the run, reviewed before proceeding. Risks: simulation cannot validate real execution/slippage/fee behavior, and a short/lucky paper-trading window can create false confidence — insist on enough elapsed time and varied market conditions; Phase 14 exists precisely because simulation alone isn't sufficient proof. Acceptance criteria: no unresolved app-level issues (crashes, missed reconnects, undetected stale-data incidents) during the paper-trading window, and an explicit decision to proceed to live testing.

**Phase 14 — Controlled Live Deployment**
Objective: execute stages 3–5 of §16's staged rollout (minimal real order → extended minimal-size monitoring → gradual increase). Dependencies: Phase 13 complete and clean, explicit user go-ahead. Deliverables: a real, small, monitored live-trading track record, scaled up only as confidence is earned through observed behavior rather than assumed. Risks: this is real money — resist the urge to skip straight to intended position sizing after one or two good days; risk is mitigated, never eliminated, by everything in §6–§9. Acceptance criteria: the extended-monitoring bar from §16 stage 4 is met — no reconciliation mismatches, no unhandled errors, risk-limit behavior matching design intent — before capital is meaningfully increased.

---

## 20. Cost Analysis

| Component | Recommended Solution | Cost | Free Alternative | Limitations |
|---|---|---|---|---|
| Android development | Android Studio + Kotlin + Gradle | ₹0 | N/A (already free) | None at this scale |
| Backend | None (Option A architecture) | ₹0 | N/A — not built | Trades off some reliability for zero cost (§4); mitigated by exchange-side stops |
| Database | Room/SQLite, on-device | ₹0 | N/A (already free) | Not concurrently accessible by a second process, which is fine since there is none |
| Hosting | None required | ₹0 | Oracle Always Free (if a backend is ever added) | Oracle tier is genuinely free but has real capacity/SLA caveats (§15) |
| Monitoring | Local heartbeat + WorkManager health checks | ₹0 | N/A | Only as good as the phone being reachable to notice; mitigated by exchange-side stops regardless |
| Logging | Local file/Room-based logs | ₹0 | N/A | No off-device backup of logs by default; acceptable for a personal audit trail |
| Domain | Not used | ₹0 | N/A — not needed | None (no public-facing service exists) |
| SSL | Not used | ₹0 | N/A — not needed | None (no public-facing service exists) |
| CI/CD | GitHub Actions free tier | ₹0 | N/A | Free minutes/month cap, irrelevant at solo-repo scale |
| Notifications | Telegram Bot API | ₹0 | N/A (already free) | None meaningful at personal-use volume |
| Version control | GitHub private repo | ₹0 | N/A (already free) | None at this scale |

**Initial cost: ₹0.**
**Monthly cost: ₹0.**

Both targets are realistically achievable **specifically because Option A (Android-only, §4) is the recommended architecture.** If the recommendation is ever overridden in favor of a backend, the honest floor becomes either the Oracle Always Free tier (still ₹0, but with the capacity/SLA caveats spelled out in §15 — not a clean substitute for a paid guarantee) or approximately ₹300–500/month for a small, properly-hardened VPS. This document does not recommend that trade for V1, and if you do reach a legitimate need for it later, treat it as a deliberate reliability-for-cost trade you're consciously making, not something to back into by accident.

---

## 21. Final Feasibility Review

**Technical feasibility — can this be built?**
Yes. Nothing in the requirements list is architecturally infeasible, and the individual pieces (Android foreground service, REST/WebSocket integration, local persistence, a pluggable strategy interface) are all well-understood, mature patterns. The main risk isn't "can it be built" but "will it be built with enough discipline around the failure-handling and reconciliation logic in §6–§7," which is a process risk, not a technical one.

**CoinDCX feasibility — does the API provide what's needed?**
Mostly yes, with real gaps in verification, not in capability. Every required capability appears to exist somewhere in the official documentation (auth, futures orders, positions, TP/SL, trade history, wallet management). The honest caveat: a meaningful fraction of the futures-specific endpoints are confirmed-to-exist-by-name but not confirmed-by-schema in this research pass, and no official futures rate-limit table was found anywhere. Phase 2 exists specifically to close this gap before any strategy code depends on assumptions.

**Android feasibility — can the A30s reliably support this?**
Yes, for a swing/intraday strategy, with real conditions attached: a Foreground Service architecture, manual battery-optimization exemption, a `BOOT_COMPLETED` receiver, and heartbeat-based crash detection are not optional nice-to-haves — they are the specific mechanisms that make "yes" true rather than "probably, if nothing goes wrong." Without them, the honest answer would be "no, not reliably." With them, "yes" is a fair, defensible conclusion, not an overpromise.

**Zero-cost feasibility — can the full system run with no ongoing expense?**
Yes, on the condition that Option A is used and no backend is introduced. Every component identified across §3, §14, and §20 is genuinely free at this project's scale. The one place this plan refuses to overstate zero-cost: if a backend is ever added, "free" stops being an honest description of the *reliable* path (only the operationally-fragile Oracle-Free-Tier path stays technically ₹0).

**Reliability — can automated trading operate safely under this architecture?**
Yes, but only with the safety design in §6, §7, and §10 fully implemented — not as an afterthought layered on top of a "happy path" build. The single load-bearing safety mechanism, if only one could be picked, is the exchange-side stop-loss on every position, because it is the one protection that survives the phone being completely offline. Everything else in this plan is defense-in-depth around that core.

**Security — what is the biggest concern?**
Physical device compromise (theft, loss, or a rooted/malware-infected phone) — because the API credentials, while properly encrypted at rest, are necessarily decrypted in memory during use on the one device that holds them. This is a real but manageable risk (standard device hygiene — lock screen, no rooting, disk encryption — addresses most of it), and notably a *smaller* attack surface than the server-compromise risk that a backend architecture would introduce instead.

**Trading risk — what is the biggest financial/operational risk?**
Not the strategy's market risk (that's inherent to trading and outside this document's scope), but the **ambiguous order state** problem described throughout §6–§7: assuming an order succeeded when it didn't, or vice versa, and letting downstream risk/position logic act on a wrong assumption. This is precisely why the `UNKNOWN` order state and mandatory reconciliation are treated as first-class, non-negotiable design elements rather than edge-case handling.

**Recommended architecture — which, and why?**
Option A: Android-only, with the phone treated as inherently unreliable rather than server-grade, and every safety net in §6–§7 built accordingly. This is the only option that genuinely satisfies the zero-cost constraint without quietly accepting a worse reliability/security trade-off than the alternative it's supposedly avoiding.

**V1 recommendation — what should actually be built first?**
Phase 2 (API validation) before a single line of app UI or strategy-integration code. The single greatest risk to this whole project is discovering, mid-build, that a futures endpoint's real schema doesn't match what was assumed from community documentation — better to find that out with a throwaway script in an afternoon than after wiring the assumption into the Order Manager.

**Things that must be validated before coding (consolidated open questions):**
- Exact request/response schema for every futures endpoint marked "Needs Verification" in §9 (order create/cancel/edit, list orders, list/get positions, TP/SL creation, get trades, wallet details, leverage/margin management).
- Whether CoinDCX's futures order/position endpoints support `client_order_id`-based idempotent lookups the same way spot does — this is load-bearing for the entire ambiguous-order-state design in §6–§7.
- The actual futures API rate limits (no official table found; must be empirically probed in Phase 2, not assumed to match spot).
- Whether a dedicated realized/unrealized P&L field exists anywhere in the futures position/trade responses, or whether the P&L Engine must compute 100% of this itself.
- Whether CoinDCX offers any form of sandbox/testnet (unconfirmed either way in this research) — determines whether the local simulation mode in §16 is a nice-to-have or the only paper-trading option available.
- The exact granularity of API key permission scoping available in the CoinDCX dashboard (trade-only vs. trade+withdraw) — check directly at key-creation time.
- Whether CoinDCX's futures product involves perpetual-style funding payments that need to be folded into the P&L Engine.
- Your own independent comfort with §9.1's Algorithmic Trading terms as they apply to a personal-use single-account bot.

---

## 22. Quality Standard & Rules Followed

This document deliberately avoids generic placeholder statements ("use a database," "handle errors properly") in favor of specifics: which database and why (§13), which failure modes and their exact detection/response/recovery behavior (§6), which endpoints are confirmed versus assumed and by what evidence (§9). Every claim about CoinDCX's API is sourced from `docs.coindcx.com` retrieved directly during this session, with community sources used only as cross-reference material explicitly marked as unofficial and unconfirmed. No application code has been written as part of this planning pass, per the brief's explicit instruction.