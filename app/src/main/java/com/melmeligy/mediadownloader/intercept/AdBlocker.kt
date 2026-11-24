package com.melmeligy.mediadownloader.intercept

import android.content.Context
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.WebResourceResponse

/**
 * High-speed domain and VAST/ad-path blocker used by WebView request interception,
 * HeadlessWebExtractor, and MediaSniffer so ad networks, preroll VAST servers, popunders,
 * and live-cam widgets never pollute the format sheet or hijack navigation.
 */
object AdBlocker {

    private val AD_PATH_MARKERS = listOf(
        "/vast.xml",
        "/vast?",
        "/vast/",
        "/vast-",
        "vast-30s-ad",
        "vast_tag=",
        "ad_tag=",
        "/adserver/",
        "/addelivery/",
        "/preroll/",
        "/midroll/",
        "/postroll/",
        "action=ads",
        "/popunder",
        "/out/click"
    )

    private val BUILTIN_AD_HOSTS = setOf(
        // Tube / Adult & Movie-portal ad networks, pop-unders & VAST preroll servers
        "llvpn.com",
        "playertest.longtailvideo.com",
        "acscdn.com",
        "alwingulla.com",
        "tzegilo.com",
        "a-ads.com",
        "monetag.com",
        "adcash.com",
        "galaksion.com",
        "exoclick.com",
        "exosrv.com",
        "exdynsrv.com",
        "realsrv.com",
        "magsrv.com",
        "tsyndicate.com",
        "pxl-tsyndicate.com",
        "trafficjunky.net",
        "trafficjunky.com",
        "ptgncdn.com",
        "adtng.com",
        "trafficstars.com",
        "tsyndicate.net",
        "juicyads.com",
        "adsco.re",
        "popads.net",
        "popcash.net",
        "bngpt.com",
        "plugrush.com",
        "clickadu.com",
        "hilltopads.net",
        "hilltopads.com",
        "adsterra.com",
        "adsterratools.com",
        "onclkds.com",
        "onclickalgo.com",
        "onclickmax.com",
        "etahub.com",
        "adxpansion.com",
        "ero-advertising.com",
        "crakrevenue.com",
        "trafficfactory.biz",
        "traffichaus.com",
        "propellerads.com",
        "highperformanceformat.com",
        "profitablecpmrate.com",
        "wpadmngr.com",
        "cdn4ads.com",
        "gogukypynijoxy.com",
        "mulewosizeseha.com",
        "xvideo.com",
        // Live-cam promo widgets embedded on tube pages
        "stripchat.com",
        "xlirdr.com",
        "chaturbate.com",
        "livejasmin.com",
        "bongacams.com",
        "camsoda.com",
        "myfreecams.com",
        "cam4.com",
        "jerkmate.com",
        "flirt4free.com",
        // General ad/tracker networks
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "acint.net",
        "yandex.ru"
    )

    /**
     * Standard ad/analytics hosts commonly probed by anti-adblock scripts (e.g. WeCima).
     * Never blocked in WebView `shouldInterceptRequest` so anti-adblock modals do not trigger,
     * while still filtered out of captured media streams and popup redirects.
     */
    val ANTI_ADBLOCK_BAIT_HOSTS = setOf(
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "doubleclick.net"
    )

    /**
     * Known video player / embed host tokens that must never be blocked as ads even if a
     * third-party host list ever adds a matching domain.
     */
    val PROTECTED_EMBED_HOST_TOKENS = listOf(
        "earnvids",
        "vidhide",
        "filemoon",
        "byse",
        "voe",
        "dood",
        "mixdrop",
        "m1xdrop",
        "savefiles",
        "streamwish",
        "streamtape",
        "uqload",
        "lulustream",
        "vidguard",
        "filelions",
        "vidmoly",
        "upstream",
        "streamlare",
        "vtube",
        "vidoza",
        "mp4upload",
        "goodstream",
        "vembed",
        "wecima",
        "mycima",
        "okstream",
        "updown",
        "vinovo",
        "vidaraa",
        "fastved",
        "luluvdo",
        "abstream",
        "movearnpre",
        "dingtezuni",
        "dintezuvio",
        "callistanise",
        "vidroba",
        "morencius",
        "delucloud",
        "vibuxer",
        "hanerix",
        "niramirus",
        "hglamioz",
        "playnixes",
        "medixiru",
        "byseqekaho",
        "byseraguci",
        "bysezejataos",
        "firestream",
        "yonaplay",
        "megamax",
        "playmogo",
        "myvidplay",
        "videoland"
    )

    private val POPUP_REDIRECT_PATH_MARKERS = listOf(
        "/popunder",
        "/out/click",
        "/afu.php",
        "/go.php",
        "/click.php",
        "/adclick",
        "/redirect/ad"
    )

