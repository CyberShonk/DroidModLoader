package com.shonkware.droidmodloader

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

class SupportReportFileProvider : FileProvider() {
    companion object {
        fun createShareIntent(context: Context, report: File): Intent {
            val uri = getUriForFile(context, "${context.packageName}.supportreports", report)
            return Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("DML support report", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
