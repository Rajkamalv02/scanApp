package com.coindcx.trading.engine.scanner

import com.coindcx.trading.engine.ExchangeStateSnapshot
import com.coindcx.trading.engine.allocation.AllocationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MarketScanState {
    private val _latestAllocation = MutableStateFlow<AllocationResult?>(null)
    val latestAllocation: StateFlow<AllocationResult?> = _latestAllocation.asStateFlow()

    private val _topOpportunities = MutableStateFlow<List<MarketOpportunity>>(emptyList())
    val topOpportunities: StateFlow<List<MarketOpportunity>> = _topOpportunities.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isRefreshingExchange = MutableStateFlow(false)
    val isRefreshingExchange: StateFlow<Boolean> = _isRefreshingExchange.asStateFlow()

    private val _nextScanSecondsRemaining = MutableStateFlow(0)
    val nextScanSecondsRemaining: StateFlow<Int> = _nextScanSecondsRemaining.asStateFlow()

    private val _lastScanTimestamp = MutableStateFlow(0L)
    val lastScanTimestamp: StateFlow<Long> = _lastScanTimestamp.asStateFlow()

    private val _scanCycleCount = MutableStateFlow(0)
    val scanCycleCount: StateFlow<Int> = _scanCycleCount.asStateFlow()

    private val _lastExchangeRefreshTimestamp = MutableStateFlow(0L)
    val lastExchangeRefreshTimestamp: StateFlow<Long> = _lastExchangeRefreshTimestamp.asStateFlow()

    private val _latestExchangeSnapshot = MutableStateFlow<ExchangeStateSnapshot?>(null)
    val latestExchangeSnapshot: StateFlow<ExchangeStateSnapshot?> = _latestExchangeSnapshot.asStateFlow()

    private val _executionAuditMap = MutableStateFlow<Map<String, TradeExecutionAudit>>(emptyMap())
    val executionAuditMap: StateFlow<Map<String, TradeExecutionAudit>> = _executionAuditMap.asStateFlow()

    private val _paperAccountSummary = MutableStateFlow<com.coindcx.trading.engine.paper.PaperAccountSummary?>(null)
    val paperAccountSummary: StateFlow<com.coindcx.trading.engine.paper.PaperAccountSummary?> = _paperAccountSummary.asStateFlow()

    private val _paperAnalyticsReport = MutableStateFlow<com.coindcx.trading.engine.paper.PaperAnalyticsReport?>(null)
    val paperAnalyticsReport: StateFlow<com.coindcx.trading.engine.paper.PaperAnalyticsReport?> = _paperAnalyticsReport.asStateFlow()

    fun update(
        opportunities: List<MarketOpportunity>,
        allocation: AllocationResult,
        cycle: Int = _scanCycleCount.value
    ) {
        _topOpportunities.value = opportunities
        _latestAllocation.value = allocation
        _scanCycleCount.value = cycle
        _lastScanTimestamp.value = System.currentTimeMillis()
    }

    fun updatePaperState(
        summary: com.coindcx.trading.engine.paper.PaperAccountSummary?,
        report: com.coindcx.trading.engine.paper.PaperAnalyticsReport?
    ) {
        if (summary != null) _paperAccountSummary.value = summary
        if (report != null) _paperAnalyticsReport.value = report
    }

    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
    }

    fun setRefreshingExchange(refreshing: Boolean) {
        _isRefreshingExchange.value = refreshing
    }

    fun setNextScanSecondsRemaining(seconds: Int) {
        _nextScanSecondsRemaining.value = seconds.coerceAtLeast(0)
    }

    fun updateExchangeSnapshot(snapshot: ExchangeStateSnapshot) {
        _latestExchangeSnapshot.value = snapshot
        _lastExchangeRefreshTimestamp.value = snapshot.timestamp
    }

    fun updateAudit(audits: List<TradeExecutionAudit>) {
        val newMap = mutableMapOf<String, TradeExecutionAudit>()
        for (a in audits) {
            newMap[a.pair] = a
        }
        _executionAuditMap.value = newMap
    }

    fun clearAudits() {
        _executionAuditMap.value = emptyMap()
    }
}
