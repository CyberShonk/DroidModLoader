package com.shonkware.droidmodloader.engine.deploy.plan

data class ScopedDeploymentPlan(
    val dataPlan: DeploymentPlan,
    val rootPlan: DeploymentPlan
) {
    val totalOperationCount: Int
        get() = dataPlan.operationCount + rootPlan.operationCount

    fun toDebugSummary(): String {
        return buildString {
            appendLine("Scoped Deploy Plan")
            appendLine("  Total operations: $totalOperationCount")
            appendLine()
            appendLine(dataPlan.toDebugSummary())
            appendLine()
            appendLine(rootPlan.toDebugSummary())
        }
    }
}