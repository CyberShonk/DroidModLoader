package com.shonkware.droidmodloader.engine.factory

import android.content.Context
import com.shonkware.droidmodloader.engine.ModEngine
import com.shonkware.droidmodloader.engine.io.ProfileStoragePaths
import java.io.File

internal class ProfileScopedEngineFactory(
    private val appContext: Context,
    private val externalFilesDirProvider: () -> File?,
    private val profileStoragePaths: ProfileStoragePaths,
    private val selectedGameIdProvider: () -> String,
    private val appendError: (String) -> Unit
) {
    fun create(): ModEngine? {
        val externalBaseDir = externalFilesDirProvider()
        if (externalBaseDir == null) {
            appendError("External files directory is null")
            return null
        }

        val profileInternalDir = profileStoragePaths.getProfileInternalDir()
        val profileStateDir = profileStoragePaths.getProfileStateDir(externalBaseDir)
        val selectedGameId = selectedGameIdProvider()

        val tempDir = File(profileInternalDir, "temp")
        val modsDir = File(profileInternalDir, "mods")
        val stateFile = File(profileStateDir, "installed_mods.json")
        val pluginListFile = File(profileStateDir, "plugins.json")
        val pluginsTxtFile = File(profileStateDir, "plugins.txt")
        val loadorderTxtFile = File(profileStateDir, "loadorder.txt")
        val deploymentManifestFile = File(profileStateDir, "deployment_manifest.json")
        val gameConfigFile = File(profileStateDir, "game_deployment_configs.json")
        val archiveLibraryDir = File(externalBaseDir, "downloads/archive_library")
        val downloadedArchiveListFile = File(profileStateDir, "downloaded_archives.json")
        val deployDir = File(
            externalBaseDir,
            "deploy_target/profiles/${profileStoragePaths.getActiveProfileStorageKey()}/$selectedGameId/Data"
        )

        tempDir.mkdirs()
        modsDir.mkdirs()
        profileStateDir.mkdirs()
        archiveLibraryDir.mkdirs()
        deployDir.mkdirs()

        return ModEngine(
            appContext = appContext,
            tempDir = tempDir,
            modsDir = modsDir,
            stateFile = stateFile,
            deploymentManifestFile = deploymentManifestFile,
            deployRootDir = deployDir,
            gameConfigFile = gameConfigFile,
            pluginListFile = pluginListFile,
            pluginsTxtFile = pluginsTxtFile,
            loadorderTxtFile = loadorderTxtFile,
            archiveLibraryDir = archiveLibraryDir,
            downloadedArchiveListFile = downloadedArchiveListFile
        )
    }
}
