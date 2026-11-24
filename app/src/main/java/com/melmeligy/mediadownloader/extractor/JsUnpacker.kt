package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.roundToLong

/**
 * Pure-Kotlin JavaScript deobfuscation and player-configuration extractor for streaming embed hosts.
 *
 * Supports:
 * - Dean Edwards `eval(function(p,a,c,k,e,d)...)` / `(p,a,c,k,e,r)` packed scripts (radices 2..62,
 *   single/double quotes, escaped quotes/backslashes, multiple and nested blocks).
 * - Streamtape `robotlink` / `norobotlink` / `ideoolink` JS string concatenation with chained
 *   `.substring(...)` / `.slice(...)` calls.
 * - `atob("...")` Base64 and `\xHH` hex-escaped URLs.
 * - PlayerJS (`new Playerjs({file: ...})` including `[720p]url,[480p]url`), JWPlayer, Plyr,
 *   Clappr, and Video.js configs, explicitly stripping VAST/preroll/midroll/postroll ad fields.
 */
object JsUnpacker {

    data class ExtractedSource(
        val url: String,
        val label: String = "",
        val width: Int? = null,
        val height: Int? = null,
        val isHlsHint: Boolean = false
    )

    data class PlayerConfigResult(
        val sources: List<ExtractedSource> = emptyList(),
        val durationSec: Long? = null,
        val posterUrl: String? = null
    )

    private const val MAX_UNPACKED_BLOCKS = 8
    private const val MAX_BLOCK_LENGTH = 512 * 1024

