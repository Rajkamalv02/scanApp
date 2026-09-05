package com.coindcx.trading.engine.scanner

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

    fun update(
        opportunities: List<MarketOpportunity>,
        allocation: AllocationResult
    ) {
        _topOpportunities.value = opportunities
        _latestAllocation.value = allocation
    }

    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
    }
}
