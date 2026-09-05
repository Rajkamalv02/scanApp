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

    private val _lastExchangeRefreshTimestamp = MutableStateFlow(0L)
    val lastExchangeRefreshTimestamp: StateFlow<Long> = _lastExchangeRefreshTimestamp.asStateFlow()

    private val _latestExchangeSnapshot = MutableStateFlow<ExchangeStateSnapshot?>(null)
    val latestExchangeSnapshot: StateFlow<ExchangeStateSnapshot?> = _latestExchangeSnapshot.asStateFlow()

    private val _executionAuditMap = MutableStateFlow<Map<String, TradeExecutionAudit>>(emptyMap())
    val executionAuditMap: StateFlow<Map<String, TradeExecutionAudit>> = _executionAuditMap.asStateFlow()

    fun update(
        opportunities: List<MarketOpportunity>,
        allocation: AllocationResult
    ) {
        _topOpportunities.value = opportunities
        _latestAllocation.value = allocation
        _lastScanTimestamp.value = System.currentTimeMillis()
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
        val current = _executionAuditMap.value.toMutableMap()
        for (a in audits) {
            current[a.pair] = a
        }
        _executionAuditMap.value = current
    }

    fun clearAudits() {
        _executionAuditMap.value = emptyMap()
    }
}
