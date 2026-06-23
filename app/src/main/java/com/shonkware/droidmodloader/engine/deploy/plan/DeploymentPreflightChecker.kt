package com.shonkware.droidmodloader.engine.deploy.plan

import com.shonkware.droidmodloader.engine.model.GameDeploymentConfig
import java.io.File

class DeploymentPreflightChecker {

    fun check(
        config: GameDeploymentConfig?,
        plan: ScopedDeploymentPlan
    ): DeploymentPreflightResult {
        val issues = mutableListOf<DeploymentPreflightIssue>()

        checkConfig(config, issues)
        checkTargets(config, plan, issues)
        checkPlanPaths(plan, issues)
        checkSourceFiles(plan, issues)

        if (issues.isEmpty()) {
            issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.INFO,
                    title = "No preflight issues found.",
                    details = "The current deploy plan did not find obvious safety problems."
                )
            )
        }

        return DeploymentPreflightResult(issues)
    }

    private fun checkConfig(
        config: GameDeploymentConfig?,
        issues: MutableList<DeploymentPreflightIssue>
    ) {
        if (config == null) {
            issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.WARNING,
                    title = "No saved game deploy config found.",
                    details = "The app may use fallback test output folders instead of a selected real target."
                )
            )
            return
        }

        if (!config.realDeployEnabled) {
            issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.INFO,
                    title = "Real deploy is disabled.",
                    details = "Deploy will use the app's test output folders instead of real game folders."
                )
            )
        }
    }

    private fun checkTargets(
        config: GameDeploymentConfig?,
        plan: ScopedDeploymentPlan,
        issues: MutableList<DeploymentPreflightIssue>
    ) {
        if (config == null || !config.realDeployEnabled) return

        if (config.dataPathReselectionRequired) {
            issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.ERROR,
                    title = "Data target must be reselected.",
                    details = "The previous folder permission cannot be converted into a direct path."
                )
            )
        } else {
            checkPathTarget(
                label = "Data target",
                path = config.targetDataPath,
                required = true,
                issues = issues
            )
        }

        val rootOperationsNeeded = plan.rootPlan.operationCount > 0
        if (config.rootPathReselectionRequired && rootOperationsNeeded) {
            issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.ERROR,
                    title = "Game Root target must be reselected.",
                    details = "The previous folder permission cannot be converted into a direct path."
                )
            )
        } else if (config.targetRootPath.isNotBlank() || rootOperationsNeeded) {
            checkPathTarget(
                label = "Game Root target",
                path = config.targetRootPath,
                required = rootOperationsNeeded,
                issues = issues
            )
        }
    }

    private fun checkPathTarget(
        label: String,
        path: String,
        required: Boolean,
        issues: MutableList<DeploymentPreflightIssue>
    ) {
        if (path.isBlank()) {
            if (required) {
                issues.add(
                    DeploymentPreflightIssue(
                        severity = DeploymentPreflightSeverity.ERROR,
                        title = "$label is not selected.",
                        details = "Choose a direct filesystem folder before real deploy."
                    )
                )
            }
            return
        }

        val folder = runCatching { File(path).canonicalFile }.getOrElse { error ->
            issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.ERROR,
                    title = "$label path could not be resolved.",
                    details = error.message ?: path
                )
            )
            return
        }

        when {
            !folder.exists() -> issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.ERROR,
                    title = "$label path does not exist.",
                    details = folder.path
                )
            )

            !folder.isDirectory -> issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.ERROR,
                    title = "$label path is not a folder.",
                    details = folder.path
                )
            )

            !folder.canRead() -> issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.ERROR,
                    title = "$label path is not readable.",
                    details = folder.path
                )
            )

            !folder.canWrite() -> issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.ERROR,
                    title = "$label path is not writable.",
                    details = folder.path
                )
            )

            else -> issues.add(
                DeploymentPreflightIssue(
                    severity = DeploymentPreflightSeverity.INFO,
                    title = "$label is available.",
                    details = folder.path
                )
            )
        }
    }

    private fun checkPlanPaths(
        plan: ScopedDeploymentPlan,
        issues: MutableList<DeploymentPreflightIssue>
    ) {
        val allOperations = plan.dataPlan.operations + plan.rootPlan.operations

        for (operation in allOperations) {
            val path = operation.normalizedPath

            if (path.isBlank()) {
                issues.add(
                    DeploymentPreflightIssue(
                        severity = DeploymentPreflightSeverity.ERROR,
                        title = "Blank deploy path found.",
                        details = operation.type.name
                    )
                )
                continue
            }

            if (path.startsWith("/") ||
                path.startsWith("\\") ||
                path.contains("\\") ||
                path.split("/").any { it == ".." } ||
                path.contains(":")
            ) {
                issues.add(
                    DeploymentPreflightIssue(
                        severity = DeploymentPreflightSeverity.ERROR,
                        title = "Unsafe deploy path found.",
                        details = path
                    )
                )
            }
        }
    }

    private fun checkSourceFiles(
        plan: ScopedDeploymentPlan,
        issues: MutableList<DeploymentPreflightIssue>
    ) {
        val operations = plan.dataPlan.operations + plan.rootPlan.operations

        val sourceOperations = operations.filter {
            it.type == DeploymentPlanOperationType.ADD ||
                    it.type == DeploymentPlanOperationType.UPDATE ||
                    it.type == DeploymentPlanOperationType.FORCE_REWRITE
        }
        for (operation in sourceOperations) {
            val sourcePath = operation.newRecord?.sourceFilePath

            if (sourcePath.isNullOrBlank()) {
                issues.add(
                    DeploymentPreflightIssue(
                        severity = DeploymentPreflightSeverity.ERROR,
                        title = "Missing source file path.",
                        details = operation.normalizedPath
                    )
                )
                continue
            }

            val sourceFile = File(sourcePath)

            when {
                !sourceFile.exists() -> {
                    issues.add(
                        DeploymentPreflightIssue(
                            severity = DeploymentPreflightSeverity.ERROR,
                            title = "Source file does not exist.",
                            details = "${operation.normalizedPath} -> $sourcePath"
                        )
                    )
                }

                !sourceFile.isFile -> {
                    issues.add(
                        DeploymentPreflightIssue(
                            severity = DeploymentPreflightSeverity.ERROR,
                            title = "Source path is not a file.",
                            details = "${operation.normalizedPath} -> $sourcePath"
                        )
                    )
                }

                !sourceFile.canRead() -> {
                    issues.add(
                        DeploymentPreflightIssue(
                            severity = DeploymentPreflightSeverity.ERROR,
                            title = "Source file is not readable.",
                            details = "${operation.normalizedPath} -> $sourcePath"
                        )
                    )
                }
            }
        }
    }
}