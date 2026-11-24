package com.melmeligy.mediadownloader.extractor

import android.content.Context
import android.content.SharedPreferences
import com.melmeligy.mediadownloader.core.AppLogger
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.data.prefs.SecureSettingsStore
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Regression & adversarial tests covering:
 * 1. Anti-adblock bait script pass-through, always-on popup/redirect blocking, per-site Shield
 *    toggle persistence, zero false positives on embed hosts, and HeadlessWebExtractor non-blocking.
 * 2. Unpacked `links = {"hls2":...,"hls3":...,"hls4":...}`, `MDCore.wurl`, protocol/root-relative
 *    URLs, and strict `#EXTM3U`-validated disguised `.txt`/extensionless HLS master playlists.
 * 3. Bounded cross-origin embed `<iframe>` promotion, immediate exit on first playable stream,
 *    promoted-embed `Referer` preservation, and JVM-safe `AppLogger` stage diagnostics.
 */
class AntiAdblockAndNestedEmbedRegressionTest {

    private val testDispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    // ---- 1. Anti-Adblock, Bait Pass-Through, Popup/Redirect & Embed Host Safety ----

    @Test
    fun adBlocker_passesThroughAntiAdblockBaitScripts_butStillBlocksPopupsAndAdSubResources() {
        val baitScripts = listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://www.google-analytics.com/analytics.js",
            "https://www.googletagmanager.com/gtag/js?id=G-ABC123",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js"
        )
        for (url in baitScripts) {
            assertTrue("Expected anti-adblock bait recognition for $url", AdBlocker.isAntiAdblockBait(url))
            assertFalse("Bait script must NOT be blocked in shouldInterceptRequest: $url", AdBlocker.shouldBlockSubResource(url))
            // Still blocked if an ad tries to open a top-level redirect or sneak into media streams
            assertTrue("Bait/ad URL must still be filtered from media sniffer: $url", MediaSniffer.isPreviewOrAdUrl(url))
        }

        // Real intrusive ad networks are still blocked as sub-resources when site ad-block is ON
        assertTrue(AdBlocker.shouldBlockSubResource("https://cdn.popads.net/pop.js", "https://wecima.example/watch/1"))
        assertTrue(AdBlocker.shouldBlockSubResource("https://syndication.exoclick.com/ads.js", "https://wecima.example/watch/1"))

        // When per-site Shield is OFF for wecima.example, sub-resource blocking is bypassed for that host only
        val disabled = setOf("wecima.example")
        assertFalse(AdBlocker.shouldBlockSubResource("https://cdn.popads.net/pop.js", "https://www.wecima.example/watch/1", disabled))
        assertTrue(AdBlocker.shouldBlockSubResource("https://cdn.popads.net/pop.js", "https://other-site.example/watch/1", disabled))

