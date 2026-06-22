package com.shonkware.droidmodloader.engine.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

class AllFilesAccessManager(
    private val context: Context
) {
    fun state(): AllFilesAccessState {
        val managerGranted = if (Build.VERSION.SDK_INT >= AllFilesAccessPolicy.ANDROID_11_API_LEVEL) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        return AllFilesAccessPolicy.resolve(
            sdkInt = Build.VERSION.SDK_INT,
            isExternalStorageManager = managerGranted
        )
    }

    fun isGranted(): Boolean {
        return state() != AllFilesAccessState.DENIED
    }

    fun appSpecificSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < AllFilesAccessPolicy.ANDROID_11_API_LEVEL) {
            return null
        }

        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun fallbackSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < AllFilesAccessPolicy.ANDROID_11_API_LEVEL) {
            return null
        }

        return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}
