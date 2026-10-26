package com.shonkware.droidmodloader.engine.deploy

import com.shonkware.droidmodloader.engine.storage.DirectPathValidator
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

enum class GameTargetType(val displayName: String) {
    DATA("Data"),
    GAME_ROOT("Game Root")
}

enum class GameTargetValidationSeverity {
    INFO,
    WARNING,
    ERROR
}

data class GameTargetValidationFinding(
    val code: String,
    val severity: GameTargetValidationSeverity,
    val title: String,
    val details: String = ""
)

data class GameTargetValidationResult(
    val gameId: String,
    val targetType: GameTargetType,
    val canonicalPath: String?,
    val findings: List<GameTargetValidationFinding>
) {
    val errorCount: Int
        get() = findings.count { it.severity == GameTargetValidationSeverity.ERROR }

    val warningCount: Int
        get() = findings.count { it.severity == GameTargetValidationSeverity.WARNING }

    val canDeploy: Boolean
        get() = errorCount == 0

    val statusLabel: String
        get() = when {
            errorCount > 0 -> "error"
            warningCount > 0 -> "warning"
            else -> "valid"
        }

    fun toDebugSummary(): String {
        return buildString {
            appendLine("${targetType.displayName} Target Validation")
            appendLine("  Game: $gameId")
            appendLine("  Target type: ${targetType.displayName}")
            appendLine("  Validation: $statusLabel")
            appendLine("  Canonical path: ${canonicalPath ?: "unavailable"}")
            if (findings.isNotEmpty()) {
                appendLine("  Findings:")
                findings.forEach { finding ->
                    appendLine("    ${finding.severity}: ${finding.title}")
                    if (finding.details.isNotBlank()) {
                        appendLine("      ${finding.details}")
                    }
                }
            }
        }
    }
}

