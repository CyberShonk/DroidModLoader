package com.shonkware.droidmodloader.engine.install

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal enum class InstallReplacementState {
    PREPARED,
    BACKUP_CREATED,
    PROMOTED
}

internal data class InstallReplacementTransaction(
    val id: String,
    val state: InstallReplacementState,
    val finalDirectoryName: String,
    val stagedDirectoryName: String,
    val backupDirectoryName: String,
    val hadExistingInstall: Boolean
) {
    fun withState(
        newState: InstallReplacementState
    ): InstallReplacementTransaction {
        return copy(state = newState)
    }
}

internal class InstallReplacementTransactionStore {
    companion object {
        private const val FORMAT_VERSION = "1"
        private const val FILE_PREFIX = "_dml_transaction_"
        private const val FILE_SUFFIX = ".properties"
    }

    fun create(
        modsDir: File,
        transaction: InstallReplacementTransaction
    ): File {
        val transactionFile = transactionFile(
            modsDir = modsDir,
            transactionId = transaction.id
        )

        if (transactionFile.exists()) {
            throw IOException(
                "Install transaction already exists: " +
                        transactionFile.name
            )
        }

        write(
            transactionFile = transactionFile,
            transaction = transaction
        )

        return transactionFile
    }

    fun update(
        transactionFile: File,
        transaction: InstallReplacementTransaction
    ) {
        if (!transactionFile.exists()) {
            throw IOException(
                "Install transaction record is missing: " +
                        transactionFile.name
            )
        }

        write(
            transactionFile = transactionFile,
            transaction = transaction
        )
    }

    fun delete(transactionFile: File) {
        if (
            transactionFile.exists() &&
            !transactionFile.delete()
        ) {
            throw IOException(
                "Could not remove install transaction record: " +
                        transactionFile.name
            )
        }
    }

    fun load(
        transactionFile: File
    ): InstallReplacementTransaction {
        val properties = Properties()

        try {
            transactionFile.inputStream()
                .buffered()
                .use(properties::load)
        } catch (exception: Exception) {
            throw IOException(
                "Could not read install transaction record: " +
                        transactionFile.name,
                exception
            )
        }

        val version = properties.getProperty("version")

        if (version != FORMAT_VERSION) {
            throw IOException(
                "Unsupported install transaction version in " +
                        transactionFile.name
            )
        }

        val id = requireProperty(
            properties = properties,
            key = "id",
            transactionFile = transactionFile
        )
        val stateValue = requireProperty(
            properties = properties,
            key = "state",
            transactionFile = transactionFile
        )
        val finalDirectoryName = requireSafeDirectoryName(
            properties = properties,
            key = "finalDirectoryName",
            transactionFile = transactionFile
        )
        val stagedDirectoryName = requireSafeDirectoryName(
            properties = properties,
            key = "stagedDirectoryName",
            transactionFile = transactionFile
        )
        val backupDirectoryName = requireSafeDirectoryName(
            properties = properties,
            key = "backupDirectoryName",
            transactionFile = transactionFile
        )
        val hadExistingInstallValue = requireProperty(
            properties = properties,
            key = "hadExistingInstall",
            transactionFile = transactionFile
        )

        val state = try {
            InstallReplacementState.valueOf(stateValue)
        } catch (exception: IllegalArgumentException) {
            throw IOException(
                "Invalid install transaction state in " +
                        transactionFile.name,
                exception
            )
        }

        val hadExistingInstall = when (
            hadExistingInstallValue
        ) {
            "true" -> true
            "false" -> false
            else -> {
                throw IOException(
                    "Invalid existing-install value in " +
                            transactionFile.name
                )
            }
        }

        if (
            transactionFile.name !=
            transactionFileName(id)
        ) {
            throw IOException(
                "Install transaction ID does not match its filename: " +
                        transactionFile.name
            )
        }

        return InstallReplacementTransaction(
            id = id,
            state = state,
            finalDirectoryName = finalDirectoryName,
            stagedDirectoryName = stagedDirectoryName,
            backupDirectoryName = backupDirectoryName,
            hadExistingInstall = hadExistingInstall
        )
    }

