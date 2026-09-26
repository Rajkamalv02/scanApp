package com.coindcx.trading.data.api

import com.coindcx.trading.data.api.models.BinanceFutures24hTicker
import com.coindcx.trading.data.api.models.BinanceFuturesBookTicker
import retrofit2.Response
import retrofit2.http.GET

/**
 * Public REST API service for Crypto Futures market data (Binance USDT-M Futures).
 * CoinDCX derivatives contracts (e.g. B-BTC_USDT, B-1000PEPE_USDT) mirror Binance USDT-M perpetuals.
 */
interface BinanceFuturesApiService {

    @GET("/fapi/v1/ticker/24hr")
    suspend fun get24hTickers(): Response<List<BinanceFutures24hTicker>>

    @GET("/fapi/v1/ticker/bookTicker")
    suspend fun getBookTickers(): Response<List<BinanceFuturesBookTicker>>
}
