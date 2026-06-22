package com.shonkware.droidmodloader.engine.profile

import com.shonkware.droidmodloader.engine.model.AppSetupState
import com.shonkware.droidmodloader.engine.model.GameProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProfileRepository(
    private val profilesFile: File,
    private val setupStateFile: File
) {
    fun loadProfiles(): List<GameProfile> {
        if (!profilesFile.exists()) return emptyList()

        val array = JSONArray(profilesFile.readText())
        val results = mutableListOf<GameProfile>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val targetDataPath = obj.optString("targetDataPath", "").trim()
            val targetRootPath = obj.optString("targetRootPath", "").trim()
            val legacyDataUri = obj.optString("targetTreeUri", "").trim()
            val legacyRootUri = obj.optString("targetRootTreeUri", "").trim()

            results.add(
                GameProfile(
                    profileId = obj.getString("profileId"),
                    profileName = obj.getString("profileName"),
                    gameId = obj.getString("gameId"),
                    gameDisplayName = obj.getString("gameDisplayName"),
                    targetDataPath = targetDataPath,
                    realDeployEnabled = obj.optBoolean("realDeployEnabled", false),
                    targetRootPath = targetRootPath,
                    dataPathReselectionRequired = obj.optBoolean(
                        "dataPathReselectionRequired",
                        targetDataPath.isBlank() && legacyDataUri.isNotBlank()
                    ),
                    rootPathReselectionRequired = obj.optBoolean(
                        "rootPathReselectionRequired",
                        targetRootPath.isBlank() && legacyRootUri.isNotBlank()
                    ),
                    iniPresetId = obj.optString("iniPresetId").ifBlank { null }
                )
            )
        }

        return results
    }

    fun saveProfiles(profiles: List<GameProfile>) {
        val array = JSONArray()

        for (profile in profiles) {
            val obj = JSONObject()
            obj.put("profileId", profile.profileId)
            obj.put("profileName", profile.profileName)
            obj.put("gameId", profile.gameId)
            obj.put("gameDisplayName", profile.gameDisplayName)
            obj.put("targetDataPath", profile.targetDataPath)
            obj.put("targetRootPath", profile.targetRootPath)
            obj.put("realDeployEnabled", profile.realDeployEnabled)
            obj.put("dataPathReselectionRequired", profile.dataPathReselectionRequired)
            obj.put("rootPathReselectionRequired", profile.rootPathReselectionRequired)
            obj.put("iniPresetId", profile.iniPresetId)
            array.put(obj)
        }

        profilesFile.parentFile?.mkdirs()
        profilesFile.writeText(array.toString(2))
    }

    fun loadSetupState(): AppSetupState {
        if (!setupStateFile.exists()) {
            return AppSetupState(setupComplete = false, activeProfileId = null)
        }

        val obj = JSONObject(setupStateFile.readText())
        return AppSetupState(
            setupComplete = obj.optBoolean("setupComplete", false),
            activeProfileId = obj.optString("activeProfileId").ifBlank { null }
        )
    }

    fun saveSetupState(state: AppSetupState) {
        val obj = JSONObject()
        obj.put("setupComplete", state.setupComplete)
        obj.put("activeProfileId", state.activeProfileId)

        setupStateFile.parentFile?.mkdirs()
        setupStateFile.writeText(obj.toString(2))
    }
}
