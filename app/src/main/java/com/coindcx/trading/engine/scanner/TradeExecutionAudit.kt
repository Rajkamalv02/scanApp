package com.coindcx.trading.engine.scanner

enum class AuditStatus {
    EXECUTED,
    SKIPPED_EXISTING_POSITION,
    SKIPPED_INSUFFICIENT_BALANCE,
    UNFUNDED_LIMIT,
    FAILED
}

data class TradeExecutionAudit(
    val rank: Int,
    val pair: String,
    val action: String,
    val status: AuditStatus,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)
