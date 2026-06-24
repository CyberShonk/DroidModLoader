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
            "oblivion" -> oblivionRules()
            "fallout_3" -> fallout3Rules()
            "fallout_nv" -> falloutNvRules()
            "ttw" -> ttwRules()
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
            OfficialPluginRule("Dragonborn.esm", "official_dlc", 5, locked = true, defaultEnabled = true),

            // Optional official High Resolution Texture Pack plugins.
            OfficialPluginRule("HighResTexturePack01.esp", "official_dlc", 6, locked = false, defaultEnabled = true),
            OfficialPluginRule("HighResTexturePack02.esp", "official_dlc", 7, locked = false, defaultEnabled = true),
            OfficialPluginRule("HighResTexturePack03.esp", "official_dlc", 8, locked = false, defaultEnabled = true)
        )
    }

    private fun oblivionRules(): List<OfficialPluginRule> {
        return listOf(
            OfficialPluginRule("Oblivion.esm", "base_game", 1, locked = true, defaultEnabled = true),

            // Shivering Isles / official DLC plug-ins.
            OfficialPluginRule("DLCShiveringIsles.esp", "official_dlc", 2, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCBattlehornCastle.esp", "official_dlc", 3, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCFrostcrag.esp", "official_dlc", 4, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCHorseArmor.esp", "official_dlc", 5, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCMehrunesRazor.esp", "official_dlc", 6, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCOrrery.esp", "official_dlc", 7, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCSpellTomes.esp", "official_dlc", 8, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCThievesDen.esp", "official_dlc", 9, locked = true, defaultEnabled = true),
            OfficialPluginRule("DLCVileLair.esp", "official_dlc", 10, locked = true, defaultEnabled = true),
            OfficialPluginRule("Knights.esp", "official_dlc", 11, locked = true, defaultEnabled = true)
        )
    }

    private fun fallout3Rules(): List<OfficialPluginRule> {
        return listOf(
            OfficialPluginRule("Fallout3.esm", "base_game", 1, locked = true, defaultEnabled = true),
            OfficialPluginRule("Anchorage.esm", "official_dlc", 2, locked = true, defaultEnabled = true),
            OfficialPluginRule("ThePitt.esm", "official_dlc", 3, locked = true, defaultEnabled = true),
            OfficialPluginRule("BrokenSteel.esm", "official_dlc", 4, locked = true, defaultEnabled = true),
            OfficialPluginRule("PointLookout.esm", "official_dlc", 5, locked = true, defaultEnabled = true),
            OfficialPluginRule("Zeta.esm", "official_dlc", 6, locked = true, defaultEnabled = true)
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

            // Courier's Stash packs. They are official, but leave them movable/disableable.
            OfficialPluginRule("ClassicPack.esm", "official_dlc", 7, locked = false, defaultEnabled = true),
            OfficialPluginRule("MercenaryPack.esm", "official_dlc", 8, locked = false, defaultEnabled = true),
            OfficialPluginRule("TribalPack.esm", "official_dlc", 9, locked = false, defaultEnabled = true),
            OfficialPluginRule("CaravanPack.esm", "official_dlc", 10, locked = false, defaultEnabled = true)
        )
    }

    private fun ttwRules(): List<OfficialPluginRule> {
        return listOf(
            // Fallout: New Vegas and its required DLC masters.
            OfficialPluginRule("FalloutNV.esm", "base_game", 1, locked = true, defaultEnabled = true),
            OfficialPluginRule("DeadMoney.esm", "official_dlc", 2, locked = true, defaultEnabled = true),
            OfficialPluginRule("HonestHearts.esm", "official_dlc", 3, locked = true, defaultEnabled = true),
            OfficialPluginRule("OldWorldBlues.esm", "official_dlc", 4, locked = true, defaultEnabled = true),
            OfficialPluginRule("LonesomeRoad.esm", "official_dlc", 5, locked = true, defaultEnabled = true),
            OfficialPluginRule("GunRunnersArsenal.esm", "official_dlc", 6, locked = true, defaultEnabled = true),

            // Fallout 3 and its required DLC masters.
            OfficialPluginRule("Fallout3.esm", "base_game", 7, locked = true, defaultEnabled = true),
            OfficialPluginRule("Anchorage.esm", "official_dlc", 8, locked = true, defaultEnabled = true),
            OfficialPluginRule("ThePitt.esm", "official_dlc", 9, locked = true, defaultEnabled = true),
            OfficialPluginRule("BrokenSteel.esm", "official_dlc", 10, locked = true, defaultEnabled = true),
            OfficialPluginRule("PointLookout.esm", "official_dlc", 11, locked = true, defaultEnabled = true),
            OfficialPluginRule("Zeta.esm", "official_dlc", 12, locked = true, defaultEnabled = true),

            // TTW requires the complete Courier's Stash master set.
            OfficialPluginRule("CaravanPack.esm", "official_dlc", 13, locked = true, defaultEnabled = true),
            OfficialPluginRule("ClassicPack.esm", "official_dlc", 14, locked = true, defaultEnabled = true),
            OfficialPluginRule("MercenaryPack.esm", "official_dlc", 15, locked = true, defaultEnabled = true),
            OfficialPluginRule("TribalPack.esm", "official_dlc", 16, locked = true, defaultEnabled = true),

            // Core TTW plugins.
            OfficialPluginRule("TaleOfTwoWastelands.esm", "ttw_core", 17, locked = true, defaultEnabled = true),
            OfficialPluginRule("YUPTTW.esm", "ttw_core", 18, locked = true, defaultEnabled = true)
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