    private val PACKER_START_REGEX = Regex(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val WORD_TOKEN_REGEX = Regex("""\b[0-9A-Za-z]+\b""")

    private val AD_FIELD_VALUE_REGEX = Regex(
        """(?:["']?(?:preroll|midroll|postroll|vast|vastTag|adTag|adTagUrl|ad_tag|vast_tag|advertising_url|adUrl)["']?\s*:\s*['"][^'"]*['"])""",
        RegexOption.IGNORE_CASE
    )
    private val AD_OBJECT_BLOCK_REGEX = Regex(
        """(?:["']?(?:advertising|vastOptions|vast|ads|adSchedule|preroll|midroll|postroll)["']?\s*:\s*\{[^{}]{0,1200}\})""",
        RegexOption.IGNORE_CASE
    )

    private val DURATION_REGEX = Regex(
        """(?:["']?duration["']?\s*:\s*['"]?(\d+(?:\.\d+)?)['"]?)""",
        RegexOption.IGNORE_CASE
    )
    private val POSTER_REGEX = Regex(
        """(?:["']?(?:poster|poster_url|posterUrl|image|thumbnail|thumbnail_url|thumbnailUrl|thumb|cover)["']?\s*:\s*['"]((?:https?:)?//[^'"\s]+)['"])""",
        RegexOption.IGNORE_CASE
    )
    private val WIDTH_REGEX = Regex(
        """(?:["']?width["']?\s*:\s*['"]?(\d{3,4})['"]?)""",
        RegexOption.IGNORE_CASE
    )
    private val HEIGHT_REGEX = Regex(
        """(?:["']?height["']?\s*:\s*['"]?(\d{3,4})['"]?)""",
        RegexOption.IGNORE_CASE
    )

    private val PLAYERJS_FILE_REGEX = Regex(
        """(?:["']?(?:file|source|src|hls|hls2|hls3|hls4|m3u8|mp4)["']?\s*:\s*(['"])([^'"]{6,4000})\1)""",
        RegexOption.IGNORE_CASE
    )
    private val PLAYERJS_TAGGED_URL_REGEX = Regex(
        """\[([^\]]{1,32})]\s*((?:https?:)?//[^,\s"'<>]+|/[^,\s"'<>]+)"""
    )
    private val SOURCE_OBJ_REGEX = Regex(
        """\{[^{}]{0,500}?(?:["']?(?:file|src|url)["']?\s*:\s*['"]((?:https?:)?//[^'"]+|/[^'"]+)['"])[^{}]{0,500}?\}""",
        RegexOption.IGNORE_CASE
    )
    private val SOURCE_LABEL_REGEX = Regex(
        """(?:["']?(?:label|res|size|title|quality)["']?\s*:\s*['"]?([0-9]{3,4}p?|4k|2k|fhd|hd|sd)['"]?)""",
        RegexOption.IGNORE_CASE
    )
    private val LINKS_OBJECT_REGEX = Regex(
        """(?:(?:var|let|const)\s+)?links\s*[:=]\s*\{([^{}]{1,4000})\}""",
        RegexOption.IGNORE_CASE
    )
    private val LINKS_ENTRY_REGEX = Regex(
        """["']?(hls\d*|file|mp4|src|source)["']?\s*:\s*(['"])([^'"]{4,2000})\2""",
        RegexOption.IGNORE_CASE
    )
    private val MDCORE_WURL_REGEX = Regex(
        """MDCore\.wurl\s*=\s*(['"])([^'"]{6,2000})\1""",
        RegexOption.IGNORE_CASE
    )

    private val STREAMTAPE_ASSIGN_REGEX = Regex(
        """getElementById\s*\(\s*['"](?:no)?robotlink['"]\s*\)\.innerHTML\s*=\s*([^\r\n;]+)|getElementById\s*\(\s*['"]ideoolink['"]\s*\)\.innerHTML\s*=\s*([^\r\n;]+)""",
        RegexOption.IGNORE_CASE
    )
    private val SUBSTRING_CALL_REGEX = Regex(
        """\.(?:substring|slice)\s*\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)"""
    )

    private val ATOB_REGEX = Regex(
        """atob\s*\(\s*['"]([A-Za-z0-9+/=_-]{16,2048})['"]\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val HEX_LITERAL_REGEX = Regex(
        """['"]((?:\\x[0-9a-fA-F]{2}){8,512}[^'"]*)['"]"""
    )
    private val URL_IN_TEXT_REGEX = Regex(
        """https?://[^\s"'<>\\]+"""
    )

    /**
     * Unpacks all Dean Edwards `p,a,c,k,e,d` blocks found inside [script], supporting up to
     * 2 nesting layers. Returns the list of decoded JS blocks.
     */
    fun unpackAll(script: String): List<String> {
        if (!script.contains("function(p,a,c,k,e", ignoreCase = true) &&
            !script.contains("function (p,a,c,k,e", ignoreCase = true)
        ) {
            return emptyList()
        }
        val out = mutableListOf<String>()
        unpackPass(script, out, depth = 0)
        return out
    }

    private fun unpackPass(source: String, out: MutableList<String>, depth: Int) {
        if (depth > 1 || out.size >= MAX_UNPACKED_BLOCKS) return
        for (match in PACKER_START_REGEX.findAll(source)) {
            if (out.size >= MAX_UNPACKED_BLOCKS) break
            val decoded = unpackAt(source, match.range.first) ?: continue
            if (decoded.isNotBlank() && decoded.length <= MAX_BLOCK_LENGTH) {
                out.add(decoded)
                if (PACKER_START_REGEX.containsMatchIn(decoded)) {
                    unpackPass(decoded, out, depth + 1)
                }
            }
        }
    }

    private fun unpackAt(src: String, evalStart: Int): String? {
        val len = src.length
        // 1. Find opening '{' of function(p,a,c,k,e,d){...}
        var idx = src.indexOf('{', evalStart)
        if (idx < 0) return null
        idx = skipBalancedBraces(src, idx)
        if (idx < 0) return null

        // 2. Expect '(' starting the invocation arguments: ('payload',radix,count,'dict'.split('|'))
        idx = skipWhitespace(src, idx)
        if (idx >= len || src[idx] != '(') return null
        idx++

        // 3. Arg 1: packed payload string p
        idx = skipWhitespace(src, idx)
        val pParsed = readJsString(src, idx) ?: return null
        val payload = pParsed.first
        idx = skipWhitespace(src, pParsed.second)
        if (idx >= len || src[idx] != ',') return null
        idx++

        // 4. Arg 2: radix a
        idx = skipWhitespace(src, idx)
        val aParsed = readInt(src, idx) ?: return null
        val radix = aParsed.first
        if (radix !in 2..62) return null
        idx = skipWhitespace(src, aParsed.second)
        if (idx >= len || src[idx] != ',') return null
        idx++

        // 5. Arg 3: count c
        idx = skipWhitespace(src, idx)
        val cParsed = readInt(src, idx) ?: return null
        val count = cParsed.first
        if (count < 0 || count > 50_000) return null
        idx = skipWhitespace(src, cParsed.second)
        if (idx >= len || src[idx] != ',') return null
        idx++

        // 6. Arg 4: keyword dictionary ('a|b|c'.split('|') or ['a','b'])
        idx = skipWhitespace(src, idx)
        val keywords: List<String> = if (idx < len && (src[idx] == '\'' || src[idx] == '"')) {
            val kParsed = readJsString(src, idx) ?: return null
            val dictStr = kParsed.first
            var sep = "|"
            val afterK = skipWhitespace(src, kParsed.second)
            if (src.startsWith(".split(", afterK)) {
                val sepStart = skipWhitespace(src, afterK + 7)
                readJsString(src, sepStart)?.let { (s, _) ->
                    if (s.isNotEmpty()) sep = s
                }
            }
            dictStr.split(sep)
        } else if (idx < len && src[idx] == '[') {
            readJsStringArray(src, idx) ?: return null
        } else {
            return null
        }

        // Build base-`radix` symbol table matching Dean Edwards' exact encoder
        val symtab = HashMap<String, String>(count.coerceAtLeast(16))
        for (i in 0 until minOf(count, keywords.size)) {
            val replacement = keywords[i]
            if (replacement.isNotEmpty()) {
                symtab[encodeBase(i, radix)] = replacement
            }
        }

        return WORD_TOKEN_REGEX.replace(payload) { m ->
            symtab[m.value] ?: m.value
        }
    }

    internal fun encodeBase(value: Int, radix: Int): String {
        if (value == 0) return "0"
        val sb = StringBuilder()
        var n = value
        while (n > 0) {
            val rem = n % radix
            sb.append(
                when {
                    rem < 10 -> ('0'.code + rem).toChar()
                    rem < 36 -> ('a'.code + (rem - 10)).toChar()
                    else -> ('A'.code + (rem - 36)).toChar()
                }
            )
            n /= radix
        }
        return sb.reverse().toString()
    }

    /**
     * Strips VAST/preroll/midroll/postroll/advertising fields from [text] so ad URLs
     * embedded alongside real stream configs (such as PlayerJS `preroll: "..."`) are ignored.
     */
    fun stripAdFields(text: String): String {
        if (text.isBlank()) return text
        var cleaned = AD_OBJECT_BLOCK_REGEX.replace(text, " ")
        cleaned = AD_FIELD_VALUE_REGEX.replace(cleaned, " ")
        return cleaned
    }

    /**
     * Extracts stream URLs, quality hints, duration, and poster from PlayerJS, JWPlayer,
     * Plyr, Clappr, Video.js, `links = {"hls2":...,"hls3":...,"hls4":...}`, and `MDCore.wurl`
     * scripts (both raw HTML and unpacked JS).
     *
     * When [baseUrl] is supplied, protocol-relative (`//...`) and root-relative (`/...`)
     * stream URLs are resolved against [baseUrl].
     */
    fun extractPlayerConfigs(text: String, baseUrl: String? = null): PlayerConfigResult {
        if (text.isBlank()) return PlayerConfigResult()
        val sanitized = stripAdFields(text)
        val unescaped = unescapeJs(sanitized)

        val durationSec = DURATION_REGEX.findAll(unescaped)
            .mapNotNull { it.groupValues[1].toDoubleOrNull()?.roundToLong() }
            .filter { it in 1L..86_400L }
            .maxOrNull()

        val posterUrl = POSTER_REGEX.find(unescaped)?.groupValues?.getOrNull(1)
            ?.let { resolveRelativeStreamUrl(it, baseUrl) }
            ?.takeIf { !AdBlocker.isAd(it) }

        val cfgWidth = WIDTH_REGEX.find(unescaped)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 160..7680 }
        val cfgHeight = HEIGHT_REGEX.find(unescaped)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 120..4320 }

