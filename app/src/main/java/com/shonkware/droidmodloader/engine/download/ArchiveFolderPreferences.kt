package com.shonkware.droidmodloader.engine.download

import android.content.SharedPreferences

interface ArchiveFolderSelectionStore {
    fun getSelectedFolderUri(): String?
    fun saveSelectedFolderUri(treeUri: String)
    fun clearSelectedFolderUri()
}

class ArchiveFolderPreferences(
    private val preferences: SharedPreferences
) : ArchiveFolderSelectionStore {
    override fun getSelectedFolderUri(): String? {
        return preferences
            .getString(KEY_SELECTED_FOLDER_URI, null)
            ?.takeIf { it.isNotBlank() }
    }

    override fun saveSelectedFolderUri(treeUri: String) {
        preferences.edit()
            .putString(KEY_SELECTED_FOLDER_URI, treeUri)
            .apply()
    }

    override fun clearSelectedFolderUri() {
        preferences.edit()
            .remove(KEY_SELECTED_FOLDER_URI)
            .apply()
    }

    companion object {
        const val PREFERENCES_NAME = "archive_folder_browser"
        private const val KEY_SELECTED_FOLDER_URI = "selected_archive_folder_uri"
    }
}
