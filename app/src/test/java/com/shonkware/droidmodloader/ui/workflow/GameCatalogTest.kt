package com.shonkware.droidmodloader.ui.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCatalogTest {

    @Test
    fun `ttw is listed exactly once as a supported game`() {
        assertTrue("ttw" in GameCatalog.supportedGameIds)
        assertEquals(
            1,
            GameCatalog.supportedGameIds.count { it == "ttw" }
        )
    }

    @Test
    fun `ttw uses the expected display name`() {
        assertEquals(
            "Tale of Two Wastelands",
            GameCatalog.displayName("ttw")
        )
    }

    @Test
    fun `ttw remains selected by supported game validation`() {
        assertEquals(
            "ttw",
            GameCatalog.supportedOrDefault("ttw")
        )
    }

    @Test
    fun `unknown game ids still fall back to the default game`() {
        assertEquals(
            GameCatalog.DEFAULT_GAME_ID,
            GameCatalog.supportedOrDefault("unknown_game")
        )
    }
}