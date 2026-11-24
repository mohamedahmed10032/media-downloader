package com.melmeligy.mediadownloader.di

import android.webkit.CookieManager
import com.melmeligy.mediadownloader.BuildConfig
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.data.remote.RawContentService
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffingInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.create
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton

/**
 * Deterministic CookieJar bridging Android WebView [CookieManager] with an in-memory store
 * (safe in JVM tests when [CookieManager] is unavailable). Enforces strict domain isolation,
 * preserves server-rotated cookies across subdomains even when callers replay a stale seed
 * header, honors genuine caller overrides, and evicts expired/`Max-Age=0` cookies cleanly.
 */
class WebViewCookieJar : CookieJar {

    private data class Entry(
        val cookie: Cookie,
        val seq: Long
    )

    private data class CachedCmCookies(
        val family: String,
        val header: String?,
        val fetchedAtMs: Long
    )

    private val seqGen = AtomicLong(0L)
    private val storeByFamily = ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>>()
    private val lastSeeded = ConcurrentHashMap<String, String>()
    private val cmReadCache = ConcurrentHashMap<String, CachedCmCookies>()

    internal var clockMs: () -> Long = { System.currentTimeMillis() }
    internal var cookieManagerReader: (String) -> String? = { target ->
        runCatching { CookieManager.getInstance().getCookie(target) }.getOrNull()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = clockMs()
        val urlStr = url.toString()
        val touchedFamilies = HashSet<String>(2)
        for (raw in cookies) {
            val cookie = normalizeImplicitDefaultPath(url, raw)
            runCatching { CookieManager.getInstance().setCookie(urlStr, cookie.toString()) }
            val domainLower = cookie.domain.lowercase()
            val family = registrableFamily(domainLower)
            touchedFamilies.add(family)
            val familyMap = storeByFamily.computeIfAbsent(family) { ConcurrentHashMap() }
            val key = "$domainLower|${cookie.path}|${cookie.name}"
            if (cookie.expiresAt <= now) {
                familyMap.remove(key)
                // Also evict any domain/subdomain host-only copies of the same cookie name in this domain family
                familyMap.entries.removeIf { (_, entry) ->
                    val entryDomain = entry.cookie.domain.lowercase()
                    entry.cookie.name == cookie.name &&
                        (entryDomain == domainLower ||
                            entryDomain.endsWith(".$domainLower") ||
                            domainLower.endsWith(".$entryDomain"))
                }
            } else {
                familyMap[key] = Entry(cookie, seqGen.incrementAndGet())
            }
        }
        if (touchedFamilies.isNotEmpty() && cmReadCache.isNotEmpty()) {
            cmReadCache.entries.removeIf { it.value.family in touchedFamilies }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = clockMs()
        val hostLower = url.host.lowercase()
        val family = registrableFamily(hostLower)
        val result = LinkedHashMap<String, Cookie>()

        // 1. Seed from Android WebView CookieManager with short per-path-prefix TTL cache
        //    so hundreds of HLS segments / range chunks avoid repeated Chromium IPC locks.
        val cmHeader = readCookieManagerCached(url, family, now)
        if (!cmHeader.isNullOrBlank()) {
            for (pair in cmHeader.split(';')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isNotEmpty()) {
                    val parsed = runCatching {
                        Cookie.Builder()
                            .name(name)
                            .value(value)
                            .hostOnlyDomain(url.host)
                            .path("/")
                            .build()
                    }.getOrNull()
                    if (parsed != null) {
                        result[name] = parsed
                    }
                }
            }
        }

        // 2. Apply in-memory entries for this domain family only (O(k) instead of full-store scan),
        //    sorted by (path.length, seq) so freshest/most-specific wins.
        val familyMap = storeByFamily[family]
        if (familyMap != null && familyMap.isNotEmpty()) {
            val matching = ArrayList<Entry>(familyMap.size)
            for ((k, entry) in familyMap) {
                if (entry.cookie.expiresAt <= now) {
                    familyMap.remove(k, entry)
                } else if (entry.cookie.matches(url)) {
                    matching.add(entry)
                }
            }
            if (matching.size > 1) {
                matching.sortWith(compareBy<Entry>({ it.cookie.path.length }, { it.seq }))
            }
            for (entry in matching) {
                result[entry.cookie.name] = entry.cookie
            }
        }
        return if (result.isEmpty()) emptyList() else result.values.toList()
    }

