package com.shonkware.droidmodloader.ui.workflow

/** Coordinates the ordered startup sequence without owning Android lifecycle APIs. */
internal class AppStartupCoordinator<T>(
    private val runInBackground: (() -> Unit) -> Unit,
    private val loadSetupState: () -> Unit,
    private val migrateLegacyProfileStorage: () -> Unit,
    private val refreshGameOptions: () -> Unit,
    private val loadSelectedGameConfig: () -> Unit,
    private val migratePrioritySpacing: () -> Unit,
    private val ensureDataBaseline: () -> Unit,
    private val createRuntime: () -> T?,
    private val checkRecovery: (T) -> Unit,
    private val synchronizePluginsAndRefresh: (T?) -> Unit,
    private val appendLog: (String) -> Unit
) {
    fun initialize() {
        runInBackground {
            loadSetupState()
            migrateLegacyProfileStorage()
            refreshGameOptions()
            loadSelectedGameConfig()
            migratePrioritySpacing()
            ensureDataBaseline()

            val runtime = createRuntime()
            if (runtime != null) {
                checkRecovery(runtime)
            }
            synchronizePluginsAndRefresh(runtime)
        }

        appendLog("UI ready.")
    }
}
