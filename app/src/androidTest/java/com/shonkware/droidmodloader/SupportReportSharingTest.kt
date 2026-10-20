package com.shonkware.droidmodloader

import android.content.Intent
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupportReportSharingTest {
    @Test
    fun largeReportUsesSmallReadOnlyUriIntentAndPreservesFullContents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "support-reports").apply { mkdirs() }
        val report = File.createTempFile("sharing-fixture-", ".txt", directory)
        val outside = File.createTempFile("not-shareable-", ".txt", context.cacheDir)
        try {
            val contents = "0123456789 Ω🚀\n".repeat(75_000)
            report.writeText(contents)
            assertTrue(report.length() > 1_104_036)
            val intent = SupportReportFileProvider.createShareIntent(context, report)
            val uri = intent.clipData!!.getItemAt(0).uri
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            assertTrue(intent.hasExtra(Intent.EXTRA_STREAM))
            assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.supportreports", uri.authority)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            val provider = checkNotNull(context.packageManager.resolveContentProvider(uri.authority!!, 0))
            assertFalse(provider.exported)
            assertTrue(provider.grantUriPermissions)
            val received = checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
            assertEquals(contents, received)

            val chooser = Intent.createChooser(intent, "Share Logs")
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val parcel = Parcel.obtain()
            try {
                chooser.writeToParcel(parcel, 0)
                assertTrue("Report bytes must not be embedded in Binder parcel", parcel.dataSize() < 16_384)
            } finally {
                parcel.recycle()
            }
            assertThrows(IllegalArgumentException::class.java) {
                SupportReportFileProvider.createShareIntent(context, outside)
            }
        } finally {
            report.delete()
            outside.delete()
        }
    }
}
