package com.miaocun.app.douyin

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 极简 HTTP 封装：只做 GET 文本与流式下载。
 */
object Http {

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    const val REFERER = "https://www.douyin.com/"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun request(url: String, referer: String?): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { if (!referer.isNullOrEmpty()) header("Referer", referer) }
            .build()

    fun getString(url: String, referer: String? = REFERER): String {
        client.newCall(request(url, referer)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("响应为空")
        }
    }

    fun downloadTo(
        url: String,
        referer: String?,
        out: OutputStream,
        onProgress: (Long, Long) -> Unit
    ): Long {
        client.newCall(request(url, referer)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")
            val total = body.contentLength()
            var sum = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    sum += read
                    onProgress(sum, total)
                }
            }
            out.flush()
            return sum
        }
    }
}
