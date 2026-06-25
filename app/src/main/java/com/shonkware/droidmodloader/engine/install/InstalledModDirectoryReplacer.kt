package com.shonkware.droidmodloader.engine.install

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class InstalledModDirectoryReplacer internal constructor(
    private val operations: InstalledModDirectoryOperations,
    private val replacementId: () -> String,
    private val cleanupWarning: (String) -> Unit,
    private val transactionStore:
    InstallReplacementTransactionStore
)  {
    constructor() : this(
        operations = DefaultInstalledModDirectoryOperations,
        replacementId = {
            System.currentTimeMillis().toString()
        },
        cleanupWarning = {},
        transactionStore =
            InstallReplacementTransactionStore()
    )

    fun replace(
        stagedDir: File,
        finalDir: File
    ): File {
        requireValidPaths(stagedDir, finalDir)

        val modsDir = requireNotNull(finalDir.parentFile)
        val id = replacementId()
        val backupDir = File(
            modsDir,
            "_dml_backup_${finalDir.name}_$id"
        )

        if (backupDir.exists()) {
            throw IOException(
                "Replacement backup already exists: " +
                        backupDir.absolutePath
            )
        }

        val hadExistingInstall = finalDir.exists()
        var transaction =
            InstallReplacementTransaction(
                id = id,
                state =
                    InstallReplacementState.PREPARED,
                finalDirectoryName = finalDir.name,
                stagedDirectoryName = stagedDir.name,
                backupDirectoryName = backupDir.name,
                hadExistingInstall = hadExistingInstall
            )
        val transactionFile =
            transactionStore.create(
                modsDir = modsDir,
                transaction = transaction
            )

        if (hadExistingInstall) {
            try {
                operations.move(finalDir, backupDir)
            } catch (exception: Exception) {
                deleteTransactionOrSuppress(
                    transactionFile = transactionFile,
                    primaryFailure = exception
                )

                throw IOException(
                    "Could not preserve the existing installed mod " +
                            "before replacement.",
                    exception
                )
            }

            transaction = transaction.withState(
                InstallReplacementState.BACKUP_CREATED
            )

            try {
                transactionStore.update(
                    transactionFile = transactionFile,
                    transaction = transaction
                )
            } catch (exception: Exception) {
                val failure = IOException(
                    "The existing mod was backed up, but DML " +
                            "could not record the replacement state.",
                    exception
                )

                try {
                    operations.move(backupDir, finalDir)
                    deleteTransactionOrSuppress(
                        transactionFile = transactionFile,
                        primaryFailure = failure
                    )
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                }

                throw failure
            }
        }

        try {
            operations.move(stagedDir, finalDir)
        } catch (promotionFailure: Exception) {
            if (!hadExistingInstall) {
                deleteTransactionOrSuppress(
                    transactionFile = transactionFile,
                    primaryFailure = promotionFailure
                )

                throw IOException(
                    "Could not promote the staged mod installation.",
                    promotionFailure
                )
            }

            restoreBackupAfterPromotionFailure(
                finalDir = finalDir,
                backupDir = backupDir,
                transactionFile = transactionFile,
                promotionFailure = promotionFailure
            )
        }

        transaction = transaction.withState(
            InstallReplacementState.PROMOTED
        )

        try {
            transactionStore.update(
                transactionFile = transactionFile,
                transaction = transaction
            )
        } catch (exception: Exception) {
            throw IOException(
                "The replacement was promoted, but DML could not " +
                        "record its completed state. The transaction " +
                        "was retained for recovery.",
                exception
            )
        }

        val backupRemoved =
            cleanupBackupAfterSuccessfulReplacement(
                backupDir = backupDir
            )

        if (backupRemoved) {
            transactionStore.delete(transactionFile)
        }

        return finalDir
    }

    private fun requireValidPaths(
        stagedDir: File,
        finalDir: File
    ) {
        if (!stagedDir.exists() || !stagedDir.isDirectory) {
            throw IOException(
                "Staged mod directory does not exist: ${stagedDir.absolutePath}"
            )
        }

        val stagedParent = stagedDir.parentFile?.canonicalFile
            ?: throw IOException("Staged mod directory has no parent.")

        val finalParent = finalDir.parentFile?.canonicalFile
            ?: throw IOException("Final mod directory has no parent.")

        if (stagedParent != finalParent) {
            throw IOException(
                "Staged and final mod directories must have the same parent."
            )
        }

        if (!finalParent.exists() && !finalParent.mkdirs()) {
            throw IOException(
                "Could not create the installed-mod directory: ${finalParent.absolutePath}"
            )
        }

        if (!finalParent.isDirectory) {
            throw IOException(
                "Installed-mod path is not a directory: ${finalParent.absolutePath}"
            )
        }

        if (stagedDir.canonicalFile == finalDir.canonicalFile) {
            throw IOException(
                "Staged and final mod directories must be different."
            )
        }
    }

    private fun restoreBackupAfterPromotionFailure(
        finalDir: File,
        backupDir: File,
        transactionFile: File,
        promotionFailure: Exception
    ): Nothing {
        val rollbackFailures = mutableListOf<Exception>()

        if (finalDir.exists()) {
            try {
                if (!operations.deleteRecursively(finalDir)) {
                    rollbackFailures += IOException(
                        "Could not remove the incomplete replacement directory."
                    )
                }
            } catch (exception: Exception) {
                rollbackFailures += exception
            }
        }

        try {
            if (!backupDir.exists()) {
                rollbackFailures += IOException(
                    "The replacement backup is missing."
                )
            } else {
                operations.move(backupDir, finalDir)
            }
        } catch (exception: Exception) {
            rollbackFailures += exception
        }

        if (rollbackFailures.isNotEmpty()) {
            val exception = IOException(
                "Could not promote the staged mod and rollback was incomplete. " +
                        "Recoverable content may remain at ${backupDir.absolutePath}.",
                promotionFailure
            )

            rollbackFailures.forEach(exception::addSuppressed)
            throw exception
        }
        deleteTransactionOrSuppress(
            transactionFile = transactionFile,
            primaryFailure = promotionFailure
        )

        throw IOException(
            "Could not promote the staged mod. The prior installation was restored.",
            promotionFailure
        )
    }

    private fun cleanupBackupAfterSuccessfulReplacement(
        backupDir: File
    ): Boolean{
        if (!backupDir.exists()) {
            return true
        }

        val warning = try {
            if (operations.deleteRecursively(backupDir)) {
                null
            } else {
                "The replacement succeeded, but the backup could not be removed: " +
                        backupDir.absolutePath
            }
        } catch (exception: Exception) {
            "The replacement succeeded, but backup cleanup failed: " +
                    "${backupDir.absolutePath}: ${exception.message}"
        }

        if (warning != null) {
            runCatching {
                cleanupWarning(warning)
            }

            return false
        }

        return true
    }

    private fun deleteTransactionOrSuppress(
        transactionFile: File,
        primaryFailure: Throwable
    ) {
        try {
            transactionStore.delete(transactionFile)
        } catch (cleanupFailure: Exception) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }
}

internal interface InstalledModDirectoryOperations {
    fun move(
        source: File,
        target: File
    )

    fun deleteRecursively(target: File): Boolean
}

internal object DefaultInstalledModDirectoryOperations :
    InstalledModDirectoryOperations {

    override fun move(
        source: File,
        target: File
    ) {
        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException(
                    "Could not create directory: ${parent.absolutePath}"
                )
            }
        }

        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath()
            )
        }
    }

    override fun deleteRecursively(target: File): Boolean {
        return target.deleteRecursively()
    }
}