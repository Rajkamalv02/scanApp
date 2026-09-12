package com.coindcx.trading

import android.app.Application
import android.util.Log
import com.coindcx.trading.util.AppLogManager

class TradingApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        AppLogManager.init(this)

        // Global Uncaught Exception Handler to catch and log any fatal crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogManager.e("CRASH", "FATAL UNCAUGHT EXCEPTION in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
