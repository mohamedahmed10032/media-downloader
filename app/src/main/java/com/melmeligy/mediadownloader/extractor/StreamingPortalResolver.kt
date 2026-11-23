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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
            override val parentPageUrl: String
        ) : ServerJob()
    }

    private val myAjaxUrlRegex = Regex(
        """(?:MyAjaxURL|themeAjaxUrl|ThemeAjax)\s*=\s*['"](https?://[^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val ajaxUrlRegex = Regex(
        """(?:ajaxURL|ajax_url|ajaxUrl)\s*=\s*['"](https?://[^'"]+)['"]""",
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

        // 1. Try servers & iframes directly on the current page first
        val directJobs = collectServerJobs(pageUrl, html, doc)
        if (directJobs.isNotEmpty()) {
            progressTracker.update(ExtractionProgressTracker.STAGE_SERVERS)
            val (streams, poster) = executeServerJobs(
                jobs = directJobs,
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
            val subJobs = collectServerJobs(subUrl, subHtml, subDoc)
            if (subJobs.isEmpty()) continue

            val (streams, poster) = executeServerJobs(
                jobs = subJobs,
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

        PortalExtractionResult(emptyList(), discoveredPoster)
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

        doc.select("a[href]").forEach { el ->
            val rawHref = el.absUrl("href").ifBlank { el.attr("href") }.trim()
            if (!rawHref.startsWith("http://") && !rawHref.startsWith("https://")) return@forEach
            val hrefUri = runCatching { URI(UrlUtils.encodeNonAsciiUrl(rawHref)) }.getOrNull() ?: return@forEach
            val hrefHost = normalizeHost(hrefUri.host) ?: return@forEach
            if (hrefHost != currentHost) return@forEach

            val hrefPath = normalizePath(hrefUri.path)
            if (hrefPath == currentPath || hrefPath.isEmpty()) return@forEach

            val isChildPath = currentPath.length > 1 && hrefPath.startsWith("$currentPath/")
            val tail = hrefPath.substringAfterLast('/').lowercase()
            val textAndClass = "${el.text()} ${el.className()} ${el.id()}".lowercase()

            val isWatch = tail in setOf("watch", "play", "stream", "servers", "player") ||
                (isChildPath && (textAndClass.contains("مشاهدة") || textAndClass.contains("مشاهده") || textAndClass.contains("watch")))
            val isDownload = tail in setOf("download", "downloads") ||
                (isChildPath && (textAndClass.contains("تحميل") || textAndClass.contains("download")))

            when {
                isWatch && (isChildPath || hrefPath.contains("/watch")) -> watchLinks.add(rawHref)
                isDownload && (isChildPath || hrefPath.contains("/download")) -> downloadLinks.add(rawHref)
            }
        }

        return (watchLinks.take(1) + downloadLinks.take(1)).distinct()
    }

    private fun collectServerJobs(
        pageUrl: String,
        html: String,
        doc: Document
    ): List<ServerJob> {
        val jobs = mutableListOf<ServerJob>()
        val seenDirectUrls = HashSet<String>()

        fun addDirect(rawUrl: String, serverName: String) {
            val resolved = normalizeEmbedUrl(pageUrl, rawUrl) ?: return
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

        // 2. Static server buttons/tabs with direct embed URLs in data-* attributes
        val directAttrSelectors = listOf(
            "data-embed", "data-watch", "data-url", "data-iframe", "data-link", "data-player", "data-src"
        )
        for (attr in directAttrSelectors) {
            doc.select("[$attr]").forEach { el ->
                if (el.tagName().equals("img", ignoreCase = true) ||
                    el.tagName().equals("script", ignoreCase = true) ||
                    el.tagName().equals("link", ignoreCase = true)
                ) return@forEach
                val raw = el.attr(attr).trim()
                if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("//")) {
                    val label = el.text().trim().take(32).ifBlank { "Server" }
                    addDirect(raw, label)
                }
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

    private fun resolveServerAjaxEndpoint(pageUrl: String, html: String): String? {
        myAjaxUrlRegex.find(html)?.groupValues?.getOrNull(1)?.let { base ->
            val trimmed = base.trimEnd('/')
            return if (trimmed.endsWith(".php", ignoreCase = true)) trimmed else "$trimmed/Single/Server.php"
        }
        ajaxUrlRegex.find(html)?.groupValues?.getOrNull(1)?.let { base ->
            val trimmed = base.trimEnd('/')
            return if (trimmed.endsWith(".php", ignoreCase = true)) trimmed else "$trimmed/Single/Server.php"
        }
        val origin = originOf(pageUrl) ?: return null
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
                try {
                    semaphore.withPermit {
                        outcome = withTimeoutOrNull(SERVER_PROBE_TIMEOUT_MS) {
                            withCancellableHttpCalls(timeoutMs = SERVER_PROBE_TIMEOUT_MS) {
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
                                        val ajaxHeaders = LinkedHashMap<String, String>(baseHeaders).apply {
                                            put("Referer", job.parentPageUrl)
                                            originOf(job.parentPageUrl)?.let { put("Origin", it) }
                                            put("X-Requested-With", "XMLHttpRequest")
                                        }
                                        val resp = try {
                                            http.postForm(job.endpointUrl, job.formBody, ajaxHeaders)
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (_: Exception) {
                                            ""
                                        }
                                        val embedUrl = extractEmbedUrlFromAjaxResponse(resp, job.parentPageUrl)
                                            ?: return@withCancellableHttpCalls PortalExtractionResult(emptyList())
                                        if (isBlockedOrNonPlayerEmbed(embedUrl)) {
                                            return@withCancellableHttpCalls PortalExtractionResult(emptyList())
                                        }
                                        progressTracker.update(ExtractionProgressTracker.STAGE_EMBEDS)
                                        resolveEmbedRecursive(
                                            embedUrl = embedUrl,
                                            parentUrl = job.parentPageUrl,
                                            baseHeaders = baseHeaders,
                                            serverName = job.serverName,
                                            depth = 1,
                                            visited = visitedEmbeds,
                                            idCounter = idCounter
                                        )
                                    }
                                }
                            }
                        } ?: PortalExtractionResult(emptyList())
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
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
                    if (firstWinDeadlineMs == null) {
                        if (remaining == 0) break
                        firstWinDeadlineMs = System.currentTimeMillis() + SIBLING_MERGE_GRACE_MS
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
                        return normalizeEmbedUrl(pageUrl, it)
                    }
                    return normalizeEmbedUrl(pageUrl, raw)
                }
            }
        }

        // HTML `<iframe src="...">` snippet (TopCinema / movies2023 `Single/Server.php`)
        iframeSrcInHtmlRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let {
            return normalizeEmbedUrl(pageUrl, it)
        }

        // Raw URL response
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("//")) {
            return normalizeEmbedUrl(pageUrl, trimmed.lineSequence().first().trim())
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

        // Direct media URL inside iframe/mirror
        val directExt = UrlUtils.fileExtension(embedUrl)
        if (directExt == "m3u8") {
            val h = buildStreamHeadersForHost(parentUrl, baseHeaders)
            val expanded = runCatching { hlsExtractor.extractVerified(embedUrl, h) }.getOrNull()
            return@coroutineScope PortalExtractionResult(expanded?.streams.orEmpty())
        }

        val fetchHeaders = buildHopHeaders(embedUrl, parentUrl, baseHeaders)
        val embedHtml = withTimeoutOrNull(EMBED_HOP_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = EMBED_HOP_TIMEOUT_MS) {
                runCatching { http.fetchString(embedUrl, fetchHeaders) }.getOrNull()
            }
        }.orEmpty()
        if (embedHtml.isBlank()) return@coroutineScope PortalExtractionResult(emptyList())

        // Critical: streams loaded BY this embed player must carry the EMBED URL as Referer & Origin!
        val streamHeaders = buildStreamHeadersForHost(embedUrl, baseHeaders)

        val unpackedBlocks = JsUnpacker.unpackAll(embedHtml)
        val combinedScriptText = buildString {
            append(embedHtml)
            for (block in unpackedBlocks) {
                append('\n')
                append(block)
            }
        }

        val playerCfg = JsUnpacker.extractPlayerConfigs(combinedScriptText)
        val durationHeaders = if (playerCfg.durationSec != null && playerCfg.durationSec > 0L) {
            streamHeaders + ("X-Internal-Duration-Seconds" to playerCfg.durationSec.toString())
        } else {
            streamHeaders
        }

        val candidates = LinkedHashMap<String, JsUnpacker.ExtractedSource>()
        fun addCandidate(url: String, label: String = "", width: Int? = null, height: Int? = null) {
            val resolved = UrlUtils.resolveUrl(embedUrl, url.trim())
            val cleaned = MediaSniffer.cleanMediaUrl(resolved)
            if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) return
            if (AdBlocker.isAd(cleaned) || MediaSniffer.isPreviewOrAdUrl(cleaned)) return
            if (MediaSniffer.isHexCipherUrl(cleaned)) return
            val key = MediaSniffer.dedupeKey(cleaned)
            val normH = JsUnpacker.normalizeVideoHeight(width, height) ?: UrlUtils.inferResolution(cleaned, label)
            val existing = candidates[key]
            if (existing == null || (existing.height == null && normH != null)) {
                candidates[key] = JsUnpacker.ExtractedSource(
                    url = cleaned,
                    label = label.ifBlank { existing?.label.orEmpty() },
                    width = width ?: existing?.width,
                    height = normH ?: existing?.height
                )
            }
        }

        // 1. PlayerJS / JWPlayer / Plyr / Clappr / Video.js sources
        playerCfg.sources.forEach { src ->
            addCandidate(src.url, src.label, src.width, src.height)
        }

        // 2. Streamtape robotlink / ideoolink
        JsUnpacker.extractStreamtapeUrls(combinedScriptText).forEach { stUrl ->
            addCandidate(stUrl, label = "")
        }

        // 3. Base64 atob() & hex-escaped URLs
        JsUnpacker.decodeObfuscatedUrls(combinedScriptText).forEach { obfUrl ->
            addCandidate(obfUrl, label = "")
        }

        // 4. HTML5 <video>/<source> elements inside the embed DOM
        val embedDoc = Jsoup.parse(embedHtml, embedUrl)
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
            m3u8Regex.findAll(stripped).take(3).forEach { addCandidate(it.value) }
            mpdRegex.findAll(stripped).take(2).forEach { addCandidate(it.value) }
            directVideoRegex.findAll(stripped).take(4).forEach { addCandidate(it.value) }
        }

        val resolvedPoster = HtmlThumbnailExtractor.extractThumbnail(
            doc = embedDoc,
            pageUrl = embedUrl,
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
                normalizeEmbedUrl(embedUrl, raw)?.let { u ->
                    if (!isBlockedOrNonPlayerEmbed(u)) nestedUrls.add(u)
                }
            }
            jsIframeRedirectRegex.findAll(combinedScriptText).forEach { m ->
                normalizeEmbedUrl(embedUrl, m.groupValues[1])?.let { u ->
                    if (!isBlockedOrNonPlayerEmbed(u)) nestedUrls.add(u)
                }
            }

            val nestedList = nestedUrls.take(2)
            if (nestedList.size == 1) {
                val deeper = resolveEmbedRecursive(
                    embedUrl = nestedList.first(),
                    parentUrl = embedUrl,
                    baseHeaders = baseHeaders,
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
                                    parentUrl = embedUrl,
                                    baseHeaders = baseHeaders,
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

        if (url.contains(".m3u8", ignoreCase = true)) {
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
            return emptyList()
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

        val finalUrl = MediaSniffer.cleanMediaUrl(probe.finalUrl.ifBlank { url })
        val ext = UrlUtils.fileExtension(finalUrl)?.takeIf { it in setOf("mp4", "mkv", "webm", "mov", "m4v") }
            ?: if (ct.contains("webm")) "webm" else "mp4"
        val normHeight = JsUnpacker.normalizeVideoHeight(cand.width, cand.height)
            ?: UrlUtils.inferResolution(finalUrl, cand.label)
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
                if (me.error == com.melmeligy.mediadownloader.core.AppError.GEO_BLOCKED) continue
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
        merged["Referer"] = embedUrl
        originOf(embedUrl)?.let { merged["Origin"] = it }
        runCatching {
            CookieManager.getInstance().getCookie(embedUrl)?.takeIf { it.isNotBlank() }?.let {
                merged["Cookie"] = it
            }
        }
        return merged
    }

    private fun normalizeEmbedUrl(baseUrl: String, raw: String): String? {
        val trimmed = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (trimmed.isEmpty() || trimmed.startsWith("javascript:", true) || trimmed.startsWith("about:", true)) {
            return null
        }
        val resolved = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> UrlUtils.resolveUrl(baseUrl, trimmed)
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

    private fun normalizePath(path: String?): String =
        path.orEmpty().trim().trimEnd('/').lowercase()

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        const val MAX_EMBED_HOPS = 3
        const val MAX_SERVERS = 8
        const val MAX_CONCURRENT_SERVERS = 6
        const val PAGE_FETCH_TIMEOUT_MS = 4_500L
        const val SERVER_PROBE_TIMEOUT_MS = 4_000L
        const val EMBED_HOP_TIMEOUT_MS = 3_500L
        const val PROGRESSIVE_PROBE_TIMEOUT_MS = 2_200L
        const val PORTAL_TOTAL_BUDGET_MS = 8_500L
        const val SIBLING_MERGE_GRACE_MS = 180L
    }
}
