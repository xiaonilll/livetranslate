package com.minis.livetranslate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Translation(val src: String, val dst: String, val text: String, val ms: Long)

/**
 * Dispatches a translation request according to the user's [ApiConfig].
 *
 * Every engine is reached through the same entry point so the overlay never has
 * to care which backend is active. Unknown/absent config degrades to the free
 * Google endpoint, which needs no key.
 */
object Translator {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Chinese-heavy -> English, Latin-heavy -> Chinese. */
    fun detect(text: String): Pair<String, String> {
        val han = text.count { it in '\u4e00'..'\u9fff' }
        val lat = text.count { it.isLetter() && it.code < 128 }
        return when {
            han == 0 && lat > 0 -> "en" to "zh-CN"
            lat == 0 && han > 0 -> "zh-CN" to "en"
            else -> if (han >= lat) "zh-CN" to "en" else "en" to "zh-CN"
        }
    }

    private fun resolveLangs(cfg: ApiConfig, text: String): Pair<String, String> {
        val (autoSrc, autoDst) = detect(text)
        val src = if (cfg.sourceLang == "auto" || cfg.sourceLang.isBlank()) autoSrc else cfg.sourceLang
        val dst = if (cfg.targetLang == "auto" || cfg.targetLang.isBlank()) autoDst else cfg.targetLang
        return src to dst
    }

    suspend fun translate(ctx: Context, text: String): Translation = withContext(Dispatchers.IO) {
        val cfg = ApiConfigStore.load(ctx)
        val (src, dst) = resolveLangs(cfg, text)
        val t0 = System.currentTimeMillis()
        val out = when (cfg.preset.bodyStyle) {
            BodyStyle.GOOGLE_QUERY -> googleFree(cfg, src, dst, text)
            BodyStyle.GOOGLE_V2 -> googleV2(cfg, src, dst, text)
            BodyStyle.FORM -> deepL(cfg, src, dst, text)
            BodyStyle.OPENAI_CHAT -> openAiChat(cfg, src, dst, text)
        }
        Translation(src, dst, out, System.currentTimeMillis() - t0)
    }

    /** Fire the request, applying the configured auth header when present. */
    private fun call(cfg: ApiConfig, req: Request): String {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val brief = body.take(200).replace('\n', ' ')
                throw RuntimeException("HTTP ${resp.code}: $brief")
            }
            return body
        }
    }

    private fun withAuth(cfg: ApiConfig, b: Request.Builder): Request.Builder {
        if (cfg.authHeader.isNotBlank() && cfg.apiKey.isNotBlank()) {
            b.header(cfg.authHeader, cfg.authPrefix + cfg.apiKey)
        }
        b.header("User-Agent", "LiveTranslate/0.2 (Android)")
        return b
    }

    private fun googleFree(cfg: ApiConfig, src: String, dst: String, text: String): String {
        val base = cfg.endpoint.ifBlank { EnginePreset.GOOGLE_FREE.defaultEndpoint }
        val url = "$base&sl=${enc(src)}&tl=${enc(dst)}&q=${enc(text)}"
        val body = call(cfg, Request.Builder().url(url).build())
        // [[["译文","原文",...],...],null,"zh-CN",...]
        val root = JSONArray(body)
        val segs = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val seg = segs.optJSONArray(i) ?: continue
            if (!seg.isNull(0)) sb.append(seg.optString(0, ""))
        }
        return sb.toString()
    }

    private fun googleV2(cfg: ApiConfig, src: String, dst: String, text: String): String {
        val url = "${cfg.endpoint}&key=${enc(cfg.apiKey)}"
        val json = JSONObject()
            .put("q", text)
            .put("target", dst)
            .put("format", "text")
        if (src != "auto") json.put("source", src)
        val req = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON))
            .build()
        val body = call(cfg, req)
        return JSONObject(body)
            .getJSONObject("data")
            .getJSONArray("translations")
            .getJSONObject(0)
            .getString("translatedText")
    }

    private fun deepL(cfg: ApiConfig, src: String, dst: String, text: String): String {
        val form = FormBody.Builder()
            .add("text", text)
            .add("target_lang", deepLCode(dst))
        if (src != "auto") form.add("source_lang", deepLCode(src))
        val req = withAuth(cfg, Request.Builder().url(cfg.endpoint)).post(form.build()).build()
        val body = call(cfg, req)
        return JSONObject(body).getJSONArray("translations").getJSONObject(0).getString("text")
    }

    private fun openAiChat(cfg: ApiConfig, src: String, dst: String, text: String): String {
        val sys = "You are a translation engine. Translate the user's text from " +
            "$src to $dst. Output ONLY the translation, no quotes, no explanation."
        val json = JSONObject()
            .put("model", cfg.model.ifBlank { "gpt-4o-mini" })
            .put("temperature", 0)
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", text))
            })
        val req = withAuth(cfg, Request.Builder().url(cfg.endpoint))
            .post(json.toString().toRequestBody(JSON))
            .build()
        val body = call(cfg, req)
        return JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    /** DeepL wants EN / ZH, not en / zh-CN. */
    private fun deepLCode(code: String): String = when {
        code.startsWith("zh") -> "ZH"
        code.contains("-") -> code.substringBefore("-").uppercase()
        else -> code.uppercase()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
