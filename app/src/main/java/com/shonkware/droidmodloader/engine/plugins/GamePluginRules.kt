package com.shonkware.droidmodloader.engine.plugins

data class OfficialPluginRule(
    val pluginName: String,
    val sourceType: String,
    val orderRank: Int,
    val locked: Boolean,
    val defaultEnabled: Boolean
)

class GamePluginRules {

    fun findRule(gameId: String, pluginName: String): OfficialPluginRule? {
        val lower = pluginName.lowercase()
        return getOfficialRules(gameId).firstOrNull {
            it.pluginName.lowercase() == lower
        }
    }

    private fun getOfficialRules(gameId: String): List<OfficialPluginRule> {
        return when (gameId) {
            "skyrim_le" -> skyrimLeRules()
            "fallout_nv" -> falloutNvRules()
            "oblivion" -> oblivionRules()
            "fallout_4" -> fallout4Rules()
            else -> emptyList()
        }
    }

    private fun skyrimLeRules(): List<OfficialPluginRule> {
        return listOf(
            OfficialPluginRule("Skyrim.esm", "base_game", 1, locked = true, defaultEnabled = true),
            OfficialPluginRule("Update.esm", "base_game", 2, locked = true, defaultEnabled = true),
            OfficialPluginRule("Dawnguard.esm", "official_dlc", 3, locked = true, defaultEnabled = true),
            OfficialPluginRule("HearthFires.esm", "official_dlc", 4, locked = true, defaultEnabled = true),
            OfficialPluginRule("Dragonborn.esm", "official_dlc", 5, locked = true, defaultEnabled = true)
        )
    }

    private fun falloutNvRules(): List<OfficialPluginRule> {
        return listOf(
            OfficialPluginRule("FalloutNV.esm", "base_game", 1, locked = true, defaultEnabled = true),
            OfficialPluginRule("DeadMoney.esm", "official_dlc", 2, locked = true, defaultEnabled = true),
            OfficialPluginRule("HonestHearts.esm", "official_dlc", 3, locked = true, defaultEnabled = true),
            OfficialPluginRule("OldWorldBlues.esm", "official_dlc", 4, locked = true, defaultEnabled = true),
            OfficialPluginRule("LonesomeRoad.esm", "official_dlc", 5, locked = true, defaultEnabled = true),
            OfficialPluginRule("GunRunnersArsenal.esm", "official_dlc", 6, locked = true, defaultEnabled = true),
            OfficialPluginRule("ClassicPack.esm", "official_dlc", 7, locked = false, defaultEnabled = true),
            OfficialPluginRule("MercenaryPack.esm", "official_dlc", 8, locked = false, defaultEnabled = true),
            OfficialPluginRule("TribalPack.esm", "official_dlc", 9, locked = false, defaultEnabled = true),
            OfficialPluginRule("CaravanPack.esm", "official_dlc", 10, locked = false, defaultEnabled = true)
        )
    }

    private fun oblivionRules(): List<OfficialPluginRule> {
        return listOf(
            OfficialPluginRule("Oblivion.esm", "base_game", 1, locked = true, defaultEnabled = true)
        )
    }

    private fun fallout4Rules(): List<OfficialPluginRule> {
        return listOf(
            OfficialPluginRule("Fallout4.esm", "base_game", 1, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCRobot.esm", "official_dlc", 2, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCworkshop01.esm", "official_dlc", 3, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCCoast.esm", "official_dlc", 4, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCworkshop02.esm", "official_dlc", 5, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCworkshop03.esm", "official_dlc", 6, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCNukaWorld.esm", "official_dlc", 7, locked = true, defaultEnabled = true)
        )
    }
}