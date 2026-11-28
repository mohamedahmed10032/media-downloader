package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Multi-generation link decryptor for WeCima (`wecima.tips`, `wecima.cx`, `wecima.here`, etc.)
 * and Arabic streaming portals:
 *
 * 1. **WeCima CMS v2.8 AES-256-GCM (`wecima.tips` / `wecima.cx`, Nov 2026+)**:
 *    `data-url` / `data-href` = `Base64(iv[12] || AES-256-GCM(url) || tag[16])`
 *    keyed by `SHA-256(UTF-8(LINK_KEY))`, where `LINK_KEY` is defined in `/static/js/mains.js`.
 * 2. **Pure-Kotlin Auto-Healing `mains.js` Key Extractor**:
 *    When `LINK_KEY` rotates, [extractLinkKeyFromMainsJs] decodes the obfuscated string table
 *    (swapped-case Base64), evaluates the checksum rotation loop without JS `eval`, and recovers
 *    the new `LINK_KEY` automatically.
 * 3. **Legacy WeCima Obfuscated Base64**: `"aHR0c"` prefix stripped + `+` noise inserted.
 * 4. **Standard Base64 / Base64URL** and **TopCinema `play.php?to=`** unwrapping.
 */
object WeCimaLinkDecryptor {

    /** Known `LINK_KEY` from `wecima.tips/static/js/mains.js` (v12.4, Nov 2026). */
    const val DEFAULT_LINK_KEY = "9ajT3vqphrtyQ3ZfdeBSepU7iUNSC4Z7SXjS9Dg9"

    private val cachedLinkKey = AtomicReference<String>(DEFAULT_LINK_KEY)

