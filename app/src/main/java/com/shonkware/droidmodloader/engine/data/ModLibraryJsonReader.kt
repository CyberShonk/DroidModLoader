package com.shonkware.droidmodloader.engine.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Bounded-record JSON reader: reject duplicate keys and excessive nesting before
 * JSONObject can discard evidence or recurse without a limit. Scalars/strings use
 * the existing JSON implementation; schema/type validation belongs to the repository.
 */
internal object ModLibraryJsonReader {
    private val numberLiteral = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun read(text: String): JSONObject {
        require('\u0000' !in text) { "NUL in record text" }
        // Require JSON control/escape rules independently of the underlying parser's
        // leniency before delegating string decoding to JSONTokener.
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                require(character >= ' ') { "Unescaped control in record string" }
                if (escaped) {
                    require(character in "\"\\/bfnrtu") { "Invalid record string escape" }
                    escaped = false
                } else when (character) {
                    '\\' -> escaped = true
                    '"' -> quoted = false
                }
            } else if (character == '"') quoted = true
        }
        require(!quoted && !escaped) { "Unterminated record string" }
        val tokens = JSONTokener(text)
        val root = readValue(tokens, 0) as JSONObject
        require(nextToken(tokens) == '\u0000' && !tokens.more()) { "Trailing record data" }
        return root
    }

    private fun readValue(tokens: JSONTokener, depth: Int): Any {
        require(depth <= 8) { "Record nesting exceeds limit" }
        return when (val token = nextToken(tokens)) {
            '{' -> {
                val result = JSONObject()
                if (nextToken(tokens) != '}') {
                    tokens.back()
                    while (true) {
                        require(nextToken(tokens) == '"') { "Expected quoted field name" }
                        val key = tokens.nextString('"')
                        require(!result.has(key)) { "Duplicate record field" }
                        require(nextToken(tokens) == ':') { "Expected field separator" }
                        result.put(key, readValue(tokens, depth + 1))
                        when (nextToken(tokens)) {
                            '}' -> break
                            ',' -> Unit
                            else -> error("Expected object separator")
                        }
                    }
                }
                result
            }
            '[' -> {
                val result = JSONArray()
                if (nextToken(tokens) != ']') {
                    tokens.back()
                    while (true) {
                        result.put(readValue(tokens, depth + 1))
                        when (nextToken(tokens)) {
                            ']' -> break
                            ',' -> Unit
                            else -> error("Expected array separator")
                        }
                    }
                }
                result
            }
            '"' -> tokens.nextString('"')
            else -> {
                require(token != '\u0000') { "Missing value" }
                val literal = buildString {
                    append(token)
                    while (tokens.more()) {
                        val character = tokens.next()
                        if (character in ",]} \t\r\n") { tokens.back(); break }
                        append(character)
                    }
                }
                when (literal) {
                    "true" -> true
                    "false" -> false
                    "null" -> JSONObject.NULL
                    else -> {
                        require(literal.matches(numberLiteral)) { "Expected a JSON number or literal" }
                        // Known integer fields reject Double below the parser. Still
                        // recognize other JSON numbers so unknown fields/versions can
                        // be reported as unsupported, not silently discarded/coerced.
                        if ('.' !in literal && 'e' !in literal && 'E' !in literal) {
                            literal.toLongOrNull() ?: literal.toDouble().also { require(it.isFinite()) }
                        } else literal.toDouble().also { require(it.isFinite()) }
                    }
                }
            }
        }
    }

    private fun nextToken(tokens: JSONTokener): Char {
        var character: Char
        do { character = tokens.next() } while (character in " \t\r\n")
        return character
    }
}