        val sources = LinkedHashMap<String, ExtractedSource>()

        fun addIfMedia(
            rawUrl: String,
            label: String = "",
            width: Int? = null,
            height: Int? = null,
            isHlsHint: Boolean = false,
            allowOpaqueStreamPath: Boolean = false
        ) {
            val resolved = resolveRelativeStreamUrl(unescapeJs(rawUrl.trim()), baseUrl) ?: return
            if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) return
            if (AdBlocker.isAd(resolved) || MediaSniffer.isPreviewOrAdUrl(resolved)) return
            if (MediaSniffer.isHexCipherUrl(resolved)) return
            val lower = resolved.lowercase()
            val pathNoQuery = lower.substringBefore('?').substringBefore('#')
            if (pathNoQuery.endsWith(".jpg") || pathNoQuery.endsWith(".jpeg") || pathNoQuery.endsWith(".png") ||
                pathNoQuery.endsWith(".webp") || pathNoQuery.endsWith(".vtt") || pathNoQuery.endsWith(".srt") ||
                pathNoQuery.endsWith(".xml") || pathNoQuery.endsWith(".css") || pathNoQuery.endsWith(".js") ||
                pathNoQuery.endsWith(".svg") || pathNoQuery.endsWith(".ico")
            ) return

            val disguisedHls = MediaSniffer.isDisguisedHlsUrl(resolved)
            val isMediaCandidate = allowOpaqueStreamPath ||
                isHlsHint ||
                disguisedHls ||
                lower.contains(".m3u8") ||
                lower.contains(".mpd") ||
                lower.contains(".mp4") ||
                lower.contains(".webm") ||
                lower.contains(".mkv") ||
                lower.contains(".mov") ||
                lower.contains(".m4a") ||
                lower.contains("/get_video?") ||
                lower.contains("/hls/") ||
                lower.contains("/hls2/") ||
                lower.contains("/hls3/") ||
                lower.contains("/hls4/")
            if (!isMediaCandidate) return

