package com.coindcx.trading.engine

import com.coindcx.trading.data.api.CoinDCXApiService
import com.coindcx.trading.data.db.dao.OrderDao
import com.coindcx.trading.data.db.dao.SystemLogDao
import com.coindcx.trading.data.db.dao.TradeDao
import com.coindcx.trading.data.db.entities.SystemLogEntity

sealed class ReconciliationResult {
    object Clean : ReconciliationResult()
    data class DiscrepancyFound(val summary: String) : ReconciliationResult()
}

/**
 * Reconciliation Engine
 * Runs at startup and periodically. Treats exchange as authoritative ground truth.
 */
class ReconciliationEngine(
    private val apiService: CoinDCXApiService,
    private val tradeDao: TradeDao,
    private val orderDao: OrderDao,
    private val logDao: SystemLogDao
) {
    suspend fun reconcile(): ReconciliationResult {
        logDao.insert(SystemLogEntity(level = "INFO", tag = "RECON", message = "Starting reconciliation sweep..."))

        // 1. Resolve UNKNOWN orders
        val unknownOrders = orderDao.getUnknownOrders()
        if (unknownOrders.isNotEmpty()) {
            val ordersPayload = mapOf("page" to "1", "size" to "50", "timestamp" to System.currentTimeMillis())
            val openOrdersResp = apiService.getOpenOrders(ordersPayload)

            if (openOrdersResp.isSuccessful && openOrdersResp.body() != null) {
                val liveOrders = openOrdersResp.body()!!
                for (uOrder in unknownOrders) {
                    val matching = liveOrders.find { it.clientOrderId == uOrder.clientOrderId }
                    if (matching != null) {
                        orderDao.update(uOrder.copy(exchangeOrderId = matching.id, status = matching.status))
                        logDao.insert(SystemLogEntity(level = "INFO", tag = "RECON", message = "Resolved UNKNOWN order ${uOrder.clientOrderId} -> ${matching.status}"))
                    } else {
                        // Order not found in open orders -> either filled and closed, or was never submitted
                        orderDao.update(uOrder.copy(status = "RECON_PENDING_FILL_CHECK"))
                    }
                }
            }
        }

        // 2. Reconcile Open Positions
        val positionsPayload = mapOf("page" to "1", "size" to "50", "margin_currency_short_name" to listOf("USDT"), "timestamp" to System.currentTimeMillis())
        val positionsResp = apiService.getPositions(positionsPayload)

        if (!positionsResp.isSuccessful || positionsResp.body() == null) {
            val err = "Reconciliation failed: Could not fetch live positions from CoinDCX"
            logDao.insert(SystemLogEntity(level = "ERROR", tag = "RECON", message = err))
            return ReconciliationResult.DiscrepancyFound(err)
        }

        val livePositions = positionsResp.body()!!.filter { it.isOpen }
        val localOpenTrades = tradeDao.getOpenTrades()

        if (livePositions.size != localOpenTrades.size) {
            val discrepancy = "Position mismatch: Exchange has ${livePositions.size} open positions, Local DB has ${localOpenTrades.size}."
            logDao.insert(SystemLogEntity(level = "RISK", tag = "RECON", message = discrepancy))
            return ReconciliationResult.DiscrepancyFound(discrepancy)
        }

        logDao.insert(SystemLogEntity(level = "INFO", tag = "RECON", message = "Reconciliation clean. Local state matches exchange."))
        return ReconciliationResult.Clean
    }
}
