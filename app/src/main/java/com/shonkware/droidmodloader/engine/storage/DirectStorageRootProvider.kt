package com.shonkware.droidmodloader.engine.storage

import android.content.Context
import android.os.Environment
import java.io.File

class DirectStorageRootProvider(
    private val context: Context
) {
    fun roots(): List<File> {
        val roots = mutableListOf<File>()
        roots.add(Environment.getExternalStorageDirectory())

        context.getExternalFilesDirs(null).forEach { appExternalDir ->
            if (appExternalDir == null) return@forEach

            val marker = "${File.separator}Android${File.separator}data${File.separator}"
            val rootPath = appExternalDir.absolutePath.substringBefore(marker)
            if (rootPath.isNotBlank() && rootPath != appExternalDir.absolutePath) {
                roots.add(File(rootPath))
            }
        }

        return roots.distinctBy { root ->
            runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        }
    }
}
