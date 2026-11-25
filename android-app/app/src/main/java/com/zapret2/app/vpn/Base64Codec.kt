package com.zapret2.app.vpn

import java.nio.charset.StandardCharsets

/**
 * A small base64 decoder.
 *
 * `java.util.Base64` only exists from API 26 and `android.util.Base64` cannot run in a plain
 * JVM unit test, so subscription decoding uses this instead: it works on every supported release
 * and stays testable.
 */
internal object Base64Codec {

    private const val PAD = -2
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val output = StringBuilder()
        var index = 0
        while (index + 2 < bytes.size) {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                (bytes[index + 2].toInt() and 0xFF)
            output.append(ALPHABET[(chunk shr 18) and 0x3F])
            output.append(ALPHABET[(chunk shr 12) and 0x3F])
            output.append(ALPHABET[(chunk shr 6) and 0x3F])
            output.append(ALPHABET[chunk and 0x3F])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val chunk = (bytes[index].toInt() and 0xFF) shl 16
                output.append(ALPHABET[(chunk shr 18) and 0x3F])
                output.append(ALPHABET[(chunk shr 12) and 0x3F])
                output.append("==")
            }
            2 -> {
                val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                    ((bytes[index + 1].toInt() and 0xFF) shl 8)
                output.append(ALPHABET[(chunk shr 18) and 0x3F])
                output.append(ALPHABET[(chunk shr 12) and 0x3F])
                output.append(ALPHABET[(chunk shr 6) and 0x3F])
                output.append('=')
            }
        }
        return output.toString()
    }
    fun decode(value: String): String? {
        val compact = value.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
        if (compact.isEmpty()) return null
        val normalized = compact.replace('-', '+').replace('_', '/')
        if (normalized.length % 4 == 1) return null
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val output = ByteArray(padded.length / 4 * 3)
        var buffer = 0
        var bits = 0
        var written = 0
        for (char in padded) {
            val symbol = decodeSymbol(char)
            if (symbol == PAD) break
            if (symbol < 0) return null
            buffer = (buffer shl 6) or symbol
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output[written++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return String(output.copyOf(written), StandardCharsets.UTF_8)
    }

    private fun decodeSymbol(char: Char): Int = when (char) {
        in 'A'..'Z' -> char - 'A'
        in 'a'..'z' -> char - 'a' + 26
        in '0'..'9' -> char - '0' + 52
        '+' -> 62
        '/' -> 63
        '=' -> PAD
        else -> -1
    }

}
