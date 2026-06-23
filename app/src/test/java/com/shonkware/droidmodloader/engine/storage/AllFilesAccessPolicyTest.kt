package com.shonkware.droidmodloader.engine.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class AllFilesAccessPolicyTest {
    @Test
    fun `android 10 and older do not require special access`() {
        assertEquals(
            AllFilesAccessState.NOT_REQUIRED,
            AllFilesAccessPolicy.resolve(
                sdkInt = 29,
                isExternalStorageManager = false
            )
        )
    }

    @Test
    fun `android 11 and newer report granted access`() {
        assertEquals(
            AllFilesAccessState.GRANTED,
            AllFilesAccessPolicy.resolve(
                sdkInt = 30,
                isExternalStorageManager = true
            )
        )
    }

    @Test
    fun `android 11 and newer report denied access`() {
        assertEquals(
            AllFilesAccessState.DENIED,
            AllFilesAccessPolicy.resolve(
                sdkInt = 36,
                isExternalStorageManager = false
            )
        )
    }
}
