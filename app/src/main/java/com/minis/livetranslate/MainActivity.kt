package com.minis.livetranslate

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
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                toggleOverlay()
            }
        }

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        OverlayBus.connected.observeForever(connObserver)
        refresh()
    }

    private fun toggleOverlay() {
        OverlayBus.enabled.value = !(OverlayBus.enabled.value ?: false)
        val i = Intent(this, OverlayService::class.java)
        if (OverlayBus.enabled.value == true) startService(i) else stopService(i)
        refresh()
    }

    private fun refresh() {
        val acc = OverlayBus.connected.value == true
        val ov = OverlayBus.enabled.value == true
        findViewById<TextView>(R.id.tvStatus).text = buildString {
            append("无障碍服务：").append(if (acc) "已开启 ✓" else "未开启 ✗").append('\n')
            append("悬浮窗：").append(if (ov) "运行中 ✓" else "已停止").append('\n')
            append('\n')
            append(
                if (acc && ov) "现在去任意输入框打字，译文会出现在下方悬浮条。"
                else "请先开启无障碍服务，再点「启动悬浮窗」。"
            )
        }
    }

    override fun onDestroy() {
        OverlayBus.connected.removeObserver(connObserver)
        super.onDestroy()
    }
}
