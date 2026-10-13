package com.minis.livetranslate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Translation(val src: String, val dst: String, val text: String, val ms: Long)

/** Google's free endpoint. Same one the shell prototype used. */
object Translator {
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun detect(text: String): Pair<String, String> {
        val han = text.count { it in '\u4e00'..'\u9fff' }
        val lat = text.count { it.isLetter() && it.code < 128 }
        return when {
            han == 0 && lat > 0 -> "en" to "zh-CN"
            lat == 0 && han > 0 -> "zh-CN" to "en"
            else -> if (han >= lat) "zh-CN" to "en" else "en" to "zh-CN"
        }
    }

    suspend fun translate(text: String): Translation = withContext(Dispatchers.IO) {
        val (src, dst) = detect(text)
        val url = ENDPOINT +
            "?client=gtx&sl=" + src +
            "&tl=" + dst +
            "&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
        val t0 = System.currentTimeMillis()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val ms = System.currentTimeMillis() - t0
            if (!resp.isSuccessful || body.isEmpty()) {
                throw RuntimeException("translate HTTP ${resp.code}")
            }
            val root = JSONArray(body)
            val segs = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                val piece = seg.optString(0, "")
                if (piece.isNotEmpty()) sb.append(piece)
            }
            Translation(src, dst, sb.toString(), ms)
        }
    }
}
