package com.shonkware.droidmodloader.engine.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamePluginLoadOrderRulesTest {
    private val rules = GamePluginLoadOrderRules()

    @Test
    fun selectableGamesDeclareExpectedOrderingMechanisms() {
        assertEquals(
            PluginLoadOrderMechanism.TEXT_FILES,
            rules.require("skyrim_le").mechanism
        )

        listOf(
            "oblivion",
            "fallout_3",
            "fallout_nv",
            "ttw"
        ).forEach { gameId ->
            assertEquals(
                PluginLoadOrderMechanism.FILE_TIMESTAMPS,
                rules.require(gameId).mechanism
            )
        }
    }

    @Test
    fun skyrimWritesCompleteLoadorderFile() {
        val rule = rules.require("skyrim_le")

        assertEquals(
            PluginListMembership.ENABLED_ONLY,
            rule.pluginsTxtMembership
        )
        assertEquals(
            PluginListMembership.COMPLETE_ORDER,
            rule.loadorderTxtMembership
        )
    }

    @Test
    fun timestampGamesDoNotDeclareLoadorderFile() {
        listOf(
            "oblivion",
            "fallout_3",
            "fallout_nv",
            "ttw"
        ).forEach { gameId ->
            val rule = rules.require(gameId)

            assertEquals(
                PluginListMembership.ENABLED_ONLY,
                rule.pluginsTxtMembership
            )
            assertNull(rule.loadorderTxtMembership)
        }
    }

    @Test
    fun fallout4IsNotCurrentOutputRule() {
        assertNull(rules.find("fallout_4"))
    }
}