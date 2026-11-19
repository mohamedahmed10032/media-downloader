package com.melmeligy.mediadownloader.extractor

import org.json.JSONObject

/**
 * Decodes hex-obfuscated media URLs (full string or leading path segment) using the 7-PRNG
 * stream cipher family and applies optional CDN subdomain remapping via `hostMap`.
 */
object XhamsterCipher {

    private val hexRegex = Regex("^[0-9a-fA-F]+$")
    private val httpAuthorityRegex = Regex("^(https?://)([^/?#]+)([\\s\\S]*)$", RegexOption.IGNORE_CASE)
    private val ipv4Regex = Regex("^\\d+(?:\\.\\d+){3}$")

    fun decryptHex(hex: String): String? {
        if (hex.length < 12 || (hex.length and 1) != 0 || !hexRegex.matches(hex)) return null
        val byteLen = hex.length ushr 1
        val bytes = IntArray(byteLen)
        var pos = 0
        var outIdx = 0
        while (pos < hex.length) {
            val v = hex.substring(pos, pos + 2).toIntOrNull(16) ?: return null
            bytes[outIdx++] = v and 0xFF
            pos += 2
        }

        val algoId = bytes[0]
        if (algoId !in 1..7) return null

        var state = (bytes[1] and 0xFF) or
            ((bytes[2] and 0xFF) shl 8) or
            ((bytes[3] and 0xFF) shl 16) or
            ((bytes[4] and 0xFF) shl 24)

        val out = ByteArray(byteLen - 5)
        for (idx in 5 until byteLen) {
            val ks = when (algoId) {
                1 -> {
                    state = state * 1664525 + 1013904223
                    state and 0xFF
                }
                2 -> {
                    state = state xor (state shl 13)
                    state = state xor (state ushr 17)
                    state = state xor (state shl 5)
                    state and 0xFF
                }
                3 -> {
                    state += -1640531527 // 0x9e3779b9
                    var e = state xor (state ushr 16)
                    e *= -2048144777 // 0x85ebca77
                    e = e xor (e ushr 13)
                    e *= -1028477379 // 0xc2b2ae3d
                    (e xor (e ushr 16)) and 0xFF
                }
                4 -> {
                    state += 1831565813 // 0x6d2b79f5
                    var e = (state shl 7) or (state ushr 25)
                    e += -1640531527 // 0x9e3779b9
                    e = e xor (e ushr 11)
                    e *= 668265261 // 0x27d4eb2d
                    e and 0xFF
                }
                5 -> {
                    state = state xor (state shl 7)
                    state = state xor (state ushr 9)
                    state = state xor (state shl 8)
                    state += -1515870811 // 0xa5a5a5a5
                    state and 0xFF
                }
                6 -> {
                    state = state * 747796405 + -1403630843 // 0x2c9277b5, 0xac564b05
                    (((state xor (state ushr 18)) ushr ((state ushr 27) and 31)) and 0xFF)
                }
                7 -> {
                    state += -1640531527 // 0x9e3779b9
                    var e = state xor (state shl 5)
                    e *= 2146121005 // 0x7feb352d
                    e = e xor (e ushr 15)
                    e *= -2073254261 // 0x846ca68b
                    e and 0xFF
                }
                else -> return null
            }

            val ch = (bytes[idx] xor ks) and 0xFF
            if (ch < 32 || ch > 126) return null
            out[idx - 5] = ch.toByte()
        }

        val text = String(out, Charsets.US_ASCII).trim()
        return text.ifEmpty { null }
    }

    fun decryptSourceUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed

        decryptHex(trimmed)?.let { dec ->
            return normalizeScheme(dec.trim())
        }

        val schemeIdx = trimmed.indexOf("://")
        val firstSlash = when {
            schemeIdx != -1 -> trimmed.indexOf('/', schemeIdx + 3)
            trimmed.startsWith("//") -> trimmed.indexOf('/', 2)
            else -> trimmed.indexOf('/')
        }

        if (firstSlash != -1 && firstSlash + 1 < trimmed.length) {
            val segStart = firstSlash + 1
            val nextSlash = trimmed.indexOf('/', segStart)
            val nextComma = trimmed.indexOf(',', segStart)
            val segEnd = when {
                nextSlash != -1 && nextComma != -1 -> minOf(nextSlash, nextComma)
                nextSlash != -1 -> nextSlash
                nextComma != -1 -> nextComma
                else -> trimmed.length
            }
            if (segEnd > segStart) {
                val segment = trimmed.substring(segStart, segEnd)
                decryptHex(segment)?.let { dec ->
                    return normalizeScheme(trimmed.substring(0, segStart) + dec + trimmed.substring(segEnd))
                }
            }
        }

        return normalizeScheme(trimmed)
    }

    fun applyHostMap(url: String, hostSub: String?): String {
        val normalized = normalizeScheme(url.trim())
        val sub = hostSub?.trim()?.takeIf { it.isNotEmpty() } ?: return normalized
        val match = httpAuthorityRegex.matchEntire(normalized) ?: return normalized
        val scheme = match.groupValues[1]
        val authority = match.groupValues[2]
        val rest = match.groupValues[3]

        if (authority.startsWith("[") || authority.contains("@")) return normalized

        val colon = authority.indexOf(':')
        val host = if (colon >= 0) authority.substring(0, colon) else authority
        val port = if (colon >= 0) authority.substring(colon) else ""

        if (ipv4Regex.matches(host)) return normalized

        val dot = host.indexOf('.')
        if (dot <= 0 || dot == host.length - 1) return normalized

        val newHost = sub + host.substring(dot)
        return "$scheme$newHost$port$rest"
    }

    fun applyHostMap(url: String, hostMap: JSONObject?, key: String?): String {
        val sub = if (hostMap != null && !key.isNullOrBlank()) {
            hostMap.optString(key).takeIf { it.isNotBlank() }
        } else {
            null
        }
        return applyHostMap(url, sub)
    }

    private fun normalizeScheme(url: String): String =
        if (url.startsWith("//")) "https:$url" else url
}
