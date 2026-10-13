package com.minis.livetranslate

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val connObserver = Observer<Boolean> { refresh() }
    private var pendingUpdate: UpdateInfo? = null
    private var checkedOnLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            // Overlay permission is granted. Just make sure the service is up.
            // No in-memory flag: every tap queries real system state instead, so
            // the process being recreated cannot flip this into a "stop".
            startOverlayService()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            refresh()
        }

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            checkForUpdates(userInitiated = true)
        }

        OverlayBus.connected.observeForever(connObserver)
        OverlayBus.translation.observeForever { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Auto-check once per activity instance, not on every resume, so the
        // user isn't nagged after returning from the installer.
        if (!checkedOnLaunch) {
            checkedOnLaunch = true
            checkForUpdates(userInitiated = false)
        }
    }

    private fun startOverlayService() {
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    /** Ask ActivityManager whether our service actually runs, don't trust a static flag. */
    private fun isOverlayRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val running = am.getRunningServices(Int.MAX_VALUE)
        return running.any { it.service.className == OverlayService::class.java.name }
    }

    // ---- updates -----------------------------------------------------------

    private fun checkForUpdates(userInitiated: Boolean) {
        val btn = findViewById<Button>(R.id.btnUpdate)
        if (userInitiated) {
            btn.isEnabled = false
            btn.text = "检查中…"
        }
        lifecycleScope.launch {
            try {
                val info = UpdateChecker.check(this@MainActivity)
                pendingUpdate = info
                if (userInitiated) showUpdateDialog(info)
                else refresh()  // surface the "有新版" hint on the button
            } catch (e: UpdateChecker.NoUpdate) {
                if (userInitiated) {
                    toast("已是最新版本（${UpdateChecker.currentVersionName(this@MainActivity)}）")
                }
            } catch (e: Exception) {
                // Silent on auto-check: the user did not ask, and offline is normal.
                if (userInitiated) toast("检查失败：${e.message}")
            } finally {
                if (userInitiated) {
                    btn.isEnabled = true
                    btn.text = "检查更新"
                }
            }
        }
    }

    private fun renderUpdateButton() {
        val btn = findViewById<Button>(R.id.btnUpdate)
        val cur = UpdateChecker.currentVersionName(this)
        val up = pendingUpdate
        if (up != null) {
            btn.text = "⬆ 有新版本 v${up.versionName}（当前 $cur）"
        } else {
            btn.text = "检查更新（当前 $cur）"
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val sizeMb = if (info.apkSizeBytes > 0) {
            "%.1f MB".format(info.apkSizeBytes / 1024.0 / 1024.0)
        } else {
            "未知大小"
        }
        val msg = buildString {
            append("当前版本：").append(UpdateChecker.currentVersionName(this@MainActivity)).append('\n')
            append("最新版本：").append(info.versionName).append("（").append(sizeMb).append("）")
            if (info.changelog.isNotBlank()) {
                append("\n\n").append(info.changelog.take(600))
            }
            append("\n\n下载完成后会弹出系统安装界面，需要你点一下「安装」。")
        }
        AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(msg)
            .setPositiveButton("下载并安装") { _, _ -> startUpdate(info) }
            .setNegativeButton("以后再说", null)
            .show()
    }

    private fun startUpdate(info: UpdateInfo) {
        // Android 8+ gates this behind a per-app toggle; send them there first.
        if (!ApkInstaller.canRequestInstalls(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要一项授权")
                .setMessage(
                    "系统要求先允许「同步翻译」安装应用，才能自动更新。\n\n" +
                        "点「去授权」后会打开设置页，打开开关再回来即可。"
                )
                .setPositiveButton("去授权") { _, _ ->
                    ApkInstaller.openInstallPermissionSettings(this)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        Toast.makeText(this, "开始下载更新…", Toast.LENGTH_SHORT).show()
        findViewById<Button>(R.id.btnUpdate).text = "下载中…"
        ApkInstaller.download(this, info) { result ->
            result.fold(
                onSuccess = { apk ->
                    findViewById<Button>(R.id.btnUpdate).text = "下载完成，拉起安装器"
                    if (!ApkInstaller.install(this, apk)) {
                        toast("无法拉起安装器，请手动安装")
                    }
                    renderUpdateButton()
                },
                onFailure = { e ->
                    toast("下载失败：${e.message}")
                    renderUpdateButton()
                },
            )
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---- status ------------------------------------------------------------

    private fun refresh() {
        val acc = OverlayBus.connected.value == true
        val ov = isOverlayRunning()
        val last = OverlayBus.translation.value

        findViewById<TextView>(R.id.tvStatus).text = buildString {
            append("无障碍服务：").append(if (acc) "已开启 ✓" else "未开启 ✗").append('\n')
            append("悬浮窗：").append(if (ov) "运行中 ✓" else "未运行 ✗")
            if (last != null && last.text.isNotEmpty()) {
                append('\n').append("最近翻译：").append(last.text)
            }
            append('\n').append('\n')
            append(
                when {
                    !acc -> "① 点下面第一颗按钮，开启「同步翻译 · 输入监听」"
                    !ov -> "② 无障碍已就绪，点「启动悬浮窗」"
                    else -> "③ 已完成，去任意输入框打字试试"
                }
            )
            pendingUpdate?.let {
                append('\n').append('\n').append("发现新版本 v").append(it.versionName).append("，点下方按钮更新")
            }
        }
        renderUpdateButton()
    }

    override fun onDestroy() {
        OverlayBus.connected.removeObserver(connObserver)
        super.onDestroy()
    }
}