            val effectiveHlsHint = isHlsHint || disguisedHls || lower.contains(".m3u8")
            val key = MediaSniffer.dedupeKey(resolved)
            val normHeight = normalizeVideoHeight(width, height) ?: UrlUtils.inferResolution(resolved, label)
            val existing = sources[key]
            if (existing == null || (existing.height == null && normHeight != null) || (!existing.isHlsHint && effectiveHlsHint)) {
                sources[key] = ExtractedSource(
                    url = resolved,
                    label = label.ifBlank { existing?.label.orEmpty() },
                    width = width ?: existing?.width,
                    height = normHeight ?: existing?.height,
                    isHlsHint = effectiveHlsHint || (existing?.isHlsHint == true)
                )
            }
        }

        // 0a. Unpacked `links = {"hls2":"...", "hls3":"...", "hls4":"..."}` (EarnVids, Byse, VidHide, SaveFiles, StreamWish)
        LINKS_OBJECT_REGEX.findAll(unescaped).forEach { blockMatch ->
            val body = blockMatch.groupValues[1]
            val entries = LINKS_ENTRY_REGEX.findAll(body)
                .map { it.groupValues[1].lowercase() to it.groupValues[3].trim() }
                .sortedBy { (key, _) ->
                    when (key) {
                        "hls2" -> 0
                        "hls3" -> 1
                        "hls4" -> 2
                        "hls" -> 3
                        else -> 4
                    }
                }
                .toList()
            for ((key, rawVal) in entries) {
                val isHlsKey = key.startsWith("hls")
                addIfMedia(
                    rawUrl = rawVal,
                    width = cfgWidth,
                    height = cfgHeight,
                    isHlsHint = isHlsKey,
                    allowOpaqueStreamPath = true
                )
            }
        }

        // 0b. Mixdrop / m1xdrop `MDCore.wurl = "//..."`
        MDCORE_WURL_REGEX.findAll(unescaped).forEach { match ->
            val rawVal = match.groupValues[2].trim()
            addIfMedia(
                rawUrl = rawVal,
                width = cfgWidth,
                height = cfgHeight,
                isHlsHint = rawVal.contains(".m3u8", ignoreCase = true) || MediaSniffer.isDisguisedHlsUrl(rawVal),
                allowOpaqueStreamPath = true
            )
        }

        // 1. Structured `{file/src/url: "...", label/res: "..."}` objects
        SOURCE_OBJ_REGEX.findAll(unescaped).forEach { match ->
            val block = match.value
            val url = match.groupValues[1]
            val label = SOURCE_LABEL_REGEX.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val w = WIDTH_REGEX.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val h = HEIGHT_REGEX.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
            addIfMedia(url, label, w ?: cfgWidth, h ?: cfgHeight)
        }

        // 2. PlayerJS & JWPlayer direct `file: "..."` / `source: "..."` / `hls2: "..."` properties
        PLAYERJS_FILE_REGEX.findAll(unescaped).forEach { match ->
            val fullProp = match.value.lowercase()
            val isHlsProp = fullProp.startsWith("hls") || fullProp.startsWith("\"hls") || fullProp.startsWith("'hls") ||
                fullProp.startsWith("m3u8") || fullProp.startsWith("\"m3u8") || fullProp.startsWith("'m3u8")
            val rawVal = match.groupValues[2].trim()
            val taggedMatches = PLAYERJS_TAGGED_URL_REGEX.findAll(rawVal).toList()
            if (taggedMatches.isNotEmpty()) {
                taggedMatches.forEach { tm ->
                    val tag = tm.groupValues[1].trim()
                    val url = tm.groupValues[2].trim()
                    addIfMedia(url, label = tag, isHlsHint = isHlsProp)
                }
            } else if (rawVal.startsWith("http://") || rawVal.startsWith("https://") ||
                rawVal.startsWith("//") || rawVal.startsWith("/")
            ) {
                // Handle PlayerJS `or` fallback separator (`url1 or url2`)
                val parts = rawVal.split(Regex("""\s+or\s+""", RegexOption.IGNORE_CASE))
                parts.forEach { part ->
                    addIfMedia(part.trim(), width = cfgWidth, height = cfgHeight, isHlsHint = isHlsProp)
                }
            }
        }

        return PlayerConfigResult(
            sources = sources.values.toList(),
            durationSec = durationSec,
            posterUrl = posterUrl
        )
    }

    internal fun resolveRelativeStreamUrl(rawUrl: String, baseUrl: String?): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true)
        ) {
            return null
        }
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> {
                val scheme = baseUrl?.substringBefore("://", "")
                    ?.takeIf { it.equals("http", ignoreCase = true) || it.equals("https", ignoreCase = true) }
                    ?: "https"
                "$scheme:$trimmed"
            }
            trimmed.startsWith("/") && !baseUrl.isNullOrBlank() -> {
                UrlUtils.resolveUrl(baseUrl, trimmed).takeIf {
                    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
                }
            }
            else -> null
        }
    }

    /**
     * Resolves Streamtape `document.getElementById('norobotlink'|'robotlink'|'ideoolink').innerHTML = ...`
     * string concatenation expressions including chained `.substring(n)` / `.slice(n)` operations.
     */
    fun extractStreamtapeUrls(html: String): List<String> {
        if (!html.contains("robotlink", ignoreCase = true) &&
            !html.contains("ideoolink", ignoreCase = true)
        ) {
            return emptyList()
        }
        val results = LinkedHashSet<String>()
        STREAMTAPE_ASSIGN_REGEX.findAll(html).forEach { m ->
            val expr = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            val evaluated = evaluateConcatExpression(expr) ?: return@forEach
            val normalized = when {
                evaluated.startsWith("https://") || evaluated.startsWith("http://") -> evaluated
                evaluated.startsWith("//") -> "https:$evaluated"
                evaluated.startsWith("/") -> "https:/$evaluated"
                else -> return@forEach
            }
            if (normalized.contains("get_video", ignoreCase = true) && normalized.contains("token=")) {
                val withStream = if (normalized.contains("stream=1")) normalized else "$normalized&stream=1"
                results.add(withStream)
            }
        }
        return results.toList()
    }

    internal fun evaluateConcatExpression(expr: String): String? {
        val parts = splitTopLevelPlus(expr)
        if (parts.isEmpty()) return null
        val sb = StringBuilder()
        for (rawPart in parts) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            val evaluated = evaluateStringTerm(part) ?: return null
            sb.append(evaluated)
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun evaluateStringTerm(term: String): String? {
        var idx = 0
        val len = term.length
        while (idx < len && term[idx].isWhitespace()) idx++
        if (idx >= len) return null

        val baseStr: String
        var cursor: Int
        if (term[idx] == '(') {
            val innerStart = skipWhitespace(term, idx + 1)
            val parsed = readJsString(term, innerStart) ?: return null
            baseStr = parsed.first
            val closeIdx = skipWhitespace(term, parsed.second)
            if (closeIdx >= len || term[closeIdx] != ')') return null
            cursor = closeIdx + 1
        } else if (term[idx] == '\'' || term[idx] == '"') {
            val parsed = readJsString(term, idx) ?: return null
            baseStr = parsed.first
            cursor = parsed.second
        } else {
            return null
        }

        var current = baseStr
        val tail = term.substring(cursor)
        for (call in SUBSTRING_CALL_REGEX.findAll(tail)) {
            val start = call.groupValues[1].toIntOrNull() ?: return null
            val end = call.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (start < 0 || start > current.length) return null
            current = if (end != null) {
                val safeEnd = end.coerceIn(start, current.length)
                current.substring(start, safeEnd)
            } else {
                current.substring(start)
            }
        }
        return current
    }

    private fun splitTopLevelPlus(expr: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var parenDepth = 0
        var inQuote = false
        var quoteChar = '\''
        var escaped = false
        for (ch in expr) {
            if (escaped) {
                cur.append(ch)
                escaped = false
                continue
            }
            if (inQuote) {
                cur.append(ch)
                if (ch == '\\') escaped = true
                else if (ch == quoteChar) inQuote = false
            } else {
                when (ch) {
                    '\'', '"' -> {
                        inQuote = true
                        quoteChar = ch
                        cur.append(ch)
                    }
                    '(' -> {
                        parenDepth++
                        cur.append(ch)
                    }
                    ')' -> {
                        parenDepth = (parenDepth - 1).coerceAtLeast(0)
                        cur.append(ch)
                    }
                    '+' -> {
                        if (parenDepth == 0) {
                            out.add(cur.toString())
                            cur.setLength(0)
                        } else {
                            cur.append(ch)
                        }
                    }
                    else -> cur.append(ch)
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /**
     * Decodes `atob("...")` Base64 payloads and `\xHH` hex-escaped string literals to uncover
     * obfuscated `.m3u8`, `.mpd`, or `.mp4` stream URLs.
     */
    fun decodeObfuscatedUrls(text: String): List<String> {
        val out = LinkedHashSet<String>()
        val sanitized = stripAdFields(text)

        ATOB_REGEX.findAll(sanitized).forEach { m ->
            val b64 = m.groupValues[1].trim()
            val decoded = decodeBase64Safe(b64) ?: return@forEach
            URL_IN_TEXT_REGEX.findAll(decoded).forEach { urlMatch ->
                val candidate = urlMatch.value.trimEnd(')', ']', '}', ';', ',')
                if (isUsefulMediaUrl(candidate)) out.add(candidate)
            }
        }

        HEX_LITERAL_REGEX.findAll(sanitized).forEach { m ->
            val decoded = decodeHexEscapes(m.groupValues[1])
            URL_IN_TEXT_REGEX.findAll(decoded).forEach { urlMatch ->
                val candidate = urlMatch.value.trimEnd(')', ']', '}', ';', ',')
                if (isUsefulMediaUrl(candidate)) out.add(candidate)
            }
        }

        return out.toList()
    }

    /**
     * Normalizes widescreen cinema resolutions (e.g. `1280x532` or `1280x640` -> `720`,
     * `1920x800` -> `1080`) and portrait resolutions (`720x1280` -> `720`) into canonical
     * quality heights so the format picker and Default Video Quality preference match accurately.
     */
    fun normalizeVideoHeight(width: Int?, height: Int?): Int? =
        com.melmeligy.mediadownloader.core.util.FormatUtils.normalizeQualityHeight(width, height)

    private fun isUsefulMediaUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (AdBlocker.isAd(url) || MediaSniffer.isPreviewOrAdUrl(url)) return false
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains(".mkv")
    }

    private fun decodeBase64Safe(raw: String): String? = runCatching {
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padLen = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(padLen)
        val bytes = Base64.getDecoder().decode(padded)
        String(bytes, StandardCharsets.UTF_8)
    }.getOrNull()

    private fun decodeHexEscapes(raw: String): String =
        Regex("""\\x([0-9a-fA-F]{2})""").replace(raw) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }

    private fun unescapeJs(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")

    private fun skipWhitespace(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun skipBalancedBraces(s: String, openIndex: Int): Int {
        var depth = 0
        var inQuote = false
        var quoteChar = '\''
        var escaped = false
        var i = openIndex
        while (i < s.length) {
            val c = s[i]
            if (escaped) {
                escaped = false
            } else if (inQuote) {
                if (c == '\\') escaped = true
                else if (c == quoteChar) inQuote = false
            } else {
                when (c) {
                    '\'', '"' -> {
                        inQuote = true
                        quoteChar = c
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun readJsString(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length) return null
        val quote = s[start]
        if (quote != '\'' && quote != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    '\\', '\'', '"' -> {
                        sb.append(next)
                        i += 2
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                    }
                    't' -> {
                        sb.append('\t')
                        i += 2
                    }
                    'x' -> {
                        if (i + 3 < s.length) {
                            val code = s.substring(i + 2, i + 4).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 4
                            } else {
                                sb.append(next)
                                i += 2
                            }
                        } else {
                            sb.append(next)
                            i += 2
                        }
                    }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(next)
                                i += 2
                            }
                        } else {
                            sb.append(next)
                            i += 2
                        }
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else if (c == quote) {
                return sb.toString() to (i + 1)
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    private fun readJsStringArray(s: String, openBracket: Int): List<String>? {
        if (openBracket >= s.length || s[openBracket] != '[') return null
        val out = mutableListOf<String>()
        var i = openBracket + 1
        while (i < s.length) {
            i = skipWhitespace(s, i)
            if (i >= s.length) return null
            if (s[i] == ']') return out
            val str = readJsString(s, i) ?: return null
            out.add(str.first)
            i = skipWhitespace(s, str.second)
            if (i < s.length && s[i] == ',') {
                i++
            } else if (i < s.length && s[i] == ']') {
                return out
            } else {
                return null
            }
        }
        return null
    }

    private fun readInt(s: String, start: Int): Pair<Int, Int>? {
        var i = start
        while (i < s.length && s[i].isDigit()) i++
        if (i == start) return null
        val num = s.substring(start, i).toIntOrNull() ?: return null
        return num to i
    }
}
