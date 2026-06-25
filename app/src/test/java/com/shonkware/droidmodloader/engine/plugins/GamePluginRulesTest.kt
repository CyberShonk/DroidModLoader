package com.shonkware.droidmodloader.engine.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePluginRulesTest {
    private val rules = GamePluginRules()

    @Test
    fun `ttw official plugins have the expected fixed order`() {
        val expectedRanks = linkedMapOf(
            "FalloutNV.esm" to 1,
            "DeadMoney.esm" to 2,
            "HonestHearts.esm" to 3,
            "OldWorldBlues.esm" to 4,
            "LonesomeRoad.esm" to 5,
            "GunRunnersArsenal.esm" to 6,
            "Fallout3.esm" to 7,
            "Anchorage.esm" to 8,
            "ThePitt.esm" to 9,
            "BrokenSteel.esm" to 10,
            "PointLookout.esm" to 11,
            "Zeta.esm" to 12,
            "CaravanPack.esm" to 13,
            "ClassicPack.esm" to 14,
            "MercenaryPack.esm" to 15,
            "TribalPack.esm" to 16,
            "TaleOfTwoWastelands.esm" to 17,
            "YUPTTW.esm" to 18
        )

        expectedRanks.forEach { (pluginName, expectedRank) ->
            val rule = requireNotNull(
                rules.findRule("ttw", pluginName)
            )

            assertEquals(pluginName, rule.pluginName)
            assertEquals(expectedRank, rule.orderRank)
            assertTrue("$pluginName should be locked", rule.locked)
            assertTrue(
                "$pluginName should be enabled by default",
                rule.defaultEnabled
            )
        }
    }

    @Test
    fun `ttw core plugins use the ttw core source type`() {
        listOf(
            "TaleOfTwoWastelands.esm",
            "YUPTTW.esm"
        ).forEach { pluginName ->
            val rule = requireNotNull(
                rules.findRule("ttw", pluginName)
            )

            assertEquals("ttw_core", rule.sourceType)
            assertTrue(rule.locked)
            assertTrue(rule.defaultEnabled)
        }
    }

    @Test
    fun `ttw courier stash plugins are locked unlike fallout new vegas`() {
        listOf(
            "CaravanPack.esm",
            "ClassicPack.esm",
            "MercenaryPack.esm",
            "TribalPack.esm"
        ).forEach { pluginName ->
            val ttwRule = requireNotNull(
                rules.findRule("ttw", pluginName)
            )
            val falloutNvRule = requireNotNull(
                rules.findRule("fallout_nv", pluginName)
            )

            assertTrue(
                "$pluginName should be locked for TTW",
                ttwRule.locked
            )
            assertFalse(
                "$pluginName should remain movable for Fallout New Vegas",
                falloutNvRule.locked
            )
        }
    }

    @Test
    fun `official plugin lookup is case insensitive`() {
        val rule = requireNotNull(
            rules.findRule("ttw", "yupttw.ESM")
        )

        assertEquals("YUPTTW.esm", rule.pluginName)
        assertEquals(18, rule.orderRank)
    }

    @Test
    fun `unknown ttw plugins do not receive official rules`() {
        assertNull(
            rules.findRule("ttw", "ExampleMod.esp")
        )
    }
}