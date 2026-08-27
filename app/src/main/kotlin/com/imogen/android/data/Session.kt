package com.imogen.android.data

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.imogen.sdk.ClientOptions
import com.imogen.sdk.ImogenClient
import com.imogen.sdk.OAuthClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import android.content.Context as AndroidContext

/**
 * One account, ready to use: a client that authenticates itself, and an image loader that
 * does too.
 *
 * The refresh lives here rather than in a screen because every screen would otherwise
 * need to know about it. A request that comes back 401 asks this for a new token and is
 * sent again exactly once; a token already known to be stale is replaced before the
 * request goes out at all.
 */
class Session(
    val accountId: String,
    private val store: AccountStore,
    private val http: OkHttpClient,
    private val cacheDir: java.io.File,
    private val platformContext: PlatformContext,
    initial: Account,
) {
    @Volatile
    private var account: Account = initial

    private val refreshLock = Mutex()

    val serverUrl: String get() = account.serverUrl
    val snapshot: Account get() = account

    val client: ImogenClient = ImogenClient(
        ClientOptions(
            baseUrl = initial.serverUrl,
            token = { accessToken() },
            onUnauthorized = { forceRefresh() },
            engine = HttpClient(OkHttp) { engine { preconfigured = http } },
        ),
    )

    /**
     * Thumbnails need the same bearer token the API does, so the loader carries an
     * interceptor rather than the caller building headers at every call site.
     *
     * A disk cache per account, keyed by directory: two servers can hand out the same
     * asset id, and one cache would show the wrong photograph.
     */
    val imageLoader: ImageLoader = ImageLoader.Builder(platformContext)
        .crossfade(true)
        .memoryCache {
            MemoryCache.Builder().maxSizePercent(platformContext, 0.25).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images/$accountId"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .components {
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = {
                        http.newBuilder()
                            .addInterceptor { chain ->
                                // Coil fetches on a background thread of its own, so
                                // blocking for a token here costs nothing but a moment
                                // of that thread — and only when one needs refreshing.
                                val token = runBlocking { accessToken() }
                                val request = chain.request().newBuilder()
                                    .apply { token?.let { header("Authorization", "Bearer $it") } }
                                    .build()
                                chain.proceed(request)
                            }
                            .build()
                    },
                ),
            )
        }
        .build()

    /** The URL of one variant, for the image loader to fetch. */
    fun assetUrl(assetId: String, variant: String): String =
        "${account.serverUrl}/api/v1/assets/$assetId/$variant"

    fun faceThumbnailUrl(faceId: String): String =
        "${account.serverUrl}/api/v1/people/thumbnail/$faceId"

    suspend fun accessToken(): String? {
        val current = account.tokens
        if (!current.isExpired(System.currentTimeMillis())) return current.accessToken
        return forceRefresh() ?: current.accessToken
    }

    /**
     * Exchanges the refresh token. Returns null when there is nothing to exchange or the
     * server refused, which means the grant is gone and the account needs signing in
     * again — the caller decides how loudly to say so.
     */
    suspend fun forceRefresh(): String? = refreshLock.withLock {
        val current = account.tokens
        // Another caller may have refreshed while this one waited for the lock.
        if (!current.isExpired(System.currentTimeMillis())) return current.accessToken
        val refreshToken = current.refreshToken ?: return null

        val renewed = runCatching {
            OAuthClient(account.serverUrl, HttpClient(OkHttp) { engine { preconfigured = http } })
                .use { it.refresh(account.clientId, refreshToken) }
        }.getOrNull() ?: return null

        val tokens = TokenSet(
            accessToken = renewed.tokens.accessToken,
            // Rotation: the server hands back a new refresh token and retires the old
            // one. Keeping the old one would revoke the whole family on next use.
            refreshToken = renewed.tokens.refreshToken ?: refreshToken,
            obtainedAt = renewed.obtainedAt,
            expiresIn = renewed.tokens.expiresIn,
            scope = renewed.tokens.scope.ifEmpty { current.scope },
        )
        account = account.copy(tokens = tokens)
        store.update(accountId) { it.copy(tokens = tokens) }
        tokens.accessToken
    }

    /** Called when the stored account changes underneath us — a rename, a backup toggle. */
    fun adopt(updated: Account) {
        if (updated.id == accountId) account = updated
    }

    fun close() {
        client.close()
        imageLoader.shutdown()
    }
}

/**
 * The live sessions, one per account, built lazily and kept.
 *
 * Rebuilding a client per screen would mean a fresh connection pool and a cold image
 * cache every time somebody moved between tabs.
 */
class SessionManager(
    context: AndroidContext,
    private val store: AccountStore,
) {
    private val appContext = context.applicationContext
    private val cacheDir = appContext.cacheDir
    private val sessions = mutableMapOf<String, Session>()
    private val lock = Any()

    /**
     * One connection pool and one thread pool for the whole app. Every account shares
     * them; a phone with three servers configured should not hold three of each.
     */
    private val http: OkHttpClient = OkHttpClient.Builder().build()

    fun sessionFor(account: Account): Session = synchronized(lock) {
        sessions[account.id]?.also { it.adopt(account) }
            ?: Session(account.id, store, http, cacheDir, appContext, account)
                .also { sessions[account.id] = it }
    }

    fun forget(accountId: String) = synchronized(lock) {
        sessions.remove(accountId)?.close()
        cacheDir.resolve("images/$accountId").deleteRecursively()
        Unit
    }
}
