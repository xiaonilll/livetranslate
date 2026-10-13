package com.minis.livetranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
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
 * The debounce lives here (not in the accessibility service) so a burst of
 * keystrokes collapses into one API call, and an in-flight request is dropped
 * the moment the text changes again.
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

    private val inputObserver = Observer<String> { text -> onTextChanged(text) }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundSafely()
        buildBar()
        OverlayBus.rawInput.observeForever(inputObserver)
    }

    private fun startForegroundSafely() {
        try {
            val chId = CHANNEL_ID
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = getSystemService(NotificationManager::class.java)
                if (mgr.getNotificationChannel(chId) == null) {
                    mgr.createNotificationChannel(
                        NotificationChannel(chId, "同步翻译", NotificationManager.IMPORTANCE_MIN)
                    )
                }
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, chId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val n = builder
                .setContentTitle("同步翻译运行中")
                .setContentText("在输入框打字即可看到译文")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            Log.i(TAG, "foreground started")
        } catch (e: Exception) {
            // A failed startForeground must not take the overlay down with it.
            Log.e(TAG, "startForeground failed", e)
        }
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

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(8)
            y = dp(160)
        }
        try {
            wm.addView(root, lp)
            bar = root
            Log.i(TAG, "overlay attached")
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            statusView?.text = "悬浮窗创建失败: ${e.message}"
        }
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
                val t = Translator.translate(applicationContext, text)
                srcView?.text = text
                dstView?.text = t.text
                statusView?.text = "${t.src} → ${t.dst} · ${t.ms}ms"
                OverlayBus.translation.value = t
                Log.i(TAG, "translated ${t.ms}ms")
            } catch (e: Exception) {
                statusView?.text = "失败: ${e.message}"
                Log.e(TAG, "translate failed", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If MIUI kills and restarts us, come back in the same state.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        debounceJob?.cancel()
        scope.cancel()
        OverlayBus.rawInput.removeObserver(inputObserver)
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LiveTranslate"
        private const val NOTIF_ID = 4421
        private const val CHANNEL_ID = "lt_overlay"
        const val DEBOUNCE_MS = 300L
    }
}
