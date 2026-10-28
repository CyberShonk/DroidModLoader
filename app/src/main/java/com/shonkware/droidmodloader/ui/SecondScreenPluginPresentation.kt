package com.shonkware.droidmodloader.ui

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shonkware.droidmodloader.engine.model.PluginEntry

class SecondScreenPluginPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    private var plugins: List<PluginEntry> = emptyList()
    private var activeProfileName: String = "No profile"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    fun update(
        plugins: List<PluginEntry>,
        activeProfileName: String
    ) {
        this.plugins = plugins.sortedBy { it.priority }
        this.activeProfileName = activeProfileName

        if (isShowing) {
            render()
        }
    }

    private fun render() {
        val scrollView = ScrollView(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        container.addView(
            TextView(context).apply {
                text = "Plugins"
                textSize = 22f
                gravity = Gravity.START
                setPadding(0, 0, 0, 8)
            }
        )

        container.addView(
            TextView(context).apply {
                text = "Profile: $activeProfileName"
                textSize = 14f
                setPadding(0, 0, 0, 16)
            }
        )

        if (plugins.isEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = "No plugins found."
                    textSize = 16f
                }
            )
        } else {
            plugins.forEach { plugin ->
                container.addView(createPluginRow(plugin))
            }
        }

        scrollView.addView(container)
        setContentView(scrollView)
    }

    private fun createPluginRow(plugin: PluginEntry): LinearLayout {
        val sourceLabel = when (plugin.sourceType) {
            "base_game" -> "Base Game"
            "official_dlc" -> "Official DLC"
            "unmanaged_data" -> "Game Data folder"
            "managed_mod" -> plugin.sourceModName
            else -> plugin.sourceModName
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)

            addView(
                TextView(context).apply {
                    text = "${plugin.priority.toString().padStart(3, '0')} | ${plugin.pluginName}"
                    textSize = 16f
                }
            )

            addView(
                TextView(context).apply {
                    text = "${plugin.pluginType} | ${if (plugin.enabled) "Enabled" else "Disabled"}"
                    textSize = 13f
                }
            )

            addView(
                TextView(context).apply {
                    text = "From: $sourceLabel"
                    textSize = 13f
                }
            )

            if (plugin.locked) {
                addView(
                    TextView(context).apply {
                        text = "Locked official plugin"
                        textSize = 12f
                    }
                )
            }
        }
    }
}