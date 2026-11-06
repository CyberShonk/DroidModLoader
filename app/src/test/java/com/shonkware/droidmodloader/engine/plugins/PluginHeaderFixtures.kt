package com.shonkware.droidmodloader.engine.plugins

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independent synthetic byte vectors; no real plugins or production serialization code. */
internal object PluginHeaderFixtures {
    val hedr = byteArrayOf(0x48, 0x45, 0x44, 0x52, 12, 0, 0, 0, 0x80.toByte(), 0x3f, 0, 0, 0, 0, 0, 0, 0, 0)
    val baseMaster = byteArrayOf(0x4d, 0x41, 0x53, 0x54, 9, 0, 0x42, 0x61, 0x73, 0x65, 0x2e, 0x65, 0x73, 0x6d, 0)
    val data = byteArrayOf(0x44, 0x41, 0x54, 0x41, 8, 0) + ByteArray(8)

    fun opening(body: ByteArray = hedr, flags: Int = 0, headerLength: Int = 24): ByteArray =
        ByteBuffer.allocate(headerLength).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(0x54, 0x45, 0x53, 0x34)).putInt(body.size).putInt(flags).array() + body

    fun subrecord(kind: String, payload: ByteArray): ByteArray =
        kind.toByteArray(Charsets.US_ASCII) + byteArrayOf(payload.size.toByte(), (payload.size shr 8).toByte()) + payload

    fun extended(kind: String, payload: ByteArray): ByteArray =
        byteArrayOf(0x58, 0x58, 0x58, 0x58, 4, 0) +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array() +
            kind.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + payload

    fun masters(count: Int): ByteArray = ByteArrayOutputStream().apply {
        write(hedr)
        repeat(count) { write(subrecord("MAST", "Master$it.esm\u0000".toByteArray())) }
    }.toByteArray()

    fun source(file: File, root: File = file.parentFile): PluginHeaderSource = PluginHeaderSource(
        "profile-a", "capture-1", PluginSourceScope.MANAGED_CANDIDATE, root.absolutePath, file.absolutePath, "mod-a"
    )
}
