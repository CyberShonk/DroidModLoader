package com.shonkware.droidmodloader.engine.install

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Metadata observations only; none authorize a manual retry or rollback. */
data class InstallRecoveryFact(
    val transactionFileName: String,
    val recordedState: String? = null,
    val hadExistingInstall: Boolean? = null,
    val finalPresent: Boolean? = null,
    val stagedPresent: Boolean? = null,
    val backupPresent: Boolean? = null,
    val problem: String? = null,
    val installDirectoryName: String? = null
) {
    fun toDebugSummary(): String = "$transactionFileName: install=${installDirectoryName ?: "unknown"}; state=${recordedState ?: "unknown"}; " +
        "replacement=${hadExistingInstall ?: "unknown"}; final=${finalPresent ?: "unknown"}; " +
        "staged=${stagedPresent ?: "unknown"}; backup=${backupPresent ?: "unknown"}" +
        (problem?.let { "; $it" } ?: "")
}

enum class InstallReplacementRecoveryAction {
    DISCARDED_STAGED_INSTALL,
    RESTORED_PREVIOUS_INSTALL,
    COMPLETED_PROMOTED_INSTALL,
    CLEARED_STALE_TRANSACTION
}

data class InstallReplacementRecoveryResult(
    val transactionFileName: String,
    val action: InstallReplacementRecoveryAction?,
    val failureMessage: String?
) {
    val recovered: Boolean
        get() = action != null
}

