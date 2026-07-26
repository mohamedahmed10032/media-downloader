package com.melmeligy.mediadownloader.di

import com.melmeligy.mediadownloader.BuildConfig
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.data.remote.RawContentService
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffingInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.create
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(engine: MediaInterceptionEngine): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .connectTimeout(Constants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(Constants.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(logging)
            // Sniffs media traffic that never passes through the WebView (probes, playlists).
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
