package com.shonkware.droidmodloader.engine.rules

import com.shonkware.droidmodloader.engine.model.DeployScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployFileClassifierTest {

    private val classifier = DeployFileClassifier()

    @Test
    fun classify_treatsNormalDataFilesAsDataDeployable() {
        assertEquals(
            DeployScope.DATA,
            classifier.classify("textures/armor/iron.dds")
        )

        assertEquals(
            DeployScope.DATA,
            classifier.classify("meshes/weapons/sword.nif")
        )
    }

    @Test
    fun classify_treatsRootDllsAndLoadersAsGameRootFiles() {
        assertEquals(
            DeployScope.GAME_ROOT,
            classifier.classify("skse_loader.exe")
        )

        assertEquals(
            DeployScope.GAME_ROOT,
            classifier.classify("d3d9.dll")
        )
    }

    @Test
    fun classify_treatsPluginOutputFilesAsProfileOnly() {
        assertEquals(
            DeployScope.PROFILE_ONLY,
            classifier.classify("plugins.txt")
        )

        assertEquals(
            DeployScope.PROFILE_ONLY,
            classifier.classify("loadorder.txt")
        )
    }

    @Test
    fun classify_treatsInstallerMetadataAsManagerOnly() {
        assertEquals(
            DeployScope.MANAGER_ONLY,
            classifier.classify("fomod/moduleconfig.xml")
        )

        assertEquals(
            DeployScope.MANAGER_ONLY,
            classifier.classify("docs/readme.txt")
        )
    }

    @Test
    fun classify_ignoresUnsafePaths() {
        assertEquals(
            DeployScope.IGNORE,
            classifier.classify("../Data/textures/bad.dds")
        )

        assertEquals(
            DeployScope.IGNORE,
            classifier.classify("C:/Windows/System32/bad.dll")
        )
    }

    @Test
    fun deployableHelpers_matchExpectedScopes() {
        assertTrue(classifier.isDeployable(DeployScope.DATA))
        assertTrue(classifier.isDeployable(DeployScope.GAME_ROOT))

        assertFalse(classifier.isDeployable(DeployScope.PROFILE_ONLY))
        assertFalse(classifier.isDeployable(DeployScope.MANAGER_ONLY))
        assertFalse(classifier.isDeployable(DeployScope.IGNORE))

        assertTrue(classifier.isDeployableToCurrentStaging(DeployScope.DATA))
        assertFalse(classifier.isDeployableToCurrentStaging(DeployScope.GAME_ROOT))
    }
}