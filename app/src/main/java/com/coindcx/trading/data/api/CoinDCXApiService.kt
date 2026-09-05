package com.coindcx.trading.data.api

import com.coindcx.trading.data.api.models.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit Interface for CoinDCX Futures & Market Data
 * All endpoints match the verified contracts in schema_reference.md.
 */
interface CoinDCXApiService {

    // --- Market Data (Public) ---

    @Headers("X-PUBLIC-REQUEST: true")
    @GET("/exchange/v1/derivatives/futures/data/active_instruments")
    suspend fun getActiveInstruments(): Response<List<String>>

    @Headers("X-PUBLIC-REQUEST: true")
    @GET("/exchange/ticker")
    suspend fun getTicker(): Response<List<Map<String, Any>>>

    @Headers("X-PUBLIC-REQUEST: true")
    @GET("/market_data/candles")
    suspend fun getCandles(
        @Query("pair") pair: String,
        @Query("interval") interval: String = "1m"
    ): Response<List<MarketCandle>>

    // --- Futures Account & Wallets (Private) ---

    @GET("/exchange/v1/derivatives/futures/wallets")
    suspend fun getFuturesWallets(
        @Query("timestamp") timestamp: Long = System.currentTimeMillis() / 1000
    ): Response<List<FuturesWallet>>

    @POST("/exchange/v1/derivatives/futures/positions")
    suspend fun getPositions(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<List<FuturesPosition>>

    // --- Futures Orders (Private) ---

    @POST("/exchange/v1/derivatives/futures/orders")
    suspend fun getOpenOrders(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<List<FuturesOrder>>

    @POST("/exchange/v1/derivatives/futures/orders/create")
    suspend fun createOrder(
        @Body request: CreateOrderRequest
    ): Response<FuturesOrder>

    @POST("/exchange/v1/derivatives/futures/orders/cancel")
    suspend fun cancelOrder(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Map<String, Any>>
}