internal class InstallReplacementRecovery(
    private val operations: InstalledModDirectoryOperations =
        DefaultInstalledModDirectoryOperations,
    private val transactionStore:
    InstallReplacementTransactionStore =
        InstallReplacementTransactionStore()
) {
    /** Does not run recovery, follow transaction paths outside modsDir or modify evidence. */
    fun inspectAll(modsDir: File): List<InstallRecoveryFact> = try {
        if (!directoryPresent(modsDir)) emptyList() else transactionStore.listTransactionFiles(modsDir).also {
            check(it.size <= 1_000) { "Install recovery inspection exceeds 1,000 transaction limit" }
        }.map { file ->
            try {
                check(!Files.isSymbolicLink(file.toPath()) && file.isFile) { "Transaction is not a regular local file" }
                val transaction = transactionStore.load(file)
                InstallRecoveryFact(file.name, transaction.state.name, transaction.hadExistingInstall,
                    directoryPresent(File(modsDir, transaction.finalDirectoryName)),
                    directoryPresent(File(modsDir, transaction.stagedDirectoryName)),
                    directoryPresent(File(modsDir, transaction.backupDirectoryName)),
                    installDirectoryName = transaction.finalDirectoryName)
            } catch (failure: Exception) {
                InstallRecoveryFact(file.name, problem = failure.message ?: "Transaction evidence unavailable")
            }
        }
    } catch (failure: Exception) {
        listOf(InstallRecoveryFact("Installed-mod recovery", problem = failure.message ?: "Recovery evidence unavailable"))
    }

    private fun directoryPresent(file: File): Boolean = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isDirectory && !attributes.isSymbolicLink) { "Recovery path is not a local directory: ${file.name}" }
        true
    } catch (_: NoSuchFileException) { false }

    fun recoverAll(
        modsDir: File
    ): List<InstallReplacementRecoveryResult> {
        if (!modsDir.exists()) {
            return emptyList()
        }

        if (!modsDir.isDirectory) {
            throw IOException(
                "Installed-mod path is not a directory: " +
                        modsDir.absolutePath
            )
        }

        return transactionStore
            .listTransactionFiles(modsDir)
            .map { transactionFile ->
                try {
                    val transaction =
                        transactionStore.load(transactionFile)

                    val action = recoverTransaction(
                        modsDir = modsDir,
                        transactionFile = transactionFile,
                        transaction = transaction
                    )

                    InstallReplacementRecoveryResult(
                        transactionFileName =
                            transactionFile.name,
                        action = action,
                        failureMessage = null
                    )
                } catch (exception: Exception) {
                    InstallReplacementRecoveryResult(
                        transactionFileName =
                            transactionFile.name,
                        action = null,
                        failureMessage =
                            exception.message
                                ?: "Install recovery failed."
                    )
                }
            }
    }

    private fun recoverTransaction(
        modsDir: File,
        transactionFile: File,
        transaction: InstallReplacementTransaction
    ): InstallReplacementRecoveryAction {
        val finalDir = File(
            modsDir,
            transaction.finalDirectoryName
        )
        val stagedDir = File(
            modsDir,
            transaction.stagedDirectoryName
        )
        val backupDir = File(
            modsDir,
            transaction.backupDirectoryName
        )

        requireDirectoryOrAbsent(
            directory = finalDir,
            description = "final installation"
        )
        requireDirectoryOrAbsent(
            directory = stagedDir,
            description = "staged installation"
        )
        requireDirectoryOrAbsent(
            directory = backupDir,
            description = "replacement backup"
        )

        return if (transaction.hadExistingInstall) {
            recoverReplacement(
                transactionFile = transactionFile,
                transaction = transaction,
                finalDir = finalDir,
                stagedDir = stagedDir,
                backupDir = backupDir
            )
        } else {
            recoverFirstInstall(
                transactionFile = transactionFile,
                transaction = transaction,
                finalDir = finalDir,
                stagedDir = stagedDir,
                backupDir = backupDir
            )
        }
    }

    private fun recoverFirstInstall(
        transactionFile: File,
        transaction: InstallReplacementTransaction,
        finalDir: File,
        stagedDir: File,
        backupDir: File
    ): InstallReplacementRecoveryAction {
        if (backupDir.exists()) {
            throw unresolved(
                transaction = transaction,
                reason =
                    "A first-install transaction unexpectedly has a backup."
            )
        }

        return when (transaction.state) {
            InstallReplacementState.PREPARED -> {
                when {
                    !finalDir.exists() && stagedDir.exists() -> {
                        deleteDirectory(stagedDir)
                        transactionStore.delete(transactionFile)

                        InstallReplacementRecoveryAction
                            .DISCARDED_STAGED_INSTALL
                    }

                    finalDir.exists() && !stagedDir.exists() -> {
                        transactionStore.delete(transactionFile)

                        InstallReplacementRecoveryAction
                            .COMPLETED_PROMOTED_INSTALL
                    }

                    !finalDir.exists() && !stagedDir.exists() -> {
                        transactionStore.delete(transactionFile)

                        InstallReplacementRecoveryAction
                            .CLEARED_STALE_TRANSACTION
                    }

                    else -> {
                        throw unresolved(
                            transaction = transaction,
                            reason =
                                "Both the staged and final directories exist."
                        )
                    }
                }
            }

            InstallReplacementState.BACKUP_CREATED -> {
                throw unresolved(
                    transaction = transaction,
                    reason =
                        "A first-install transaction cannot create a backup."
                )
            }

            InstallReplacementState.PROMOTED -> {
                if (!finalDir.exists()) {
                    throw unresolved(
                        transaction = transaction,
                        reason =
                            "The promoted final directory is missing."
                    )
                }

                deleteDirectoryIfPresent(stagedDir)
                transactionStore.delete(transactionFile)

                InstallReplacementRecoveryAction
                    .COMPLETED_PROMOTED_INSTALL
            }
        }
    }

    private fun recoverReplacement(
        transactionFile: File,
        transaction: InstallReplacementTransaction,
        finalDir: File,
        stagedDir: File,
        backupDir: File
    ): InstallReplacementRecoveryAction {
        return when (transaction.state) {
            InstallReplacementState.PREPARED -> {
                when {
                    backupDir.exists() &&
                            !finalDir.exists() -> {
                        restoreBackup(
                            transactionFile = transactionFile,
                            finalDir = finalDir,
                            stagedDir = stagedDir,
                            backupDir = backupDir
                        )
                    }

                    !backupDir.exists() &&
                            finalDir.exists() &&
                            stagedDir.exists() -> {
                        deleteDirectory(stagedDir)
                        transactionStore.delete(transactionFile)

                        InstallReplacementRecoveryAction
                            .DISCARDED_STAGED_INSTALL
                    }

                    else -> {
                        throw unresolved(
                            transaction = transaction,
                            reason =
                                "The PREPARED filesystem state is ambiguous."
                        )
                    }
                }
            }

            InstallReplacementState.BACKUP_CREATED -> {
                when {
                    backupDir.exists() &&
                            !finalDir.exists() -> {
                        restoreBackup(
                            transactionFile = transactionFile,
                            finalDir = finalDir,
                            stagedDir = stagedDir,
                            backupDir = backupDir
                        )
                    }

                    backupDir.exists() &&
                            finalDir.exists() &&
                            !stagedDir.exists() -> {
                        finishPromotedInstall(
                            transactionFile = transactionFile,
                            finalDir = finalDir,
                            stagedDir = stagedDir,
                            backupDir = backupDir
                        )
                    }

                    !backupDir.exists() &&
                            finalDir.exists() -> {
                        deleteDirectoryIfPresent(stagedDir)
                        transactionStore.delete(transactionFile)

                        InstallReplacementRecoveryAction
                            .CLEARED_STALE_TRANSACTION
                    }

                    else -> {
                        throw unresolved(
                            transaction = transaction,
                            reason =
                                "The BACKUP_CREATED filesystem state " +
                                        "is ambiguous."
                        )
                    }
                }
            }

            InstallReplacementState.PROMOTED -> {
                when {
                    finalDir.exists() -> {
                        finishPromotedInstall(
                            transactionFile = transactionFile,
                            finalDir = finalDir,
                            stagedDir = stagedDir,
                            backupDir = backupDir
                        )
                    }

                    backupDir.exists() -> {
                        restoreBackup(
                            transactionFile = transactionFile,
                            finalDir = finalDir,
                            stagedDir = stagedDir,
                            backupDir = backupDir
                        )
                    }

                    else -> {
                        throw unresolved(
                            transaction = transaction,
                            reason =
                                "Both the promoted installation and " +
                                        "its backup are missing."
                        )
                    }
                }
            }
        }
    }

    private fun restoreBackup(
        transactionFile: File,
        finalDir: File,
        stagedDir: File,
        backupDir: File
    ): InstallReplacementRecoveryAction {
        if (finalDir.exists()) {
            throw IOException(
                "Cannot restore a backup over an existing final directory."
            )
        }

        operations.move(
            source = backupDir,
            target = finalDir
        )

        deleteDirectoryIfPresent(stagedDir)
        transactionStore.delete(transactionFile)

        return InstallReplacementRecoveryAction
            .RESTORED_PREVIOUS_INSTALL
    }

    private fun finishPromotedInstall(
        transactionFile: File,
        finalDir: File,
        stagedDir: File,
        backupDir: File
    ): InstallReplacementRecoveryAction {
        if (!finalDir.exists()) {
            throw IOException(
                "Cannot complete recovery because the final " +
                        "installation is missing."
            )
        }

        deleteDirectoryIfPresent(backupDir)
        deleteDirectoryIfPresent(stagedDir)
        transactionStore.delete(transactionFile)

        return InstallReplacementRecoveryAction
            .COMPLETED_PROMOTED_INSTALL
    }

    private fun deleteDirectoryIfPresent(
        directory: File
    ) {
        if (directory.exists()) {
            deleteDirectory(directory)
        }
    }

    private fun deleteDirectory(
        directory: File
    ) {
        if (!directory.isDirectory) {
            throw IOException(
                "Recovery path is not a directory: " +
                        directory.absolutePath
            )
        }

        if (
            !operations.deleteRecursively(directory) ||
            directory.exists()
        ) {
            throw IOException(
                "Could not remove recovery directory: " +
                        directory.absolutePath
            )
        }
    }

    private fun requireDirectoryOrAbsent(
        directory: File,
        description: String
    ) {
        if (
            directory.exists() &&
            !directory.isDirectory
        ) {
            throw IOException(
                "The $description path is not a directory: " +
                        directory.absolutePath
            )
        }
    }

    private fun unresolved(
        transaction: InstallReplacementTransaction,
        reason: String
    ): IOException {
        return IOException(
            "Install transaction ${transaction.id} requires " +
                    "manual recovery. $reason"
        )
    }
}