package com.shonkware.droidmodloader.engine.plugins

import com.shonkware.droidmodloader.engine.model.PluginEntry

data class PluginOutputContent(
    val pluginsTxt: String,
    val loadorderTxt: String?
)

class PluginOutputBuilder {
    fun build(
        plugins: List<PluginEntry>,
        rule: GamePluginLoadOrderRule
    ): PluginOutputContent {
        val orderedPlugins = plugins.sortedBy { it.priority }

        return PluginOutputContent(
            pluginsTxt = buildListContent(
                plugins = orderedPlugins,
                membership = rule.pluginsTxtMembership
            ),
            loadorderTxt = rule.loadorderTxtMembership?.let { membership ->
                buildListContent(
                    plugins = orderedPlugins,
                    membership = membership
                )
            }
        )
    }

    private fun buildListContent(
        plugins: List<PluginEntry>,
        membership: PluginListMembership
    ): String {
        return plugins
            .asSequence()
            .filter { plugin ->
                membership == PluginListMembership.COMPLETE_ORDER || plugin.enabled
            }
            .joinToString(separator = "\n") { it.pluginName }
    }
}
