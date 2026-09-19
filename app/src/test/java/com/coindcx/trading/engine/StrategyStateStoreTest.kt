package com.coindcx.trading.engine

import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.state.ImpulseState
import com.coindcx.trading.engine.state.StrategyStateStore
import org.junit.Assert.*
import org.junit.Test

class StrategyStateStoreTest {

    @Test
    fun `test put get and staleness eviction`() {
        val store = StrategyStateStore()

        val state = ImpulseState(
            direction = SignalDirection.LONG,
            impulseHigh = 100.0,
            impulseLow = 95.0,
            impulseVolume = 500.0,
            impulseBarTime = 1_000_000L,
            barsSinceImpulse = 0,
            lastUpdatedBarOpenTime = 1_000_000L
        )

        store.put("B-BTC_USDT", "irc", state)
        assertEquals(1, store.size())

        // 1. Fresh retrieve (3 bars later on 15m = 3 * 900,000 = 2,700,000 ms elapsed -> current = 3,700,000L)
        // maxTolerance = 6 bars
        val freshState = store.getIfFresh(
            symbol = "B-BTC_USDT",
            strategyId = "irc",
            currentBarOpenTime = 1_000_000L + (3 * 15 * 60_000L),
            interval = Interval.M15,
            maxToleranceBars = 6
        )
        assertNotNull(freshState)
        assertTrue(freshState is ImpulseState)

        // 2. Stale retrieve (8 bars later > max 6 bars tolerance)
        val staleState = store.getIfFresh(
            symbol = "B-BTC_USDT",
            strategyId = "irc",
            currentBarOpenTime = 1_000_000L + (8 * 15 * 60_000L),
            interval = Interval.M15,
            maxToleranceBars = 6
        )
        assertNull("Stale state must be evicted and return null", staleState)
        assertEquals("Evicted state should be removed from store", 0, store.size())
    }

    @Test
    fun `test universe eviction drops inactive symbols`() {
        val store = StrategyStateStore()

        val state = ImpulseState(
            direction = SignalDirection.LONG,
            impulseHigh = 10.0,
            impulseLow = 9.0,
            impulseVolume = 100.0,
            impulseBarTime = 1000L,
            lastUpdatedBarOpenTime = 1000L
        )

        store.put("B-BTC_USDT", "irc", state)
        store.put("B-DELISTED_USDT", "irc", state)
        assertEquals(2, store.size())

        store.evictNotIn(setOf("B-BTC_USDT", "B-ETH_USDT"))
        assertEquals(1, store.size())
        assertNotNull(store.get("B-BTC_USDT", "irc"))
        assertNull(store.get("B-DELISTED_USDT", "irc"))
    }
}
