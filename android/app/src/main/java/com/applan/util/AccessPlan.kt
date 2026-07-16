package com.applan.util

data class AccessPlan(
    val allowedPackages: MutableSet<String>,
    val allowedAppNames: MutableList<String>,
    val planDescription: String,
    val createdAt: Long = System.currentTimeMillis(),
    val timeoutAt: Long,
    val visitedPackages: MutableSet<String> = mutableSetOf(),
    var lastForegroundPackage: String? = null,
    val violations: MutableList<ViolationRecord> = mutableListOf()
)

data class ViolationRecord(
    val packageName: String,
    val appName: String,
    val timestamp: Long = System.currentTimeMillis(),
    var resolved: Boolean = false
)
