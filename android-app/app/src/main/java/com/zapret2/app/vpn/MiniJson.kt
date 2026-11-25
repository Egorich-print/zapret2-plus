package com.zapret2.app.vpn

/**
 * A dependency-free JSON reader and writer.
 *
 * The project keeps its dependency graph locked, so the VPN feature reads and emits JSON with
 * these few hundred lines instead of adding a serialization runtime. Only what the feature needs
 * is supported: objects, arrays, strings, numbers, booleans and null.
 */
internal object MiniJson {

    fun parse(text: String): Any? = Reader(text).parseDocument()

    fun write(value: Any?): String = StringBuilder().apply { writeValue(value) }.toString()

    private fun StringBuilder.writeValue(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(value)
            is Int, is Long, is Short, is Byte -> append(value.toString())
            is Double -> append(formatNumber(value))
            is Float -> append(formatNumber(value.toDouble()))
            is Map<*, *> -> {
                append('{')
                var first = true
                value.forEach { (key, entry) ->
                    if (!first) append(',')
                    first = false
                    writeString(key.toString())
                    append(':')
                    writeValue(entry)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                value.forEach { entry ->
                    if (!first) append(',')
                    first = false
                    writeValue(entry)
                }
                append(']')
            }
            is Array<*> -> writeValue(value.asList())
            else -> writeString(value.toString())
        }
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val asLong = value.toLong()
        return if (asLong.toDouble() == value) asLong.toString() else value.toString()
    }

    private fun StringBuilder.writeString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (char < ' ') {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }

    private class Reader(private val source: String) {
        private var index = 0

        fun parseDocument(): Any? {
            skipWhitespace()
            if (index >= source.length) return null
            val value = parseValue()
            skipWhitespace()
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (index >= source.length) return null
            return when (val char = source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (char == '-' || char.isDigit()) parseNumber() else null
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val result = linkedMapOf<String, Any?>()
            index++
            skipWhitespace()
            if (index < source.length && source[index] == '}') {
                index++
                return result
            }
            while (index < source.length) {
                skipWhitespace()
                if (index >= source.length || source[index] != '"') return result
                val key = parseString() ?: return result
                skipWhitespace()
                if (index >= source.length || source[index] != ':') return result
                index++
                result[key] = parseValue()
                skipWhitespace()
                if (index >= source.length) return result
                when (source[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> return result
                }
            }
            return result
        }

        private fun parseArray(): List<Any?> {
            val result = mutableListOf<Any?>()
            index++
            skipWhitespace()
            if (index < source.length && source[index] == ']') {
                index++
                return result
            }
            while (index < source.length) {
                result += parseValue()
                skipWhitespace()
                if (index >= source.length) return result
                when (source[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> return result
                }
            }
            return result
        }

        private fun parseString(): String? {
            if (index >= source.length || source[index] != '"') return null
            index++
            val builder = StringBuilder()
            while (index < source.length) {
                val char = source[index]
                when {
                    char == '"' -> {
                        index++
                        return builder.toString()
                    }
                    char == '\\' -> {
                        index++
                        if (index >= source.length) return builder.toString()
                        when (val escape = source[index]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'u' -> {
                                val hex = source.substring(index + 1, minOf(index + 5, source.length))
                                val code = hex.toIntOrNull(16)
                                if (code != null && hex.length == 4) {
                                    builder.append(code.toChar())
                                    index += 4
                                }
                            }
                            else -> builder.append(escape)
                        }
                        index++
                    }
                    else -> {
                        builder.append(char)
                        index++
                    }
                }
            }
            return builder.toString()
        }

        private fun parseNumber(): Any? {
            val start = index
            if (index < source.length && source[index] == '-') index++
            while (index < source.length && source[index].isDigit()) index++
            var isDecimal = false
            if (index < source.length && source[index] == '.') {
                isDecimal = true
                index++
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                isDecimal = true
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                while (index < source.length && source[index].isDigit()) index++
            }
            val text = source.substring(start, index)
            if (text.isEmpty()) return null
            return if (isDecimal) text.toDoubleOrNull() else text.toLongOrNull() ?: text.toDoubleOrNull()
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!source.startsWith(literal, index)) return null
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }
}