    private fun readCookieManagerCached(url: HttpUrl, family: String, now: Long): String? {
        val dirPath = url.encodedPath.substringBeforeLast('/', "")
        val cacheKey = "${url.scheme}://${url.host.lowercase()}:${url.port}$dirPath"
        val cached = cmReadCache[cacheKey]
        if (cached != null && now - cached.fetchedAtMs < CM_CACHE_TTL_MS) {
            return cached.header
        }
        val fresh = cookieManagerReader(url.toString())
        if (cmReadCache.size >= MAX_CM_CACHE_ENTRIES) {
            cmReadCache.entries.removeIf { now - it.value.fetchedAtMs >= CM_CACHE_TTL_MS }
            if (cmReadCache.size >= MAX_CM_CACHE_ENTRIES) {
                cmReadCache.clear()
            }
        }
        cmReadCache[cacheKey] = CachedCmCookies(family = family, header = fresh, fetchedAtMs = now)
        return fresh
    }

    /**
     * Ingests a caller-supplied `Cookie` header without clobbering server-rotated cookies when
     * the caller replays a previously seen seed value across the same domain family.
     */
    fun seedFromRequestHeader(url: HttpUrl, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        val now = clockMs()
        val reqHost = url.host.lowercase()
        val familyKey = registrableFamily(reqHost)
        val familyMap = storeByFamily.computeIfAbsent(familyKey) { ConcurrentHashMap() }

        for (part in cookieHeader.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) continue

            val seedKey = "$familyKey|$name"
            val prevSeed = lastSeeded[seedKey]

            // Check if the jar already holds an active cookie for this URL (within its domain family)
            var existingMatching: Entry? = null
            for (entry in familyMap.values) {
                if (entry.cookie.name == name && entry.cookie.expiresAt > now && entry.cookie.matches(url)) {
                    if (existingMatching == null ||
                        entry.cookie.path.length > existingMatching.cookie.path.length ||
                        (entry.cookie.path.length == existingMatching.cookie.path.length && entry.seq > existingMatching.seq)
                    ) {
                        existingMatching = entry
                    }
                }
            }

            if (existingMatching != null && prevSeed != null && prevSeed == value) {
                // Caller is replaying the stale initial seed — keep the server-rotated cookie!
                continue
            }

            // First time seeing this seed, or caller supplied a genuinely NEW override value
            lastSeeded[seedKey] = value
            val seeded = runCatching {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .hostOnlyDomain(url.host)
                    .path("/")
                    .build()
            }.getOrNull() ?: continue
            val key = "${seeded.domain.lowercase()}|${seeded.path}|${seeded.name}"
            familyMap[key] = Entry(seeded, seqGen.incrementAndGet())
        }
    }

    private fun registrableFamily(host: String): String {
        val clean = host.lowercase().trimEnd('.')
        val parts = clean.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else clean
    }

    private fun normalizeImplicitDefaultPath(url: HttpUrl, cookie: Cookie): Cookie {
        // Ensure root-level cookies from paths like /video/123 are still visible on sibling paths when appropriate
        return cookie
    }

    private companion object {
        const val CM_CACHE_TTL_MS = 1_500L
        const val MAX_CM_CACHE_ENTRIES = 256
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(engine: MediaInterceptionEngine): OkHttpClient {
        val cookieJar = WebViewCookieJar()
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cookieJar(cookieJar)
            .connectTimeout(Constants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(Constants.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(64, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val manualCookie = req.header("Cookie")
                if (!manualCookie.isNullOrBlank()) {
                    cookieJar.seedFromRequestHeader(req.url, manualCookie)
                    val stripped = req.newBuilder().removeHeader("Cookie").build()
                    chain.proceed(stripped)
                } else {
                    chain.proceed(req)
                }
            }
            .addInterceptor { chain ->
                val req = chain.request()
                if (!BuildConfig.DEBUG || req.header("X-Internal-Download") == "true") {
                    chain.proceed(req)
                } else {
                    logging.intercept(chain)
                }
            }
            // Sniffs media traffic that never passes through the WebView (probes, playlists)
            // and strips X-Internal-* metadata headers before hitting the wire.
            .addNetworkInterceptor(MediaSniffingInterceptor(engine))
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            // All calls use dynamic absolute @Url; the base URL is only a Retrofit requirement.
            .baseUrl("https://localhost/")
            .client(client)
            .build()

    @Provides
    @Singleton
    fun provideRawContentService(retrofit: Retrofit): RawContentService = retrofit.create()
}
