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
                    val cleaned = raw.substringBefore('#').trim().lowercase()
                    if (cleaned.isNotEmpty() && !cleaned.contains(' ')) {
                        blockedHosts.add(cleaned.removePrefix("www."))
                    }
                }
            }
        }
    }

    fun isAd(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val trimmed = rawUrl.trim()
        val host = extractHost(trimmed) ?: return false
        if (isBlockedHost(host)) return true

        val lower = trimmed.lowercase()
        return AD_PATH_MARKERS.any { lower.contains(it) }
    }

    fun isBlockedHost(rawHost: String): Boolean {
        var current = rawHost.trim().lowercase().removePrefix("www.").trimEnd('.')
        if (current.isEmpty()) return false
        while (true) {
            if (blockedHosts.contains(current)) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
            if (!current.contains('.')) return false
        }
    }

    fun createEmptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

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
