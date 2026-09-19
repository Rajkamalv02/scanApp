package com.coindcx.trading.engine.telemetry

import com.coindcx.trading.util.AppLogManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Institutional Scan Telemetry & Observability Engine.
 * Records condition rejection frequencies, emitted signal metadata, and realized slippage.
 */
object ScanTelemetry {

    // Counters: StrategyId -> (RejectionCode -> Count)
    private val rejectionCounters = ConcurrentHashMap<String, ConcurrentHashMap<RejectionCode, AtomicInteger>>()

    // Realized slippage records: StrategyId -> List<Double> (basis points or pct)
    private val slippageRecords = ConcurrentHashMap<String, MutableList<Double>>()

    fun recordRejection(strategyId: String, code: RejectionCode) {
        val stratMap = rejectionCounters.computeIfAbsent(strategyId) { ConcurrentHashMap() }
        stratMap.computeIfAbsent(code) { AtomicInteger(0) }.incrementAndGet()
    }

    fun recordRealizedSlippage(strategyId: String, entryRef: Double, fillPrice: Double) {
        if (entryRef <= 0.0) return
        val slippagePct = ((fillPrice - entryRef) / entryRef) * 100.0
        val list = slippageRecords.computeIfAbsent(strategyId) { mutableListOf() }
        synchronized(list) {
            list.add(slippagePct)
            if (list.size > 200) {
                list.removeAt(0)
            }
        }
        AppLogManager.d("TELEMETRY", "[$strategyId] Realized Slippage: ${"%.4f".format(slippagePct)}% (Ref=$entryRef, Fill=$fillPrice)")
    }

    fun getRejectionSummary(strategyId: String): Map<RejectionCode, Int> {
        val stratMap = rejectionCounters[strategyId] ?: return emptyMap()
        return stratMap.mapValues { it.value.get() }
    }

    fun getAverageSlippagePct(strategyId: String): Double {
        val list = slippageRecords[strategyId] ?: return 0.0
        synchronized(list) {
            return if (list.isNotEmpty()) list.average() else 0.0
        }
    }

    fun clear() {
        rejectionCounters.clear()
        slippageRecords.clear()
    }

    fun dumpTelemetryReport(): String {
        val sb = StringBuilder("================ SCAN TELEMETRY REPORT ================\n")
        for ((strat, map) in rejectionCounters) {
            val total = map.values.sumOf { it.get() }
            sb.append("Strategy [$strat] (Total Rejections: $total):\n")
            map.entries.sortedByDescending { it.value.get() }.take(5).forEach { (code, count) ->
                sb.append("   - %-36s : %d\n".format(code.name, count.get()))
            }
            val avgSlip = getAverageSlippagePct(strat)
            if (avgSlip != 0.0) {
                sb.append("   - Avg Realized Slippage: ${"%.4f".format(avgSlip)}%\n")
            }
        }
        sb.append("========================================================")
        return sb.toString()
    }
}