    private val BASE64_LIKE_REGEX = Regex("""^[A-Za-z0-9+/=_-]+$""")
    private val MAINS_JS_SRC_REGEX = Regex(
        """<script[^>]+src\s*=\s*['"]([^'"]*/js/mains\.js[^'"]*)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val PLAIN_LINK_KEY_REGEX = Regex(
        """(?:var|let|const)\s+LINK_KEY\s*=\s*['"]([A-Za-z0-9_!@#\$%^&*()-]{12,128})['"]"""
    )
    private val OBF_TABLE_REGEX = Regex(
        """function\s+_0x[0-9a-f]+\s*\(\)\s*\{\s*var\s+_0x[0-9a-f]+\s*=\s*\[('.*?')]\s*;""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val OBF_OFFSET_REGEX = Regex(
        """function\s+_0x[0-9a-f]+\s*\(\s*_0x[0-9a-f]+\s*,\s*_0x[0-9a-f]+\s*\)\s*\{\s*_0x[0-9a-f]+\s*=\s*_0x[0-9a-f]+\s*-\s*(0x[0-9a-f]+)\s*;"""
    )
    private val OBF_CHECK_REGEX = Regex(
        """var\s+_0x[0-9a-f]+\s*=\s*(parseInt\(.*?)\s*;\s*if\s*\(\s*_0x[0-9a-f]+\s*===\s*_0x[0-9a-f]+\s*\)\s*break\s*;.*?\}\s*\(\s*_0x[0-9a-f]+\s*,\s*(0x[0-9a-f]+)\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val OBF_LINK_KEY_PARTS_REGEX = Regex("""(?:var|let|const)\s+LINK_KEY\s*=\s*([^;]+);""")
    private val QUOTED_ITEM_REGEX = Regex("""'([^']*)'""")
    private val PARSE_INT_LOOKUP_REGEX = Regex("""parseInt\(_0x[0-9a-f]+\((0x[0-9a-f]+)\)\)""")
    private val CHECKSUM_TOKEN_REGEX = Regex("""P0x[0-9a-f]+|0x[0-9a-f]+|\d+|[-+*/()]""")
    private val LEADING_INT_REGEX = Regex("""^\s*([+-]?\d+)""")
    private val HEX_ARG_REGEX = Regex("""\(\s*(0x[0-9a-f]+)\s*\)""")

    fun currentLinkKey(): String = cachedLinkKey.get()

    fun updateCachedLinkKey(newKey: String) {
        val clean = newKey.trim()
        if (clean.length >= 8) {
            cachedLinkKey.set(clean)
        }
    }

    internal fun resetCachedKeyForTesting() {
        cachedLinkKey.set(DEFAULT_LINK_KEY)
    }

    /**
     * Unwraps TopCinema/portal `play.php?to=<encoded>` redirector URLs (including
     * double-prefixed `https://...https://...` targets).
     */
    fun unwrapPlayRedirectUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (!trimmed.contains("play.php?to=", ignoreCase = true)) return trimmed
        val encodedPart = trimmed.substringAfter("play.php?to=").substringBefore('&').trim()
        if (encodedPart.isEmpty()) return trimmed
        var decoded = runCatching { URLDecoder.decode(encodedPart, StandardCharsets.UTF_8.name()).trim() }
            .getOrDefault(encodedPart)
        val lastHttps = decoded.lastIndexOf("https://")
        val lastHttp = decoded.lastIndexOf("http://")
        val lastScheme = maxOf(lastHttps, lastHttp)
        if (lastScheme > 0) {
            decoded = decoded.substring(lastScheme)
        }
        return normalizeValidHttpOrProtoRelative(decoded) ?: trimmed
    }

    /**
     * Decodes direct, `play.php?to=`, WeCima AES-256-GCM, standard Base64/Base64URL, and
     * legacy WeCima (`aHR0c` stripped + `+` noise) embed/download URLs.
     *
     * Returns `null` for undecodable opaque Base64 blobs so callers never resolve them as bogus
     * same-host relative paths.
     */
    fun decodePortalEmbedUrl(raw: String, linkKeyOverride: String? = null): String? {
        val trimmed = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (trimmed.isEmpty() ||
            trimmed.startsWith("javascript:", true) ||
            trimmed.startsWith("about:", true) ||
            trimmed.startsWith("data:", true)
        ) {
            return null
        }
        if (trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true) ||
            trimmed.startsWith("//")
        ) {
            return unwrapPlayRedirectUrl(trimmed)
        }
        if (trimmed.startsWith("/")) {
            return unwrapPlayRedirectUrl(trimmed)
        }
        if (trimmed.length >= 10 && BASE64_LIKE_REGEX.matches(trimmed)) {
            val rawBytes = decodeBase64Bytes(trimmed)
            // 1. WeCima CMS v2.8 AES-256-GCM: iv(12) + ciphertext(>=1) + tag(16) => len > 28
            if (rawBytes != null && rawBytes.size > 28) {
                val keysToTry = buildList {
                    linkKeyOverride?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    val cached = cachedLinkKey.get()
                    if (cached.isNotEmpty() && cached !in this) add(cached)
                    if (DEFAULT_LINK_KEY !in this) add(DEFAULT_LINK_KEY)
                }
                for (key in keysToTry) {
                    decryptAes256GcmBytes(rawBytes, key)?.let { plain ->
                        return unwrapPlayRedirectUrl(plain)
                    }
                }
            }
            // 2. Standard Base64 / Base64URL plain HTTP(S) URL
            if (rawBytes != null) {
                val asText = String(rawBytes, StandardCharsets.UTF_8).trim()
                normalizeValidHttpOrProtoRelative(asText)?.let { return unwrapPlayRedirectUrl(it) }
            }
            // 3. Legacy WeCima obfuscated Base64: strip '+' noise and prepend stripped "aHR0c" ("http")
            val stripped = trimmed.replace("+", "").trim()
            if (stripped.isNotEmpty()) {
                val wecimaCandidate = if (stripped.startsWith("aHR0c")) stripped else "aHR0c$stripped"
                decodeBase64Bytes(wecimaCandidate)?.let { legacyBytes ->
                    val legacyText = String(legacyBytes, StandardCharsets.UTF_8).trim()
                    normalizeValidHttpOrProtoRelative(legacyText)?.let { return unwrapPlayRedirectUrl(it) }
                }
            }
            // Opaque Base64-like string that decoded to neither AES-GCM nor HTTP(S) -> drop!
            return null
        }
        return if (trimmed.contains('/') || trimmed.contains('.')) unwrapPlayRedirectUrl(trimmed) else null
    }

    /** True when [raw] looks like an opaque Base64 ciphertext payload (>= 29 decoded bytes) that may need a fresh `mains.js` key. */
    fun looksLikeEncryptedWeCimaValue(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.length < 40) return false
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) ||
            trimmed.startsWith("//") || trimmed.startsWith("/")
        ) return false
        if (!BASE64_LIKE_REGEX.matches(trimmed)) return false
        val bytes = decodeBase64Bytes(trimmed) ?: return false
        return bytes.size > 28
    }

    /** Extracts the absolute URL of `/js/mains.js` referenced by a WeCima HTML page, if present. */
    fun extractMainsJsUrl(pageUrl: String, html: String): String? {
        val rawSrc = MAINS_JS_SRC_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim() ?: return null
        if (rawSrc.isEmpty()) return null
        val resolved = UrlUtils.resolveUrl(pageUrl, rawSrc)
        return resolved.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    /**
     * Encrypts [plainUrl] into WeCima CMS v2.8 AES-256-GCM format (`Base64(iv[12] || ct || tag[16])`)
     * using `SHA-256(linkKey)`. Used by unit/loopback tests to construct authentic test vectors.
     */
    internal fun encryptForTesting(plainUrl: String, linkKey: String = DEFAULT_LINK_KEY, iv: ByteArray? = null): String {
        val actualIv = iv?.takeIf { it.size == 12 } ?: ByteArray(12) { (it * 17 + 5).toByte() }
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(linkKey.toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, actualIv))
        val ctWithTag = cipher.doFinal(plainUrl.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(actualIv + ctWithTag)
    }

    /**
     * Pure-Kotlin extractor for `var LINK_KEY = ...` from WeCima's `/static/js/mains.js`:
     * supports both plain literals and `javascript-obfuscator` rotated swapped-case Base64 string tables.
     */
    fun extractLinkKeyFromMainsJs(jsSource: String): String? {
        if (jsSource.isBlank()) return null

        // Fast path: plain literal `var LINK_KEY = "..."`
        PLAIN_LINK_KEY_REGEX.find(jsSource)?.groupValues?.getOrNull(1)?.trim()?.let { plain ->
            if (plain.length >= 12) return plain
        }

        // Obfuscated path: string table + offset + checksum rotation loop + LINK_KEY concatenation
        val tableMatch = OBF_TABLE_REGEX.find(jsSource) ?: return null
        val offsetMatch = OBF_OFFSET_REGEX.find(jsSource) ?: return null
        val checkMatch = OBF_CHECK_REGEX.find(jsSource) ?: return null
        val partsMatch = OBF_LINK_KEY_PARTS_REGEX.find(jsSource) ?: return null

        val arr = QUOTED_ITEM_REGEX.findAll(tableMatch.groupValues[1])
            .map { it.groupValues[1] }
            .toMutableList()
        if (arr.isEmpty()) return null

        val offset = offsetMatch.groupValues[1].removePrefix("0x").toIntOrNull(16) ?: return null
        val rawExpr = checkMatch.groupValues[1]
        val normalizedExpr = PARSE_INT_LOOKUP_REGEX.replace(rawExpr, "P$1")
        val tokens = CHECKSUM_TOKEN_REGEX.findAll(normalizedExpr).map { it.value }.toList()
        if (tokens.joinToString("") != normalizedExpr.replace(" ", "")) return null

        val target = checkMatch.groupValues[2].removePrefix("0x").toLongOrNull(16)?.toDouble() ?: return null
        val partHexIndices = HEX_ARG_REGEX.findAll(partsMatch.groupValues[1])
            .mapNotNull { it.groupValues[1].removePrefix("0x").toIntOrNull(16) }
            .toList()
        if (partHexIndices.isEmpty()) return null

        fun decodeJsTableString(encoded: String): String {
            val swapped = buildString(encoded.length) {
                for (c in encoded) {
                    append(
                        when {
                            c.isUpperCase() -> c.lowercaseChar()
                            c.isLowerCase() -> c.uppercaseChar()
                            else -> c
                        }
                    )
                }
            }
            val bytes = decodeBase64Bytes(swapped) ?: return ""
            return String(bytes, StandardCharsets.UTF_8)
        }

        fun pickInt(tableIdx: Int): Double {
            val pos = tableIdx - offset
            if (pos !in arr.indices) throw IndexOutOfBoundsException()
            val decoded = decodeJsTableString(arr[pos])
            val m = LEADING_INT_REGEX.find(decoded) ?: throw NumberFormatException("NaN")
            return m.groupValues[1].toDouble()
        }

        repeat(arr.size) {
            val evalResult = runCatching { evaluateChecksumExpr(tokens, ::pickInt) }.getOrNull()
            if (evalResult != null && evalResult == target) {
                val joined = buildString {
                    for (hexIdx in partHexIndices) {
                        val pos = hexIdx - offset
                        if (pos !in arr.indices) return@repeat
                        append(decodeJsTableString(arr[pos]))
                    }
                }.trim()
                if (joined.length >= 8) {
                    return joined
                }
            }
            val first = arr.removeAt(0)
            arr.add(first)
        }
        return null
    }

    private fun evaluateChecksumExpr(tokens: List<String>, pick: (Int) -> Double): Double {
        var pos = 0
        fun peek(): String = if (pos < tokens.size) tokens[pos] else ""
        fun take(): String = tokens[pos++]

        lateinit var parseExpr: () -> Double

        fun parseFactor(): Double {
            val tok = take()
            return when {
                tok == "-" -> -parseFactor()
                tok == "+" -> parseFactor()
                tok == "(" -> {
                    val v = parseExpr()
                    if (peek() == ")") take()
                    v
                }
                tok.startsWith("P") -> {
                    val idx = tok.substring(1).removePrefix("0x").toInt(16)
                    pick(idx)
                }
                tok.startsWith("0x", ignoreCase = true) -> tok.substring(2).toLong(16).toDouble()
                else -> tok.toLong().toDouble()
            }
        }

        fun parseTerm(): Double {
            var value = parseFactor()
            while (peek() == "*" || peek() == "/") {
                val op = take()
                val rhs = parseFactor()
                if (op == "*") {
                    value *= rhs
                } else {
                    if (rhs == 0.0) throw ArithmeticException("div0")
                    value /= rhs
                }
            }
            return value
        }

        parseExpr = {
            var value = parseTerm()
            while (peek() == "+" || peek() == "-") {
                val op = take()
                val rhs = parseTerm()
                value = if (op == "+") value + rhs else value - rhs
            }
            value
        }

        return parseExpr()
    }

    private fun decryptAes256GcmBytes(raw: ByteArray, linkKey: String): String? {
        if (raw.size <= 28 || linkKey.isBlank()) return null
        return runCatching {
            val keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(linkKey.toByteArray(StandardCharsets.UTF_8))
            val iv = raw.copyOfRange(0, 12)
            val cipherAndTag = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(cipherAndTag)
            val plainText = String(plainBytes, StandardCharsets.UTF_8).trim()
            normalizeValidHttpOrProtoRelative(plainText)
        }.getOrNull()
    }

    private fun normalizeValidHttpOrProtoRelative(text: String): String? {
        if (text.isEmpty()) return null
        if (text.any { it.code < 0x20 || it.code == 0x7F || it.isWhitespace() }) return null
        val normalized = if (text.startsWith("//")) "https:$text" else text
        return if ((normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) &&
            normalized.length > 10 &&
            normalized.substringAfter("://").substringBefore('/').isNotBlank()
        ) {
            normalized
        } else {
            null
        }
    }

    private fun decodeBase64Bytes(raw: String): ByteArray? = runCatching {
        val normalized = raw.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - normalized.length % 4) % 4
        Base64.getDecoder().decode(normalized + "=".repeat(pad))
    }.getOrNull()
}
