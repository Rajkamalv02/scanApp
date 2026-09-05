package com.coindcx.trading.data.db

import android.content.Context
import android.util.Log
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
        private const val TAG = "AppDatabase"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 1 to 2 had no schema modifications
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateTradesTable(db)
                migrateOrdersTable(db)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `equity_snapshots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `equityInr` REAL NOT NULL,
                        `availableBalanceInr` REAL NOT NULL,
                        `unrealizedPnlInr` REAL NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3.migrate(db)
            }
        }

        private fun migrateTradesTable(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `trades_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `pair` TEXT NOT NULL,
                    `side` TEXT NOT NULL,
                    `entryPrice` REAL NOT NULL,
                    `exitPrice` REAL,
                    `quantity` REAL NOT NULL,
                    `leverage` INTEGER NOT NULL,
                    `stopLoss` REAL,
                    `takeProfit` REAL,
                    `fees` REAL NOT NULL,
                    `realizedPnl` REAL,
                    `unrealizedPnl` REAL,
                    `allocatedMarginInr` REAL NOT NULL,
                    `exchangePositionId` TEXT,
                    `clientOrderId` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `entryTime` INTEGER NOT NULL,
                    `exitTime` INTEGER,
                    `strategyName` TEXT NOT NULL,
                    `exitReason` TEXT,
                    `signalPrice` REAL NOT NULL,
                    `orderPrice` REAL,
                    `currentPrice` REAL,
                    `notionalValueInr` REAL NOT NULL,
                    `estimatedLiquidationPrice` REAL,
                    `grossPnl` REAL,
                    `fundingFees` REAL NOT NULL,
                    `slippageRate` REAL NOT NULL,
                    `roiPercent` REAL,
                    `durationMillis` INTEGER,
                    `timeframe` TEXT NOT NULL,
                    `tradeResult` TEXT,
                    `sessionId` TEXT NOT NULL
                )
            """.trimIndent())

            try {
                // Inspect existing columns in trades table to dynamically build migration query
                val cursor = db.query("PRAGMA table_info(`trades`)")
                val existingCols = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val nameIdx = cursor.getColumnIndex("name")
                    if (nameIdx >= 0) existingCols.add(cursor.getString(nameIdx))
                }
                cursor.close()

                val selectSignalPrice = if (existingCols.contains("signalPrice")) "`signalPrice`" else "`entryPrice`"
                val selectOrderPrice = if (existingCols.contains("orderPrice")) "`orderPrice`" else "`entryPrice`"
                val selectCurrentPrice = if (existingCols.contains("currentPrice")) "`currentPrice`" else "`entryPrice`"
                val selectNotional = if (existingCols.contains("notionalValueInr")) "`notionalValueInr`" else "(`allocatedMarginInr` * `leverage`)"
                val selectEstLiq = if (existingCols.contains("estimatedLiquidationPrice")) "`estimatedLiquidationPrice`" else "NULL"
                val selectGrossPnl = if (existingCols.contains("grossPnl")) "`grossPnl`" else "NULL"
                val selectFundingFees = if (existingCols.contains("fundingFees")) "`fundingFees`" else "0.0"
                val selectSlippageRate = if (existingCols.contains("slippageRate")) "`slippageRate`" else "0.0005"
                val selectRoi = if (existingCols.contains("roiPercent")) "`roiPercent`" else "NULL"
                val selectDuration = if (existingCols.contains("durationMillis")) "`durationMillis`" else "NULL"
                val selectTimeframe = if (existingCols.contains("timeframe")) "`timeframe`" else "'15m'"
                val selectTradeResult = if (existingCols.contains("tradeResult")) "`tradeResult`" else "NULL"
                val selectSessionId = if (existingCols.contains("sessionId")) "`sessionId`" else "'session_default'"

                db.execSQL("""
                    INSERT INTO `trades_new` (
                        `id`, `pair`, `side`, `entryPrice`, `exitPrice`, `quantity`, `leverage`,
                        `stopLoss`, `takeProfit`, `fees`, `realizedPnl`, `unrealizedPnl`,
                        `allocatedMarginInr`, `exchangePositionId`, `clientOrderId`, `status`,
                        `entryTime`, `exitTime`, `strategyName`, `exitReason`,
                        `signalPrice`, `orderPrice`, `currentPrice`, `notionalValueInr`,
                        `estimatedLiquidationPrice`, `grossPnl`, `fundingFees`, `slippageRate`,
                        `roiPercent`, `durationMillis`, `timeframe`, `tradeResult`, `sessionId`
                    )
                    SELECT
                        `id`, `pair`, `side`, `entryPrice`, `exitPrice`, `quantity`, `leverage`,
                        `stopLoss`, `takeProfit`, `fees`, `realizedPnl`, `unrealizedPnl`,
                        `allocatedMarginInr`, `exchangePositionId`, `clientOrderId`, `status`,
                        `entryTime`, `exitTime`, `strategyName`, `exitReason`,
                        $selectSignalPrice, $selectOrderPrice, $selectCurrentPrice, $selectNotional,
                        $selectEstLiq, $selectGrossPnl, $selectFundingFees, $selectSlippageRate,
                        $selectRoi, $selectDuration, $selectTimeframe, $selectTradeResult, $selectSessionId
                    FROM `trades`
                """.trimIndent())

                db.execSQL("DROP TABLE `trades`")
                db.execSQL("ALTER TABLE `trades_new` RENAME TO `trades`")
            } catch (e: Exception) {
                Log.w(TAG, "Trades table migration fallback: ${e.message}")
                db.execSQL("DROP TABLE IF EXISTS `trades`")
                db.execSQL("ALTER TABLE `trades_new` RENAME TO `trades`")
            }
        }

        private fun migrateOrdersTable(db: SupportSQLiteDatabase) {
            try {
                val cursor = db.query("PRAGMA table_info(`orders`)")
                var hasRejectionReason = false
                while (cursor.moveToNext()) {
                    val nameIdx = cursor.getColumnIndex("name")
                    if (nameIdx >= 0 && cursor.getString(nameIdx) == "rejectionReason") {
                        hasRejectionReason = true
                        break
                    }
                }
                cursor.close()

                if (!hasRejectionReason) {
                    db.execSQL("ALTER TABLE `orders` ADD COLUMN `rejectionReason` TEXT")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Orders table migration fallback: ${e.message}")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = try {
                    buildDatabase(context).also {
                        // Force database open to verify schema integrity immediately
                        it.openHelper.writableDatabase
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Database schema migration failed. Recovering fresh database: ${t.message}", t)
                    try {
                        context.deleteDatabase("coindcx_trading.db")
                    } catch (_: Throwable) {}
                    buildDatabase(context).also {
                        it.openHelper.writableDatabase
                    }
                }
                INSTANCE = db
                db
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "coindcx_trading.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_1_3, MIGRATION_2_3)
             .fallbackToDestructiveMigration()
             .fallbackToDestructiveMigrationOnDowngrade()
             .build()
        }
    }
}
