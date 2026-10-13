package com.minis.livetranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the floating bar and the debounce loop.
 *
 * The debounce lives here (not in the accessibility service) so that a burst of
 * keystrokes collapses into exactly one API call, and so a slower in-flight
 * request can be cancelled the moment the text changes again.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bar: LinearLayout? = null
    private var srcView: TextView? = null
    private var dstView: TextView? = null
    private var statusView: TextView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var debounceJob: Job? = null
    private var lastText: String = ""

    private val inputObserver = Observer<String> { text ->
        onTextChanged(text)
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundSafely()
        buildBar()
        OverlayBus.rawInput.observeForever(inputObserver)
    }

    private fun startForegroundSafely() {
        val chId = "lt_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(chId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(chId, "Live Translate",
                        NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, chId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("同步翻译运行中")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        startForeground(NOTIF_ID, n)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildBar() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF21A1D24.toInt())
                setStroke(dp(1), 0xFF2E3542.toInt())
            }
        }

        statusView = TextView(this).apply {
            text = "等待输入…"
            setTextColor(0xFF8B93A5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        srcView = TextView(this).apply {
            setTextColor(0xFF7E879B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
        }
        dstView = TextView(this).apply {
            setTextColor(0xFF4C8DFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 3
        }

        root.addView(statusView)
        root.addView(srcView)
        root.addView(dstView)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(8)
            y = dp(120)
        }
        wm.addView(root, lp)
        bar = root
    }

    private fun onTextChanged(text: String) {
        if (text == lastText) return
        lastText = text
        debounceJob?.cancel()

        if (text.isBlank()) {
            srcView?.text = ""
            dstView?.text = ""
            statusView?.text = "等待输入…"
            return
        }

        statusView?.text = "输入中…"
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            statusView?.text = "翻译中…"
            try {
                val t = Translator.translate(text)
                srcView?.text = text
                dstView?.text = t.text
                statusView?.text = "${t.src} → ${t.dst} · ${t.ms}ms"
            } catch (e: Exception) {
                statusView?.text = "失败: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        debounceJob?.cancel()
        scope.cancel()
        OverlayBus.rawInput.removeObserver(inputObserver)
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 4421
        const val DEBOUNCE_MS = 300L
    }
}
