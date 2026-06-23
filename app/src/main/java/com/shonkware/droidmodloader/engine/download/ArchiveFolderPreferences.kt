package com.shonkware.droidmodloader.engine.download

import android.content.SharedPreferences

interface ArchiveFolderSelectionStore {
    fun getSelectedFolderPath(profileId: String): String?
    fun saveSelectedFolderPath(profileId: String, path: String)
    fun clearSelectedFolderPath(profileId: String)
    fun isReselectionRequired(profileId: String): Boolean
}

class ArchiveFolderPreferences(
    private val preferences: SharedPreferences
) : ArchiveFolderSelectionStore {
    override fun getSelectedFolderPath(profileId: String): String? {
        val selectedPath = preferences
            .getString(selectedFolderPathKey(profileId), null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (selectedPath != null) {
            return selectedPath
        }

        migrateLegacyUriSelection(profileId)
        return null
    }

    override fun saveSelectedFolderPath(profileId: String, path: String) {
        require(path.isNotBlank()) { "Archive folder path must not be blank." }

        preferences.edit()
            .putString(selectedFolderPathKey(profileId), path)
            .remove(profileLegacyUriKey(profileId))
            .remove(LEGACY_SELECTED_FOLDER_URI_KEY)
            .remove(reselectionRequiredKey(profileId))
            .apply()
    }

    override fun clearSelectedFolderPath(profileId: String) {
        preferences.edit()
            .remove(selectedFolderPathKey(profileId))
            .remove(reselectionRequiredKey(profileId))
            .apply()
    }

    override fun isReselectionRequired(profileId: String): Boolean {
        getSelectedFolderPath(profileId)
        return preferences.getBoolean(reselectionRequiredKey(profileId), false)
    }

    private fun migrateLegacyUriSelection(profileId: String) {
        val hasProfileUri = !preferences
            .getString(profileLegacyUriKey(profileId), null)
            .isNullOrBlank()
        val hasAppWideUri = !preferences
            .getString(LEGACY_SELECTED_FOLDER_URI_KEY, null)
            .isNullOrBlank()

        if (!hasProfileUri && !hasAppWideUri) return

        preferences.edit()
            .remove(profileLegacyUriKey(profileId))
            .remove(LEGACY_SELECTED_FOLDER_URI_KEY)
            .putBoolean(reselectionRequiredKey(profileId), true)
            .apply()
    }

    private fun selectedFolderPathKey(profileId: String): String {
        require(profileId.isNotBlank()) { "Profile ID must not be blank." }
        return "$SELECTED_FOLDER_PATH_KEY_PREFIX$profileId"
    }

    private fun profileLegacyUriKey(profileId: String): String {
        require(profileId.isNotBlank()) { "Profile ID must not be blank." }
        return "$LEGACY_SELECTED_FOLDER_URI_KEY_PREFIX$profileId"
    }

    private fun reselectionRequiredKey(profileId: String): String {
        require(profileId.isNotBlank()) { "Profile ID must not be blank." }
        return "$RESELECTION_REQUIRED_KEY_PREFIX$profileId"
    }

    companion object {
        const val PREFERENCES_NAME = "archive_folder_browser"
        private const val LEGACY_SELECTED_FOLDER_URI_KEY = "selected_archive_folder_uri"
        private const val LEGACY_SELECTED_FOLDER_URI_KEY_PREFIX = "selected_archive_folder_uri."
        private const val SELECTED_FOLDER_PATH_KEY_PREFIX = "selected_archive_folder_path."
        private const val RESELECTION_REQUIRED_KEY_PREFIX = "archive_folder_reselection_required."
    }
}
