package com.shonkware.droidmodloader.ui.workflow

import com.shonkware.droidmodloader.engine.model.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileConfigUiMapperTest {

    @Test
    fun activeProfileNameUsesPersistedProfileName() {
        val profile = GameProfile(
            profileId = "fallout_nv_test",
            profileName = "Fallout NV Test",
            gameId = "fallout_nv",
            gameDisplayName = "Fallout New Vegas",
            targetDataPath = "/storage/emulated/0/Games/FalloutNV/Data",
            realDeployEnabled = true,
            targetRootPath = "/storage/emulated/0/Games/FalloutNV"
        )

        assertEquals(
            "Fallout NV Test",
            ProfileConfigUiMapper.activeProfileName(profile)
        )
    }

    @Test
    fun activeProfileNameFallsBackWhenNoProfileIsResolved() {
        assertEquals(
            "No profile",
            ProfileConfigUiMapper.activeProfileName(null)
        )
    }
}
