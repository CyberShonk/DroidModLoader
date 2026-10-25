package com.shonkware.droidmodloader.engine.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class StorageCapacityTest {
    @Test
    fun `shared volume combines writes but never adds free space`() {
        val result = assess(listOf(write("archive", 60), write("extract", 60))) { measured("shared", 100) }
        assertEquals(1, result.size)
        assertEquals(100L, result.single().availableBytes)
        assertEquals(120L, result.single().minimumRequiredBytes)
        assertEquals(StorageCapacityStatus.INSUFFICIENT, result.single().status)
    }

    @Test
    fun `separate volumes cannot lend each other free space`() {
        val result = assess(listOf(write("archive", 90), write("extract", 200))) { path ->
            measured(path.name, 100)
        }
        assertEquals(2, result.size)
        assertEquals(listOf(StorageCapacityStatus.ENOUGH_FOR_MEASURED_BYTES, StorageCapacityStatus.INSUFFICIENT),
            result.map { it.status })
    }

    @Test
    fun `unknown identity never groups paths or returns combined sufficiency`() {
        val result = assess(listOf(write("archive", 60), write("extract", 60))) { measured(null, 100) }
        assertEquals(2, result.size)
        assertTrue(result.all { it.status == StorageCapacityStatus.UNKNOWN })
    }

    @Test
    fun `unknown identity still allows measurable per-path insufficiency`() {
        val result = assess(listOf(write("archive", 60))) { measured(null, 50) }.single()
        assertEquals(StorageCapacityStatus.INSUFFICIENT, result.status)
    }

    @Test
    fun `unknown expanded bytes remain unknown alongside a measured archive`() {
        val result = assess(listOf(write("archive", 60), write("extract", null))) { measured("shared", 100) }.single()
        assertEquals(60L, result.minimumRequiredBytes)
        assertFalse(result.requirementComplete)
        assertEquals(StorageCapacityStatus.UNKNOWN, result.status)
    }

    @Test
    fun `known lower bound may reject insufficient space without an exact total`() {
        val result = assess(listOf(write("archive", 120), write("extract", null))) { measured("shared", 100) }.single()
        assertFalse(result.requirementComplete)
        assertEquals(StorageCapacityStatus.INSUFFICIENT, result.status)
    }

    @Test
    fun `entirely unknown requirement is not a zero requirement`() {
        val result = assess(listOf(write("extract", null))) { measured("shared", 100) }.single()
        assertNull(result.minimumRequiredBytes)
        assertEquals(StorageCapacityStatus.UNKNOWN, result.status)
    }

    @Test
    fun `headroom is charged once per volume without overflowing`() {
        val writes = listOf(write("archive", 40), write("stage", 50))
        val enough = StorageCapacity.assess(writes, StorageCapacityProbe { measured("shared", 100) }, 10).single()
        assertEquals(StorageCapacityStatus.ENOUGH_FOR_MEASURED_BYTES, enough.status)
        val low = StorageCapacity.assess(writes, StorageCapacityProbe { measured("shared", 99) }, 10).single()
        assertEquals(StorageCapacityStatus.INSUFFICIENT, low.status)
        val hugeReserve = StorageCapacity.assess(writes, StorageCapacityProbe { measured("shared", Long.MAX_VALUE) },
            Long.MAX_VALUE).single()
        assertEquals(StorageCapacityStatus.INSUFFICIENT, hugeReserve.status)
    }

    @Test
    fun `overflowed requirement is never wrapped or reported exact`() {
        val result = assess(listOf(write("one", Long.MAX_VALUE), write("two", 1))) {
            measured("shared", Long.MAX_VALUE)
        }.single()
        assertNull(result.minimumRequiredBytes)
        assertFalse(result.requirementComplete)
        assertEquals(StorageCapacityStatus.UNKNOWN, result.status)
        assertTrue(result.details.contains("overflowed"))
    }

    @Test
    fun `permission loss and capacity failure remain unavailable`() {
        val result = assess(listOf(write("stage", 20))) { throw IOException("Access denied") }.single()
        assertNull(result.volumeId)
        assertNull(result.availableBytes)
        assertEquals(StorageCapacityStatus.UNKNOWN, result.status)
        assertTrue(result.locations.single().measurement.details.isNotBlank())
    }

    @Test
    fun `unavailable reading is not replaced by another path reading`() {
        val result = assess(listOf(write("archive", 20), write("stage", 20))) { path ->
            if (path.name == "archive") measured("shared", 100) else measured("shared", null)
        }.single()
        assertNull(result.availableBytes)
        assertEquals(StorageCapacityStatus.UNKNOWN, result.status)
    }

    @Test
    fun `varying shared readings use conservative minimum and probe each location once`() {
        var probes = 0
        val result = assess(listOf(write("stage", 30), write("stage", 30), write("backup", 30))) { path ->
            probes++
            measured("shared", if (path.name == "stage") 100 else 80)
        }.single()
        assertEquals(2, probes)
        assertEquals(80L, result.availableBytes)
        assertEquals(90L, result.minimumRequiredBytes)
        assertEquals(StorageCapacityStatus.INSUFFICIENT, result.status)
    }

    @Test
    fun `measured full volume is not unknown capacity`() {
        val result = assess(listOf(write("stage", 1))) { measured("shared", 0) }.single()
        assertEquals(0L, result.availableBytes)
        assertEquals(StorageCapacityStatus.INSUFFICIENT, result.status)
    }

    private fun write(name: String, bytes: Long?) =
        StorageWriteRequirement(name, File("/test/$name"), "DML owned", bytes, bytes != null)

    private fun measured(id: String?, bytes: Long?) = StorageCapacityMeasurement(id, bytes)

    private fun assess(writes: List<StorageWriteRequirement>, probe: (File) -> StorageCapacityMeasurement) =
        StorageCapacity.assess(writes, StorageCapacityProbe(probe), reserveBytes = 0)
}