        // Popup/redirect blocking stays ALWAYS ON regardless of per-site toggle
        assertTrue(AdBlocker.shouldBlockPopupOrRedirect("intent://scan/#Intent;scheme=zxing;end", "https://wecima.example/watch/1"))
        assertTrue(AdBlocker.shouldBlockPopupOrRedirect("market://details?id=com.spam.app", "https://wecima.example/watch/1"))
        assertTrue(AdBlocker.shouldBlockPopupOrRedirect("https://popads.net/click?id=123", "https://wecima.example/watch/1"))
        assertTrue(AdBlocker.shouldBlockPopupOrRedirect("https://random-hijack.example/popunder?u=1", "https://wecima.example/watch/1"))
        assertFalse(AdBlocker.shouldBlockPopupOrRedirect("https://wecima.example/watch/episode-2/", "https://wecima.example/watch/episode-1/"))
        assertFalse(AdBlocker.shouldBlockPopupOrRedirect("https://earnvids.example/e/abc123", "https://wecima.example/watch/episode-1/"))
    }

    @Test
    fun adBlocker_andAdBlockHostAsset_haveZeroFalsePositivesOnVideoEmbedHosts() {
        val embedHosts = listOf(
            "earnvids.com", "vidhidepro.com", "filemoon.sx", "byse.sx", "byse.to",
            "voe.sx", "dood.li", "doodstream.com", "mixdrop.ag", "m1xdrop.net",
            "savefiles.com", "streamwish.to", "streamtape.com", "uqload.net",
            "lulustream.com", "vidguard.to", "filelions.to", "vidmoly.to"
        )
        for (host in embedHosts) {
            val embedUrl = "https://$host/e/abc123xyz"
            assertFalse("AdBlocker.isBlockedHost false positive on $host", AdBlocker.isBlockedHost(host))
            assertFalse("AdBlocker.isAd false positive on $embedUrl", AdBlocker.isAd(embedUrl))
            assertFalse("AdBlocker.shouldBlockSubResource false positive on $embedUrl", AdBlocker.shouldBlockSubResource(embedUrl))
            assertFalse("AdBlocker.shouldBlockPopupOrRedirect false positive on $embedUrl", AdBlocker.shouldBlockPopupOrRedirect(embedUrl, "https://wecima.example/watch/1"))
        }

        // Also scan the actual bundled asset file if present in workspace
        val assetFile = listOf(
            File("src/main/assets/ad_block_host"),
            File("app/src/main/assets/ad_block_host")
        ).firstOrNull { it.isFile }
        assertNotNull("Expected bundled ad_block_host asset file", assetFile)
        val lines = assetFile!!.readLines().map { it.substringBefore('#').trim().lowercase() }.filter { it.isNotEmpty() }
        val protectedTokens = listOf("earnvids", "vidhide", "filemoon", "byse", "voe", "dood", "mixdrop", "m1xdrop", "savefiles", "streamwish")
        val falsePositives = lines.filter { line -> protectedTokens.any { tok -> line == tok || line.contains(".$tok.") || line.startsWith("$tok.") } }
        assertTrue("ad_block_host must not contain embed player hosts, found: $falsePositives", falsePositives.isEmpty())
    }

    @Test
    fun perSiteAdBlockToggle_persistsAcrossSecureSettingsStoreReloads() = runTest {
        val prefs = InMemorySharedPreferences()
        val mockCtx = mockk<Context>(relaxed = true)

        val store1 = SecureSettingsStore(mockCtx, testDispatchers, prefsFactory = { prefs })
        assertTrue(store1.current().isAdBlockEnabledFor("https://www.WeCima.example/watch/1"))

        store1.setAdBlockEnabledForHost("https://www.WeCima.example/watch/1", enabled = false)
        assertFalse(store1.current().isAdBlockEnabledFor("https://wecima.example/watch/2"))
        assertTrue(store1.current().isAdBlockEnabledFor("https://other.example/watch/1"))

        // Reload fresh SecureSettingsStore backed by the same persisted SharedPreferences
        val store2 = SecureSettingsStore(mockCtx, testDispatchers, prefsFactory = { prefs })
        assertFalse(store2.current().isAdBlockEnabledFor("https://wecima.example/watch/3"))
        assertEquals(setOf("wecima.example"), store2.current().adBlockDisabledHosts)

        // Re-enable for the host and confirm persistence
        store2.setAdBlockEnabledForHost("wecima.example", enabled = true)
        val store3 = SecureSettingsStore(mockCtx, testDispatchers, prefsFactory = { prefs })
        assertTrue(store3.current().isAdBlockEnabledFor("https://wecima.example/watch/3"))
        assertTrue(store3.current().adBlockDisabledHosts.isEmpty())
    }

    @Test
    fun headlessWebExtractor_doesNotBlockSubResources_butStillFiltersAdAndVastStreams() {
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "*/*", false))
        assertFalse(HeadlessWebExtractor.shouldBlockAuxiliaryResource("https://cdn.popads.net/pop.js", "*/*", false))
        assertTrue(MediaSniffer.isPreviewOrAdUrl("https://playertest.longtailvideo.com/vast-30s-ad.xml"))
        assertTrue(MediaSniffer.isPreviewOrAdUrl("https://ads.trafficjunky.net/preroll.mp4"))
        assertEquals(StreamType.UNKNOWN, MediaSniffer.classify("https://ads.trafficjunky.net/preroll.mp4", "video/mp4"))
    }

    // ---- 2. Unpacker, Sniffer & Portal Resolver (links={hls2..4}, MDCore.wurl, relative URLs, strict #EXTM3U) ----

    @Test
    fun jsUnpacker_extractsLinksHlsObject_mdCoreWurl_andResolvesProtocolAndRootRelativeUrls() {
        val embedBase = "https://earnvids.example/e/movie123"
        val unpackedLinksJs = """
            var links = {
              "hls2": "/stream/01/master.txt?t=tok123",
              "hls3": "//cdn3.earnvids.example/hls3/01/index.txt?t=tok456",
              "hls4": "https://cdn4.earnvids.example/hls4/01/master.m3u8"
            };
        """.trimIndent()

        val cfg = JsUnpacker.extractPlayerConfigs(unpackedLinksJs, baseUrl = embedBase)
        assertEquals(3, cfg.sources.size)
        assertEquals("https://earnvids.example/stream/01/master.txt?t=tok123", cfg.sources[0].url)
        assertTrue(cfg.sources[0].isHlsHint)
        assertEquals("https://cdn3.earnvids.example/hls3/01/index.txt?t=tok456", cfg.sources[1].url)
        assertTrue(cfg.sources[1].isHlsHint)
        assertEquals("https://cdn4.earnvids.example/hls4/01/master.m3u8", cfg.sources[2].url)

        val mixdropUnpacked = """
            MDCore.ref = "";
            MDCore.wurl = "//s-delivery12.m1xdrop.example/v/e8f9a1.mp4?s=abc123&e=1799999999";
        """.trimIndent()
        val mdCfg = JsUnpacker.extractPlayerConfigs(mixdropUnpacked, baseUrl = "https://m1xdrop.example/e/xyz")
        assertEquals(1, mdCfg.sources.size)
        assertEquals("https://s-delivery12.m1xdrop.example/v/e8f9a1.mp4?s=abc123&e=1799999999", mdCfg.sources[0].url)
    }

    @Test
    fun mediaSniffer_enforcesStrictExtm3uStartValidationForDisguisedTxtAndExtensionlessPlaylists() {
        val txtMasterUrl = "https://cdn.byse.example/hls2/01/master.txt?t=abc"
        val extensionlessHlsUrl = "https://cdn.byse.example/hls2/01/master?t=abc"
        val robotsTxt = "https://cdn.byse.example/robots.txt"

        assertTrue(MediaSniffer.isDisguisedHlsUrl(txtMasterUrl))
        assertTrue(MediaSniffer.isDisguisedHlsUrl(extensionlessHlsUrl))
        assertFalse(MediaSniffer.isDisguisedHlsUrl(robotsTxt))

        // Valid #EXTM3U (including UTF-8 BOM) passes
        val validBody = "\uFEFF#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nindex.m3u8"
        assertTrue(MediaSniffer.isValidHlsPlaylistBody(validBody))
        assertEquals(StreamType.HLS, MediaSniffer.classifyWithBody(txtMasterUrl, "text/plain", validBody))
        assertEquals(StreamType.HLS, MediaSniffer.classifyWithBody(extensionlessHlsUrl, "text/plain", validBody))

        // Adversarial non-media text / analytics / HTML containing '#EXTM3U' in the middle must be REJECTED
        val fakeAnalytics = "ok=1\nstatus=200\n#EXTM3U"
        val htmlError = "<html><body>Error: #EXTM3U not found</body></html>"
        assertFalse(MediaSniffer.isValidHlsPlaylistBody(fakeAnalytics))
        assertFalse(MediaSniffer.isValidHlsPlaylistBody(htmlError))
        assertEquals(StreamType.UNKNOWN, MediaSniffer.classifyWithBody(txtMasterUrl, "text/plain", fakeAnalytics))
        assertEquals(StreamType.UNKNOWN, MediaSniffer.classifyWithBody(extensionlessHlsUrl, "text/html", htmlError))
    }

    @Test
    fun streamingPortalResolver_resolvesDisguisedTxtHlsFromLinks_andRejectsFakeTxtWithoutExtm3u() = runTest {
        val pageUrl = "https://portal.example/watch/film-1/"
        val goodEmbedUrl = "https://earnvids.example/e/good1"
        val fakeTxtEmbedUrl = "https://savefiles.example/e/badtxt1"

        val goodEmbedHtml = """
            <html><body><script>
            var links = {"hls2": "/hls2/01/master.txt?t=valid"};
            </script></body></html>
        """.trimIndent()

        val fakeTxtEmbedHtml = """
            <html><body><script>
            var links = {"hls2": "/hls2/01/master.txt?t=fake"};
            </script></body></html>
        """.trimIndent()

        val validMaster = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1920x1080
            /hls2/01/v1080.m3u8
        """.trimIndent()

        val validVariant = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            /hls2/01/seg_1.ts
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val u = req.url.toString()
                fun resp(body: String, mime: String, code: Int = 200) = Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(code).message("OK")
                    .body(body.toResponseBody(mime.toMediaType()))
                    .build()

                when (u) {
                    goodEmbedUrl -> resp(goodEmbedHtml, "text/html")
                    fakeTxtEmbedUrl -> resp(fakeTxtEmbedHtml, "text/html")
                    "https://earnvids.example/hls2/01/master.txt?t=valid" -> {
                        if (req.header("Referer") != goodEmbedUrl) resp("Forbidden", "text/plain", 403)
                        else resp(validMaster, "text/plain")
                    }
                    "https://earnvids.example/hls2/01/v1080.m3u8" -> resp(validVariant, "application/vnd.apple.mpegurl")
                    "https://earnvids.example/hls2/01/seg_1.ts" -> Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                        .header("Content-Type", "video/mp2t")
                        .header("Content-Range", "bytes 0-0/1000000")
                        .body(byteArrayOf(0x47).toResponseBody("video/mp2t".toMediaType()))
                        .build()
                    // Adversarial fake .txt response that does NOT start with #EXTM3U
                    "https://savefiles.example/hls2/01/master.txt?t=fake" -> resp("analytics_ok=1\n#EXTM3U", "text/plain")
                    else -> resp("Not found", "text/plain", 404)
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val resolver = StreamingPortalResolver(http, HlsExtractor(http), DashExtractor(http))

        // 1. Valid disguised .txt HLS master resolves cleanly with embed Referer
        val goodPageHtml = """<html><body><iframe src="$goodEmbedUrl"></iframe></body></html>"""
        val goodResult = resolver.resolvePortalAndEmbeds(pageUrl, goodPageHtml, Jsoup.parse(goodPageHtml, pageUrl), emptyMap())
        assertTrue("Expected resolved HLS stream from disguised .txt master", goodResult.streams.isNotEmpty())
        assertEquals(1080, goodResult.streams.first().height)
        assertEquals(goodEmbedUrl, goodResult.streams.first().headers["Referer"])

        // 2. Adversarial fake .txt without leading #EXTM3U produces 0 streams
        val fakePageHtml = """<html><body><iframe src="$fakeTxtEmbedUrl"></iframe></body></html>"""
        val fakeResult = resolver.resolvePortalAndEmbeds(pageUrl, fakePageHtml, Jsoup.parse(fakePageHtml, pageUrl), emptyMap())
        assertTrue("Fake .txt not starting with #EXTM3U must be rejected", fakeResult.streams.isEmpty())
    }

    // ---- 3. Bounded Cross-Origin Embed Promotion & Promoted Referer Preservation ----

    @Test
    fun headlessWebExtractor_embedPromotion_isBoundedTo3_exitsImmediatelyOnFirstPlayable_andPreservesPromotedReferer() = runTest {
        val outerUrl = "https://wecima.example/watch/movie-2026/"
        val candidates = listOf(
            "https://wecima.example/same-origin-banner", // same-origin -> skip
            "https://doubleclick.net/ad-frame", // ad -> skip
            "https://www.google.com/recaptcha/api2/anchor", // captcha -> skip
            "https://earnvids.example/e/dead1", // embed #1 (returns 0 streams)
            "https://byse.example/e/working2", // embed #2 (returns playable HLS)
            "https://mixdrop.example/e/never3", // embed #3 (must NOT be loaded because #2 won)
            "https://dood.example/e/overbound4" // embed #4 (exceeds bound of 3)
        )

        val selected = HeadlessWebExtractor.selectPromotableEmbedUrls(outerUrl, candidates, maxEmbeds = 3)
        assertEquals(
            listOf(
                "https://earnvids.example/e/dead1",
                "https://byse.example/e/working2",
                "https://mixdrop.example/e/never3"
            ),
            selected
        )

        val visitedEmbeds = mutableListOf<Pair<String, String>>()
        val captured = HeadlessWebExtractor.runBoundedEmbedPromotion(
            outerPageUrl = outerUrl,
            candidateEmbeds = candidates,
            maxEmbeds = 3
        ) { embedUrl, refererUrl ->
            visitedEmbeds.add(embedUrl to refererUrl)
            when (embedUrl) {
                "https://earnvids.example/e/dead1" -> emptyList()
                "https://byse.example/e/working2" -> listOf(
                    MediaStreamPayload(
                        url = "https://cdn.byse.example/hls2/master.m3u8",
                        streamType = StreamType.HLS,
                        // Simulate outer portal Referer leaked before rewrite
                        headersMap = mapOf("Referer" to outerUrl, "User-Agent" to "TestUA"),
                        pageUrl = outerUrl
                    )
                )
                else -> error("Must exit immediately after embed #2 succeeds; should not load $embedUrl")
            }
        }

        // Only embeds #1 and #2 were visited, each loaded with Referer = outerUrl
        assertEquals(2, visitedEmbeds.size)
        assertEquals("https://earnvids.example/e/dead1" to outerUrl, visitedEmbeds[0])
        assertEquals("https://byse.example/e/working2" to outerUrl, visitedEmbeds[1])

        // Captured stream's download Referer & Origin MUST be the promoted embed URL, NOT the outer portal page
        assertEquals(1, captured.size)
        assertEquals("https://byse.example/e/working2", captured[0].headersMap["Referer"])
        assertEquals("https://byse.example", captured[0].headersMap["Origin"])
        assertEquals("https://byse.example/e/working2", captured[0].pageUrl)
    }

    @Test
    fun appLogger_recordsStageDiagnosticsSafelyInJvmTests() {
        AppLogger.clearRecent()
        AppLogger.d("Headless", "Stage test started")
        AppLogger.w("Headless", "No streams captured")
        val logs = AppLogger.recentLogs()
        assertTrue(logs.any { it.contains("D/Headless: Stage test started") })
        assertTrue(logs.any { it.contains("W/Headless: No streams captured") })
    }

    // ---- Test Double ----

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((map[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = key != null && map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clear = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) map.clear()
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
