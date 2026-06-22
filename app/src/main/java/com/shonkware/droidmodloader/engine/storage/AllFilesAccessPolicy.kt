package com.shonkware.droidmodloader.engine.storage

enum class AllFilesAccessState {
    NOT_REQUIRED,
    GRANTED,
    DENIED
}

object AllFilesAccessPolicy {
    const val ANDROID_11_API_LEVEL = 30

    fun resolve(
        sdkInt: Int,
        isExternalStorageManager: Boolean
    ): AllFilesAccessState {
        return when {
            sdkInt < ANDROID_11_API_LEVEL -> AllFilesAccessState.NOT_REQUIRED
            isExternalStorageManager -> AllFilesAccessState.GRANTED
            else -> AllFilesAccessState.DENIED
        }
    }
}
