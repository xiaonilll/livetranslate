package com.minis.livetranslate

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an APK and hands it to the system package installer.
 *
 * Two hard platform rules shape this:
 *  1. A file:// Uri passed to the installer throws FileUriExposedException on
 *     Android 7+, so everything goes through our FileProvider.
 *  2. Only the system installer can replace an installed app. We can at best
 *     launch it; the final confirm tap is the user's.
 */
object ApkInstaller {

    private const val TAG = "LiveTranslate"
    private const val APK_NAME = "update.apk"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null

    /** Where downloaded APKs live: app-private, so no storage permission needed. */
    private fun apkFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)

    fun download(ctx: Context, info: UpdateInfo, onDone: (Result<File>) -> Unit) {
        val target = apkFile(ctx)
        if (target.exists()) target.delete()

        // GitHub asset URLs redirect to objects.githubusercontent.com.
        // DownloadManager follows redirects; a plain OkHttp call needs them too,
        // which is why we let the system handle it here.
        val req = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("同步翻译 ${info.versionName}")
            setDescription("正在下载更新")
            setDestinationInExternalFilesDir(
                ctx, Environment.DIRECTORY_DOWNLOADS, APK_NAME
            )
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setMimeType("application/vnd.android.package-archive")
        }

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = try {
            dm.enqueue(req)
        } catch (e: Exception) {
            Log.e(TAG, "enqueue failed, falling back to direct download", e)
            directDownload(ctx, info, target, onDone)
            return
        }

        // The receiver must be unregistered once it fires, otherwise it leaks.
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val doneId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (doneId != id) return
                runCatching { ctx.unregisterReceiver(this) }
                receiver = null

                scope.launch {
                    val ok = isSuccessful(dm, id)
                    withContext(Dispatchers.Main) {
                        if (ok && target.exists()) {
                            onDone(Result.success(target))
                        } else {
                            onDone(Result.failure(IOException("下载未完成")))
                        }
                    }
                }
            }
        }
        receiver = r
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(
                r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun isSuccessful(dm: DownloadManager, id: Long): Boolean {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c == null || !c.moveToFirst()) return false
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    /** Fallback when DownloadManager refuses the request (e.g. no handler). */
    private fun directDownload(
        ctx: Context,
        info: UpdateInfo,
        target: File,
        onDone: (Result<File>) -> Unit,
    ) {
        scope.launch {
            val result = runCatching {
                var url = info.apkUrl
                var hops = 0
                while (hops < 5) {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", "LiveTranslate-Android")
                    }
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: break
                        conn.disconnect()
                        url = loc
                        hops++
                        continue
                    }
                    conn.inputStream.use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                    conn.disconnect()
                    break
                }
                if (!target.exists() || target.length() == 0L) {
                    throw IOException("下载为空")
                }
                target
            }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /** Launch the system installer. Returns false if no activity handled it. */
    fun install(ctx: Context, apk: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    uri, "application/vnd.android.package-archive"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "install intent failed", e)
            Toast.makeText(
                ctx, "无法启动安装器：${e.message}", Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    /** Android 8+ needs per-app permission before the installer will accept us. */
    fun canRequestInstalls(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermissionSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }
    }
}
