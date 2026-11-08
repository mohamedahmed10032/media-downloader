package com.melmeligy.mediadownloader.intercept

import io.mockk.mockk
import io.mockk.verify
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class MediaSniffingInterceptorTest {

    private val engine = mockk<MediaInterceptionEngine>(relaxed = true)
    private val interceptor = MediaSniffingInterceptor(engine)

    @Test
    fun intercept_bypassesEngineWhenDownloadHeaderPresent() {
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .body("video-content".toByteArray().toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val request = Request.Builder()
            .url("https://example.com/video.mp4")
            .header("X-Internal-Download", "true")
            .build()

        client.newCall(request).execute().close()

        verify(exactly = 0) { engine.submit(any()) }
    }

    @Test
    fun intercept_submitsMediaWhenDownloadHeaderAbsent() {
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .body("video-content".toByteArray().toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val request = Request.Builder()
            .url("https://example.com/video.mp4")
            .build()

        client.newCall(request).execute().close()

        verify(atLeast = 1) { engine.submit(any()) }
    }
}
