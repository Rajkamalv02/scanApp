package com.coindcx.trading.engine.backtest

import com.coindcx.trading.data.api.models.MarketCandle
import com.coindcx.trading.engine.data.CandleSeries
import com.coindcx.trading.engine.data.Interval
import com.coindcx.trading.engine.time.FixedClock
import com.google.gson.JsonParser
import java.io.File
import java.io.FileReader

object BacktestDataLoader {

    fun loadFromFile(
        file: File,
        interval: Interval,
        symbol: String = "B-BTC_USDT"
    ): CandleSeries {
        require(file.exists()) { "Backtest data file does not exist: ${file.absolutePath}" }

        FileReader(file).use { reader ->
            val jsonArray = JsonParser.parseReader(reader).asJsonArray
            val candles = ArrayList<MarketCandle>(jsonArray.size())

            for (i in 0 until jsonArray.size()) {
                val row = jsonArray.get(i).asJsonArray
                val openTime = row.get(0).asLong
                val open = row.get(1).asString.toDouble()
                val high = row.get(2).asString.toDouble()
                val low = row.get(3).asString.toDouble()
                val close = row.get(4).asString.toDouble()
                val volume = row.get(5).asString.toDouble()

                candles.add(MarketCandle(open, high, low, close, volume, openTime))
            }

            // Set clock far enough in the future so that historical completed bars are not dropped
            val maxTime = candles.maxOfOrNull { it.time } ?: 0L
            val clock = FixedClock(maxTime + interval.durationMs + 10_000L)
            return CandleSeries.fromApi(candles, interval, clock, symbol)
        }
    }
}
