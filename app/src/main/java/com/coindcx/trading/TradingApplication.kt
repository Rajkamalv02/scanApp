package com.coindcx.trading

import android.app.Application
import android.util.Log

class TradingApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Global Uncaught Exception Handler to catch and log any fatal crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("TradingApplication", "FATAL UNCAUGHT EXCEPTION in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
