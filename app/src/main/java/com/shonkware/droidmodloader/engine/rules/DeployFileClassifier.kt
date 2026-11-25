package com.shonkware.droidmodloader.engine.rules

import com.shonkware.droidmodloader.engine.model.DeployScope
import java.util.Locale

class DeployFileClassifier {

    fun classify(normalizedPath: String): DeployScope {
        val path = normalizedPath
            .replace("\\", "/")
            .lowercase()
            .trim('/')

        val fileName = path.substringAfterLast('/')

        return when {
            path.isBlank() ->
                DeployScope.IGNORE

            isUnsafePath(path) ->
                DeployScope.IGNORE

            path == "plugins.txt" || path == "loadorder.txt" ->
                DeployScope.PROFILE_ONLY

            isIgnored(path) ->
                DeployScope.IGNORE

            isSetupOnly(path) ->
                DeployScope.MANAGER_ONLY

            isSourceOrDevFile(path, fileName) ->
                DeployScope.MANAGER_ONLY

            isProbableGameRootFile(path) ->
                DeployScope.GAME_ROOT

            else ->
                DeployScope.DATA
        }
    }

    /** Reserved source namespaces for the staged projection only; legacy classification
     * is unchanged. Ordinary mod configuration JSON/text is not a manager namespace. */
    internal fun isReservedManagerSourcePath(normalizedPath: String): Boolean {
        val path = normalizedPath.lowercase(Locale.ROOT)
        val parts = path.split('/')
        return parts.firstOrNull() == "dml export" || parts.any {
            it.startsWith(".dml_") || it.startsWith("_dml_") || it.startsWith("_installing_")
        } || path in setOf("plugins.txt", "loadorder.txt")
    }

    internal fun isReservedOverwritePath(normalizedPath: String): Boolean =
        isReservedManagerSourcePath(normalizedPath) || normalizedPath.lowercase(Locale.ROOT) == "dml - invalidation.bsa"

    fun isDataDeployable(scope: DeployScope): Boolean {
        return scope == DeployScope.DATA
    }

    fun isGameRootDeployable(scope: DeployScope): Boolean {
        return scope == DeployScope.GAME_ROOT
    }

    fun isDeployable(scope: DeployScope): Boolean {
        return scope == DeployScope.DATA || scope == DeployScope.GAME_ROOT
    }

    fun isDeployableToCurrentStaging(scope: DeployScope): Boolean {
        return isDataDeployable(scope)
    }

    private fun isUnsafePath(path: String): Boolean {
        return path.startsWith("/") ||
                path.startsWith("\\") ||
                path.contains("\\") ||
                path.contains(":") ||
                path.split("/").any { it == ".." }
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

    private fun isSetupOnly(path: String): Boolean {
        return path.startsWith("fomod/") ||
                path.startsWith("omod conversion data/") ||
                path.startsWith("omod/") ||
                path.startsWith("wizard images/")
    }

    private fun isSourceOrDevFile(
        path: String,
        fileName: String
    ): Boolean {
        if (path.startsWith("source/scripts/")) {
            return false
        }

        if (
            path.startsWith("src/") ||
            path.startsWith("source/") ||
            path.startsWith("sources/")
        ) {
            return true
        }

        return fileName.endsWith(".cpp") ||
                fileName.endsWith(".c") ||
                fileName.endsWith(".h") ||
                fileName.endsWith(".hpp") ||
                fileName.endsWith(".sln") ||
                fileName.endsWith(".vcxproj") ||
                fileName.endsWith(".vcproj") ||
                fileName.endsWith(".filters") ||
                fileName.endsWith(".pdb") ||
                fileName.endsWith(".lib") ||
                fileName.endsWith(".exp")
    }

    private fun isProbableGameRootFile(path: String): Boolean {
        if (path.contains("/")) return false

        return path.endsWith(".dll") ||
                path.endsWith(".exe") ||
                path.endsWith(".asi") ||
                path == "d3d9.dll" ||
                path == "d3d11.dll" ||
                path == "dxgi.dll" ||
                path == "dinput8.dll" ||
                path == "xinput1_3.dll" ||
                path == "enblocal.ini" ||
                path == "enbseries.ini" ||
                path.startsWith("skse_loader") ||
                path.startsWith("nvse_loader") ||
                path.startsWith("obse_loader") ||
                path.startsWith("fose_loader") ||
                path.startsWith("f4se_loader")
    }
}