class GameTargetValidator(
    private val directPathValidator: DirectPathValidator = DirectPathValidator()
) {
    fun validateTarget(
        gameId: String,
        targetType: GameTargetType,
        path: String,
        maxEntries: Int? = null
    ): GameTargetValidationResult {
        require(maxEntries == null || maxEntries > 0)
        val definition = GAME_DEFINITIONS[gameId]
        if (definition == null) {
            return GameTargetValidationResult(
                gameId = gameId,
                targetType = targetType,
                canonicalPath = null,
                findings = listOf(
                    GameTargetValidationFinding(
                        code = "UNKNOWN_GAME",
                        severity = GameTargetValidationSeverity.ERROR,
                        title = "No target validation definition exists for this game.",
                        details = gameId
                    )
                )
            )
        }

        val broadRoot = resolveObviouslyBroadRoot(path)
        if (broadRoot != null) {
            return GameTargetValidationResult(
                gameId = gameId,
                targetType = targetType,
                canonicalPath = broadRoot.absolutePath,
                findings = listOf(
                    GameTargetValidationFinding(
                        code = "TARGET_PATH_TOO_BROAD",
                        severity = GameTargetValidationSeverity.ERROR,
                        title = "${targetType.displayName} target is too broad for deployment.",
                        details = "Choose the specific game folder instead of ${broadRoot.absolutePath}."
                    )
                )
            )
        }

        val directValidation = directPathValidator.validateDirectory(
            path = path,
            requireWritable = true
        )
        if (!directValidation.isValid || directValidation.canonicalPath == null) {
            return GameTargetValidationResult(
                gameId = gameId,
                targetType = targetType,
                canonicalPath = directValidation.canonicalPath,
                findings = listOf(
                    GameTargetValidationFinding(
                        code = "TARGET_PATH_INVALID",
                        severity = GameTargetValidationSeverity.ERROR,
                        title = "${targetType.displayName} target is not available.",
                        details = directValidation.message ?: path
                    )
                )
            )
        }

        val folder = File(directValidation.canonicalPath)

        val names = try {
            if (maxEntries == null) {
                folder.listFiles()?.asSequence()?.filter { it.isFile }
                    ?.mapTo(linkedSetOf()) { it.name.lowercase(Locale.ROOT) }
            } else {
                boundedFileNames(folder, maxEntries)
            }
        } catch (_: InspectionLimitExceeded) {
            return GameTargetValidationResult(
                gameId, targetType, folder.absolutePath,
                listOf(GameTargetValidationFinding(
                    "TARGET_INSPECTION_LIMIT", GameTargetValidationSeverity.ERROR,
                    "${targetType.displayName} marker check is unavailable: inspection limit reached.",
                    "More than $maxEntries top-level entries; no missing-marker conclusion was drawn."
                ))
            )
        } catch (failure: Exception) {
            // The bounded diagnostic must not mistake permission or enumeration failure for absence.
            if (maxEntries == null) throw failure
            null
        } ?: return GameTargetValidationResult(
                gameId = gameId,
                targetType = targetType,
                canonicalPath = folder.absolutePath,
                findings = listOf(
                    GameTargetValidationFinding(
                        code = "TARGET_CONTENTS_UNAVAILABLE",
                        severity = GameTargetValidationSeverity.ERROR,
                        title = "${targetType.displayName} target contents could not be inspected.",
                        details = folder.absolutePath
                    )
                )
            )

        val requiredMarkers = definition.requiredMarkers(targetType)
        val oppositeMarkers = definition.requiredMarkers(targetType.opposite())
        val missingMarkers = requiredMarkers.filterNot { it in names }
        val findings = mutableListOf<GameTargetValidationFinding>()

        if (missingMarkers.isNotEmpty()) {
            val oppositeMatches = oppositeMarkers.filter { it in names }
            if (oppositeMatches.isNotEmpty()) {
                findings.add(
                    GameTargetValidationFinding(
                        code = "TARGET_TYPE_MISMATCH",
                        severity = GameTargetValidationSeverity.ERROR,
                        title = "Selected folder looks like ${targetType.opposite().displayName}, not ${targetType.displayName}.",
                        details = "Found ${oppositeMatches.joinToString()}, but expected ${requiredMarkers.joinToString()}."
                    )
                )
            } else {
                val foreignMatches = findForeignMarkers(
                    selectedGameId = gameId,
                    targetType = targetType,
                    names = names
                )
                if (foreignMatches.isNotEmpty()) {
                    findings.add(
                        GameTargetValidationFinding(
                            code = "WRONG_GAME_TARGET",
                            severity = GameTargetValidationSeverity.ERROR,
                            title = "${targetType.displayName} target appears to belong to another game.",
                            details = "Found ${foreignMatches.joinToString()}, but expected ${requiredMarkers.joinToString()} for ${definition.displayName}."
                        )
                    )
                } else {
                    findings.add(
                        GameTargetValidationFinding(
                            code = "GAME_MARKERS_MISSING",
                            severity = GameTargetValidationSeverity.ERROR,
                            title = "${targetType.displayName} target does not match ${definition.displayName}.",
                            details = "Missing required marker${if (missingMarkers.size == 1) "" else "s"}: ${missingMarkers.joinToString()}."
                        )
                    )
                }
            }
        } else {
            findings.add(
                GameTargetValidationFinding(
                    code = "TARGET_AVAILABLE",
                    severity = GameTargetValidationSeverity.INFO,
                    title = "${targetType.displayName} target is available.",
                    details = "Matched ${definition.displayName} using ${requiredMarkers.joinToString()}. Canonical path: ${folder.absolutePath}"
                )
            )

            if (targetType == GameTargetType.DATA && !folder.name.equals("Data", ignoreCase = true)) {
                findings.add(
                    GameTargetValidationFinding(
                        code = "UNUSUAL_DATA_FOLDER_NAME",
                        severity = GameTargetValidationSeverity.WARNING,
                        title = "Data target uses an unusual folder name.",
                        details = "Game markers match, but the selected folder is named '${folder.name}' instead of 'Data'."
                    )
                )
            }

            val unexpectedMarkers = findForeignMarkers(
                selectedGameId = gameId,
                targetType = targetType,
                names = names
            ).filterNot { it in definition.allowedAdditionalMarkers }
            if (unexpectedMarkers.isNotEmpty()) {
                findings.add(
                    GameTargetValidationFinding(
                        code = "ADDITIONAL_GAME_MARKERS",
                        severity = GameTargetValidationSeverity.WARNING,
                        title = "Additional game markers were found.",
                        details = unexpectedMarkers.joinToString()
                    )
                )
            }

            if (gameId == "ttw" && targetType == GameTargetType.DATA) {
                val ttwMarker = "taleoftwowastelands.esm"
                findings.add(
                    GameTargetValidationFinding(
                        code = if (ttwMarker in names) "TTW_MARKER_PRESENT" else "TTW_MARKER_NOT_YET_PRESENT",
                        severity = GameTargetValidationSeverity.INFO,
                        title = if (ttwMarker in names) {
                            "TTW files are present in the selected Data target."
                        } else {
                            "TTW files are not present in the selected Data target yet."
                        },
                        details = if (ttwMarker in names) {
                            "TaleOfTwoWastelands.esm was found."
                        } else {
                            "A valid Fallout New Vegas Data folder is acceptable before an initial TTW deployment."
                        }
                    )
                )
            }
        }

        return GameTargetValidationResult(
            gameId = gameId,
            targetType = targetType,
            canonicalPath = folder.absolutePath,
            findings = findings
        )
    }

    fun validateRelationship(
        dataResult: GameTargetValidationResult,
        rootResult: GameTargetValidationResult
    ): List<GameTargetValidationFinding> {
        val dataPath = dataResult.canonicalPath ?: return emptyList()
        val rootPath = rootResult.canonicalPath ?: return emptyList()
        if (!dataResult.canDeploy || !rootResult.canDeploy) return emptyList()

        val dataFolder = File(dataPath)
        val rootFolder = File(rootPath)

        if (dataFolder == rootFolder) {
            return listOf(
                GameTargetValidationFinding(
                    code = "TARGETS_IDENTICAL",
                    severity = GameTargetValidationSeverity.ERROR,
                    title = "Data and Game Root targets cannot be the same folder.",
                    details = dataFolder.absolutePath
                )
            )
        }

        if (dataFolder.parentFile != rootFolder) {
            return listOf(
                GameTargetValidationFinding(
                    code = "TARGETS_DIFFERENT_INSTALLATIONS",
                    severity = GameTargetValidationSeverity.ERROR,
                    title = "Data and Game Root targets do not belong to the same installation.",
                    details = "Data parent: ${dataFolder.parentFile?.absolutePath ?: "none"}; Game Root: ${rootFolder.absolutePath}"
                )
            )
        }

        return listOf(
            GameTargetValidationFinding(
                code = "TARGET_RELATIONSHIP_VALID",
                severity = GameTargetValidationSeverity.INFO,
                title = "Data and Game Root targets match the same installation.",
                details = rootFolder.absolutePath
            )
        )
    }

    private class InspectionLimitExceeded : IOException()

    private fun boundedFileNames(folder: File, maxEntries: Int): Set<String> {
        val names = linkedSetOf<String>()
        Files.newDirectoryStream(folder.toPath()).use { entries ->
            var count = 0
            for (entry in entries) {
                if (++count > maxEntries) throw InspectionLimitExceeded()
                if (entry.toFile().isFile) names.add(entry.fileName.toString().lowercase(Locale.ROOT))
            }
        }
        return names
    }

    private fun findForeignMarkers(
        selectedGameId: String,
        targetType: GameTargetType,
        names: Set<String>
    ): List<String> {
        val selectedDefinition = GAME_DEFINITIONS.getValue(selectedGameId)
        val selectedMarkers = selectedDefinition.requiredMarkers(targetType).toSet()

        return GAME_DEFINITIONS.values
            .asSequence()
            .filter { it.gameId != selectedGameId }
            .flatMap { it.requiredMarkers(targetType).asSequence() }
            .filter { it !in selectedMarkers && it in names }
            .distinct()
            .sorted()
            .toList()
    }

    private fun resolveObviouslyBroadRoot(path: String): File? {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return null

        val candidate = File(trimmed)
        if (!candidate.isAbsolute) return null

        val canonical = try {
            candidate.canonicalFile
        } catch (_: IOException) {
            return null
        }

        return canonical.takeIf(::isObviouslyBroadRoot)
    }

    private fun isObviouslyBroadRoot(folder: File): Boolean {
        val normalized = folder.absolutePath
            .replace('\\', '/')
            .trimEnd('/')
            .ifBlank { "/" }
        return normalized in BROAD_ROOT_PATHS || folder.parentFile == null
    }

    private fun GameTargetType.opposite(): GameTargetType {
        return when (this) {
            GameTargetType.DATA -> GameTargetType.GAME_ROOT
            GameTargetType.GAME_ROOT -> GameTargetType.DATA
        }
    }

    private data class GameTargetDefinition(
        val gameId: String,
        val displayName: String,
        val rootExecutables: Set<String>,
        val dataMasters: Set<String>,
        val allowedAdditionalMarkers: Set<String> = emptySet()
    ) {
        fun requiredMarkers(targetType: GameTargetType): Set<String> {
            return when (targetType) {
                GameTargetType.DATA -> dataMasters
                GameTargetType.GAME_ROOT -> rootExecutables
            }
        }
    }

    private companion object {
        val GAME_DEFINITIONS = listOf(
            GameTargetDefinition(
                gameId = "skyrim_le",
                displayName = "Skyrim Legendary Edition",
                rootExecutables = setOf("tesv.exe"),
                dataMasters = setOf("skyrim.esm")
            ),
            GameTargetDefinition(
                gameId = "oblivion",
                displayName = "Oblivion",
                rootExecutables = setOf("oblivion.exe"),
                dataMasters = setOf("oblivion.esm")
            ),
            GameTargetDefinition(
                gameId = "fallout_3",
                displayName = "Fallout 3",
                rootExecutables = setOf("fallout3.exe"),
                dataMasters = setOf("fallout3.esm")
            ),
            GameTargetDefinition(
                gameId = "fallout_nv",
                displayName = "Fallout New Vegas",
                rootExecutables = setOf("falloutnv.exe"),
                dataMasters = setOf("falloutnv.esm")
            ),
            GameTargetDefinition(
                gameId = "ttw",
                displayName = "Tale of Two Wastelands",
                rootExecutables = setOf("falloutnv.exe"),
                dataMasters = setOf("falloutnv.esm"),
                allowedAdditionalMarkers = setOf(
                    "fallout3.esm",
                    "taleoftwowastelands.esm"
                )
            )
        ).associateBy { it.gameId }

        val BROAD_ROOT_PATHS = setOf(
            "/",
            "/data",
            "/data/data",
            "/data/user",
            "/mnt",
            "/sdcard",
            "/storage",
            "/storage/emulated",
            "/storage/emulated/0"
        )
    }
}
