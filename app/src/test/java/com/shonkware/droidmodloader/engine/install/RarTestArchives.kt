package com.shonkware.droidmodloader.engine.install

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Independently authored stored-data vectors, not a general RAR compressor. */
internal object RarTestArchives {
    val signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 7, 1, 0)

    fun join(vararg arrays: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        arrays.forEach { write(it) }
    }.toByteArray()

    fun vint(value: Long): ByteArray = ByteArrayOutputStream().apply {
        var remaining = value
        do {
            val next = (remaining and 127).toInt()
            remaining = remaining ushr 7
            write(next or if (remaining == 0L) 0 else 128)
        } while (remaining != 0L)
    }.toByteArray()

    private fun little(value: Long, size: Int) = ByteArray(size) { (value ushr (it * 8)).toByte() }
    private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    fun header(type: Int, flags: Int, fields: ByteArray): ByteArray {
        val body = join(vint(type.toLong()), vint(flags.toLong()), fields)
        val tail = join(vint(body.size.toLong()), body)
        return join(little(crc(tail), 4), tail)
    }

    fun rar5(vararg entries: ByteArray): ByteArray = join(
        signature, header(1, 0, vint(0)), join(*entries), header(5, 0, vint(0))
    )

    fun entry(
        name: String,
        content: ByteArray = byteArrayOf(),
        directory: Boolean = false,
        compression: Int = 0,
        type: Int = 2,
        flags: Int = 0,
        attributes: Long = if (directory) 0x41ed else 0x81a4
    ): ByteArray {
        val utf8 = name.toByteArray(Charsets.UTF_8)
        val fields = join(
            vint(content.size.toLong()), vint(if (directory) 5 else 4),
            vint(content.size.toLong()), vint(attributes), little(crc(content), 4),
            vint(compression.toLong()), vint(1), vint(utf8.size.toLong()), utf8
        )
        return join(header(type, flags or 2, fields), content)
    }

    fun rar4(name: String, content: ByteArray): ByteArray = rar4(name to content)

    fun rar4(vararg files: Pair<String, ByteArray>): ByteArray {
        fun block(type: Int, flags: Int, fields: ByteArray): ByteArray {
            val tail = join(byteArrayOf(type.toByte()), little(flags.toLong(), 2),
                little((7 + fields.size).toLong(), 2), fields)
            return join(little(crc(tail), 2), tail)
        }
        val entries = files.map { (name, content) ->
            val utf8 = name.toByteArray(Charsets.UTF_8)
            join(block(0x74, 0x8000, join(
                little(content.size.toLong(), 4), little(content.size.toLong(), 4),
                byteArrayOf(3), little(crc(content), 4), little(0, 4),
                byteArrayOf(20, 0x30), little(utf8.size.toLong(), 2), little(0x81a4, 4), utf8
            )), content)
        }
        return join(signature.copyOf(7).apply { this[6] = 0 },
            block(0x73, 0, ByteArray(6)), join(*entries.toTypedArray()), block(0x7b, 0, byteArrayOf()))
    }
}
