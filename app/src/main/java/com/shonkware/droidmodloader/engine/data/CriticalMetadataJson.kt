package com.shonkware.droidmodloader.engine.data

import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

/** Strict scalar decoding shared only by the two critical JSON stores. No coercion/defaults. */
internal object CriticalMetadataJson {
    fun <T> decode(description: String, text: String, parse: (Any) -> T): T {
        try {
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            require(tokener.nextClean() == '\u0000') { "Trailing data" }
            return parse(value)
        } catch (exception: Exception) {
            throw IOException("Invalid or unsupported $description; existing evidence was not replaced.", exception)
        }
    }

    fun JSONObject.requiredString(key: String): String {
        val value = get(key)
        require(value is String && value.isNotBlank()) { "Invalid $key" }
        return value
    }

    fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = get(key)
        require(value is String) { "Invalid $key" }
        return value.takeIf { it.isNotBlank() }
    }

    fun JSONObject.requiredLong(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "Invalid $key" }
        return (value as Number).toLong().also { require(it >= 0) { "Invalid $key" } }
    }

    fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else requiredLong(key)

    fun JSONObject.requiredInt(key: String): Int = requiredLong(key).also {
        require(it <= Int.MAX_VALUE) { "Invalid $key" }
    }.toInt()

    fun JSONObject.requiredBoolean(key: String): Boolean {
        val value = get(key)
        require(value is Boolean) { "Invalid $key" }
        return value
    }

    fun JSONObject.nullableObject(key: String): JSONObject? {
        if (!has(key) || isNull(key)) return null
        return getJSONObject(key)
    }
}
