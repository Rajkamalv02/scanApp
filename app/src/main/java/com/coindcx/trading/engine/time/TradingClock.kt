package com.coindcx.trading.engine.time

/**
 * Clock abstraction for deterministic time retrieval across live scanning,
 * backtesting replay, and UTC session boundary evaluation.
 */
interface TradingClock {
    fun nowUtcMillis(): Long

    companion object {
        val SYSTEM: TradingClock = SystemClock
    }
}

/**
 * Standard system clock using JVM current epoch milliseconds.
 */
object SystemClock : TradingClock {
    override fun nowUtcMillis(): Long = System.currentTimeMillis()
}

/**
 * Exchange-synchronized clock that offsets local system time with server time.
 */
class ServerSyncedClock(
    @Volatile var serverOffsetMs: Long = 0L
) : TradingClock {
    override fun nowUtcMillis(): Long = System.currentTimeMillis() + serverOffsetMs
}

/**
 * Fixed deterministic clock for testing and bar-by-bar backtesting replay.
 */
class FixedClock(
    var currentMillis: Long = 0L
) : TradingClock {
    override fun nowUtcMillis(): Long = currentMillis

    fun advance(millis: Long) {
        currentMillis += millis
    }

    fun set(millis: Long) {
        currentMillis = millis
    }
}
