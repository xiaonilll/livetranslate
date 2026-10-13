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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer

class MainActivity : AppCompatActivity() {

    private val connObserver = Observer<Boolean> { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                // Not granted yet: the only thing we can do is send them to the toggle.
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            // Overlay permission is granted. Just make sure the service is up.
            // No in-memory flag: on every tap we query the real system state and
            // force the service into the running state. Tapping twice is harmless.
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

        OverlayBus.connected.observeForever(connObserver)
        OverlayBus.translation.observeForever { refresh() }
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

    override fun onResume() {
        super.onResume()
        refresh()
    }

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
        }
    }

    override fun onDestroy() {
        OverlayBus.connected.removeObserver(connObserver)
        super.onDestroy()
    }
}
