package com.shonkware.droidmodloader

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.shonkware.droidmodloader.engine.storage.AllFilesAccessManager
import com.shonkware.droidmodloader.ui.DroidModLoaderScreen
import com.shonkware.droidmodloader.ui.SecondScreenController
import com.shonkware.droidmodloader.ui.theme.DmlTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var session: SessionViewModel
    private val allFilesAccessSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { session.refreshAccessState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this)[SessionViewModel::class.java]
        session.initialize(savedInstanceState)
        val secondScreen = SecondScreenController(this)

        setContent {
            val actions = remember(session) {
                session.buildActions(session::requestSupportReport, ::requestAllFilesAccess)
            }
            val state = session.dashboardState()
            // Presentation/windows are recreated for the current Activity; only
            // the user's enabled intent and plugin data belong to the session.
            SideEffect {
                secondScreen.update(state.plugins, state.activeProfileName)
            }
            DisposableEffect(secondScreen, state.secondScreenEnabled) {
                fun synchronize() {
                    if (state.secondScreenEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        secondScreen.start()
                    } else {
                        secondScreen.stop()
                    }
                }
                val observer = LifecycleEventObserver { _, _ -> synchronize() }
                lifecycle.addObserver(observer)
                synchronize()
                onDispose {
                    lifecycle.removeObserver(observer)
                    secondScreen.stop()
                }
            }
            LaunchedEffect(session.settingsRequested) {
                if (session.settingsRequested) {
                    session.consumeSettingsRequest()
                    requestAllFilesAccess()
                }
            }
            LaunchedEffect(session.pendingSupportReportFile) {
                session.consumeSupportReport()?.let(::shareLogs)
            }
            val deleteMod = session.pendingDeleteMod
            DisposableEffect(deleteMod) {
                val dialog = deleteMod?.let { mod ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete Mod")
                        .setMessage(
                            "Are you sure you want to delete '${mod.name}'?\n\n" +
                                "This will permanently remove this installed mod folder from Droid Mod Loader.\n\n" +
                                "Run Deploy afterward to remove its deployed files from the selected game Data folder."
                        )
                        .setPositiveButton("Delete") { _, _ -> session.confirmDelete(mod) }
                        .setNegativeButton("Cancel") { _, _ -> session.cancelDelete() }
                        .setOnCancelListener { session.cancelDelete() }
                        .show()
                }
                // Dismiss the old window without cancelling retained intent.
                onDispose { dialog?.dismiss() }
            }
            DmlTheme {
                DroidModLoaderScreen(state = state, actions = actions)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        session.saveAttentionState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        session.onResume()
    }

    private fun shareLogs(report: File) {
        try {
            val sendIntent = SupportReportFileProvider.createShareIntent(this, report)
            startActivity(Intent.createChooser(sendIntent, "Share Logs"))
        } catch (error: RuntimeException) {
            session.logShareUnavailable(error)
        }
    }

    private fun requestAllFilesAccess() {
        val manager = AllFilesAccessManager(applicationContext)
        val primary = manager.appSpecificSettingsIntent() ?: return
        try {
            allFilesAccessSettingsLauncher.launch(primary)
        } catch (_: ActivityNotFoundException) {
            val fallback = manager.fallbackSettingsIntent()
            if (fallback == null) {
                session.logSettingsUnavailable()
                return
            }
            try {
                allFilesAccessSettingsLauncher.launch(fallback)
            } catch (error: ActivityNotFoundException) {
                session.logSettingsUnavailable(error)
            }
        }
    }
}
