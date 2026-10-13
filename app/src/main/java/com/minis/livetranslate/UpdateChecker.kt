package com.minis.livetranslate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update checking against GitHub Releases.
 *
 * The repo is public, so the API works without a token. We deliberately compare
 * versionCode rather than parsing versionName: the code is an integer, so it
 * never suffers the "0.10.0 < 0.9.0" string-comparison trap.
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val changelog: String,
)

object UpdateChecker {

    private const val OWNER = "xiaonilll"
    private const val REPO = "livetranslate"
    private const val LATEST_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Thrown for expected, human-readable failures (offline, no release yet). */
    class NoUpdate(val currentCode: Int) : Exception("已是最新版本")
    class CheckFailed(msg: String) : Exception(msg)

    suspend fun check(ctx: Context): UpdateInfo = withContext(Dispatchers.IO) {
        val current = currentVersionCode(ctx)

        val body = try {
            httpGet(LATEST_API)
        } catch (e: IOException) {
            throw CheckFailed("网络不可用：${e.message}")
        }

        val release = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw CheckFailed("返回内容无法解析")
        }

        if (release.has("message")) {
            // GitHub returns {"message":"Not Found"} when there are no releases
            throw CheckFailed("尚未发布任何版本")
        }

        val tag = release.optString("tag_name", "").removePrefix("v")
        val notes = release.optString("body", "")
        val assets = release.optJSONArray("assets")

        var apkUrl = ""
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url", "")
                    size = a.optLong("size", 0L)
                    break
                }
            }
        }
        if (apkUrl.isEmpty()) throw CheckFailed("这个版本没有附带 APK")

        val remoteCode = versionCodeFromName(tag)
        if (remoteCode <= current) throw NoUpdate(current)

        UpdateInfo(tag, remoteCode, apkUrl, size, notes)
    }

    fun currentVersionCode(ctx: Context): Int {
        val pm = ctx.packageManager
        return try {
            val pi = pm.getPackageInfo(ctx.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pi.versionCode
        } catch (e: Exception) {
            0
        }
    }

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /**
     * Turn "0.3.0" into the same integer scheme used by versionCode in build.gradle.
     * Must stay in sync with that file: versionCode = minor * 100 + patch.
     */
    private fun versionCodeFromName(name: String): Int {
        val parts = name.trim().removePrefix("v").split(".")
        val minor = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        return minor * 100 + patch
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LiveTranslate-Android")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
