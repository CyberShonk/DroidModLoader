package com.shonkware.droidmodloader.engine.download

import android.content.SharedPreferences

interface ArchiveFolderSelectionStore {
    fun getSelectedFolderUri(profileId: String): String?
    fun saveSelectedFolderUri(profileId: String, treeUri: String)
    fun clearSelectedFolderUri(profileId: String)
}

class ArchiveFolderPreferences(
    private val preferences: SharedPreferences
) : ArchiveFolderSelectionStore {
    override fun getSelectedFolderUri(profileId: String): String? {
        val profileKey = selectedFolderKey(profileId)
        val selectedFolderUri = preferences
            .getString(profileKey, null)
            ?.takeIf { it.isNotBlank() }

        if (selectedFolderUri != null) {
            return selectedFolderUri
        }

        val legacyFolderUri = preferences
            .getString(LEGACY_SELECTED_FOLDER_URI_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        preferences.edit()
            .putString(profileKey, legacyFolderUri)
            .remove(LEGACY_SELECTED_FOLDER_URI_KEY)
            .apply()

        return legacyFolderUri
    }

    override fun saveSelectedFolderUri(profileId: String, treeUri: String) {
        preferences.edit()
            .putString(selectedFolderKey(profileId), treeUri)
            .apply()
    }

    override fun clearSelectedFolderUri(profileId: String) {
        preferences.edit()
            .remove(selectedFolderKey(profileId))
            .apply()
    }

    private fun selectedFolderKey(profileId: String): String {
        require(profileId.isNotBlank()) { "Profile ID must not be blank." }
        return "$SELECTED_FOLDER_URI_KEY_PREFIX$profileId"
    }

    companion object {
        const val PREFERENCES_NAME = "archive_folder_browser"
        private const val LEGACY_SELECTED_FOLDER_URI_KEY = "selected_archive_folder_uri"
        private const val SELECTED_FOLDER_URI_KEY_PREFIX = "selected_archive_folder_uri."
    }
}
