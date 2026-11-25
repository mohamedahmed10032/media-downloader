package com.melmeligy.mediadownloader.extractor

import android.webkit.CookieManager
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tier 1 generalized streaming-portal and multi-hop embed resolver.
 *
 * Handles:
 * 1. Movie/episode info pages -> automatic `/watch/` & `/download/` sub-page unwrapping.
 * 2. Multi-server tabs (static `data-embed`/`data-url` attributes + AJAX server-switch POST
 *    endpoints such as WordPress `movies2023` `Single/Server.php` and DooPlay `admin-ajax.php`).
 * 3. Concurrent bounded probing of up to 8 servers so a 403/dead Server #0 seamlessly falls
 *    through to working servers.
 * 4. Up to 3-hop recursive iframe unwrapping with cycle protection and strict per-hop
 *    `Referer`/`Origin` propagation (so CDN requests carry the exact embed origin that served
 *    the player, not the outer portal domain).
 * 5. Dean Edwards `p,a,c,k,e,d` JS unpacking, Streamtape `robotlink` resolution, `atob()`/hex
 *    decoding, and ad-stripped PlayerJS/JWPlayer/Plyr/Video.js extraction.
 */
class StreamingPortalResolver(
    private val http: ExtractorHttp,
    private val hlsExtractor: HlsExtractor,
    private val dashExtractor: DashExtractor,
    private val progressTracker: ExtractionProgressTracker = ExtractionProgressTracker()
) {

    data class PortalExtractionResult(
        val streams: List<MediaStream>,
        val posterUrl: String? = null
    )

    private sealed class ServerJob {
        abstract val serverName: String
        abstract val parentPageUrl: String

        data class DirectEmbed(
            val embedUrl: String,
            override val serverName: String,
            override val parentPageUrl: String
        ) : ServerJob()

        data class AjaxServer(
            val endpointUrl: String,
            val formBody: String,
            override val serverName: String,
            override val parentPageUrl: String,
            val method: String = "POST"
        ) : ServerJob()
    }

    internal var topCinemaMirrorOrigins: List<String> = CANONICAL_TOPCINEMA_MIRRORS

    private val myAjaxUrlRegex = Regex(
        """(?:MyAjaxURL|AjaxtURL|themeAjaxUrl|ThemeAjax)\s*=\s*['"]((?:https?:)?//[^'"]+|/[^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val ajaxUrlRegex = Regex(
        """(?:ajaxURL|ajax_url|ajaxUrl)\s*=\s*['"]((?:https?:)?//[^'"]+|/[^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val themeDirRegex = Regex(
        """\bthemeDir\s*=\s*['"]((?:https?:)?//[^'"]+|/[^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val voPostIdRegex = Regex(
        """\bvo_postID\s*=\s*['"]?(\d+)['"]?""",
        RegexOption.IGNORE_CASE
    )
    private val getServerOnClickRegex = Regex(
        """getServer\s*\(\s*[^,)]+,\s*['"]?(\d+)['"]?\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val getServer2OnClickRegex = Regex(
        """getServer2\s*\(\s*[^,)]+,\s*['"]?(\d+)['"]?\s*,\s*['"]?([^'")\s]+)['"]?\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val wpThemeDirRegex = Regex(
        """/wp-content/themes/([A-Za-z0-9_-]+)/""",
        RegexOption.IGNORE_CASE
    )
    private val iframeSrcInHtmlRegex = Regex(
        """<iframe[^>]+?(?:src|data-src)\s*=\s*['"]((?:https?:)?//[^'"\s<>]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val jsIframeRedirectRegex = Regex(
        """(?:iframe\.src|playerFrame\.src|window\.location(?:\.href)?|location\.replace\()\s*=?\s*['"]((?:https?:)?//[^'"\s<>]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val m3u8Regex = Regex("""https?://[^"'\s<>\\]+?\.m3u8[^"'\s<>\\]*""", RegexOption.IGNORE_CASE)
    private val mpdRegex = Regex("""https?://[^"'\s<>\\]+?\.mpd[^"'\s<>\\]*""", RegexOption.IGNORE_CASE)
    private val directVideoRegex = Regex(
        """https?://[^"'\s<>\\]+?\.(?:mp4|webm|mkv|mov)(?:[^"'\s<>\\]*)""",
        RegexOption.IGNORE_CASE
    )

    suspend fun resolvePortalAndEmbeds(
        pageUrl: String,
        html: String,
        doc: Document,
        baseHeaders: Map<String, String>
    ): PortalExtractionResult = withContext(Dispatchers.IO) {
        val visitedEmbeds = ConcurrentHashMap.newKeySet<String>()
        val idCounter = AtomicInteger(0)
        var discoveredPoster: String? = null
        var sawDecoySubPage = false

        // 1. Try servers & iframes directly on the current page first (auto-healing rotated WeCima mains.js key if needed)
        var directJobs = collectServerJobs(pageUrl, html, doc)
        if (directJobs.isEmpty() && maybeRefreshWeCimaKeyForDoc(pageUrl, html, doc, baseHeaders)) {
            directJobs = collectServerJobs(pageUrl, html, doc)
        }
        if (directJobs.isNotEmpty()) {
            progressTracker.update(ExtractionProgressTracker.STAGE_SERVERS)
            val (streams, poster) = executeServerJobs(
                jobs = rankServerJobs(directJobs),
                baseHeaders = baseHeaders,
                visitedEmbeds = visitedEmbeds,
                idCounter = idCounter
            )
            if (poster != null) discoveredPoster = poster
            if (streams.isNotEmpty()) {
                return@withContext PortalExtractionResult(
                    streams = streams,
                    posterUrl = discoveredPoster
                )
            }
        }

        // 2. If this looks like a movie/episode info page, unwrap same-host `/watch/` & `/download/` links
        val subPages = discoverPortalSubPages(pageUrl, doc)
        for (subUrl in subPages) {
            progressTracker.update(ExtractionProgressTracker.STAGE_SERVERS)
            val subHeaders = buildHopHeaders(subUrl, pageUrl, baseHeaders)
            val subHtml = withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                withCancellableHttpCalls(timeoutMs = PAGE_FETCH_TIMEOUT_MS) {
                    runCatching { http.fetchString(subUrl, subHeaders) }.getOrNull()
                }
            }.orEmpty()
            if (subHtml.isBlank()) continue

            val subDoc = Jsoup.parse(subHtml, subUrl)
            var subJobs = collectServerJobs(subUrl, subHtml, subDoc)
            if (subJobs.isEmpty() && maybeRefreshWeCimaKeyForDoc(subUrl, subHtml, subDoc, subHeaders)) {
                subJobs = collectServerJobs(subUrl, subHtml, subDoc)
            }
            if (subJobs.isEmpty()) {
                if (isDecoyAdTrackerPortalPage(subDoc, subHtml)) {
                    sawDecoySubPage = true
                }
                continue
            }

            val (streams, poster) = executeServerJobs(
                jobs = rankServerJobs(subJobs),
                baseHeaders = subHeaders,
                visitedEmbeds = visitedEmbeds,
                idCounter = idCounter
            )
            if (poster != null && discoveredPoster == null) discoveredPoster = poster
            if (streams.isNotEmpty()) {
                return@withContext PortalExtractionResult(
                    streams = streams,
                    posterUrl = discoveredPoster
                )
            }
        }

        // 3. Canonical TopCinema mirror failover when a TopCinema-family clone/decoy page (e.g. topcima.info)
        //    replaces watch servers with ad-tracker links (`free-wd.online`) or has dead/empty servers.
        if (isTopCinemaFamilyUrl(pageUrl) || sawDecoySubPage || isDecoyAdTrackerPortalPage(doc, html)) {
            val mirrorRes = resolveTopCinemaCanonicalMirrors(
                pageUrl = pageUrl,
                baseHeaders = baseHeaders,
                visitedEmbeds = visitedEmbeds,
                idCounter = idCounter
            )
            if (mirrorRes.streams.isNotEmpty()) {
                return@withContext PortalExtractionResult(
                    streams = mirrorRes.streams,
                    posterUrl = discoveredPoster ?: mirrorRes.posterUrl
                )
            }
        }

        PortalExtractionResult(emptyList(), discoveredPoster)
    }

    data class AjaxServerSpec(
        val endpointUrl: String,
        val formBody: String,
        val serverName: String,
        val method: String = "POST"
    )

    /**
     * Fast hybrid entry point used by [HeadlessWebExtractor]: accepts embed/server URLs scraped
     * directly from the live WebView DOM (e.g. `.WatchServersList [data-url]` or `<iframe>`s) and
     * unpacks + verifies them concurrently over OkHttp with the WebView's cookies & User-Agent.
     */
    suspend fun resolveDirectEmbedUrls(
        pageUrl: String,
        embedUrls: Collection<String>,
        baseHeaders: Map<String, String>
    ): PortalExtractionResult = resolveHybridDomAndAjax(
        pageUrl = pageUrl,
        embedUrls = embedUrls,
        ajaxServers = emptyList(),
        baseHeaders = baseHeaders,
        mainsJsUrl = null
    )

    /**
     * Combined hybrid entry point used by [HeadlessWebExtractor]: concurrently resolves BOTH
     * direct embed URLs (`<iframe>`, `[data-url]`, `.openLinkDown[data-href]`, `.List--Download--Wecima--Single`)
     * AND AJAX server tabs (`[data-id][data-server]` -> `Single/Server.php`, `[data-post][data-nume]` ->
     * `admin-ajax.php`) scraped from the live WebView DOM using the WebView's cookies & User-Agent.
     */
    suspend fun resolveHybridDomAndAjax(
        pageUrl: String,
        embedUrls: Collection<String>,
        ajaxServers: Collection<AjaxServerSpec>,
        baseHeaders: Map<String, String>,
        mainsJsUrl: String? = null
    ): PortalExtractionResult = withContext(Dispatchers.IO) {
        if (embedUrls.isEmpty() && ajaxServers.isEmpty()) return@withContext PortalExtractionResult(emptyList())
        val visitedEmbeds = ConcurrentHashMap.newKeySet<String>()
        val idCounter = AtomicInteger(0)
        val seen = HashSet<String>()
        val jobs = mutableListOf<ServerJob>()
        val undecodedEncrypted = mutableListOf<Pair<Int, String>>()

        fun addDecodedEmbed(idx: Int, raw: String): Boolean {
            val decoded = decodePortalEmbedUrl(raw) ?: return false
            val resolved = normalizeEmbedUrl(pageUrl, decoded) ?: return false
            if (isBlockedOrNonPlayerEmbed(resolved)) return false
            if (seen.add(dedupeEmbedKey(resolved))) {
                jobs.add(ServerJob.DirectEmbed(resolved, "Embed ${idx + 1}", pageUrl))
            }
            return true
        }

        embedUrls.forEachIndexed { idx, raw ->
            if (!addDecodedEmbed(idx, raw) && WeCimaLinkDecryptor.looksLikeEncryptedWeCimaValue(raw)) {
                undecodedEncrypted.add(idx to raw)
            }
        }

        // Auto-heal rotated WeCima AES-256-GCM LINK_KEY from mains.js if encrypted attributes failed to open
        if (undecodedEncrypted.isNotEmpty()) {
            val sampleCipher = undecodedEncrypted.first().second
            if (refreshWeCimaLinkKeyFromMainsJs(pageUrl, mainsJsUrl, sampleCipher, baseHeaders)) {
                for ((idx, raw) in undecodedEncrypted) {
                    addDecodedEmbed(idx, raw)
                }
            }
        }

        val seenAjax = HashSet<String>()
        ajaxServers.forEach { spec ->
            if (spec.endpointUrl.startsWith("http://", true) || spec.endpointUrl.startsWith("https://", true)) {
                val isGet = spec.method.equals("GET", ignoreCase = true)
                val key = "${spec.method.uppercase()}|${spec.endpointUrl}|${spec.formBody}"
                if ((isGet || spec.formBody.isNotBlank()) && seenAjax.add(key)) {
                    jobs.add(
                        ServerJob.AjaxServer(
                            endpointUrl = spec.endpointUrl,
                            formBody = spec.formBody,
                            serverName = spec.serverName.ifBlank { "Server" },
                            parentPageUrl = pageUrl,
                            method = if (isGet) "GET" else "POST"
                        )
                    )
                }
            }
        }
        if (jobs.isEmpty()) return@withContext PortalExtractionResult(emptyList())
        val (streams, poster) = executeServerJobs(
            jobs = rankServerJobs(jobs).take(MAX_SERVERS),
            baseHeaders = baseHeaders,
            visitedEmbeds = visitedEmbeds,
            idCounter = idCounter
        )
        PortalExtractionResult(streams = streams, posterUrl = poster)
    }

    private suspend fun maybeRefreshWeCimaKeyForDoc(
        pageUrl: String,
        html: String,
        doc: Document,
        baseHeaders: Map<String, String>
    ): Boolean {
        val sampleEncrypted = doc.select("[data-url], [data-href]")
            .asSequence()
            .map { it.attr("data-url").ifBlank { it.attr("data-href") }.trim() }
            .firstOrNull { WeCimaLinkDecryptor.looksLikeEncryptedWeCimaValue(it) && decodePortalEmbedUrl(it) == null }
            ?: return false
        val mainsUrl = WeCimaLinkDecryptor.extractMainsJsUrl(pageUrl, html)
        return refreshWeCimaLinkKeyFromMainsJs(pageUrl, mainsUrl, sampleEncrypted, baseHeaders)
    }

    internal suspend fun refreshWeCimaLinkKeyFromMainsJs(
        pageUrl: String,
        explicitMainsJsUrl: String?,
        sampleCiphertext: String,
        baseHeaders: Map<String, String>
    ): Boolean {
        val origin = originOf(pageUrl)
        val candidateUrls = buildList {
            explicitMainsJsUrl?.trim()?.takeIf { it.startsWith("http", true) }?.let { add(it) }
            if (origin != null) {
                val defaultPath = "$origin/static/js/mains.js"
                if (defaultPath !in this) add(defaultPath)
            }
        }
        if (candidateUrls.isEmpty()) return false
        val jsHeaders = buildHopHeaders(candidateUrls.first(), pageUrl, baseHeaders)
        for (jsUrl in candidateUrls) {
            val jsText = withTimeoutOrNull(MAINS_JS_FETCH_TIMEOUT_MS) {
                withCancellableHttpCalls(timeoutMs = MAINS_JS_FETCH_TIMEOUT_MS) {
                    runCatching { http.fetchString(jsUrl, jsHeaders) }.getOrNull()
                }
            }.orEmpty()
            if (jsText.isBlank()) continue
            val extractedKey = WeCimaLinkDecryptor.extractLinkKeyFromMainsJs(jsText) ?: continue
            if (WeCimaLinkDecryptor.decodePortalEmbedUrl(sampleCiphertext, extractedKey) != null) {
                WeCimaLinkDecryptor.updateCachedLinkKey(extractedKey)
                com.melmeligy.mediadownloader.core.AppLogger.d(
                    "Portal",
                    "Auto-healed rotated WeCima LINK_KEY from ${UrlUtils.host(jsUrl)}"
                )
                return true
            }
        }
        return false
    }

    /**
     * Ranks server jobs using [HostHealthRegistry] + direct-media / fast-SPA heuristics so
     * proven healthy & low-latency servers resolve first while degraded mirrors run last.
     */
    private fun rankServerJobs(jobs: List<ServerJob>): List<ServerJob> {
        if (jobs.size <= 1) return jobs
        return jobs.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<ServerJob>> { (_, job) -> scoreServerJob(job) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    private fun scoreServerJob(job: ServerJob): Int = when (job) {
        is ServerJob.DirectEmbed -> {
            val url = job.embedUrl
            val ext = UrlUtils.fileExtension(url)?.lowercase()
            val host = UrlUtils.host(url)?.lowercase().orEmpty()
            val typeBoost = when {
                ext in setOf("mp4", "mkv", "webm", "m3u8") -> 25
                host.contains("vidara") || host.contains("byse") || host.contains("filemoon") ||
                    host.contains("moonplayer") || host.contains("uqload") -> 15
                else -> 0
            }
            typeBoost + HostHealthRegistry.shared.scoreForRanking(url)
        }
        is ServerJob.AjaxServer -> HostHealthRegistry.shared.scoreForRanking(job.endpointUrl)
    }

    /**
     * Discovers same-host `/watch/` and `/download/` sub-pages from a movie or episode info page,
     * ordering watch pages first and avoiding unrelated sidebar movie links.
     */
    internal fun discoverPortalSubPages(pageUrl: String, doc: Document): List<String> {
        val currentUri = runCatching { URI(UrlUtils.encodeNonAsciiUrl(pageUrl)) }.getOrNull() ?: return emptyList()
        val currentHost = normalizeHost(currentUri.host) ?: return emptyList()
        val currentPath = normalizePath(currentUri.path)

        val watchLinks = LinkedHashSet<String>()
        val downloadLinks = LinkedHashSet<String>()

        val currentSlug = extractTrailingSlug(currentPath)
        val isEpisodeOrFilmPage = currentPath.startsWith("/episode/") ||
            currentPath.startsWith("/film/") ||
            currentPath.startsWith("/series/") ||
            currentPath.startsWith("/movie/")

        doc.select("a[href]").forEach { el ->
            val rawHref = el.absUrl("href").ifBlank { el.attr("href") }.trim()
            if (!rawHref.startsWith("http://") && !rawHref.startsWith("https://")) return@forEach
            val hrefUri = runCatching { URI(UrlUtils.encodeNonAsciiUrl(rawHref)) }.getOrNull() ?: return@forEach
            val hrefHost = normalizeHost(hrefUri.host) ?: return@forEach
            if (hrefHost != currentHost) return@forEach

            val hrefPath = normalizePath(hrefUri.path)
            if (hrefPath == currentPath || hrefPath.isEmpty()) return@forEach

            val isChildPath = currentPath.length > 1 && hrefPath.startsWith("$currentPath/")
            val hrefSlug = extractTrailingSlug(hrefPath)
            val isPrefixSwappedWatch = isEpisodeOrFilmPage &&
                hrefPath.startsWith("/watch/") &&
                currentSlug.isNotEmpty() &&
                hrefSlug == currentSlug
            val tail = hrefPath.substringAfterLast('/').lowercase()
            val textAndClass = "${el.text()} ${el.className()} ${el.id()}".lowercase()

            val isWatch = isPrefixSwappedWatch ||
                tail in setOf("watch", "play", "stream", "servers", "player") ||
                (isChildPath && (textAndClass.contains("مشاهدة") || textAndClass.contains("مشاهده") || textAndClass.contains("watch")))
            val isDownload = tail in setOf("download", "downloads") ||
                (isChildPath && (textAndClass.contains("تحميل") || textAndClass.contains("download")))

            when {
                isWatch && (isChildPath || isPrefixSwappedWatch || hrefPath.contains("/watch")) -> watchLinks.add(rawHref)
                isDownload && (isChildPath || hrefPath.contains("/download")) -> downloadLinks.add(rawHref)
            }
        }

        return (watchLinks.take(1) + downloadLinks.take(1)).distinct()
    }

    private fun extractTrailingSlug(normalizedPath: String): String {
        val clean = normalizedPath.trim('/')
        if (clean.isEmpty()) return ""
        val firstSlash = clean.indexOf('/')
        return if (firstSlash > 0 && firstSlash + 1 < clean.length) {
            clean.substring(firstSlash + 1)
        } else {
            clean
        }
    }

    private fun collectServerJobs(
        pageUrl: String,
        html: String,
        doc: Document
    ): List<ServerJob> {
        val jobs = mutableListOf<ServerJob>()
        val seenDirectUrls = HashSet<String>()

        fun addDirect(rawUrl: String, serverName: String) {
            val decoded = decodePortalEmbedUrl(rawUrl) ?: return
            val resolved = normalizeEmbedUrl(pageUrl, decoded) ?: return
            if (isBlockedOrNonPlayerEmbed(resolved)) return
            if (seenDirectUrls.add(dedupeEmbedKey(resolved))) {
                jobs.add(ServerJob.DirectEmbed(resolved, serverName, pageUrl))
            }
        }

        // 1. Existing iframes on page
        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEachIndexed { idx, el ->
            val raw = el.absUrl("src")
                .ifBlank { el.absUrl("data-src") }
                .ifBlank { el.absUrl("data-lazy-src") }
                .ifBlank { el.attr("src") }
            if (raw.isNotBlank()) {
                addDirect(raw, el.attr("title").ifBlank { "Server ${idx + 1}" })
            }
        }

        // 2. Static server buttons/tabs with direct, Base64, or WeCima-obfuscated embed URLs in data-* attributes
        val directAttrSelectors = listOf(
            "data-embed", "data-watch", "data-url", "data-iframe", "data-link", "data-player", "data-src"
        )
        for (attr in directAttrSelectors) {
            doc.select("[$attr]").forEach { el ->
                if (el.tagName().equals("img", ignoreCase = true) ||
                    el.tagName().equals("script", ignoreCase = true) ||
                    el.tagName().equals("link", ignoreCase = true)
                ) return@forEach
                val decoded = decodePortalEmbedUrl(el.attr(attr).trim()) ?: return@forEach
                if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true) || decoded.startsWith("//")) {
                    val label = el.text().trim().take(32).ifBlank { "Server" }
                    addDirect(decoded, label)
                }
            }
        }

        // 2b. WeCima direct download links (.List--Download--Wecima--Single a[href]) & obfuscated .openLinkDown[data-href]
        doc.select(".List--Download--Wecima--Single a[href], a.downloadsLink[href]").forEach { a ->
            val rawHref = a.attr("href").trim().ifBlank { a.absUrl("href").trim() }
            val decoded = decodePortalEmbedUrl(rawHref) ?: return@forEach
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true) || decoded.startsWith("//")) {
                val qualityText = a.selectFirst("quality")?.text()?.trim().orEmpty()
                val label = qualityText.ifBlank { a.text().trim() }.take(32).ifBlank { "WeCima Download" }
                addDirect(decoded, label)
            }
        }
        doc.select(".openLinkDown[data-href], [data-href]").forEach { el ->
            val rawHref = el.attr("data-href").trim()
            if (rawHref.isEmpty()) return@forEach
            val decoded = decodePortalEmbedUrl(rawHref) ?: return@forEach
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true) || decoded.startsWith("//")) {
                val qualityText = el.selectFirst("quality")?.text()?.trim().orEmpty()
                val label = qualityText.ifBlank { el.text().trim() }.take(32).ifBlank { "WeCima Download" }
                addDirect(decoded, label)
            }
        }

        // 3. Dynamic AJAX server tabs: Pattern A (`[data-id][data-server]` -> `MyAjaxURL + Single/Server.php`)
        val serverTabs = doc.select("[data-id][data-server]")
        if (serverTabs.isNotEmpty()) {
            val ajaxEndpoint = resolveServerAjaxEndpoint(pageUrl, html)
            if (ajaxEndpoint != null) {
                val seenPairs = HashSet<String>()
                serverTabs.forEach { tab ->
                    val dataId = tab.attr("data-id").trim()
                    val dataServer = tab.attr("data-server").trim()
                    if (dataId.isEmpty() || dataServer.isEmpty()) return@forEach
                    val pairKey = "$dataId|$dataServer"
                    if (!seenPairs.add(pairKey)) return@forEach

                    // Skip active server #0 if its iframe was already preloaded on the page
                    if (tab.hasClass("active") && seenDirectUrls.isNotEmpty()) return@forEach

                    val serverName = tab.text().trim().take(32).ifBlank { "Server $dataServer" }
                    val body = "id=${urlEncode(dataId)}&i=${urlEncode(dataServer)}"
                    jobs.add(ServerJob.AjaxServer(ajaxEndpoint, body, serverName, pageUrl))
                }
            }
        }

        // 4. Dynamic AJAX server tabs: Pattern B (DooPlay / Torofilm `[data-post][data-nume]`)
        val dooTabs = doc.select("[data-post][data-nume]")
        if (dooTabs.isNotEmpty()) {
            val origin = originOf(pageUrl)
            if (origin != null) {
                val wpAjax = "$origin/wp-admin/admin-ajax.php"
                val seenPairs = HashSet<String>()
                dooTabs.forEach { tab ->
                    val post = tab.attr("data-post").trim()
                    val nume = tab.attr("data-nume").trim()
                    val type = tab.attr("data-type").trim().ifBlank { "movie" }
                    if (post.isEmpty() || nume.isEmpty() || nume.equals("trailer", ignoreCase = true)) return@forEach
                    if (!seenPairs.add("$post|$nume|$type")) return@forEach
                    val serverName = tab.text().trim().take(32).ifBlank { "Server $nume" }
                    val body = "action=doo_player_ajax&post=${urlEncode(post)}&nume=${urlEncode(nume)}&type=${urlEncode(type)}"
                    jobs.add(ServerJob.AjaxServer(wpAjax, body, serverName, pageUrl))
                }
            }
        }

        // 4b. Dynamic AJAX server tabs: Pattern C (Cinema theme `onclick="getServer(this.id, <video>)"` -> GET `$themeDir/temp/ajax/iframe.php`)
        val cinemaTabs = doc.select("[onclick*=getServer]")
        if (cinemaTabs.isNotEmpty()) {
            val rawThemeDir = themeDirRegex.find(html)?.groupValues?.getOrNull(1)
            val themeDir = rawThemeDir?.let { normalizeEmbedUrl(pageUrl, it)?.trimEnd('/') }
                ?: wpThemeDirRegex.find(html)?.groupValues?.getOrNull(1)?.let { themeName ->
                    originOf(pageUrl)?.let { "$it/wp-content/themes/$themeName" }
                }
            val postId = voPostIdRegex.find(html)?.groupValues?.getOrNull(1)?.trim()
                ?: doc.selectFirst("[data-id]")?.attr("data-id")?.trim()?.takeIf { it.all(Char::isDigit) }
            if (!themeDir.isNullOrBlank() && !postId.isNullOrBlank()) {
                val seenCinema = HashSet<String>()
                cinemaTabs.forEach { tab ->
                    val onClick = tab.attr("onclick").trim()
                    val m2 = getServer2OnClickRegex.find(onClick)
                    val m1 = if (m2 == null) getServerOnClickRegex.find(onClick) else null
                    val endpointUrl = when {
                        m2 != null -> {
                            val video = m2.groupValues[1].trim()
                            val serverId = m2.groupValues[2].trim()
                            "$themeDir/temp/ajax/iframe2.php?id=${urlEncode(postId)}&video=${urlEncode(video)}&serverId=${urlEncode(serverId)}"
                        }
                        m1 != null -> {
                            val video = m1.groupValues[1].trim()
                            "$themeDir/temp/ajax/iframe.php?id=${urlEncode(postId)}&video=${urlEncode(video)}"
                        }
                        else -> null
                    } ?: return@forEach
                    if (!seenCinema.add(endpointUrl)) return@forEach
                    val serverName = tab.text().trim().take(32).ifBlank { "Server ${seenCinema.size}" }
                    jobs.add(
                        ServerJob.AjaxServer(
                            endpointUrl = endpointUrl,
                            formBody = "",
                            serverName = serverName,
                            parentPageUrl = pageUrl,
                            method = "GET"
                        )
                    )
                }
            }
        }

        // 5. External download mirror links on `/download/` pages (when no watch tabs were found)
        if (jobs.isEmpty() && pageUrl.lowercase().contains("/download")) {
            doc.select(".DownloadBlock a[href], .DownloadsList a[href], .download-links a[href], a[href*=\"/d/\"], a[href*=\"/embed-\"]")
                .forEach { a ->
                    val href = a.absUrl("href").trim()
                    if (href.startsWith("http") &&
                        normalizeHost(runCatching { URI(href).host }.getOrNull()) !=
                        normalizeHost(runCatching { URI(UrlUtils.encodeNonAsciiUrl(pageUrl)).host }.getOrNull())
                    ) {
                        addDirect(href, a.text().trim().take(32).ifBlank { "Mirror" })
                    }
                }
        }

        return jobs.take(MAX_SERVERS)
    }

    internal fun resolveServerAjaxEndpoint(pageUrl: String, html: String): String? {
        fun normalizeEndpoint(rawBase: String): String? {
            val resolved = normalizeEmbedUrl(pageUrl, rawBase)?.trimEnd('/') ?: return null
            return if (resolved.endsWith(".php", ignoreCase = true)) resolved else "$resolved/Single/Server.php"
        }

        val myCandidates = myAjaxUrlRegex.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.let(::normalizeEndpoint) }
            .distinct()
            .toList()
        myCandidates.firstOrNull { it.contains("/wp-content/themes/", ignoreCase = true) }?.let { return it }
        myCandidates.firstOrNull()?.let { return it }

        val ajaxCandidates = ajaxUrlRegex.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.let(::normalizeEndpoint) }
            .distinct()
            .toList()
        ajaxCandidates.firstOrNull { it.contains("/wp-content/themes/", ignoreCase = true) }?.let { return it }
        ajaxCandidates.firstOrNull()?.let { return it }

        val origin = originOf(pageUrl) ?: return null
        val themeName = wpThemeDirRegex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (themeName != null && (themeName.contains("movie", ignoreCase = true) || themeName.contains("cima", ignoreCase = true) || themeName.contains("top", ignoreCase = true))) {
            return "$origin/wp-content/themes/$themeName/Ajaxat/Single/Server.php"
        }
        return "$origin/Ajaxat/Single/Server.php"
    }

    private suspend fun executeServerJobs(
        jobs: List<ServerJob>,
        baseHeaders: Map<String, String>,
        visitedEmbeds: MutableSet<String>,
        idCounter: AtomicInteger
    ): Pair<List<MediaStream>, String?> = coroutineScope {
        if (jobs.isEmpty()) return@coroutineScope emptyList<MediaStream>() to null

        val semaphore = Semaphore(MAX_CONCURRENT_SERVERS)
        val outcomeChannel = Channel<PortalExtractionResult>(capacity = jobs.size)

        val runningJobs = jobs.map { job ->
            launch(Dispatchers.IO) {
                var outcome = PortalExtractionResult(emptyList())
                val jobStartMs = System.currentTimeMillis()
                val trackedHostUrl = when (job) {
                    is ServerJob.DirectEmbed -> job.embedUrl
                    is ServerJob.AjaxServer -> job.endpointUrl
                }
                val perJobTimeoutMs = if (job is ServerJob.DirectEmbed && HostHealthRegistry.shared.isDegraded(job.embedUrl)) {
                    DEGRADED_SERVER_PROBE_TIMEOUT_MS
                } else {
                    SERVER_PROBE_TIMEOUT_MS
                }
                try {
                    semaphore.withPermit {
                        outcome = withTimeoutOrNull(perJobTimeoutMs) {
                            withCancellableHttpCalls(timeoutMs = perJobTimeoutMs) {
                                when (job) {
                                    is ServerJob.DirectEmbed -> {
                                        progressTracker.update(ExtractionProgressTracker.STAGE_EMBEDS)
                                        resolveEmbedRecursive(
                                            embedUrl = job.embedUrl,
                                            parentUrl = job.parentPageUrl,
                                            baseHeaders = baseHeaders,
                                            serverName = job.serverName,
                                            depth = 1,
                                            visited = visitedEmbeds,
                                            idCounter = idCounter
                                        )
                                    }
                                    is ServerJob.AjaxServer -> {
                                        val isGet = job.method.equals("GET", ignoreCase = true)
                                        val ajaxHeaders = LinkedHashMap<String, String>(baseHeaders).apply {
                                            put("Referer", job.parentPageUrl)
                                            originOf(job.parentPageUrl)?.let { put("Origin", it) }
                                            put("X-Requested-With", "XMLHttpRequest")
                                        }
                                        var resp = try {
                                            if (isGet) {
                                                http.fetchString(job.endpointUrl, ajaxHeaders)
                                            } else {
                                                http.postForm(job.endpointUrl, job.formBody, ajaxHeaders)
                                            }
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (_: Exception) {
                                            ""
                                        }
                                        if (!isGet && resp.isBlank() && job.endpointUrl.endsWith("/Ajaxat/Single/Server.php", ignoreCase = true)) {
                                            val origin = originOf(job.endpointUrl) ?: originOf(job.parentPageUrl)
                                            val altEndpoint = when {
                                                origin == null -> null
                                                job.endpointUrl.contains("/wp-content/themes/", ignoreCase = true) ->
                                                    "$origin/Ajaxat/Single/Server.php"
                                                else -> "$origin/wp-content/themes/movies2023/Ajaxat/Single/Server.php"
                                            }
                                            if (altEndpoint != null && altEndpoint != job.endpointUrl) {
                                                resp = try {
                                                    http.postForm(altEndpoint, job.formBody, ajaxHeaders)
                                                } catch (ce: CancellationException) {
                                                    throw ce
                                                } catch (_: Exception) {
                                                    ""
                                                }
                                            }
                                        }
                                        val embedUrl = extractEmbedUrlFromAjaxResponse(resp, job.parentPageUrl)
                                            ?: return@withCancellableHttpCalls PortalExtractionResult(emptyList())
                                        if (isBlockedOrNonPlayerEmbed(embedUrl)) {
                                            return@withCancellableHttpCalls PortalExtractionResult(emptyList())
                                        }
                                        progressTracker.update(ExtractionProgressTracker.STAGE_EMBEDS)
                                        val embedStartMs = System.currentTimeMillis()
                                        val res = resolveEmbedRecursive(
                                            embedUrl = embedUrl,
                                            parentUrl = job.parentPageUrl,
                                            baseHeaders = baseHeaders,
                                            serverName = job.serverName,
                                            depth = 1,
                                            visited = visitedEmbeds,
                                            idCounter = idCounter
                                        )
                                        if (res.streams.isNotEmpty()) {
                                            HostHealthRegistry.shared.recordSuccess(embedUrl, System.currentTimeMillis() - embedStartMs)
                                        } else {
                                            HostHealthRegistry.shared.recordFailure(embedUrl)
                                        }
                                        res
                                    }
                                }
                            }
                        } ?: PortalExtractionResult(emptyList())
                    }
                    if (job is ServerJob.DirectEmbed) {
                        if (outcome.streams.isNotEmpty()) {
                            HostHealthRegistry.shared.recordSuccess(trackedHostUrl, System.currentTimeMillis() - jobStartMs)
                        } else {
                            HostHealthRegistry.shared.recordFailure(trackedHostUrl)
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    if (job is ServerJob.DirectEmbed) {
                        HostHealthRegistry.shared.recordFailure(trackedHostUrl)
                    }
                    outcome = PortalExtractionResult(emptyList())
                } finally {
                    outcomeChannel.trySend(outcome)
                }
            }
        }

        val collectedStreams = mutableListOf<MediaStream>()
        var discoveredPoster: String? = null
        val totalDeadlineMs = System.currentTimeMillis() + PORTAL_TOTAL_BUDGET_MS
        var firstWinDeadlineMs: Long? = null
        var remaining = jobs.size

        try {
            while (remaining > 0) {
                val activeDeadline = if (firstWinDeadlineMs != null) {
                    minOf(totalDeadlineMs, firstWinDeadlineMs)
                } else {
                    totalDeadlineMs
                }
                val waitMs = activeDeadline - System.currentTimeMillis()
                if (waitMs <= 0L) break

                val outcome = withTimeoutOrNull(waitMs) {
                    outcomeChannel.receiveCatching().getOrNull()
                } ?: break

                remaining--
                if (outcome.posterUrl != null && discoveredPoster == null) {
                    discoveredPoster = outcome.posterUrl
                }
                if (outcome.streams.isNotEmpty()) {
                    collectedStreams += outcome.streams
                    val distinctHeights = collectedStreams.mapNotNull { it.height }.distinct()
                    val hasHdOrBetter = distinctHeights.any { it >= 720 }
                    val hasMultiQualityHls = collectedStreams.any { it.isHls } && distinctHeights.size >= 2
                    val hasDirectProgressive = collectedStreams.any { !it.isHls }
                    if (hasMultiQualityHls && (hasDirectProgressive || remaining == 0)) {
                        break
                    }
                    if (firstWinDeadlineMs == null) {
                        if (remaining == 0) break
                        val graceMs = when {
                            hasMultiQualityHls || (distinctHeights.size >= 2 && hasHdOrBetter) -> 250L
                            distinctHeights.isEmpty() -> UNKNOWN_HEIGHT_MERGE_GRACE_MS
                            else -> SIBLING_MERGE_GRACE_MS
                        }
                        firstWinDeadlineMs = System.currentTimeMillis() + graceMs
                    }
                }
            }
        } finally {
            runningJobs.forEach { it.cancel() }
            outcomeChannel.close()
        }

        collectedStreams to discoveredPoster
    }

    internal fun extractEmbedUrlFromAjaxResponse(responseBody: String, pageUrl: String): String? {
        val trimmed = responseBody.trim()
        if (trimmed.isEmpty()) return null

        // JSON response (e.g. DooPlay `{"embed_url": "https://..."}` or `{"url": "..."}`)
        if (trimmed.startsWith("{")) {
            runCatching {
                val obj = JSONObject(trimmed)
                val raw = obj.optString("embed_url")
                    .ifBlank { obj.optString("url") }
                    .ifBlank { obj.optString("iframe") }
                    .ifBlank { obj.optString("src") }
                if (raw.isNotBlank()) {
                    iframeSrcInHtmlRegex.find(raw)?.groupValues?.getOrNull(1)?.let {
                        return normalizeEmbedUrl(pageUrl, unwrapPlayRedirectUrl(it))
                    }
                    return normalizeEmbedUrl(pageUrl, unwrapPlayRedirectUrl(raw))
                }
            }
        }

        // HTML `<iframe src="...">` snippet (TopCinema / movies2023 `Single/Server.php`)
        iframeSrcInHtmlRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let {
            return normalizeEmbedUrl(pageUrl, unwrapPlayRedirectUrl(it))
        }

        // Raw URL response
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("//")) {
            return normalizeEmbedUrl(pageUrl, unwrapPlayRedirectUrl(trimmed.lineSequence().first().trim()))
        }

        return null
    }

    internal suspend fun resolveEmbedRecursive(
        embedUrl: String,
        parentUrl: String,
        baseHeaders: Map<String, String>,
        serverName: String,
        depth: Int,
        visited: MutableSet<String>,
        idCounter: AtomicInteger
    ): PortalExtractionResult = coroutineScope {
        if (depth > MAX_EMBED_HOPS) return@coroutineScope PortalExtractionResult(emptyList())
        val dedupe = dedupeEmbedKey(embedUrl)
        if (!visited.add(dedupe)) return@coroutineScope PortalExtractionResult(emptyList())

        // Direct media URL inside iframe/mirror (e.g. .m3u8 or WeCima .List--Download--Wecima--Single .mp4 link)
        val directExt = UrlUtils.fileExtension(embedUrl)?.lowercase()
        val directHeadersBase = if (sameRegistrableDomain(parentUrl, embedUrl)) {
            baseHeaders
        } else {
            baseHeaders.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        }
        if (directExt == "m3u8") {
            val h = buildStreamHeadersForHost(parentUrl, directHeadersBase)
            val expanded = runCatching { hlsExtractor.extractVerified(embedUrl, h) }.getOrNull()
            return@coroutineScope PortalExtractionResult(expanded?.streams.orEmpty())
        }
        if (directExt in setOf("mp4", "mkv", "webm", "mov", "m4v")) {
            val h = buildStreamHeadersForHost(parentUrl, directHeadersBase)
            val directStreams = materializeCandidate(
                cand = JsUnpacker.ExtractedSource(url = embedUrl, label = serverName),
                headers = h,
                serverName = serverName,
                idCounter = idCounter
            )
            return@coroutineScope PortalExtractionResult(directStreams)
        }

        // Fast-path native API resolution for SPA-only embed hosts (Vidara & Byse/Filemoon)
        val preHost = UrlUtils.host(embedUrl)?.lowercase().orEmpty()
        if (preHost.contains("vidara")) {
            val preHeaders = buildStreamHeadersForHost(embedUrl, baseHeaders)
            val vidaraSources = resolveVidaraApi(embedUrl, preHeaders)
            if (vidaraSources.isNotEmpty()) {
                progressTracker.update(ExtractionProgressTracker.STAGE_QUALITIES)
                val resolvedStreams = vidaraSources.map { cand ->
                    async(Dispatchers.IO) { materializeCandidate(cand, preHeaders, serverName, idCounter) }
                }.awaitAll().flatten()
                if (resolvedStreams.isNotEmpty()) {
                    return@coroutineScope PortalExtractionResult(resolvedStreams)
                }
            }
        } else if (preHost.contains("byse") || preHost.contains("filemoon") || preHost.contains("moonplayer")) {
            val preHeaders = buildStreamHeadersForHost(embedUrl, baseHeaders)
            val byseSources = resolveByseFilemoonApi(embedUrl, preHeaders)
            if (byseSources.isNotEmpty()) {
                progressTracker.update(ExtractionProgressTracker.STAGE_QUALITIES)
                val resolvedStreams = byseSources.map { cand ->
                    async(Dispatchers.IO) { materializeCandidate(cand, preHeaders, serverName, idCounter) }
                }.awaitAll().flatten()
                if (resolvedStreams.isNotEmpty()) {
                    return@coroutineScope PortalExtractionResult(resolvedStreams)
                }
            }
        }

        val fetchHeaders = buildHopHeaders(embedUrl, parentUrl, baseHeaders)
        val fetchedEmbed = withTimeoutOrNull(EMBED_HOP_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = EMBED_HOP_TIMEOUT_MS) {
                val withCookies = runCatching { http.fetchHtmlWithCookies(embedUrl, fetchHeaders) }.getOrNull()
                if (withCookies != null && withCookies.html.isNotBlank()) {
                    withCookies
                } else if (withCookies != null && withCookies.finalUrl.isBlank()) {
                    runCatching { http.fetchString(embedUrl, fetchHeaders) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ExtractorHttp.HtmlFetchResult(html = it, cookieHeader = null, finalUrl = embedUrl) }
                } else {
                    null
                }
            }
        } ?: return@coroutineScope PortalExtractionResult(emptyList())
        val embedHtml = fetchedEmbed.html
        if (embedHtml.isBlank()) return@coroutineScope PortalExtractionResult(emptyList())

        // Preserve the post-redirect mirror URL (e.g. earnvids.pro/e/x -> dintezuvio.com/e/x)
        // and any Set-Cookie tokens issued during the embed redirect chain, while NEVER leaking
        // outer portal cookies (e.g. topcinemaa.one cf_clearance) onto unrelated cross-origin embeds.
        val effectiveEmbedUrl = fetchedEmbed.finalUrl.takeIf { it.startsWith("http", ignoreCase = true) } ?: embedUrl
        val inheritedParentCookie = if (sameRegistrableDomain(parentUrl, effectiveEmbedUrl)) {
            baseHeaders["Cookie"]?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val embedScopedCookie = when {
            inheritedParentCookie != null && !fetchedEmbed.cookieHeader.isNullOrBlank() ->
                "$inheritedParentCookie; ${fetchedEmbed.cookieHeader}"
            !fetchedEmbed.cookieHeader.isNullOrBlank() -> fetchedEmbed.cookieHeader
            else -> inheritedParentCookie
        }
        val mergedBaseHeaders = LinkedHashMap<String, String>(baseHeaders).apply {
            remove("Cookie")
            if (!embedScopedCookie.isNullOrBlank()) {
                put("Cookie", embedScopedCookie)
            }
        }

        // Critical: streams loaded BY this embed player must carry the POST-REDIRECT EMBED URL as Referer & Origin!
        val streamHeaders = buildStreamHeadersForHost(effectiveEmbedUrl, mergedBaseHeaders)

        val unpackedBlocks = JsUnpacker.unpackAll(embedHtml)
        com.melmeligy.mediadownloader.core.AppLogger.d(
            "Portal",
            "Hop depth=$depth host=${UrlUtils.host(effectiveEmbedUrl)} html=${embedHtml.length}B unpacked=${unpackedBlocks.size}"
        )
        val combinedScriptText = buildString {
            append(embedHtml)
            for (block in unpackedBlocks) {
                append('\n')
                append(block)
            }
        }

        val playerCfg = JsUnpacker.extractPlayerConfigs(combinedScriptText, baseUrl = effectiveEmbedUrl)
        val durationHeaders = if (playerCfg.durationSec != null && playerCfg.durationSec > 0L) {
            streamHeaders + ("X-Internal-Duration-Seconds" to playerCfg.durationSec.toString())
        } else {
            streamHeaders
        }

        val candidates = LinkedHashMap<String, JsUnpacker.ExtractedSource>()
        fun addCandidate(
            url: String,
            label: String = "",
            width: Int? = null,
            height: Int? = null,
            isHlsHint: Boolean = false
        ) {
            val resolved = UrlUtils.resolveUrl(effectiveEmbedUrl, url.trim())
            val cleaned = MediaSniffer.cleanMediaUrl(resolved)
            if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) return
            if (AdBlocker.isAd(cleaned) || MediaSniffer.isPreviewOrAdUrl(cleaned)) return
            if (MediaSniffer.isHexCipherUrl(cleaned)) return
            val key = MediaSniffer.dedupeKey(cleaned)
            val normH = JsUnpacker.normalizeVideoHeight(width, height) ?: UrlUtils.inferResolution(cleaned, label)
            val effectiveHlsHint = isHlsHint || cleaned.contains(".m3u8", ignoreCase = true) || MediaSniffer.isDisguisedHlsUrl(cleaned)
            val existing = candidates[key]
            if (existing == null || (existing.height == null && normH != null) || (!existing.isHlsHint && effectiveHlsHint)) {
                candidates[key] = JsUnpacker.ExtractedSource(
                    url = cleaned,
                    label = label.ifBlank { existing?.label.orEmpty() },
                    width = width ?: existing?.width,
                    height = normH ?: existing?.height,
                    isHlsHint = effectiveHlsHint || (existing?.isHlsHint == true)
                )
            }
        }

        // 1. PlayerJS / JWPlayer / Plyr / Clappr / Video.js / links={hls4,hls2,hls3} / MDCore.wurl sources
        playerCfg.sources.forEach { src ->
            addCandidate(src.url, src.label, src.width, src.height, src.isHlsHint)
        }

        // 2. Streamtape robotlink / ideoolink
        JsUnpacker.extractStreamtapeUrls(combinedScriptText).forEach { stUrl ->
            addCandidate(stUrl, label = "")
        }

        // 3. Base64 atob() & hex-escaped URLs
        JsUnpacker.decodeObfuscatedUrls(combinedScriptText).forEach { obfUrl ->
            addCandidate(obfUrl, label = "")
        }

        // 3b. Doodstream /pass_md5/<hash>/<token> dynamic stream token endpoint
        resolveDoodstreamPassMd5(effectiveEmbedUrl, combinedScriptText, streamHeaders)?.let { doodUrl ->
            addCandidate(doodUrl, label = serverName.ifBlank { "Doodstream" })
        }

        // 4. HTML5 <video>/<source> elements inside the embed DOM
        val embedDoc = Jsoup.parse(embedHtml, effectiveEmbedUrl)
        embedDoc.select("video source[src], video source[data-src], video[src], video[data-src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.absUrl("data-src") }.ifBlank { el.attr("src") }
            val qLabel = el.attr("label").ifBlank { el.attr("res") }.ifBlank { el.attr("size") }
            if (src.isNotBlank()) addCandidate(src, label = qLabel)
        }

        // 5. Generic media URL scan on ad-stripped text if structured extractors found nothing
        if (candidates.isEmpty()) {
            val stripped = JsUnpacker.stripAdFields(combinedScriptText)
                .replace("\\/", "/")
                .replace("\\u002F", "/", ignoreCase = true)
            m3u8Regex.findAll(stripped).take(3).forEach { addCandidate(it.value, isHlsHint = true) }
            mpdRegex.findAll(stripped).take(2).forEach { addCandidate(it.value) }
            directVideoRegex.findAll(stripped).take(4).forEach { addCandidate(it.value) }
        }

        // 5b. SPA API fallback triggered by HTML signatures (also works on post-redirect mirrors & loopback)
        if (candidates.isEmpty()) {
            val effHost = UrlUtils.host(effectiveEmbedUrl)?.lowercase().orEmpty()
            val looksLikeVidara = effHost.contains("vidara") ||
                combinedScriptText.contains("/api/stream", ignoreCase = true)
            val looksLikeByse = effHost.contains("byse") ||
                effHost.contains("filemoon") ||
                combinedScriptText.contains("/api/videos/", ignoreCase = true)
            if (looksLikeVidara) {
                resolveVidaraApi(effectiveEmbedUrl, streamHeaders).forEach { src ->
                    addCandidate(src.url, src.label, src.width, src.height, src.isHlsHint)
                }
            }
            if (candidates.isEmpty() && looksLikeByse) {
                resolveByseFilemoonApi(effectiveEmbedUrl, streamHeaders).forEach { src ->
                    addCandidate(src.url, src.label, src.width, src.height, src.isHlsHint)
                }
            }
        }

        val resolvedPoster = HtmlThumbnailExtractor.extractThumbnail(
            doc = embedDoc,
            pageUrl = effectiveEmbedUrl,
            preferredThumb = playerCfg.posterUrl
        )

        // Materialize & verify streams found in this embed hop
        if (candidates.isNotEmpty()) {
            progressTracker.update(ExtractionProgressTracker.STAGE_QUALITIES)
            val resolvedStreams = candidates.values.take(6).map { cand ->
                async(Dispatchers.IO) {
                    materializeCandidate(cand, durationHeaders, serverName, idCounter)
                }
            }.awaitAll().flatten()

            com.melmeligy.mediadownloader.core.AppLogger.d(
                "Portal",
                "Hop depth=$depth host=${UrlUtils.host(effectiveEmbedUrl)} candidates=${candidates.size} verified=${resolvedStreams.size}"
            )
            if (resolvedStreams.isNotEmpty()) {
                return@coroutineScope PortalExtractionResult(
                    streams = resolvedStreams,
                    posterUrl = resolvedPoster
                )
            }
        }

        // 6. No streams yet -> follow up to 2 nested iframes/JS redirects (hops 2..3)
        if (depth < MAX_EMBED_HOPS) {
            val nestedUrls = LinkedHashSet<String>()
            embedDoc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { el ->
                val raw = el.absUrl("src")
                    .ifBlank { el.absUrl("data-src") }
                    .ifBlank { el.absUrl("data-lazy-src") }
                    .ifBlank { el.attr("src") }
                normalizeEmbedUrl(effectiveEmbedUrl, raw)?.let { u ->
                    if (!isBlockedOrNonPlayerEmbed(u)) nestedUrls.add(u)
                }
            }
            jsIframeRedirectRegex.findAll(combinedScriptText).forEach { m ->
                normalizeEmbedUrl(effectiveEmbedUrl, m.groupValues[1])?.let { u ->
                    if (!isBlockedOrNonPlayerEmbed(u)) nestedUrls.add(u)
                }
            }
            // StreamWish / hgcloud lightweight JS "loading" redirect shells -> try known working mirrors
            val effHost = UrlUtils.host(effectiveEmbedUrl)?.lowercase().orEmpty()
            if (nestedUrls.isEmpty() &&
                embedHtml.length < 4096 &&
                embedHtml.contains("loading", ignoreCase = true) &&
                (effHost.contains("hgcloud") || effHost.contains("streamwish"))
            ) {
                val parsedUri = runCatching { URI(UrlUtils.encodeNonAsciiUrl(effectiveEmbedUrl)) }.getOrNull()
                val pathAndQuery = buildString {
                    append(parsedUri?.rawPath.orEmpty())
                    if (!parsedUri?.rawQuery.isNullOrBlank()) append('?').append(parsedUri?.rawQuery)
                }
                if (pathAndQuery.startsWith("/")) {
                    for (mirror in STREAMWISH_FALLBACK_MIRRORS) {
                        if (!effHost.equals(mirror, ignoreCase = true)) {
                            nestedUrls.add("https://$mirror$pathAndQuery")
                        }
                    }
                }
            }

            val nestedList = nestedUrls.take(2)
            if (nestedList.size == 1) {
                val deeper = resolveEmbedRecursive(
                    embedUrl = nestedList.first(),
                    parentUrl = effectiveEmbedUrl,
                    baseHeaders = mergedBaseHeaders,
                    serverName = serverName,
                    depth = depth + 1,
                    visited = visited,
                    idCounter = idCounter
                )
                if (deeper.streams.isNotEmpty()) {
                    return@coroutineScope deeper.copy(
                        posterUrl = deeper.posterUrl ?: resolvedPoster
                    )
                }
            } else if (nestedList.size > 1) {
                val nestedCh = Channel<PortalExtractionResult>(capacity = nestedList.size)
                val nestedJobs = nestedList.map { nested ->
                    launch(Dispatchers.IO) {
                        val res = try {
                            withCancellableHttpCalls(timeoutMs = EMBED_HOP_TIMEOUT_MS) {
                                resolveEmbedRecursive(
                                    embedUrl = nested,
                                    parentUrl = effectiveEmbedUrl,
                                    baseHeaders = mergedBaseHeaders,
                                    serverName = serverName,
                                    depth = depth + 1,
                                    visited = visited,
                                    idCounter = idCounter
                                )
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            PortalExtractionResult(emptyList())
                        }
                        nestedCh.trySend(res)
                    }
                }
                var winner: PortalExtractionResult? = null
                try {
                    for (i in nestedList.indices) {
                        val res = nestedCh.receiveCatching().getOrNull() ?: break
                        if (res.streams.isNotEmpty()) {
                            winner = res
                            break
                        }
                    }
                } finally {
                    nestedJobs.forEach { it.cancel() }
                    nestedCh.close()
                }
                if (winner != null) {
                    return@coroutineScope winner!!.copy(
                        posterUrl = winner!!.posterUrl ?: resolvedPoster
                    )
                }
            }
        }

        PortalExtractionResult(emptyList(), resolvedPoster)
    }

    private suspend fun materializeCandidate(
        cand: JsUnpacker.ExtractedSource,
        headers: Map<String, String>,
        serverName: String,
        idCounter: AtomicInteger
    ): List<MediaStream> {
        val url = cand.url
        val taggedHeaders = LinkedHashMap<String, String>(headers).apply {
            put("X-Internal-Authoritative", "true")
            if (serverName.isNotBlank()) put("X-Internal-Server-Name", serverName)
        }

        val isHlsCandidate = url.contains(".m3u8", ignoreCase = true) ||
            cand.isHlsHint ||
            MediaSniffer.isDisguisedHlsUrl(url)
        if (isHlsCandidate) {
            val expanded = runCatching {
                hlsExtractor.extractVerified(url, taggedHeaders, cand.height)
            }.getOrNull()
            if (expanded != null && expanded.streams.isNotEmpty()) {
                val internalTags = taggedHeaders.filterKeys { it.startsWith("X-Internal-", ignoreCase = true) }
                return expanded.streams.map { s ->
                    s.copy(
                        id = "portal-${idCounter.incrementAndGet()}",
                        headers = internalTags + s.headers
                    )
                }
            }
            // Disguised .txt / extensionless HLS candidates must never fall through to progressive probe
            val ext = UrlUtils.fileExtension(url)?.lowercase()
            if (url.contains(".m3u8", ignoreCase = true) || ext == "txt" || ext.isNullOrEmpty() || MediaSniffer.isDisguisedHlsUrl(url)) {
                return emptyList()
            }
        }

        if (url.contains(".mpd", ignoreCase = true)) {
            val expanded = runCatching { dashExtractor.extract(url, taggedHeaders) }.getOrNull()
            if (expanded != null && expanded.streams.isNotEmpty()) {
                return expanded.streams.map { s ->
                    s.copy(
                        id = "portal-${idCounter.incrementAndGet()}",
                        headers = taggedHeaders + s.headers
                    )
                }
            }
            return emptyList()
        }

        // Progressive direct video (.mp4, .mkv, .webm, Streamtape get_video) with 401/403 header relaxation
        val (probe, workingHeaders) = probeProgressiveWithHeaderFallback(url, taggedHeaders)
            ?: return emptyList()

        val ct = probe.contentType?.lowercase().orEmpty()
        if (ExtractorHttp.isRejectedMediaContentType(ct)) return emptyList()

        // Accept video/*, audio/*, application/octet-stream, binary/octet-stream, or empty MIME on video URLs
        val isAllowedMime = ct.isEmpty() ||
            ct.startsWith("video/") ||
            ct.startsWith("audio/") ||
            ct.contains("octet-stream") ||
            ct.contains("mpegurl")
        if (!isAllowedMime) return emptyList()

        if (probe.contentLength in 1L..40_000L) return emptyList()

        val finalUrl = if (InterceptedMediaExtractor.shouldPreserveRedirectorUrl(url, probe.finalUrl)) {
            url
        } else {
            MediaSniffer.cleanMediaUrl(probe.finalUrl.ifBlank { url })
        }
        val ext = UrlUtils.fileExtension(finalUrl)?.takeIf { it in setOf("mp4", "mkv", "webm", "mov", "m4v") }
            ?: UrlUtils.fileExtension(probe.finalUrl)?.takeIf { it in setOf("mp4", "mkv", "webm", "mov", "m4v") }
            ?: if (ct.contains("webm")) "webm" else "mp4"
        val normHeight = JsUnpacker.normalizeVideoHeight(cand.width, cand.height)
            ?: UrlUtils.inferResolution(finalUrl, cand.label)
            ?: UrlUtils.inferResolution(probe.finalUrl, cand.label)
        val label = when {
            normHeight != null && normHeight >= 1080 -> "${normHeight}p FHD"
            normHeight != null && normHeight >= 720 -> "${normHeight}p HD"
            normHeight != null -> "${normHeight}p"
            cand.label.isNotBlank() -> cand.label
            else -> "HD Video"
        }

        val finalHeaders = LinkedHashMap<String, String>(workingHeaders).apply {
            if (probe.acceptsRanges) put("X-Internal-Accepts-Ranges", "true")
        }

        return listOf(
            MediaStream(
                id = "portal-${idCounter.incrementAndGet()}",
                url = finalUrl,
                type = MediaType.VIDEO,
                container = ext,
                label = label,
                width = cand.width,
                height = normHeight,
                sizeBytes = probe.contentLength.takeIf { it > 0L },
                isHls = false,
                headers = finalHeaders
            )
        )
    }

    private fun probeProgressiveWithHeaderFallback(
        url: String,
        primaryHeaders: Map<String, String>
    ): Pair<HeadInfo, Map<String, String>>? {
        val headerCandidates = listOf(primaryHeaders) +
            ExtractorHttp.buildRelaxedHeaderVariants(primaryHeaders, includeBareNoReferer = true)
        for (hdrs in headerCandidates) {
            val probe = try {
                http.probe(url, hdrs, PROGRESSIVE_PROBE_TIMEOUT_MS)
            } catch (ce: CancellationException) {
                throw ce
            } catch (me: com.melmeligy.mediadownloader.core.MediaException) {
                if (me.error == com.melmeligy.mediadownloader.core.AppError.ACCESS_DENIED ||
                    me.error == com.melmeligy.mediadownloader.core.AppError.GEO_BLOCKED
                ) continue
                return null
            } catch (_: Exception) {
                return null
            }
            if (probe.code in 200..299 && !ExtractorHttp.isRejectedMediaContentType(probe.contentType)) {
                return probe to hdrs
            }
            if (probe.code != 401 && probe.code != 403) {
                return null
            }
        }
        return null
    }

    private fun buildHopHeaders(
        targetUrl: String,
        refererUrl: String,
        baseHeaders: Map<String, String>
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        baseHeaders["User-Agent"]?.let { merged["User-Agent"] = it }
        merged["Referer"] = refererUrl
        originOf(refererUrl)?.let { merged["Origin"] = it }
        runCatching {
            CookieManager.getInstance().getCookie(targetUrl)?.takeIf { it.isNotBlank() }?.let {
                merged["Cookie"] = it
            }
        }
        return merged
    }

    private fun buildStreamHeadersForHost(
        embedUrl: String,
        baseHeaders: Map<String, String>
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        baseHeaders["User-Agent"]?.let { merged["User-Agent"] = it }
        val origin = originOf(embedUrl)
        val hostLower = UrlUtils.host(embedUrl)?.lowercase().orEmpty()
        // ABStream / delucloud CDNs require origin-only Referer (`https://abstream.to/`) + Origin
        merged["Referer"] = if (origin != null && (hostLower.contains("abstream") || hostLower.contains("delucloud"))) {
            "$origin/"
        } else {
            embedUrl
        }
        origin?.let { merged["Origin"] = it }
        val baseCookie = baseHeaders["Cookie"]?.takeIf { it.isNotBlank() }
        val cmCookie = runCatching {
            CookieManager.getInstance().getCookie(embedUrl)?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val combinedCookie = when {
            baseCookie != null && cmCookie != null -> "$cmCookie; $baseCookie"
            baseCookie != null -> baseCookie
            cmCookie != null -> cmCookie
            else -> null
        }
        if (!combinedCookie.isNullOrBlank()) {
            merged["Cookie"] = combinedCookie
        }
        return merged
    }

    private fun normalizeEmbedUrl(baseUrl: String, raw: String): String? {
        val trimmed = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (trimmed.isEmpty() || trimmed.startsWith("javascript:", true) || trimmed.startsWith("about:", true)) {
            return null
        }
        val unwrapped = unwrapPlayRedirectUrl(trimmed)
        val resolved = when {
            unwrapped.startsWith("//") -> "https:$unwrapped"
            unwrapped.startsWith("http://") || unwrapped.startsWith("https://") -> unwrapped
            else -> UrlUtils.resolveUrl(baseUrl, unwrapped)
        }
        return resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun isBlockedOrNonPlayerEmbed(url: String): Boolean {
        if (AdBlocker.isAd(url) || MediaSniffer.isPreviewOrAdUrl(url)) return true
        val lower = url.lowercase()
        return lower.contains("googletagmanager") ||
            lower.contains("recaptcha") ||
            lower.contains("hcaptcha") ||
            lower.contains("challenges.cloudflare.com") ||
            lower.contains("facebook.com/plugins") ||
            lower.contains("twitter.com/widgets") ||
            lower.contains("disqus.com") ||
            lower.endsWith(".apk")
    }

    private fun dedupeEmbedKey(url: String): String =
        url.trim().substringBefore('#').trimEnd('/').lowercase()

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(UrlUtils.encodeNonAsciiUrl(url))
        val scheme = uri.scheme
        val host = uri.host
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) null
        else if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
    }.getOrNull()

    private fun normalizeHost(host: String?): String? =
        host?.trim()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    internal fun registrableDomain(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching { URI(UrlUtils.encodeNonAsciiUrl(url.trim())).host }.getOrNull()
            ?.trim()?.lowercase()?.removePrefix("www.")?.trimEnd('.')
            ?: return null
        if (host.isEmpty()) return null
        val parts = host.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
    }

    internal fun sameRegistrableDomain(urlA: String?, urlB: String?): Boolean {
        val a = registrableDomain(urlA) ?: return false
        val b = registrableDomain(urlB) ?: return false
        return a == b
    }

    private fun normalizePath(path: String?): String {
        val raw = path.orEmpty().trim()
        if (raw.isEmpty()) return ""
        val decoded = runCatching {
            java.net.URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(raw)
        return decoded.trim().replace(Regex("/+"), "/").trimEnd('/').lowercase()
    }

    /**
     * Returns `true` when a movie/episode portal watch page exposes watch-server tabs or player
     * open buttons that point exclusively to blocked ad-tracker redirect gateways (e.g.
     * `w1..w5.free-wd.online`, `a.cwd-open-server`) with zero legitimate playable embed targets
     * or direct `<video>` sources.
     */
    internal fun isDecoyAdTrackerPortalPage(doc: Document, html: String): Boolean {
        val baseUrl = doc.baseUri().orEmpty()
        val hasDirectVideoTag = doc.select("video[src], video source[src]").any { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }.trim()
            src.startsWith("http", ignoreCase = true) && !AdBlocker.isAd(src) && !MediaSniffer.isPreviewOrAdUrl(src)
        }
        if (hasDirectVideoTag) return false

        val watchCandidates = LinkedHashSet<String>()
        doc.select("ul#watch [data-watch], [data-watch], .WatchServersList [data-url], .WatchServersList [data-embed], .WatchServersList [data-iframe]").forEach { el ->
            for (attr in listOf("data-watch", "data-url", "data-embed", "data-iframe")) {
                val v = el.attr(attr).trim()
                if (v.isNotEmpty()) watchCandidates.add(v)
            }
        }
        doc.select(".WatchIframe a.cwd-open-server[href], a.cwd-open-server[href], .WatchIframe a[href]").forEach { el ->
            val v = el.absUrl("href").ifBlank { el.attr("href") }.trim()
            if (v.isNotEmpty()) watchCandidates.add(v)
        }
        doc.select(".WatchIframe iframe[src], .WatchIframe iframe[data-src]").forEach { el ->
            val v = el.absUrl("src").ifBlank { el.absUrl("data-src") }.ifBlank { el.attr("src") }.trim()
            if (v.isNotEmpty()) watchCandidates.add(v)
        }

        var adWatchCount = 0
        var legitWatchCount = 0
        for (raw in watchCandidates) {
            val decoded = decodePortalEmbedUrl(raw) ?: continue
            val resolved = if (baseUrl.isNotBlank()) normalizeEmbedUrl(baseUrl, decoded) else decoded
            if (resolved.isNullOrBlank() || !resolved.startsWith("http", ignoreCase = true)) continue
            if (AdBlocker.isAd(resolved)) {
                adWatchCount++
            } else if (!isBlockedOrNonPlayerEmbed(resolved)) {
                legitWatchCount++
            }
        }
        if (adWatchCount > 0 && legitWatchCount == 0) {
            return true
        }
        val lowerHtml = html.lowercase()
        return (lowerHtml.contains("free-wd.online") || lowerHtml.contains("cwd-open-server")) &&
            legitWatchCount == 0
    }

    internal fun isTopCinemaFamilyUrl(url: String): Boolean {
        val host = UrlUtils.host(url)?.lowercase()?.removePrefix("www.") ?: return false
        return host.contains("topcima") || host.contains("topcinema")
    }

    internal fun buildCanonicalTopCinemaWatchUrls(
        pageUrl: String,
        mirrorOrigins: List<String> = topCinemaMirrorOrigins
    ): List<String> {
        val uri = runCatching { URI(UrlUtils.encodeNonAsciiUrl(pageUrl)) }.getOrNull() ?: return emptyList()
        val rawPath = (uri.rawPath ?: return emptyList())
            .trim()
            .replace(Regex("/+"), "/")
            .trimEnd('/')
        if (rawPath.isEmpty() || rawPath == "/") return emptyList()

        val watchPath = when {
            rawPath.endsWith("/watch", ignoreCase = true) -> "$rawPath/"
            rawPath.endsWith("/download", ignoreCase = true) ->
                rawPath.substring(0, rawPath.length - "/download".length) + "/watch/"
            else -> "$rawPath/watch/"
        }

        val currentOrigin = originOf(pageUrl)?.lowercase()?.trimEnd('/')
        val currentHost = normalizeHost(uri.host)
        val out = LinkedHashSet<String>()
        for (rawOrigin in mirrorOrigins) {
            val cleanOrigin = rawOrigin.trim().trimEnd('/')
            if (!cleanOrigin.startsWith("http://", true) && !cleanOrigin.startsWith("https://", true)) continue
            if (currentOrigin != null && cleanOrigin.lowercase() == currentOrigin) continue
            val mirrorUri = runCatching { URI(cleanOrigin) }.getOrNull()
            val mirrorHost = normalizeHost(mirrorUri?.host)
            // Skip if exact same host AND same port (allows loopback tests on distinct ports)
            if (currentHost != null && mirrorHost == currentHost && uri.port == (mirrorUri?.port ?: -1)) continue
            out.add("$cleanOrigin$watchPath")
        }
        return out.toList()
    }

    private suspend fun resolveTopCinemaCanonicalMirrors(
        pageUrl: String,
        baseHeaders: Map<String, String>,
        visitedEmbeds: MutableSet<String>,
        idCounter: AtomicInteger
    ): PortalExtractionResult {
        val candidateUrls = buildCanonicalTopCinemaWatchUrls(pageUrl)
        if (candidateUrls.isEmpty()) return PortalExtractionResult(emptyList())

        val cleanBaseHeaders = LinkedHashMap<String, String>().apply {
            baseHeaders.forEach { (k, v) ->
                if (!k.equals("Cookie", ignoreCase = true) &&
                    !k.equals("Referer", ignoreCase = true) &&
                    !k.equals("Origin", ignoreCase = true) &&
                    !k.equals("Host", ignoreCase = true)
                ) {
                    put(k, v)
                }
            }
        }

        for (mirrorUrl in candidateUrls) {
            progressTracker.update(ExtractionProgressTracker.STAGE_SERVERS)
            val mirrorOrigin = originOf(mirrorUrl)
            val mirrorHeaders = LinkedHashMap<String, String>(cleanBaseHeaders).apply {
                if (mirrorOrigin != null) put("Referer", "$mirrorOrigin/")
            }
            val mirrorHtml = withTimeoutOrNull(MIRROR_FETCH_TIMEOUT_MS) {
                withCancellableHttpCalls(timeoutMs = MIRROR_FETCH_TIMEOUT_MS) {
                    runCatching { http.fetchString(mirrorUrl, mirrorHeaders) }.getOrNull()
                }
            }.orEmpty()
            if (mirrorHtml.isBlank()) continue

            val mirrorDoc = Jsoup.parse(mirrorHtml, mirrorUrl)
            if (isDecoyAdTrackerPortalPage(mirrorDoc, mirrorHtml)) continue

            val jobs = collectServerJobs(mirrorUrl, mirrorHtml, mirrorDoc)
            if (jobs.isNotEmpty()) {
                val (streams, poster) = executeServerJobs(
                    jobs = rankServerJobs(jobs),
                    baseHeaders = mirrorHeaders,
                    visitedEmbeds = visitedEmbeds,
                    idCounter = idCounter
                )
                if (streams.isNotEmpty()) {
                    com.melmeligy.mediadownloader.core.AppLogger.d(
                        "Portal",
                        "Canonical TopCinema mirror succeeded mirror=${UrlUtils.host(mirrorUrl)} streams=${streams.size}"
                    )
                    return PortalExtractionResult(streams = streams, posterUrl = poster)
                }
            }
        }
        return PortalExtractionResult(emptyList())
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun extractEmbedCode(embedUrl: String): String? {
        EMBED_CODE_PATH_REGEX.find(embedUrl)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return embedUrl.substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .substringBefore('.')
            .takeIf { it.length in 4..48 && it.matches(Regex("^[A-Za-z0-9_-]+$")) }
    }

    /**
     * Resolves Vidara (`vidaraa.*` / `vidara.*`) SPA embeds via `POST $origin/api/stream`.
     */
    internal suspend fun resolveVidaraApi(
        embedUrl: String,
        streamHeaders: Map<String, String>
    ): List<JsUnpacker.ExtractedSource> {
        val origin = originOf(embedUrl) ?: return emptyList()
        val filecode = extractEmbedCode(embedUrl) ?: return emptyList()
        val reqBody = JSONObject()
            .put("filecode", filecode)
            .put("device", "web")
            .put("codecs", org.json.JSONArray())
            .toString()
        val apiHeaders = LinkedHashMap<String, String>(streamHeaders).apply {
            put("Accept", "application/json")
            put("Referer", "$origin/e/$filecode")
            put("Origin", origin)
        }
        val respText = withTimeoutOrNull(EMBED_HOP_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = EMBED_HOP_TIMEOUT_MS) {
                runCatching { http.postJson("$origin/api/stream", reqBody, apiHeaders) }.getOrNull()
            }
        }?.trim()?.takeIf { it.startsWith("{") } ?: return emptyList()

        val json = runCatching { JSONObject(respText) }.getOrNull() ?: return emptyList()
        val streamUrl = json.optString("streaming_url")
            .ifBlank { json.optString("stream_url") }
            .ifBlank { json.optString("url") }
            .trim()
            .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return emptyList()
        val titleHint = json.optString("title").trim()
        val isHls = streamUrl.contains(".m3u8", ignoreCase = true) || MediaSniffer.isDisguisedHlsUrl(streamUrl)
        return listOf(
            JsUnpacker.ExtractedSource(
                url = streamUrl,
                label = titleHint,
                height = UrlUtils.inferResolution(streamUrl, titleHint),
                isHlsHint = isHls
            )
        )
    }

    /**
     * Resolves Filemoon / Byse (`byse*`, `filemoon*`) SPA embeds via `GET $origin/api/videos/{code}`
     * and AES-256-GCM decryption of the `playback` payload.
     */
    internal suspend fun resolveByseFilemoonApi(
        embedUrl: String,
        streamHeaders: Map<String, String>
    ): List<JsUnpacker.ExtractedSource> {
        val origin = originOf(embedUrl) ?: return emptyList()
        val code = extractEmbedCode(embedUrl) ?: return emptyList()
        val apiHeaders = LinkedHashMap<String, String>(streamHeaders).apply {
            put("Accept", "application/json")
            put("Referer", "$origin/e/$code")
            put("Origin", origin)
        }
        val respText = withTimeoutOrNull(EMBED_HOP_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = EMBED_HOP_TIMEOUT_MS) {
                runCatching { http.fetchString("$origin/api/videos/$code", apiHeaders) }.getOrNull()
                    ?.takeIf { it.trimStart().startsWith("{") }
                    ?: runCatching { http.fetchString("$origin/api/videos/$code/", apiHeaders) }.getOrNull()
            }
        }?.trim()?.takeIf { it.startsWith("{") } ?: return emptyList()

        val decryptedJson = decryptBysePlaybackJson(respText) ?: return emptyList()
        val decObj = runCatching { JSONObject(decryptedJson) }.getOrNull() ?: return emptyList()
        val sourcesArr = decObj.optJSONArray("sources") ?: return emptyList()
        val out = mutableListOf<JsUnpacker.ExtractedSource>()
        for (i in 0 until sourcesArr.length()) {
            val s = sourcesArr.optJSONObject(i) ?: continue
            val rawFile = s.optString("url").ifBlank { s.optString("file") }.trim()
            val resolved = normalizeEmbedUrl(embedUrl, rawFile) ?: continue
            val label = s.optString("label").ifBlank { s.optString("quality") }.trim()
            val explicitH = s.optInt("height", 0).takeIf { it > 0 }
            val isHls = resolved.contains(".m3u8", ignoreCase = true) || MediaSniffer.isDisguisedHlsUrl(resolved)
            out += JsUnpacker.ExtractedSource(
                url = resolved,
                label = label,
                height = explicitH ?: UrlUtils.inferResolution(resolved, label),
                isHlsHint = isHls
            )
        }
        return out
    }

    /**
     * Resolves Doodstream's dynamic `/pass_md5/<hash>/<token>` endpoint:
     * fetches the base media URL with `Referer: embedUrl`, then appends a 10-char random string
     * plus `?token=<token>&expiry=<epochMs>` matching Doodstream's client-side `makePlay()` script.
     */
    internal suspend fun resolveDoodstreamPassMd5(
        embedUrl: String,
        scriptText: String,
        streamHeaders: Map<String, String>
    ): String? {
        val passMatch = DOOD_PASS_MD5_REGEX.find(scriptText) ?: return null
        val passPath = passMatch.groupValues[1]
        val token = DOOD_TOKEN_REGEX.find(scriptText)?.groupValues?.getOrNull(1)
            ?: passPath.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return null
        val passUrl = UrlUtils.resolveUrl(embedUrl, passPath)
        val passHeaders = LinkedHashMap<String, String>(streamHeaders).apply {
            put("Referer", embedUrl)
            put("X-Requested-With", "XMLHttpRequest")
        }
        val baseStreamUrl = withTimeoutOrNull(EMBED_HOP_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = EMBED_HOP_TIMEOUT_MS) {
                runCatching { http.fetchString(passUrl, passHeaders).trim() }.getOrNull()
            }
        }?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return null
        val suffixChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val randomSuffix = buildString(10) {
            val rnd = java.util.Random()
            repeat(10) { append(suffixChars[rnd.nextInt(suffixChars.length)]) }
        }
        val sep = if (baseStreamUrl.contains("?")) "&" else "?"
        return "$baseStreamUrl$randomSuffix${sep}token=$token&expiry=${System.currentTimeMillis()}"
    }

    companion object {
        private val DOOD_PASS_MD5_REGEX = Regex("""['"](/pass_md5/[^'"\s]+)['"]""")
        private val DOOD_TOKEN_REGEX = Regex("""[?&]token=([A-Za-z0-9_-]+)""")
        private val EMBED_CODE_PATH_REGEX = Regex("""/(?:e|d|v|f|embed)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        private val BASE64_LIKE_REGEX = Regex("""^[A-Za-z0-9+/=_-]+$""")
        private val STREAMWISH_FALLBACK_MIRRORS = listOf("vibuxer.com", "hanerix.com")
        internal val CANONICAL_TOPCINEMA_MIRRORS = listOf(
            "https://topcinemaa.tv",
            "https://topcinemaa.one",
            "https://topcimas.co"
        )

        const val MAX_EMBED_HOPS = 3
        const val MAX_SERVERS = 8
        const val MAX_CONCURRENT_SERVERS = 6
        const val PAGE_FETCH_TIMEOUT_MS = 4_500L
        const val MIRROR_FETCH_TIMEOUT_MS = 3_500L
        const val SERVER_PROBE_TIMEOUT_MS = 5_200L
        const val DEGRADED_SERVER_PROBE_TIMEOUT_MS = 2_400L
        const val MAINS_JS_FETCH_TIMEOUT_MS = 2_500L
        const val EMBED_HOP_TIMEOUT_MS = 3_500L
        const val PROGRESSIVE_PROBE_TIMEOUT_MS = 2_200L
        const val PORTAL_TOTAL_BUDGET_MS = 5_500L
        const val SIBLING_MERGE_GRACE_MS = 850L
        const val UNKNOWN_HEIGHT_MERGE_GRACE_MS = 1_450L

        /**
         * Unwraps TopCinema/portal `play.php?to=<encoded>` redirector URLs (including
         * double-prefixed `https://...https://...` targets).
         */
        internal fun unwrapPlayRedirectUrl(rawUrl: String): String =
            WeCimaLinkDecryptor.unwrapPlayRedirectUrl(rawUrl)

        /**
         * Decodes direct, `play.php?to=`, WeCima CMS v2.8 AES-256-GCM, standard Base64/Base64URL,
         * and legacy WeCima (`aHR0c` prefix stripped + `+` noise inserted) embed/download URLs.
         * Returns `null` for undecodable opaque Base64 blobs so they never resolve as bogus
         * same-host relative paths.
         */
        internal fun decodePortalEmbedUrl(raw: String, linkKeyOverride: String? = null): String? =
            WeCimaLinkDecryptor.decodePortalEmbedUrl(raw, linkKeyOverride)

        /**
         * Pure AES-256-GCM decryptor for Byse / Filemoon `/api/videos/{code}` playback JSON.
         */
        internal fun decryptBysePlaybackJson(apiResponseJson: String): String? {
            val playback = runCatching { JSONObject(apiResponseJson).optJSONObject("playback") }.getOrNull() ?: return null
            val keyParts = playback.optJSONArray("key_parts") ?: return null
            val version = playback.optInt("version", 1)
            val iv = decodeBase64UrlBytes(playback.optString("iv")) ?: return null
            val payload = decodeBase64UrlBytes(playback.optString("payload")) ?: return null
            if (iv.isEmpty() || payload.size <= 16) return null

            val total = keyParts.length()
            val candidateKeys = ArrayList<ByteArray>(2)
            if (version in 1..20 && version <= total && (31 - version) in 1..total) {
                val p1 = decodeBase64UrlBytes(keyParts.optString(version - 1))
                val p2 = decodeBase64UrlBytes(keyParts.optString(30 - version))
                if (p1 != null && p2 != null) {
                    val combined = p1 + p2
                    if (combined.size == 32) candidateKeys.add(combined)
                }
            }
            if (total >= 2) {
                val p1 = decodeBase64UrlBytes(keyParts.optString(0))
                val p2 = decodeBase64UrlBytes(keyParts.optString(1))
                if (p1 != null && p2 != null) {
                    val combined = p1 + p2
                    if (combined.size == 32 && candidateKeys.none { it.contentEquals(combined) }) {
                        candidateKeys.add(combined)
                    }
                }
            }

            for (key in candidateKeys) {
                val plain = runCatching {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    String(cipher.doFinal(payload), StandardCharsets.UTF_8)
                }.getOrNull()
                if (!plain.isNullOrBlank() && plain.contains("sources")) {
                    return plain
                }
            }
            return null
        }

        private fun decodeBase64UrlBytes(raw: String): ByteArray? = runCatching {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val normalized = trimmed.replace('-', '+').replace('_', '/')
            val pad = (4 - normalized.length % 4) % 4
            java.util.Base64.getDecoder().decode(normalized + "=".repeat(pad))
        }.getOrNull()
    }
}
