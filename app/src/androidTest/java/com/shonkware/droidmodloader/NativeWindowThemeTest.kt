package com.shonkware.droidmodloader

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import com.shonkware.droidmodloader.ui.theme.DmlPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeWindowThemeTest {
    @Test
    fun nativeWindowMatchesComposeBackgroundInSystemLightMode() {
        assertWindowTheme(Configuration.UI_MODE_NIGHT_NO)
    }

    @Test
    fun nativeWindowMatchesComposeBackgroundInSystemDarkMode() {
        assertWindowTheme(Configuration.UI_MODE_NIGHT_YES)
    }

    private fun assertWindowTheme(nightMode: Int) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        val context = ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_DroidModLoader)
        val attrs = context.obtainStyledAttributes(intArrayOf(
            android.R.attr.windowBackground,
            android.R.attr.colorBackground,
            android.R.attr.statusBarColor,
            android.R.attr.navigationBarColor,
            android.R.attr.windowLightStatusBar,
            android.R.attr.windowLightNavigationBar,
            android.R.attr.windowIsTranslucent
        ))
        try {
            val background = DmlPalettes.CarbonRed.background.toArgb()
            assertEquals(background, (attrs.getDrawable(0) as ColorDrawable).color)
            for (index in 1..3) assertEquals(background, attrs.getColor(index, 0))
            assertFalse("Dark status-bar icons", attrs.getBoolean(4, true))
            assertFalse("Dark navigation-bar icons", attrs.getBoolean(5, true))
            // Unspecified translucency is opaque, as in the platform window default.
            assertFalse("Translucent window", attrs.getBoolean(6, false))
        } finally {
            attrs.recycle()
        }
    }
}
