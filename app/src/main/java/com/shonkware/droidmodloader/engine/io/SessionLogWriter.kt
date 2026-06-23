package com.shonkware.droidmodloader.engine.io

import java.io.File

class SessionLogWriter(
    private val externalFilesDirProvider: () -> File?
) {
    fun append(message: String) {
        try {
            val logFile = getLogFile() ?: return
            logFile.parentFile?.mkdirs()
            logFile.appendText(message + "\n")
        } catch (_: Exception) {
            // Session logging must never break the user operation being reported.
        }
    }

    fun readTextOrEmpty(): String {
        return try {
            getLogFile()
                ?.takeIf { it.exists() && it.isFile }
                ?.readText()
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun getLogFile(): File? {
        val externalBaseDir = externalFilesDirProvider() ?: return null
        return File(File(externalBaseDir, "logs"), "session_log.txt")
    }
}