    fun listTransactionFiles(
        modsDir: File
    ): List<File> {
        return modsDir.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                        file.name.startsWith(FILE_PREFIX) &&
                        file.name.endsWith(FILE_SUFFIX) &&
                        !file.name.endsWith("$FILE_SUFFIX.tmp")
            }
            .sortedBy { it.name }
    }

    private fun write(
        transactionFile: File,
        transaction: InstallReplacementTransaction
    ) {
        val parent = transactionFile.parentFile
            ?: throw IOException(
                "Install transaction has no parent directory."
            )

        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException(
                "Could not create install transaction directory: " +
                        parent.absolutePath
            )
        }

        if (!parent.isDirectory) {
            throw IOException(
                "Install transaction parent is not a directory: " +
                        parent.absolutePath
            )
        }

        validateTransaction(transaction)

        val temporaryFile = File(
            parent,
            "${transactionFile.name}.tmp"
        )
        val properties = Properties().apply {
            setProperty("version", FORMAT_VERSION)
            setProperty("id", transaction.id)
            setProperty("state", transaction.state.name)
            setProperty(
                "finalDirectoryName",
                transaction.finalDirectoryName
            )
            setProperty(
                "stagedDirectoryName",
                transaction.stagedDirectoryName
            )
            setProperty(
                "backupDirectoryName",
                transaction.backupDirectoryName
            )
            setProperty(
                "hadExistingInstall",
                transaction.hadExistingInstall.toString()
            )
        }

        try {
            FileOutputStream(temporaryFile).use { output ->
                properties.store(
                    output,
                    "Droid Mod Loader install transaction"
                )
                output.fd.sync()
            }

            moveReplacing(
                source = temporaryFile,
                target = transactionFile
            )
        } catch (exception: Exception) {
            temporaryFile.delete()

            throw IOException(
                "Could not persist install transaction record: " +
                        transactionFile.name,
                exception
            )
        }
    }

    private fun moveReplacing(
        source: File,
        target: File
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun validateTransaction(
        transaction: InstallReplacementTransaction
    ) {
        requireSafeValue(
            value = transaction.id,
            description = "transaction ID"
        )
        requireSafeValue(
            value = transaction.finalDirectoryName,
            description = "final directory name"
        )
        requireSafeValue(
            value = transaction.stagedDirectoryName,
            description = "staged directory name"
        )
        requireSafeValue(
            value = transaction.backupDirectoryName,
            description = "backup directory name"
        )
    }

    private fun requireSafeDirectoryName(
        properties: Properties,
        key: String,
        transactionFile: File
    ): String {
        val value = requireProperty(
            properties = properties,
            key = key,
            transactionFile = transactionFile
        )

        requireSafeValue(
            value = value,
            description = key
        )

        return value
    }

    private fun requireSafeValue(
        value: String,
        description: String
    ) {
        if (
            value.isBlank() ||
            value == "." ||
            value == ".." ||
            value.contains('/') ||
            value.contains('\\') ||
            value.indexOf('\u0000') >= 0
        ) {
            throw IOException(
                "Unsafe $description in install transaction."
            )
        }
    }

    private fun requireProperty(
        properties: Properties,
        key: String,
        transactionFile: File
    ): String {
        return properties.getProperty(key)
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "Missing $key in install transaction: " +
                        transactionFile.name
            )
    }

    private fun transactionFile(
        modsDir: File,
        transactionId: String
    ): File {
        requireSafeValue(
            value = transactionId,
            description = "transaction ID"
        )

        return File(
            modsDir,
            transactionFileName(transactionId)
        )
    }

    private fun transactionFileName(
        transactionId: String
    ): String {
        return "$FILE_PREFIX$transactionId$FILE_SUFFIX"
    }
}