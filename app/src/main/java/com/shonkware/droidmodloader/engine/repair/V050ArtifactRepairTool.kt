package com.shonkware.droidmodloader.engine.repair

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class V050ArtifactRepairResult(
    val reportFile: File,
    val installedModFilesRenamed: Int,
    val installedModFoldersUnwrapped: Int,
    val targetFilesRenamed: Int,
    val duplicateFoldersMerged: Int,
    val conflictsQuarantined: Int,
    val skippedCount: Int
)

class V050ArtifactRepairTool(
    private val backupRootDir: File,
    private val reportDir: File
) {

    private data class Counters(
        var installedModFilesRenamed: Int = 0,
        var installedModFoldersUnwrapped: Int = 0,
        var targetFilesRenamed: Int = 0,
        var duplicateFoldersMerged: Int = 0,
        var conflictsQuarantined: Int = 0,
        var skippedCount: Int = 0
    )

    private val badTextSuffixes = listOf(
        ".ini.txt",
        ".xml.txt",
        ".cfg.txt",
        ".json.txt",
        ".toml.txt",
        ".yaml.txt",
        ".yml.txt"
    )

    private val dataRootFolderNames = setOf(
        "data",
        "meshes",
        "textures",
        "scripts",
        "interface",
        "sound",
        "music",
        "strings",
        "video",
        "menus",
        "fonts",
        "shaders",
        "skse",
        "nvse",
        "obse",
        "fose",
        "f4se",
        "lodsettings",
        "grass",
        "seq"
    )

    fun repair(
        modsDir: File,
        dataPath: String?,
        rootPath: String?
    ): V050ArtifactRepairResult {
        val counters = Counters()
        val lines = mutableListOf<String>()

        backupRootDir.mkdirs()
        reportDir.mkdirs()

        lines.add("=== Droid Mod Loader v0.5.0-beta Artifact Repair ===")
        lines.add("Started: ${Date()}")
        lines.add("Mods dir: ${modsDir.absolutePath}")
        lines.add("Data path: ${dataPath ?: "(none)"}")
        lines.add("Root path: ${rootPath ?: "(none)"}")
        lines.add("")

        repairInstalledModFolders(
            modsDir = modsDir,
            counters = counters,
            lines = lines
        )

        repairTarget(
            label = "Data target",
            localPath = dataPath,
            counters = counters,
            lines = lines
        )

        repairTarget(
            label = "Game Root target",
            localPath = rootPath,
            counters = counters,
            lines = lines
        )

        lines.add("")
        lines.add("=== Summary ===")
        lines.add("Installed mod files renamed: ${counters.installedModFilesRenamed}")
        lines.add("Installed mod folders unwrapped: ${counters.installedModFoldersUnwrapped}")
        lines.add("Target files renamed: ${counters.targetFilesRenamed}")
        lines.add("Duplicate folders merged: ${counters.duplicateFoldersMerged}")
        lines.add("Conflicts quarantined: ${counters.conflictsQuarantined}")
        lines.add("Skipped: ${counters.skippedCount}")
        lines.add("Finished: ${Date()}")

        val reportFile = File(
            reportDir,
            "repair_v050_${timestampForFileName()}.txt"
        )

        reportFile.writeText(lines.joinToString("\n"))

        return V050ArtifactRepairResult(
            reportFile = reportFile,
            installedModFilesRenamed = counters.installedModFilesRenamed,
            installedModFoldersUnwrapped = counters.installedModFoldersUnwrapped,
            targetFilesRenamed = counters.targetFilesRenamed,
            duplicateFoldersMerged = counters.duplicateFoldersMerged,
            conflictsQuarantined = counters.conflictsQuarantined,
            skippedCount = counters.skippedCount
        )
    }

    private fun repairInstalledModFolders(
        modsDir: File,
        counters: Counters,
        lines: MutableList<String>
    ) {
        lines.add("=== Installed Mod Folder Repair ===")

        if (!modsDir.exists() || !modsDir.isDirectory) {
            lines.add("Mods directory does not exist. Skipping installed mod repair.")
            return
        }

        val modDirs = modsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        for (modDir in modDirs) {
            lines.add("")
            lines.add("Mod: ${modDir.name}")

            repairBadTextSuffixesLocal(
                root = modDir,
                rootLabel = "installed_mod/${modDir.name}",
                targetCounter = TargetCounter.INSTALLED_MOD,
                counters = counters,
                lines = lines
            )

            normalizeInstalledModWrapper(
                modDir = modDir,
                counters = counters,
                lines = lines
            )
        }
    }

    private fun normalizeInstalledModWrapper(
        modDir: File,
        counters: Counters,
        lines: MutableList<String>
    ) {
        var passes = 0

        while (passes < 3) {
            passes++

            val visibleChildren = modDir.listFiles()
                ?.filterNot { it.name == ".dml_mod.json" }
                ?: return

            val files = visibleChildren.filter { it.isFile }
            val dirs = visibleChildren.filter { it.isDirectory }

            if (files.isEmpty() && dirs.size == 1 && hasRecognizedGameContent(dirs.single())) {
                val wrapper = dirs.single()

                moveDirectoryContentsLocal(
                    sourceDir = wrapper,
                    targetDir = modDir,
                    label = "installed_mod/${modDir.name}",
                    counters = counters,
                    lines = lines
                )

                if (wrapper.listFiles()?.isEmpty() != false) {
                    wrapper.deleteRecursively()
                }

                counters.installedModFoldersUnwrapped++
                lines.add("Unwrapped single folder wrapper: ${wrapper.name}")
                continue
            }

            val dataDir = dirs.firstOrNull { it.name.equals("data", ignoreCase = true) }

            if (dataDir != null && !hasRootLevelGameRootContent(modDir)) {
                moveDirectoryContentsLocal(
                    sourceDir = dataDir,
                    targetDir = modDir,
                    label = "installed_mod/${modDir.name}/Data",
                    counters = counters,
                    lines = lines
                )

                if (dataDir.listFiles()?.isEmpty() != false) {
                    dataDir.deleteRecursively()
                }

                counters.installedModFoldersUnwrapped++
                lines.add("Flattened Data folder wrapper.")
                continue
            }

            return
        }
    }

    private fun repairTarget(
        label: String,
        localPath: String?,
        counters: Counters,
        lines: MutableList<String>
    ) {
        lines.add("")
        lines.add("=== $label Repair ===")

        if (localPath.isNullOrBlank()) {
            lines.add("$label is not configured. Skipping.")
            return
        }

        val root = File(localPath)
        if (!root.exists() || !root.isDirectory) {
            lines.add("$label path does not exist or is not a folder. Skipping: $localPath")
            counters.skippedCount++
            return
        }

        repairBadTextSuffixesLocal(
            root = root,
            rootLabel = label,
            targetCounter = TargetCounter.TARGET,
            counters = counters,
            lines = lines
        )

        repairDuplicateFoldersLocal(
            parent = root,
            relativePath = "",
            targetLabel = label,
            counters = counters,
            lines = lines
        )
    }

    private enum class TargetCounter {
        INSTALLED_MOD,
        TARGET
    }

    private fun repairBadTextSuffixesLocal(
        root: File,
        rootLabel: String,
        targetCounter: TargetCounter,
        counters: Counters,
        lines: MutableList<String>
    ) {
        if (!root.exists()) return

        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val fixedName = fixedNameForBadTextSuffix(file.name) ?: return@forEach
                val targetFile = File(file.parentFile, fixedName)

                if (!targetFile.exists()) {
                    if (moveFileLocal(file, targetFile)) {
                        when (targetCounter) {
                            TargetCounter.INSTALLED_MOD -> counters.installedModFilesRenamed++
                            TargetCounter.TARGET -> counters.targetFilesRenamed++
                        }

                        lines.add("Renamed: $rootLabel/${file.relativeTo(root).path} -> $fixedName")
                    } else {
                        lines.add("Skipped rename; move failed: ${file.absolutePath}")
                        counters.skippedCount++
                    }
                } else {
                    quarantineLocalFile(
                        file = file,
                        label = rootLabel,
                        relativePath = file.relativeTo(root).path,
                        counters = counters,
                        lines = lines
                    )
                }
            }
    }

    private fun repairDuplicateFoldersLocal(
        parent: File,
        relativePath: String,
        targetLabel: String,
        counters: Counters,
        lines: MutableList<String>
    ) {
        val dirs = parent.listFiles()
            ?.filter { it.isDirectory }
            ?: return

        for (dir in dirs) {
            repairDuplicateFoldersLocal(
                parent = dir,
                relativePath = joinPath(relativePath, dir.name),
                targetLabel = targetLabel,
                counters = counters,
                lines = lines
            )
        }

        val refreshedDirs = parent.listFiles()
            ?.filter { it.isDirectory }
            ?: return

        for (duplicate in refreshedDirs) {
            val baseName = duplicateBaseName(duplicate.name) ?: continue

            val canonical = refreshedDirs.firstOrNull {
                it != duplicate &&
                        !isDuplicateSuffixName(it.name) &&
                        it.name.equals(baseName, ignoreCase = true)
            } ?: continue

            moveDirectoryContentsLocal(
                sourceDir = duplicate,
                targetDir = canonical,
                label = "$targetLabel/${joinPath(relativePath, duplicate.name)}",
                counters = counters,
                lines = lines
            )

            if (duplicate.listFiles()?.isEmpty() != false) {
                duplicate.deleteRecursively()
            }

            counters.duplicateFoldersMerged++
            lines.add("Merged duplicate folder: $targetLabel/${joinPath(relativePath, duplicate.name)} -> ${canonical.name}")
        }
    }

    private fun moveDirectoryContentsLocal(
        sourceDir: File,
        targetDir: File,
        label: String,
        counters: Counters,
        lines: MutableList<String>
    ) {
        val children = sourceDir.listFiles() ?: return

        for (child in children) {
            val target = File(targetDir, child.name)

            if (!target.exists()) {
                if (!moveFileOrDirectoryLocal(child, target)) {
                    lines.add("Skipped move; failed: $label/${child.name}")
                    counters.skippedCount++
                }
                continue
            }

            if (child.isDirectory && target.isDirectory) {
                moveDirectoryContentsLocal(
                    sourceDir = child,
                    targetDir = target,
                    label = "$label/${child.name}",
                    counters = counters,
                    lines = lines
                )

                if (child.listFiles()?.isEmpty() != false) {
                    child.deleteRecursively()
                }
            } else {
                quarantineLocalFileOrDirectory(
                    file = child,
                    label = label,
                    relativePath = child.name,
                    counters = counters,
                    lines = lines
                )
            }
        }
    }

    private fun fixedNameForBadTextSuffix(fileName: String): String? {
        val lower = fileName.lowercase()

        if (badTextSuffixes.none { lower.endsWith(it) }) return null

        return fileName.dropLast(4)
    }

    private fun duplicateBaseName(folderName: String): String? {
        val match = Regex("""^(.+?)\s*\((\d+)\)$""").matchEntire(folderName)
            ?: return null

        return match.groupValues[1].trim()
    }

    private fun isDuplicateSuffixName(folderName: String): Boolean {
        return duplicateBaseName(folderName) != null
    }

    private fun hasRecognizedGameContent(root: File): Boolean {
        val children = root.listFiles() ?: return false

        return children.any { child ->
            val name = child.name.lowercase()

            when {
                child.isDirectory -> name in dataRootFolderNames
                child.isFile -> isLikelyGameFile(name) || isLikelyRootFile(name)
                else -> false
            }
        }
    }

    private fun hasRootLevelGameRootContent(root: File): Boolean {
        val children = root.listFiles() ?: return false

        return children.any { child ->
            child.isFile && isLikelyRootFile(child.name.lowercase())
        }
    }

    private fun isLikelyGameFile(fileName: String): Boolean {
        val lower = fileName.lowercase()

        return lower.endsWith(".esp") ||
                lower.endsWith(".esm") ||
                lower.endsWith(".esl") ||
                lower.endsWith(".bsa") ||
                lower.endsWith(".ba2") ||
                lower.endsWith(".ini")
    }

    private fun isLikelyRootFile(fileName: String): Boolean {
        val lower = fileName.lowercase()

        return lower.endsWith(".exe") ||
                lower.endsWith(".dll") ||
                lower.endsWith(".asi") ||
                lower == "d3d9.dll" ||
                lower == "d3d11.dll" ||
                lower == "dxgi.dll" ||
                lower == "dinput8.dll" ||
                lower == "xinput1_3.dll" ||
                lower == "enblocal.ini" ||
                lower == "enbseries.ini" ||
                lower.startsWith("skse_loader") ||
                lower.startsWith("nvse_loader") ||
                lower.startsWith("obse_loader") ||
                lower.startsWith("fose_loader") ||
                lower.startsWith("f4se_loader")
    }

    private fun moveFileLocal(source: File, target: File): Boolean {
        target.parentFile?.mkdirs()

        if (source.renameTo(target)) {
            return true
        }

        return try {
            source.copyTo(target, overwrite = false)
            source.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun moveFileOrDirectoryLocal(source: File, target: File): Boolean {
        target.parentFile?.mkdirs()

        if (source.renameTo(target)) {
            return true
        }

        return try {
            if (source.isDirectory) {
                source.copyRecursively(target, overwrite = false)
                source.deleteRecursively()
            } else {
                source.copyTo(target, overwrite = false)
                source.delete()
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun quarantineLocalFile(
        file: File,
        label: String,
        relativePath: String,
        counters: Counters,
        lines: MutableList<String>
    ) {
        quarantineLocalFileOrDirectory(
            file = file,
            label = label,
            relativePath = relativePath,
            counters = counters,
            lines = lines
        )
    }

    private fun quarantineLocalFileOrDirectory(
        file: File,
        label: String,
        relativePath: String,
        counters: Counters,
        lines: MutableList<String>
    ) {
        val quarantineFile = File(
            backupRootDir,
            "quarantine/${sanitizePath(label)}/${sanitizePath(relativePath)}"
        )

        quarantineFile.parentFile?.mkdirs()

        try {
            if (file.isDirectory) {
                file.copyRecursively(quarantineFile, overwrite = true)
                file.deleteRecursively()
            } else {
                file.copyTo(quarantineFile, overwrite = true)
                file.delete()
            }

            counters.conflictsQuarantined++
            lines.add("Quarantined conflict: $label/$relativePath -> ${quarantineFile.absolutePath}")
        } catch (e: Exception) {
            counters.skippedCount++
            lines.add("Skipped conflict; quarantine failed: $label/$relativePath (${e.message})")
        }
    }

    private fun joinPath(
        parent: String,
        child: String
    ): String {
        return if (parent.isBlank()) child else "$parent/$child"
    }

    private fun sanitizePath(value: String): String {
        return value
            .replace("\\", "/")
            .replace(Regex("""[^A-Za-z0-9._/-]+"""), "_")
            .trim('/')
            .ifBlank { "unknown" }
    }

    private fun timestampForFileName(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}