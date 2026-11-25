package com.shonkware.droidmodloader.engine.install

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

class PreparedArchiveInstaller internal constructor(
    private val tempDir: File,
    private val modsDir: File,
    private val directoryReplacer: InstalledModDirectoryReplacer,
    private val operationId: () -> String,
    private val debugLog: (String) -> Unit,
    private val fileCopier:
        (
        File,
        File,
        InstallCancellationSignal
    ) -> Unit =
        InstallFileCopier::copyRecursively,
    private val archiveExtractor: ArchiveExtractor = ArchiveExtractor()
) {
    constructor(
        tempDir: File,
        modsDir: File
    ) : this(
        tempDir = tempDir,
        modsDir = modsDir,
        directoryReplacer = InstalledModDirectoryReplacer(),
        operationId = {
            System.currentTimeMillis().toString()
        },
        debugLog = { message ->
            Log.d(TAG, message)
        }
    )

    companion object {
        private const val TAG = "DroidModLoader"
    }

    private val layoutAnalyzer = InstallerLayoutAnalyzer()

    fun prepare(
        archive: File,
        cancellationSignal:
        InstallCancellationSignal =
            InstallCancellationSignal.NONE
    ): PreparedArchiveInstall {
        cancellationSignal.throwIfCancellationRequested()
        val sessionRoot = createSessionRoot()
        val rawRoot = File(sessionRoot, "raw")

        try {
            if (!rawRoot.mkdirs()) {
                throw IOException(
                    "Could not create installer raw folder: " +
                            rawRoot.absolutePath
                )
            }

            val extraction = archiveExtractor.extractToRawFolder(
                archive = archive,
                outputDir = rawRoot,
                cancellationSignal =
                    cancellationSignal
            )

            cancellationSignal
                .throwIfCancellationRequested()

            val contentRoot =
                layoutAnalyzer.resolveContentRoot(rawRoot)
            val modName = archive.nameWithoutExtension

            cancellationSignal
                .throwIfCancellationRequested()

            val plan = layoutAnalyzer.analyze(
                contentRoot = contentRoot,
                modName = modName
            )

            debugLog(
                "Prepared installer plan: " +
                        "type=${plan.installerType}, " +
                        "groups=${plan.groups.size}"
            )

            return PreparedArchiveInstall(
                archivePath = archive.absolutePath,
                archiveName = archive.name,
                modName = modName,
                sessionRootPath =
                    sessionRoot.absolutePath,
                extractedRootPath =
                    rawRoot.absolutePath,
                installRootPath =
                    contentRoot.absolutePath,
                plan = plan,
                inspection = ArchiveInspection(extraction, plan.installerType),
                manualLayoutInventory = if (plan.installerType == InstallerType.MANUAL_DATA_FOLDER) {
                    ManualLayoutInventory.capture(sessionRoot, rawRoot, cancellationSignal)
                } else null
            )
        } catch (exception: Exception) {
            try {
                if (sessionRoot.exists() && !sessionRoot.deleteRecursively()) {
                    throw IOException("Could not clean failed prepared installer session: ${sessionRoot.absolutePath}")
                }
            } catch (cleanupFailure: Exception) {
                exception.addSuppressed(cleanupFailure)
            }

            if (exception is InstallCancelledException ||
                exception is ArchiveReadException || exception is ArchiveFormatProbeException
            ) {
                throw exception
            }

            throw IOException(
                "Failed to prepare archive install for " +
                        "${archive.name}: ${exception.message}",
                exception
            )
        }
    }

    private fun createSessionRoot(): File {
        // Android may expose the managed base through a platform path alias.
        // Resolve that trusted parent before creating the exclusively owned session.
        val parent = File(tempDir, "installer_sessions").canonicalFile
        java.nio.file.Files.createDirectories(parent.toPath())
        // Exclusive creation proves ownership before any cleanup can remove this session.
        return java.nio.file.Files.createDirectory(File(parent, operationId()).toPath()).toFile()
    }

    fun finalizeInstall(
        prepared: PreparedArchiveInstall,
        selection: InstallerSelection,
        cancellationSignal:
        InstallCancellationSignal =
            InstallCancellationSignal.NONE,
        manualLayoutProposal: ManualLayoutProposal? = null
    ): File = finalizeInstallInternal(prepared, selection, cancellationSignal, manualLayoutProposal, null)

    /** Reuses the existing extraction/layout/copy guards; the shared transaction owns
     * staging as soon as publication starts, including failed or uncertain publication. */
    internal fun finalizeLibraryInstall(
        prepared: PreparedArchiveInstall,
        selection: InstallerSelection,
        staging: LibraryInstallStaging,
        cancellationSignal: InstallCancellationSignal = InstallCancellationSignal.NONE,
        manualLayoutProposal: ManualLayoutProposal? = null
    ): File = finalizeInstallInternal(prepared, selection, cancellationSignal, manualLayoutProposal, staging)

    private fun finalizeInstallInternal(
        prepared: PreparedArchiveInstall,
        selection: InstallerSelection,
        cancellationSignal: InstallCancellationSignal,
        manualLayoutProposal: ManualLayoutProposal?,
        libraryStaging: LibraryInstallStaging?
    ): File {
        cancellationSignal
            .throwIfCancellationRequested()

        val confirmation = prepared.confirmation
        val manualPreview = resolveManualPreview(prepared, selection, manualLayoutProposal, cancellationSignal)

        val modsDir = libraryStaging?.finalDirectory?.parentFile ?: this.modsDir.canonicalFile
        ensureDirectoryExists(
            directory = modsDir,
            description = "installed-mod directory"
        )

        val installedName = confirmation?.modName ?: prepared.modName
        val finalDir = libraryStaging?.finalDirectory ?: File(modsDir, installedName)
        val existingInstall = if (libraryStaging != null) libraryStaging.hasExistingInstall
            else confirmation != null && findExistingModName(installedName) != null
        val previousInventory = if (existingInstall) {
            check(confirmation != null) { "An update needs an explicit existing mod action" }
            if (confirmation.existingModAction == ExistingModAction.ASK) {
                throw IOException("A mod named $installedName already exists. Choose Add / Update Files or Replace.")
            }
            // Validate the old folder even for replacement: never replace a link.
            ManualLayoutInventory.capture(modsDir, finalDir, cancellationSignal)
        } else null
        val stagedDir = libraryStaging?.stageDirectory ?: File(
            modsDir,
            "_installing_${installedName}_" +
                    operationId()
        )
        val installRoot =
            File(prepared.installRootPath)

        if (
            !installRoot.exists() ||
            !installRoot.isDirectory
        ) {
            throw IOException(
                "Prepared install root does not exist: " +
                        installRoot.absolutePath
            )
        }

        if (stagedDir.exists()) {
            throw IOException(
                "Staged prepared install already exists: " +
                        stagedDir.absolutePath
            )
        }

        if (!stagedDir.mkdirs()) {
            throw IOException(
                "Could not create staged prepared " +
                        "install folder: " +
                        stagedDir.absolutePath
            )
        }

        var publicationStarted = false
        try {
            val selectedOptions = selectedOptions(prepared, selection)

            if (selectedOptions.isEmpty() && manualPreview == null) {
                throw IllegalStateException(
                    "No installer options selected."
                )
            }

            if (confirmation?.existingModAction == ExistingModAction.ADD_UPDATE && previousInventory != null) {
                previousInventory.entries.forEach { entry ->
                    cancellationSignal.throwIfCancellationRequested()
                    val target = safeResolveInstallerPath(stagedDir, entry.path)
                    if (entry.directory) {
                        ensureDirectoryExists(target, "staged mod folder")
                    } else {
                        val file = ManualLayoutFile(entry.path, entry.path, entry.sizeBytes,
                            com.shonkware.droidmodloader.engine.model.DeployScope.IGNORE, entry.modifiedAtMillis)
                        fileCopier(previousInventory.sourceFile(file), target, cancellationSignal)
                    }
                }
            }

            if (manualPreview != null) {
                val inventory = checkNotNull(prepared.manualLayoutInventory)
                manualPreview.files.forEach { file ->
                    cancellationSignal.throwIfCancellationRequested()
                    fileCopier(inventory.sourceFile(file),
                        safeResolveInstallerPath(stagedDir, file.destinationPath), cancellationSignal)
                }
                inventory.requireCurrent(cancellationSignal)
            } else {
                selectedOptions.forEach { option ->
                    cancellationSignal
                        .throwIfCancellationRequested()

                    copyOption(
                        installRoot = installRoot,
                        option = option,
                        finalDir = stagedDir,
                        cancellationSignal =
                            cancellationSignal
                    )
                }
            }

            cancellationSignal
                .throwIfCancellationRequested()

            // Check the union after overlay too, including case/path conflicts.
            if (previousInventory != null) {
                previousInventory.requireCurrent(cancellationSignal)
                ManualLayoutInventory.capture(stagedDir.parentFile!!, stagedDir, cancellationSignal)
            }
            val installedDir = if (libraryStaging != null) {
                publicationStarted = true
                libraryStaging.publish()
                finalDir
            } else directoryReplacer.replace(
                stagedDir = stagedDir,
                finalDir = finalDir
            )

            cleanCompletedSession(prepared)

            return installedDir
        } catch (exception: Exception) {
            val stagingCleanupFailure =
                if (publicationStarted) null else cleanFailedStaging(stagedDir)

            if (
                exception is
                        InstallCancelledException
            ) {
                stagingCleanupFailure?.let(
                    exception::addSuppressed
                )

                try {
                    cancel(prepared)
                } catch (
                    cleanupException: Exception
                ) {
                    exception.addSuppressed(
                        cleanupException
                    )
                }

                throw exception
            }

            val installFailure = IOException(
                "Failed to finalize archive install for " +
                        "${prepared.archiveName}: " +
                        exception.message,
                exception
            )

            stagingCleanupFailure?.let(
                installFailure::addSuppressed
            )

            throw installFailure
        }
    }

    /** Count every selected mapping, even when the same source is copied more than
     * once. Overlapping destinations are conservatively counted without reuse credit. */
    internal fun libraryCopyBytes(
        prepared: PreparedArchiveInstall,
        selection: InstallerSelection,
        manualLayoutProposal: ManualLayoutProposal?,
        signal: InstallCancellationSignal
    ): Long {
        val preview = resolveManualPreview(prepared, selection, manualLayoutProposal, signal)
        if (preview != null) return preview.files.fold(0L) { total, file -> Math.addExact(total, file.sizeBytes) }
        val options = selectedOptions(prepared, selection)
        check(options.isNotEmpty()) { "No installer options selected." }
        return options.fold(0L) { total, option ->
            signal.throwIfCancellationRequested()
            val source = optionSource(File(prepared.installRootPath), option)
            val attributes = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val bytes = if (attributes.isDirectory) {
                ManualLayoutInventory.capture(File(prepared.sessionRootPath), source, signal).entries
                    .fold(0L) { size, entry -> Math.addExact(size, entry.sizeBytes) }
            } else {
                check(attributes.isRegularFile && !attributes.isSymbolicLink) { "Installer source is not a regular file" }
                attributes.size()
            }
            Math.addExact(total, bytes)
        }
    }

    private fun selectedOptions(prepared: PreparedArchiveInstall, selection: InstallerSelection) =
        prepared.plan.groups.flatMap { it.options }.filter { it.required || it.id in selection.selectedOptionIds }

    private fun resolveManualPreview(
        prepared: PreparedArchiveInstall,
        selection: InstallerSelection,
        manualLayoutProposal: ManualLayoutProposal?,
        signal: InstallCancellationSignal
    ): ManualLayoutPreview? {
        val confirmation = prepared.confirmation
        confirmation?.let { requireValidModName(it.modName) }
        val explicitProposal = confirmation?.manualLayout
        return if (explicitProposal != null) {
            val inventory = requireManualInventory(prepared, signal)
            inventory.preview(explicitProposal, signal).also {
                if (!it.recognizedLayout && !confirmation.allowUnrecognizedLayout) {
                    throw IOException("This mod's file structure is unrecognized.")
                }
            }
        } else if (prepared.plan.installerType == InstallerType.MANUAL_DATA_FOLDER) {
            validateManualSelection(prepared, selection, manualLayoutProposal, signal)
        } else {
            if (manualLayoutProposal != null) throw IOException("This installer does not use a manual layout.")
            null
        }
    }

    private fun optionSource(installRoot: File, option: InstallerOption): File =
        if (isCurrentDirectoryPath(option.sourcePath)) installRoot else safeResolveInstallerPath(installRoot, option.sourcePath)

    /** Folder names only; do not scan mod contents or trust a stale dashboard list. */
    fun findExistingModName(name: String): String? {
        requireValidModName(name)
        if (!modsDir.exists()) return null
        val matches = (modsDir.listFiles() ?: throw IOException("Could not read installed mods."))
            .filter { modNameKey(it.name) == modNameKey(name) }
        if (matches.size > 1 || matches.any { it.name != name }) {
            throw IOException("A mod with this name already exists with different spelling. Use its exact name or choose another name.")
        }
        return matches.singleOrNull()?.name
    }

    private fun requireManualInventory(
        prepared: PreparedArchiveInstall,
        signal: InstallCancellationSignal
    ): ManualLayoutInventory {
        val inventory = prepared.manualLayoutInventory
            ?: throw IOException("Prepared archive details are missing. Import the archive again.")
        if (File(inventory.sessionRootPath).absolutePath != File(prepared.sessionRootPath).absolutePath ||
            File(inventory.extractedRootPath).absolutePath != File(prepared.extractedRootPath).absolutePath) {
            throw IOException("Prepared archive details belong to a different installer session.")
        }
        inventory.requireCurrent(signal)
        return inventory
    }

    private fun validateManualSelection(
        prepared: PreparedArchiveInstall,
        selection: InstallerSelection,
        proposal: ManualLayoutProposal?,
        signal: InstallCancellationSignal
    ): ManualLayoutPreview {
        val options = prepared.plan.groups.flatMap { it.options }
        val selectedId = selection.selectedOptionIds.singleOrNull()
            ?: throw IOException("Choose exactly one Data folder.")
        val option = options.singleOrNull { it.id == selectedId }
            ?: throw IOException("The chosen Data folder is no longer available.")
        if (!isCurrentDirectoryPath(option.destinationPath)) {
            throw IOException("Manual Data folder destinations cannot be changed.")
        }
        val inventory = prepared.manualLayoutInventory
            ?: throw IOException("Prepared archive details are missing. Import the archive again.")
        if (File(inventory.sessionRootPath).absolutePath != File(prepared.sessionRootPath).absolutePath ||
            File(inventory.extractedRootPath).absolutePath != File(prepared.extractedRootPath).absolutePath) {
            throw IOException("Prepared archive details belong to a different installer session.")
        }
        inventory.requireCurrent(signal)
        val source = if (isCurrentDirectoryPath(option.sourcePath)) File(prepared.installRootPath)
            else safeResolveInstallerPath(File(prepared.installRootPath), option.sourcePath)
        val root = File(inventory.extractedRootPath).canonicalFile
        val candidate = source.canonicalFile
        if (candidate != root && !candidate.toPath().startsWith(root.toPath())) {
            throw IOException("The chosen Data folder is outside this prepared archive.")
        }
        val relative = root.toPath().relativize(candidate.toPath()).toString().replace(File.separatorChar, '/')
        if (proposal != null && proposal.rootPath != relative) {
            throw IOException("The layout preview belongs to a different Data folder.")
        }
        return inventory.preview(proposal ?: ManualLayoutProposal(relative), signal)
    }

    fun cancel(
        prepared: PreparedArchiveInstall
    ) {
        val sessionRoot =
            File(prepared.sessionRootPath)

        if (
            sessionRoot.exists() &&
            !sessionRoot.deleteRecursively()
        ) {
            throw IOException(
                "Could not remove prepared " +
                        "installer session: " +
                        sessionRoot.absolutePath
            )
        }
    }

    private fun cleanCompletedSession(
        prepared: PreparedArchiveInstall
    ) {
        val sessionRoot =
            File(prepared.sessionRootPath)

        if (
            sessionRoot.exists() &&
            !sessionRoot.deleteRecursively()
        ) {
            debugLog(
                "Prepared install succeeded, but its " +
                        "session could not be removed: " +
                        sessionRoot.absolutePath
            )
        }
    }

    private fun cleanFailedStaging(
        stagedDir: File
    ): IOException? {
        if (!stagedDir.exists()) {
            return null
        }

        return try {
            if (stagedDir.deleteRecursively()) {
                null
            } else {
                IOException(
                    "Could not clean failed prepared " +
                            "install staging: " +
                            stagedDir.absolutePath
                )
            }
        } catch (exception: Exception) {
            IOException(
                "Could not clean failed prepared " +
                        "install staging: " +
                        stagedDir.absolutePath,
                exception
            )
        }
    }

    private fun ensureDirectoryExists(
        directory: File,
        description: String
    ) {
        if (
            !directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IOException(
                "Could not create $description: " +
                        directory.absolutePath
            )
        }

        if (!directory.isDirectory) {
            throw IOException(
                "Configured $description is not " +
                        "a directory: " +
                        directory.absolutePath
            )
        }
    }

    private fun copyOption(
        installRoot: File,
        option: InstallerOption,
        finalDir: File,
        cancellationSignal:
        InstallCancellationSignal
    ) {
        cancellationSignal
            .throwIfCancellationRequested()

        val source = optionSource(installRoot, option)

        if (!source.exists()) {
            throw IOException(
                "Installer source does not exist: " +
                        source.absolutePath
            )
        }

        if (source.isDirectory) {
            copyDirectoryOption(
                source = source,
                option = option,
                finalDir = finalDir,
                cancellationSignal =
                    cancellationSignal
            )
        } else {
            copyFileOption(
                source = source,
                option = option,
                finalDir = finalDir,
                cancellationSignal =
                    cancellationSignal
            )
        }
    }

    private fun copyDirectoryOption(
        source: File,
        option: InstallerOption,
        finalDir: File,
        cancellationSignal:
        InstallCancellationSignal
    ) {
        val destinationRoot = if (
            isCurrentDirectoryPath(
                option.destinationPath
            )
        ) {
            finalDir
        } else {
            safeResolveInstallerPath(
                root = finalDir,
                relativePath =
                    option.destinationPath
            )
        }

        if (
            !destinationRoot.exists() &&
            !destinationRoot.mkdirs()
        ) {
            throw IOException(
                "Could not create installer " +
                        "destination: " +
                        destinationRoot.absolutePath
            )
        }

        // Copy the selected folder as-is. Do not
        // auto-collapse Data here; mixed root/Data
        // mods such as SKSE must retain root files.
        fileCopier(
            source,
            destinationRoot,
            cancellationSignal
        )
    }

    private fun copyFileOption(
        source: File,
        option: InstallerOption,
        finalDir: File,
        cancellationSignal:
        InstallCancellationSignal
    ) {
        val destination = if (
            isCurrentDirectoryPath(
                option.destinationPath
            )
        ) {
            File(finalDir, source.name)
        } else {
            val rawDestination =
                safeResolveInstallerPath(
                    root = finalDir,
                    relativePath =
                        option.destinationPath
                )

            if (
                option.destinationPath
                    .endsWith("/") ||
                option.destinationPath
                    .endsWith("\\")
            ) {
                File(
                    rawDestination,
                    source.name
                )
            } else {
                rawDestination
            }
        }

        val parent = destination.parentFile
            ?: throw IOException(
                "Installer destination has no " +
                        "parent: " +
                        destination.absolutePath
            )

        if (
            !parent.exists() &&
            !parent.mkdirs()
        ) {
            throw IOException(
                "Could not create installer " +
                        "destination parent: " +
                        parent.absolutePath
            )
        }

        fileCopier(
            source,
            destination,
            cancellationSignal
        )
    }

    private fun isCurrentDirectoryPath(
        path: String
    ): Boolean {
        val normalized = path
            .replace("\\", "/")
            .trim()
            .trim('/')

        return normalized.isBlank() ||
                normalized == "."
    }

    private fun normalizeInstallerRelativePath(
        relativePath: String
    ): String {
        return when (
            val normalized =
                ArchiveEntryPath.normalize(
                    relativePath
                )
        ) {
            ArchiveEntryPathResult.Ignore -> {
                throw IOException(
                    "Installer path is empty or " +
                            "invalid: " +
                            relativePath
                )
            }

            is ArchiveEntryPathResult.Valid -> {
                normalized.relativePath
            }
        }
    }

    private fun safeResolveInstallerPath(
        root: File,
        relativePath: String
    ): File {
        val normalizedPath =
            normalizeInstallerRelativePath(
                relativePath
            )

        return ArchiveEntryPath.safeResolve(
            root = root,
            relativePath = normalizedPath
        )
    }
}
