package com.coindcx.trading.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.coindcx.trading.data.db.dao.*
import com.coindcx.trading.data.db.entities.*

@Database(
    entities = [
        TradeEntity::class,
        OrderEntity::class,
        SystemLogEntity::class,
        EquitySnapshotEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tradeDao(): TradeDao
    abstract fun orderDao(): OrderDao
    abstract fun systemLogDao(): SystemLogDao
    abstract fun equitySnapshotDao(): EquitySnapshotDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trades ADD COLUMN signalPrice REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE trades ADD COLUMN orderPrice REAL")
                db.execSQL("ALTER TABLE trades ADD COLUMN currentPrice REAL")
                db.execSQL("ALTER TABLE trades ADD COLUMN notionalValueInr REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE trades ADD COLUMN estimatedLiquidationPrice REAL")
                db.execSQL("ALTER TABLE trades ADD COLUMN grossPnl REAL")
                db.execSQL("ALTER TABLE trades ADD COLUMN fundingFees REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE trades ADD COLUMN slippageRate REAL NOT NULL DEFAULT 0.0005")
                db.execSQL("ALTER TABLE trades ADD COLUMN roiPercent REAL")
                db.execSQL("ALTER TABLE trades ADD COLUMN durationMillis INTEGER")
                db.execSQL("ALTER TABLE trades ADD COLUMN timeframe TEXT NOT NULL DEFAULT '15m'")
                db.execSQL("ALTER TABLE trades ADD COLUMN tradeResult TEXT")
                db.execSQL("ALTER TABLE trades ADD COLUMN sessionId TEXT NOT NULL DEFAULT 'session_default'")

                db.execSQL("ALTER TABLE orders ADD COLUMN rejectionReason TEXT")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS equity_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        equityInr REAL NOT NULL,
                        availableBalanceInr REAL NOT NULL,
                        unrealizedPnlInr REAL NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "coindcx_trading.db"
                ).addMigrations(MIGRATION_2_3)
                 .fallbackToDestructiveMigration()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
