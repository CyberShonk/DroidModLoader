package com.shonkware.droidmodloader.ui.workflow

internal object GameCatalog {
    const val DEFAULT_GAME_ID = "skyrim_le"

    val supportedGameIds: List<String> = listOf(
        "skyrim_le",
        "oblivion",
        "fallout_3",
        "fallout_nv",
        "ttw"
    )

    fun displayName(gameId: String): String {
        return when (gameId) {
            "skyrim_le" -> "Skyrim Legendary Edition"
            "oblivion" -> "Oblivion"
            "fallout_3" -> "Fallout 3"
            "fallout_nv" -> "Fallout New Vegas"
            "ttw" -> "Tale Of Two Wastelands"
            "fallout_4" -> "Fallout 4"
            else -> gameId
        }
    }

    fun supportedOrDefault(gameId: String): String {
        return gameId.takeIf { it in supportedGameIds } ?: DEFAULT_GAME_ID
    }
}
