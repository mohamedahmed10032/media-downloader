package com.melmeligy.mediadownloader.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Retrofit service for fetching arbitrary page/playlist text by absolute URL.
 * Byte-range and streaming downloads use OkHttp directly (see the download engine),
 * because ranged/segmented transfers need lower-level control than Retrofit provides.
 */
interface RawContentService {
    @Streaming
    @GET
    suspend fun fetch(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): Response<ResponseBody>
}
