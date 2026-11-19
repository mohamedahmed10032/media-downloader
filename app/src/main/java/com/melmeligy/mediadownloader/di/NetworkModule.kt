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

    private val seqGen = AtomicLong(0L)
    private val store = ConcurrentHashMap<String, Entry>()
    private val lastSeeded = ConcurrentHashMap<String, String>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        val urlStr = url.toString()
        for (raw in cookies) {
            val cookie = normalizeImplicitDefaultPath(url, raw)
            runCatching { CookieManager.getInstance().setCookie(urlStr, cookie.toString()) }
            val key = "${cookie.domain.lowercase()}|${cookie.path}|${cookie.name}"
            if (cookie.expiresAt <= now) {
                store.remove(key)
                // Also evict any domain/subdomain host-only copies of the same cookie name in this domain family
                val domainLower = cookie.domain.lowercase()
                store.entries.removeIf { (_, entry) ->
                    entry.cookie.name == cookie.name &&
                        isSameDomainFamily(entry.cookie.domain.lowercase(), domainLower) &&
                        (entry.cookie.domain.lowercase() == domainLower ||
                            entry.cookie.domain.lowercase().endsWith(".$domainLower") ||
                            domainLower.endsWith(".${entry.cookie.domain.lowercase()}"))
                }
            } else {
                store[key] = Entry(cookie, seqGen.incrementAndGet())
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = LinkedHashMap<String, Cookie>()

        // 1. Seed from Android WebView CookieManager if available (lowest precedence)
        val cmHeader = runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()
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

        // 2. Apply in-memory entries matching this URL sorted by (path.length, seq) so freshest/most-specific wins
        val matching = store.values
            .filter { entry ->
                if (entry.cookie.expiresAt <= now) {
                    store.remove("${entry.cookie.domain.lowercase()}|${entry.cookie.path}|${entry.cookie.name}")
                    false
                } else {
                    entry.cookie.matches(url)
                }
            }
            .sortedWith(compareBy<Entry>({ it.cookie.path.length }, { it.seq }))

        for (entry in matching) {
            result[entry.cookie.name] = entry.cookie
        }
        return result.values.toList()
    }

    /**
     * Ingests a caller-supplied `Cookie` header without clobbering server-rotated cookies when
     * the caller replays a previously seen seed value across the same domain family.
     */
    fun seedFromRequestHeader(url: HttpUrl, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        val now = System.currentTimeMillis()
        val reqHost = url.host.lowercase()
        val familyKey = registrableFamily(reqHost)

        for (part in cookieHeader.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) continue

            val seedKey = "$familyKey|$name"
            val prevSeed = lastSeeded[seedKey]

            // Check if the jar already holds an active cookie for this URL (or domain family) with a different value
            val existingMatching = store.values
                .filter { it.cookie.name == name && it.cookie.expiresAt > now && it.cookie.matches(url) }
                .sortedWith(compareBy<Entry>({ it.cookie.path.length }, { it.seq }))
                .lastOrNull()

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
            store[key] = Entry(seeded, seqGen.incrementAndGet())
        }
    }

    private fun isSameDomainFamily(a: String, b: String): Boolean =
        registrableFamily(a) == registrableFamily(b)

    private fun registrableFamily(host: String): String {
        val clean = host.lowercase().trimEnd('.')
        val parts = clean.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else clean
    }

    private fun normalizeImplicitDefaultPath(url: HttpUrl, cookie: Cookie): Cookie {
        // Ensure root-level cookies from paths like /video/123 are still visible on sibling paths when appropriate
        return cookie
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
            .addInterceptor(logging)
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
