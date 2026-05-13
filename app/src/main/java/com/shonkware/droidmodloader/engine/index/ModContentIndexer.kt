package com.shonkware.droidmodloader.engine.index

import com.shonkware.droidmodloader.engine.model.Mod
import com.shonkware.droidmodloader.engine.util.PathUtils
import java.io.File

class ModContentIndexer {

    fun indexMod(mod: Mod): ModContentIndex {
        val root = File(mod.installPath)

        if (!root.exists() || !root.isDirectory) {
            return ModContentIndex(
                modId = mod.id,
                modName = mod.name,
                entries = emptyList()
            )
        }

        val entries = root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val relative = file.relativeTo(root).path.replace("\\", "/")
                val normalized = PathUtils.normalize(relative) ?: return@mapNotNull null
                classify(relative, normalized)
            }
            .toList()

        return ModContentIndex(
            modId = mod.id,
            modName = mod.name,
            entries = entries
        )
    }

    private fun classify(originalPath: String, normalizedPath: String): ModContentEntry {
        val lower = normalizedPath.lowercase()
        val pathParts = lower.split("/").filter { it.isNotBlank() }
        val fileName = pathParts.lastOrNull().orEmpty()
        val parent = pathParts.dropLast(1).joinToString("/")
        val isRootFile = pathParts.size == 1
        val optionalCandidate = isOptionalCandidate(originalPath, lower)

        fun entry(
            category: ModContentCategory,
            reason: String,
            deployable: Boolean,
            optional: Boolean = optionalCandidate
        ): ModContentEntry {
            return ModContentEntry(
                originalPath = originalPath,
                normalizedPath = normalizedPath,
                category = category,
                reason = reason,
                isDeployable = deployable,
                isOptionalCandidate = optional
            )
        }

        if (isIgnored(lower)) {
            return entry(ModContentCategory.IGNORED, "Ignored metadata/system file", false)
        }

        if (isDocumentation(lower, fileName)) {
            return entry(ModContentCategory.DOCUMENTATION, "Documentation/readme file", false)
        }

        if (isSetupOnly(lower)) {
            return entry(ModContentCategory.SETUP_ONLY, "Installer/setup-only file", false)
        }

        if (optionalCandidate && !isKnownGameFile(lower, isRootFile)) {
            return entry(ModContentCategory.OPTIONAL_MODULE, "Optional package/module content", false, true)
        }

        if (isPlugin(fileName)) {
            return entry(ModContentCategory.PLUGIN, "Bethesda plugin file", true, optionalCandidate)
        }

        if (isBethesdaArchive(fileName)) {
            return entry(ModContentCategory.ARCHIVE, "Bethesda archive file", true, optionalCandidate)
        }

        if (isConfigFile(fileName, isRootFile, parent)) {
            return entry(ModContentCategory.CONFIG, "Game or plugin configuration file", true, optionalCandidate)
        }

        if (isScriptExtenderFile(lower)) {
            return entry(ModContentCategory.SCRIPT_EXTENDER, "Script extender file", true, optionalCandidate)
        }

        if (isKnownDataFolder(lower)) {
            return entry(ModContentCategory.GAME_FILE, "Recognized game Data folder file", true, optionalCandidate)
        }

        if (isRootGameFile(fileName, isRootFile)) {
            return entry(ModContentCategory.ROOT_GAME_FILE, "Recognized root-level Data file", true, optionalCandidate)
        }

        if (optionalCandidate) {
            return entry(ModContentCategory.OPTIONAL_MODULE, "Optional package/module content", false, true)
        }

        return entry(ModContentCategory.UNKNOWN, "Unknown file type/location", false)
    }

    private fun isPlugin(fileName: String): Boolean {
        return fileName.endsWith(".esm") ||
                fileName.endsWith(".esp") ||
                fileName.endsWith(".esl") ||
                fileName.endsWith(".esu")
    }

    private fun isBethesdaArchive(fileName: String): Boolean {
        return fileName.endsWith(".bsa") ||
                fileName.endsWith(".ba2")
    }

    private fun isConfigFile(fileName: String, isRootFile: Boolean, parent: String): Boolean {
        if (!fileName.endsWith(".ini")) return false

        // Root-level plugin INIs like USLEEP's INI are valid Data files.
        if (isRootFile) return true

        // Script extender config INIs are valid.
        if (parent.startsWith("skse") ||
            parent.startsWith("skse/plugins") ||
            parent.startsWith("nvse") ||
            parent.startsWith("nvse/plugins") ||
            parent.startsWith("obse") ||
            parent.startsWith("obse/plugins") ||
            parent.startsWith("f4se") ||
            parent.startsWith("f4se/plugins")
        ) {
            return true
        }

        // Some mods place config under interface or menus.
        if (parent.startsWith("interface") || parent.startsWith("menus")) return true

        return false
    }

    private fun isScriptExtenderFile(path: String): Boolean {
        if (path.startsWith("skse/")) return true
        if (path.startsWith("nvse/")) return true
        if (path.startsWith("obse/")) return true
        if (path.startsWith("f4se/")) return true

        return false
    }

    private fun isKnownDataFolder(path: String): Boolean {
        val roots = listOf(
            "meshes/",
            "textures/",
            "scripts/",
            "source/scripts/",
            "interface/",
            "menus/",
            "sound/",
            "music/",
            "seq/",
            "strings/",
            "video/",
            "lodsettings/",
            "grass/",
            "shadersfx/",
            "materials/",
            "vis/",
            "terrain/",
            "trees/",
            "facegen/",
            "actors/",
            "animations/",
            "skyproc patchers/",
            "calientetools/",
            "bodyslide/",
            "tools/",
            "skse/",
            "nvse/",
            "obse/",
            "f4se/"
        )

        return roots.any { path.startsWith(it) }
    }

    private fun isRootGameFile(fileName: String, isRootFile: Boolean): Boolean {
        if (!isRootFile) return false

        return fileName.endsWith(".bsa") ||
                fileName.endsWith(".ba2") ||
                fileName.endsWith(".esp") ||
                fileName.endsWith(".esm") ||
                fileName.endsWith(".esl") ||
                fileName.endsWith(".ini") ||
                fileName.endsWith(".pex") ||
                fileName.endsWith(".psc") ||
                fileName.endsWith(".swf") ||
                fileName.endsWith(".dll") ||
                fileName.endsWith(".exe")
    }

    private fun isKnownGameFile(path: String, isRootFile: Boolean): Boolean {
        val fileName = path.substringAfterLast("/")
        return isPlugin(fileName) ||
                isBethesdaArchive(fileName) ||
                isKnownDataFolder(path) ||
                isRootGameFile(fileName, isRootFile)
    }

    private fun isSetupOnly(path: String): Boolean {
        return path.startsWith("fomod/") ||
                path.startsWith("omod conversion data/") ||
                path.startsWith("omod/") ||
                path.startsWith("wizard images/") ||
                path.endsWith("moduleconfig.xml") ||
                path.endsWith("info.xml") ||
                path.endsWith("script.cs") ||
                path.endsWith("script.txt")
    }

    private fun isDocumentation(path: String, fileName: String): Boolean {
        if (path.startsWith("docs/") ||
            path.startsWith("doc/") ||
            path.startsWith("documentation/") ||
            path.startsWith("readme/")
        ) {
            return true
        }

        if (fileName.contains("readme")) return true
        if (fileName.contains("changelog")) return true
        if (fileName.contains("changes")) return true
        if (fileName.contains("license")) return true
        if (fileName.contains("credits")) return true

        return fileName.endsWith(".txt") ||
                fileName.endsWith(".md") ||
                fileName.endsWith(".rtf") ||
                fileName.endsWith(".pdf")
    }

    private fun isIgnored(path: String): Boolean {
        val fileName = path.substringAfterLast("/")

        return path.startsWith("__macosx/") ||
                path.startsWith(".git/") ||
                path.startsWith(".github/") ||
                path.startsWith(".svn/") ||
                fileName == ".ds_store" ||
                fileName == "thumbs.db" ||
                fileName.endsWith(".url") ||
                fileName.endsWith(".lnk") ||
                fileName.endsWith(".bak") ||
                fileName.endsWith(".tmp")
    }

    private fun isOptionalCandidate(originalPath: String, normalizedPath: String): Boolean {
        val originalLower = originalPath.lowercase()
        val lower = normalizedPath.lowercase()
        val parts = originalLower.split("/", "\\").filter { it.isNotBlank() }

        if (parts.any { it == "optional" || it == "optionals" || it == "options" || it == "patches" || it == "patch" }) {
            return true
        }

        if (parts.any { it.contains("optional") || it.contains("patch") || it.contains("compatibility") }) {
            return true
        }

        // BAIN-style numbered folders: "00 Core", "01 Optional", "10 Open Cities Patch".
        val first = parts.firstOrNull().orEmpty()
        if (first.matches(Regex("""^\d{2,3}\s+.+"""))) {
            val name = first.substringAfter(" ").lowercase()
            if (!name.contains("core") && !name.contains("main") && !name.contains("required")) {
                return true
            }
        }

        // Common installer folders from manual packages.
        if (lower.startsWith("optional/") ||
            lower.startsWith("options/") ||
            lower.startsWith("patches/") ||
            lower.contains("/optional/") ||
            lower.contains("/patches/")
        ) {
            return true
        }

        return false
    }
}