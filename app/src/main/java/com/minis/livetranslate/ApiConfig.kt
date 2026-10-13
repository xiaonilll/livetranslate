package com.minis.livetranslate

import android.content.Context

/**
 * User-editable translation backend settings.
 *
 * Ship-with defaults point at Google's free endpoint so the app works on first
 * launch, but every part of the request is overridable: users can drop in their
 * own key, point at a self-hosted gateway, or switch to a proprietary API.
 */
data class ApiConfig(
    val preset: EnginePreset,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val authHeader: String,
    val authPrefix: String,
    val sourceLang: String,
    val targetLang: String,
) {
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() && (preset == EnginePreset.GOOGLE_FREE || apiKey.isNotBlank())
}

enum class EnginePreset(
    val label: String,
    val defaultEndpoint: String,
    val authHeader: String,
    val authPrefix: String,
    val needsKey: Boolean,
    val bodyStyle: BodyStyle,
) {
    /** translate.googleapis.com/translate_a/single - no key, returns nested arrays. */
    GOOGLE_FREE(
        label = "Google 免费端点（无需 Key）",
        defaultEndpoint = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t",
        authHeader = "",
        authPrefix = "",
        needsKey = false,
        bodyStyle = BodyStyle.GOOGLE_QUERY,
    ),

    /** Cloud Translation v2 - JSON in, JSON out, key as query param. */
    GOOGLE_V2(
        label = "Google Cloud Translation v2（需 Key）",
        defaultEndpoint = "https://translation.googleapis.com/language/translate/v2",
        authHeader = "",
        authPrefix = "",
        needsKey = true,
        bodyStyle = BodyStyle.GOOGLE_V2,
    ),

    /** DeepL: Authorization: DeepL-Auth-Key <key>. */
    DEEPL(
        label = "DeepL（需 Key）",
        defaultEndpoint = "https://api-free.deepl.com/v2/translate",
        authHeader = "Authorization",
        authPrefix = "DeepL-Auth-Key ",
        needsKey = true,
        bodyStyle = BodyStyle.FORM,
    ),

    /** Any OpenAI-compatible /chat/completions. We ask for the translation only. */
    OPENAI(
        label = "OpenAI 兼容接口（需 Key）",
        defaultEndpoint = "https://api.openai.com/v1/chat/completions",
        authHeader = "Authorization",
        authPrefix = "Bearer ",
        needsKey = true,
        bodyStyle = BodyStyle.OPENAI_CHAT,
    ),

    /** User supplies everything; we only substitute {text} and the placeholders. */
    CUSTOM(
        label = "自定义（自己填全部）",
        defaultEndpoint = "",
        authHeader = "Authorization",
        authPrefix = "Bearer ",
        needsKey = false,
        bodyStyle = BodyStyle.OPENAI_CHAT,
    ),
}

enum class BodyStyle { GOOGLE_QUERY, GOOGLE_V2, FORM, OPENAI_CHAT }

object ApiConfigStore {
    private const val PREFS = "livetranslate_settings"

    fun load(ctx: Context): ApiConfig {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val preset = runCatching {
            EnginePreset.valueOf(p.getString(KEY_PRESET, EnginePreset.GOOGLE_FREE.name)!!)
        }.getOrDefault(EnginePreset.GOOGLE_FREE)

        return ApiConfig(
            preset = preset,
            endpoint = p.getString(KEY_ENDPOINT, null) ?: preset.defaultEndpoint,
            apiKey = p.getString(KEY_API_KEY, "") ?: "",
            model = p.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini",
            authHeader = p.getString(KEY_AUTH_HEADER, null) ?: preset.authHeader,
            authPrefix = p.getString(KEY_AUTH_PREFIX, null) ?: preset.authPrefix,
            sourceLang = p.getString(KEY_SRC, "auto") ?: "auto",
            targetLang = p.getString(KEY_DST, "auto") ?: "auto",
        )
    }

    fun save(ctx: Context, cfg: ApiConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRESET, cfg.preset.name)
            .putString(KEY_ENDPOINT, cfg.endpoint)
            .putString(KEY_API_KEY, cfg.apiKey)
            .putString(KEY_MODEL, cfg.model)
            .putString(KEY_AUTH_HEADER, cfg.authHeader)
            .putString(KEY_AUTH_PREFIX, cfg.authPrefix)
            .putString(KEY_SRC, cfg.sourceLang)
            .putString(KEY_DST, cfg.targetLang)
            .apply()
    }

    fun reset(ctx: Context): ApiConfig {
        val preset = EnginePreset.GOOGLE_FREE
        val cfg = ApiConfig(
            preset = preset,
            endpoint = preset.defaultEndpoint,
            apiKey = "",
            model = "gpt-4o-mini",
            authHeader = preset.authHeader,
            authPrefix = preset.authPrefix,
            sourceLang = "auto",
            targetLang = "auto",
        )
        save(ctx, cfg)
        return cfg
    }

    private const val KEY_PRESET = "preset"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_AUTH_HEADER = "auth_header"
    private const val KEY_AUTH_PREFIX = "auth_prefix"
    private const val KEY_SRC = "src_lang"
    private const val KEY_DST = "dst_lang"
}
