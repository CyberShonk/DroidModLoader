package com.shonkware.droidmodloader.engine.install

import java.io.File

class InstallerLayoutAnalyzer {

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

    fun analyze(contentRoot: File, modName: String): InstallerPlan {
        val fomodConfig = findFomodConfig(contentRoot)
        if (fomodConfig != null) {
            return FomodInstallerParser().parse(contentRoot, fomodConfig, modName)
        }

        val bainPlan = createBainPlanIfApplicable(contentRoot, modName)
        if (bainPlan != null) {
            return bainPlan
        }

        if (hasRecognizedGameContent(contentRoot)) {
            return createSimplePlan(contentRoot, modName)
        }

        val candidates = findDataRootCandidates(contentRoot)

        if (candidates.isNotEmpty()) {
            return createManualDataFolderPlan(
                contentRoot = contentRoot,
                modName = modName,
                candidates = candidates
            )
        }

        // Extraction safety is already enforced. Unknown structure requires an
        // explicit file selection and acknowledgement, not an extraction failure.
        return InstallerPlan(
            installerType = InstallerType.MANUAL_DATA_FOLDER,
            displayName = modName,
            rootPath = contentRoot.absolutePath,
            groups = emptyList()
        )
    }

    fun resolveContentRoot(rawExtractRoot: File): File {
        var current = rawExtractRoot
        var passes = 0

        while (passes < 5) {
            passes++

            val children = current.listFiles()
                ?.filter { it.exists() && it.name != "." && it.name != ".." }
                ?: return current

            val files = children.filter { it.isFile }
            val dirs = children.filter { it.isDirectory }

            if (hasRecognizedGameContent(current) || findFomodConfig(current) != null) {
                return current
            }

            if (files.isEmpty() && dirs.size == 1) {
                val onlyDir = dirs.single()
                val onlyDirName = onlyDir.name.lowercase()

                if (onlyDirName in dataRootFolderNames) {
                    return current
                }

                current = onlyDir
                continue
            }

            return current
        }

        return current
    }

    private fun findFomodConfig(contentRoot: File): File? {
        val fomodDir = contentRoot.listFiles()
            ?.firstOrNull {
                it.isDirectory && it.name.equals("fomod", ignoreCase = true)
            }

        if (fomodDir != null) {
            val config = fomodDir.listFiles()
                ?.firstOrNull {
                    it.isFile && it.name.equals("ModuleConfig.xml", ignoreCase = true)
                }

            if (config != null) return config
        }

        return null
    }

