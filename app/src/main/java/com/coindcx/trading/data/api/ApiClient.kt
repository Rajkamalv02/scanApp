package com.coindcx.trading.data.api

import com.coindcx.trading.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "https://api.coindcx.com"

    var apiKey: String = BuildConfig.COINDCX_API_KEY
    var apiSecret: String = BuildConfig.COINDCX_API_SECRET

    private val authInterceptor = AuthInterceptor(
        apiKeyProvider = { apiKey },
        apiSecretProvider = { apiSecret }
    )

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val apiService: CoinDCXApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CoinDCXApiService::class.java)
    }

    private const val BINANCE_FUTURES_BASE_URL = "https://fapi.binance.com"

    val binanceFuturesApiService: BinanceFuturesApiService by lazy {
        val publicOkHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BINANCE_FUTURES_BASE_URL)
            .client(publicOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BinanceFuturesApiService::class.java)
    }
}

