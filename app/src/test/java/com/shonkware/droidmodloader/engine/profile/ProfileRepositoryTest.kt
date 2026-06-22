package com.shonkware.droidmodloader.engine.profile

import com.shonkware.droidmodloader.engine.model.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyTreeUrisRequireSafePathReselection() {
        val profilesFile = temporaryFolder.newFile("profiles.json")
        val setupFile = temporaryFolder.newFile("setup.json")
        profilesFile.writeText(
            """
            [
              {
                "profileId": "legacy",
                "profileName": "Legacy",
                "gameId": "fallout_nv",
                "gameDisplayName": "Fallout New Vegas",
                "targetDataPath": "",
                "targetTreeUri": "content://legacy-data",
                "targetRootPath": "",
                "targetRootTreeUri": "content://legacy-root",
                "realDeployEnabled": true
              }
            ]
            """.trimIndent()
        )

        val repository = ProfileRepository(profilesFile, setupFile)
        val profile = repository.loadProfiles().single()

        assertEquals("", profile.targetDataPath)
        assertEquals("", profile.targetRootPath)
        assertTrue(profile.dataPathReselectionRequired)
        assertTrue(profile.rootPathReselectionRequired)
        assertNull(profile.targetTreeUri)
        assertNull(profile.targetRootTreeUri)
    }

    @Test
    fun directPathsAreSavedWithoutLegacyUriKeys() {
        val profilesFile = temporaryFolder.newFile("profiles.json")
        val setupFile = temporaryFolder.newFile("setup.json")
        val repository = ProfileRepository(profilesFile, setupFile)

        repository.saveProfiles(
            listOf(
                GameProfile(
                    profileId = "direct",
                    profileName = "Direct",
                    gameId = "skyrim_le",
                    gameDisplayName = "Skyrim Legendary Edition",
                    targetDataPath = "/storage/emulated/0/Games/Skyrim/Data",
                    realDeployEnabled = true,
                    targetRootPath = "/storage/emulated/0/Games/Skyrim"
                )
            )
        )

        val stored = profilesFile.readText()
        assertFalse(stored.contains("targetTreeUri"))
        assertFalse(stored.contains("targetRootTreeUri"))

        val reloaded = repository.loadProfiles().single()
        assertEquals("/storage/emulated/0/Games/Skyrim/Data", reloaded.targetDataPath)
        assertEquals("/storage/emulated/0/Games/Skyrim", reloaded.targetRootPath)
        assertFalse(reloaded.dataPathReselectionRequired)
        assertFalse(reloaded.rootPathReselectionRequired)
    }
}
