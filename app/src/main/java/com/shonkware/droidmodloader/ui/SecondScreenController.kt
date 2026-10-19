package com.shonkware.droidmodloader.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.shonkware.droidmodloader.engine.model.PluginEntry

class SecondScreenController(
    private val context: Context
) {
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var started = false
    private var presentation: SecondScreenPluginPresentation? = null
    private var latestPlugins: List<PluginEntry> = emptyList()
    private var latestProfileName: String = "No profile"

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            showIfAvailable()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                dismiss()
                showIfAvailable()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            update(latestPlugins, latestProfileName)
        }
    }

    fun start() {
        if (started) return
        started = true
        displayManager.registerDisplayListener(
            displayListener,
            Handler(Looper.getMainLooper())
        )

        showIfAvailable()
    }

    fun stop() {
        if (started) {
            displayManager.unregisterDisplayListener(displayListener)
            started = false
        }
        dismiss()
    }

    fun update(
        plugins: List<PluginEntry>,
        activeProfileName: String
    ) {
        latestPlugins = plugins
        latestProfileName = activeProfileName

        presentation?.update(
            plugins = latestPlugins,
            activeProfileName = latestProfileName
        )
    }

    private fun showIfAvailable() {
        if (!started) return
        val display = findSecondaryDisplay() ?: return

        if (presentation?.display?.displayId == display.displayId && presentation?.isShowing == true) {
            update(latestPlugins, latestProfileName)
            return
        }

        dismiss()

        try {
            presentation = SecondScreenPluginPresentation(context, display).also {
                it.show()
                it.update(latestPlugins, latestProfileName)
            }
        } catch (e: Exception) {
            presentation = null
        }
    }

    private fun findSecondaryDisplay(): Display? {
        val presentationDisplays =
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        if (presentationDisplays.isNotEmpty()) {
            return presentationDisplays.first()
        }

        return displayManager.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY
        }
    }

    private fun dismiss() {
        presentation?.dismiss()
        presentation = null
    }
}