    private fun createBainPlanIfApplicable(
        contentRoot: File,
        modName: String
    ): InstallerPlan? {
        val folders = contentRoot.listFiles()
            ?.filter { it.isDirectory && isBainFolder(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        if (folders.isEmpty()) return null

        val options = folders.mapIndexed { index, folder ->
            val core = isCoreFolder(folder.name)

            InstallerOption(
                id = "bain_$index",
                name = folder.name,
                description = if (core) {
                    "Core or required package folder"
                } else {
                    "Optional package folder"
                },
                sourcePath = folder.name,
                destinationPath = "",
                required = core,
                selectedByDefault = core
            )
        }

        val warnings = mutableListOf<String>()

        val optionalCount = options.count { !it.required }
        if (optionalCount > 0) {
            warnings.add("BAIN-style optional folders detected. Choose only the options you want installed.")
        }

        if (options.none { it.required }) {
            warnings.add("BAIN-style folders were found, but no obvious core folder was detected. Select at least one folder.")
        }

        return InstallerPlan(
            installerType = InstallerType.BAIN,
            displayName = modName,
            rootPath = contentRoot.absolutePath,
            groups = listOf(
                InstallerGroup(
                    id = "bain_main",
                    name = "BAIN Package Folders",
                    type = InstallerGroupType.SELECT_ANY,
                    options = options
                )
            ),
            warnings = warnings
        )
    }

    private fun createSimplePlan(
        contentRoot: File,
        modName: String
    ): InstallerPlan {
        val hasDataFolder = File(contentRoot, "Data").exists()
        val hasRootLevelGameRootFiles = hasRootLevelGameRootContent(contentRoot)

        val source = when {
            hasDataFolder && !hasRootLevelGameRootFiles -> "Data"
            else -> "."
        }

        return InstallerPlan(
            installerType = InstallerType.SIMPLE,
            displayName = modName,
            rootPath = contentRoot.absolutePath,
            groups = listOf(
                InstallerGroup(
                    id = "simple",
                    name = "Simple Install",
                    type = InstallerGroupType.SELECT_ANY,
                    options = listOf(
                        InstallerOption(
                            id = "simple_all",
                            name = "Install detected files",
                            sourcePath = source,
                            destinationPath = "",
                            required = true,
                            selectedByDefault = true
                        )
                    )
                )
            )
        )
    }

    private fun createManualDataFolderPlan(
        contentRoot: File,
        modName: String,
        candidates: List<File>
    ): InstallerPlan {
        val options = candidates
            .distinctBy { it.absolutePath }
            .sortedBy { candidate ->
                candidate.relativeTo(contentRoot).path.length
            }
            .take(12)
            .mapIndexed { index, candidate ->
                val relativePath = candidate
                    .relativeTo(contentRoot)
                    .path
                    .replace("\\", "/")
                    .let { if (it == ".") "" else it }

                InstallerOption(
                    id = "manual_data_folder_$index",
                    name = if (relativePath.isBlank()) {
                        "Use archive root as the Data folder"
                    } else {
                        "Use $relativePath as the Data folder"
                    },
                    description = "Install the contents of this folder as the mod's Data content.",
                    sourcePath = if (relativePath.isBlank()) "." else relativePath,
                    destinationPath = "",
                    required = false,
                    selectedByDefault = index == 0
                )
            }

        return InstallerPlan(
            installerType = InstallerType.MANUAL_DATA_FOLDER,
            displayName = modName,
            rootPath = contentRoot.absolutePath,
            groups = listOf(
                InstallerGroup(
                    id = "manual_data_folder",
                    name = "Choose Data Folder",
                    type = InstallerGroupType.SELECT_EXACTLY_ONE,
                    options = options
                )
            ),
            warnings = listOf(
                "No clear game data folder was found at the archive root. Choose which folder should be installed as the Data folder."
            )
        )
    }

    private fun findDataRootCandidates(
        root: File,
        maxDepth: Int = 4
    ): List<File> {
        val results = mutableListOf<File>()

        fun visit(dir: File, depth: Int) {
            if (depth > maxDepth) return

            if (dir.name.equals("fomod", ignoreCase = true)) {
                return
            }

            if (hasRecognizedGameContent(dir)) {
                results.add(dir)
                return
            }

            val children = dir.listFiles() ?: return

            children
                .filter { it.isDirectory }
                .filterNot { it.name.equals("fomod", ignoreCase = true) }
                .forEach { child ->
                    visit(child, depth + 1)
                }
        }

        visit(root, 0)

        return results.distinctBy { it.absolutePath }
    }

    private fun hasRecognizedGameContent(root: File): Boolean {
        val children = root.listFiles() ?: return false

        return children.any { child ->
            val name = child.name.lowercase()

            when {
                child.isDirectory -> name in dataRootFolderNames
                child.isFile -> isLikelyDataRootFile(name) || isLikelyGameRootFile(name)
                else -> false
            }
        }
    }

    /** Recognition hint for a proposed output tree; not a game compatibility check. */
    internal fun hasRecognizedGamePaths(paths: List<String>): Boolean = paths.any { path ->
        if ('/' in path) path.substringBefore('/').lowercase(java.util.Locale.ROOT) in dataRootFolderNames
        else isLikelyDataRootFile(path) || isLikelyGameRootFile(path)
    }

    private fun hasRootLevelGameRootContent(contentRoot: File): Boolean {
        val children = contentRoot.listFiles() ?: return false

        return children.any { child ->
            child.isFile && isLikelyGameRootFile(child.name)
        }
    }

    private fun isBainFolder(name: String): Boolean {
        val lower = name.lowercase()

        return lower.matches(Regex("""^\d{2,3}\s+.+""")) ||
                lower.startsWith("optional") ||
                lower.startsWith("options") ||
                lower.startsWith("patches") ||
                lower.startsWith("patch")
    }

    private fun isCoreFolder(name: String): Boolean {
        val lower = name.lowercase()

        return lower.contains("core") ||
                lower.contains("main") ||
                lower.contains("required") ||
                lower.startsWith("00")
    }

    private fun isLikelyDataRootFile(fileName: String): Boolean {
        val lower = fileName.lowercase()

        return lower.endsWith(".esp") ||
                lower.endsWith(".esm") ||
                lower.endsWith(".esl") ||
                lower.endsWith(".bsa") ||
                lower.endsWith(".ba2") ||
                lower.endsWith(".ini")
    }

    private fun isLikelyGameRootFile(fileName: String): Boolean {
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
}