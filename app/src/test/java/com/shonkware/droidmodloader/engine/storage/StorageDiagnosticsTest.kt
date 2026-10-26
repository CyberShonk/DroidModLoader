package com.shonkware.droidmodloader.engine.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StorageDiagnosticsTest {
    @Test
    fun `report keeps required bytes unknown and separates known available space from inventory`() {
        val facts = StorageCapacity.assess(listOf(
            StorageWriteRequirement("Archive", File("/owned/archives"), "DML managed"),
            StorageWriteRequirement("Installed mods and staging", File("/owned/mods"), "DML managed")
        ), StorageCapacityProbe { StorageCapacityMeasurement("shared", 1234L) })
        val report = StorageDiagnostics(0L, facts, ManagedArchiveInventory(1, 99L, complete = true), emptyList()).toSummary()
        assertTrue(report.contains("historical observation at 1970-01-01T00:00:00Z"))
        assertEquals(1, Regex("Available:").findAll(report).count())
        assertTrue(report.contains("Available: 1234 bytes"))
        assertTrue(report.contains("Copy budget: unknown"))
        assertTrue(report.contains("observed logical size: 99 bytes"))
        assertTrue(report.contains("Inventory bytes are not added to free space"))
        assertTrue(report.contains("not archive validity"))
        assertFalse(report.contains("1333 bytes"))
    }

    @Test
    fun `partial inventory stays partial and is never deletion eligibility`() {
        val report = StorageDiagnostics(0L, emptyList(), ManagedArchiveInventory(details = listOf("Permission unavailable")),
            listOf("Selected target unavailable")).toSummary()
        assertTrue(report.contains("partial or unavailable"))
        assertTrue(report.contains("observed logical size: unknown"))
        assertTrue(report.contains("Permission unavailable"))
        assertTrue(report.contains("Selected target unavailable"))
        assertTrue(report.contains("not archive validity, allocated disk size or deletion eligibility"))
        assertTrue(report.contains("No managed content or archive files were changed"))
    }

    @Test
    fun `independent volumes remain separate without a combined free space total`() {
        val facts = StorageCapacity.assess(listOf(
            StorageWriteRequirement("Archive", File("/one"), "DML managed"),
            StorageWriteRequirement("Target", File("/two"), "Selected target")
        ), StorageCapacityProbe { StorageCapacityMeasurement(it.path, if (it.path == "/one") 111L else 222L) })
        val report = StorageDiagnostics(0L, facts, ManagedArchiveInventory(bytes = 0L, complete = true), emptyList()).toSummary()
        assertEquals(2, Regex("Available:").findAll(report).count())
        assertTrue(report.contains("Available: 111 bytes"))
        assertTrue(report.contains("Available: 222 bytes"))
        assertFalse(report.contains("333 bytes"))
    }
}