    private val initialized = AtomicBoolean(false)
    private val blockedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(BUILTIN_AD_HOSTS)
    }

    /** Loads the bundled `ad_block_host` list (2,300+ hosts) on a background thread. */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        runCatching {
            context.assets.open("ad_block_host").bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val cleaned = raw.substringBefore('#').trim().lowercase().removePrefix("www.")
                    if (cleaned.isNotEmpty() && !cleaned.contains(' ') && !isProtectedEmbedHost(cleaned)) {
                        blockedHosts.add(cleaned)
                    }
                }
            }
        }
    }

    /** True if [rawHost] belongs to a known video player / embed host that must never be ad-blocked. */
    fun isProtectedEmbedHost(rawHost: String?): Boolean {
        val normalized = normalizeHost(rawHost) ?: return false
        return PROTECTED_EMBED_HOST_TOKENS.any { token ->
            normalized == token ||
                normalized.startsWith("$token.") ||
                normalized.contains(".$token.") ||
                normalized.contains(token)
        }
    }

    /** True if [rawUrl] targets a standard anti-adblock bait script host. */
    fun isAntiAdblockBait(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val host = extractHost(rawUrl.trim()) ?: return false
        return matchesHostSet(host, ANTI_ADBLOCK_BAIT_HOSTS)
    }

    /**
     * Sub-resource blocking policy for WebView `shouldInterceptRequest`.
     *
     * - Passes through standard anti-adblock bait scripts (`googlesyndication.com`,
     *   `google-analytics.com`, `googletagmanager.com`, `doubleclick.net`) so anti-adblock
     *   overlays are not triggered.
     * - Respects per-site disabled hosts (`disabledHosts`).
     * - Never blocks protected video embed hosts.
     */
    fun shouldBlockSubResource(
        rawUrl: String?,
        pageUrl: String? = null,
        disabledHosts: Set<String> = emptySet()
    ): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        if (disabledHosts.isNotEmpty()) {
            val pageHost = normalizeHost(pageUrl)
            if (pageHost != null && pageHost in disabledHosts) return false
        }
        if (isAntiAdblockBait(rawUrl)) return false
        return isAd(rawUrl)
    }

    /**
     * Popup / redirect blocking policy for `shouldOverrideUrlLoading` (always ON regardless
     * of per-site ad-block toggle).
     *
     * Blocks `intent://`, `market://`, `javascript:` and other non-HTTP(S) schemes, known ad
     * domains, and cross-domain ad/popunder redirect paths.
     */
    fun shouldBlockPopupOrRedirect(rawUrl: String?, currentPageUrl: String? = null): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val trimmed = rawUrl.trim()
        if (!(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true))) {
            return true
        }
        if (isAd(trimmed)) return true

        val targetHost = normalizeHost(trimmed)
        if (targetHost != null && isProtectedEmbedHost(targetHost)) return false

        val currentHost = normalizeHost(currentPageUrl)
        val isCrossDomain = currentHost != null && targetHost != null &&
            hostFamily(currentHost) != hostFamily(targetHost)
        if (isCrossDomain) {
            val lower = trimmed.lowercase()
            if (POPUP_REDIRECT_PATH_MARKERS.any { lower.contains(it) }) {
                return true
            }
        }
        return false
    }

    fun isAd(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val trimmed = rawUrl.trim()
        val host = extractHost(trimmed) ?: return false
        if (isProtectedEmbedHost(host)) return false
        if (isBlockedHost(host)) return true

        val lower = trimmed.lowercase()
        return AD_PATH_MARKERS.any { lower.contains(it) }
    }

    fun isBlockedHost(rawHost: String): Boolean {
        val normalized = normalizeHost(rawHost) ?: return false
        if (isProtectedEmbedHost(normalized)) return false
        return matchesHostSet(normalized, blockedHosts)
    }

    fun normalizeHost(urlOrHost: String?): String? {
        if (urlOrHost.isNullOrBlank()) return null
        val trimmed = urlOrHost.trim()
        val host = if (trimmed.contains("://")) extractHost(trimmed) else trimmed
        return host?.trim()?.lowercase()?.removePrefix("www.")?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    }

    fun createEmptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun matchesHostSet(rawHost: String, hostSet: Set<String>): Boolean {
        var current = rawHost.trim().lowercase().removePrefix("www.").trimEnd('.')
        if (current.isEmpty()) return false
        while (true) {
            if (hostSet.contains(current)) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
            if (!current.contains('.')) return false
        }
    }

    private fun hostFamily(normalizedHost: String): String {
        val parts = normalizedHost.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else normalizedHost
    }

    private fun extractHost(url: String): String? {
        runCatching {
            URI(url).host?.let { if (it.isNotBlank()) return it }
        }
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            .takeIf { it.isNotBlank() }
    }
}
