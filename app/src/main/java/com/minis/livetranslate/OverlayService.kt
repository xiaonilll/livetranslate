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
import android.view.View
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
 * The floating translation bar.
 *
 * Visibility is driven by the soft keyboard: the bar is only on screen while the
 * IME is up, and it sits directly above the keyboard rather than at a fixed
 * screen position. When the keyboard closes the bar is detached entirely, so it
 * never occludes the home screen or other apps.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var bar: LinearLayout? = null
    private var attached = false

    private var srcView: TextView? = null
    private var dstView: TextView? = null
    private var statusView: TextView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var debounceJob: Job? = null
    private var lastText: String = ""

    private val inputObserver = Observer<String> { onTextChanged(it) }
    private val imeObserver = Observer<Boolean> { visible ->
        if (visible) attachAndShow() else hideAndDetach()
    }
    private val imeHeightObserver = Observer<Int> { reposition(it) }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundSafely()

        OverlayBus.rawInput.observeForever(inputObserver)
        OverlayBus.imeVisible.observeForever(imeObserver)
        OverlayBus.imeHeightPx.observeForever(imeHeightObserver)

        // If the keyboard is already up when we start, show immediately.
        if (OverlayBus.imeVisible.value == true) {
            attachAndShow()
        } else {
            Log.i(TAG, "waiting for keyboard")
        }
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
                .setContentTitle("同步翻译待命中")
                .setContentText("弹出键盘时自动显示译文")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    // ---- attach / detach ---------------------------------------------------

    /** Build (once) and attach the bar above the keyboard. */
    private fun attachAndShow() {
        if (attached) {
            reposition(OverlayBus.imeHeightPx.value ?: 0)
            return
        }
        try {
            val root = buildBar()
            lp = WindowManager.LayoutParams(
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
                x = dp(6f)
                // Lane between the keyboard's top edge and the bar.
                y = (OverlayBus.imeHeightPx.value ?: 0) + dp(GAP_ABOVE_IME_DP)
            }
            wm.addView(root, lp)
            bar = root
            attached = true
            Log.i(TAG, "overlay attached, y=${lp.y}")
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
        }
    }

    private fun hideAndDetach() {
        debounceJob?.cancel()
        lastText = ""
        clearViews()
        if (!attached) return
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        attached = false
        Log.i(TAG, "overlay detached")
    }

    /** Keep the bar pinned just above the keyboard as its height changes. */
    private fun reposition(imeHeight: Int) {
        if (!attached) return
        lp.y = imeHeight + dp(GAP_ABOVE_IME_DP)
        runCatching { wm.updateViewLayout(bar, lp) }
    }

    private fun clearViews() {
        srcView?.text = ""
        dstView?.text = ""
        statusView?.text = "等待输入…"
    }

    private fun buildBar(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(0xF21A1D24.toInt())
                setStroke(dp(1f), 0xFF2E3542.toInt())
            }
        }

        statusView = TextView(this).apply {
            text = "等待输入…"
            setTextColor(0xFF8B93A5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            visibility = View.GONE
        }
        dstView = TextView(this).apply {
            setTextColor(0xFF4C8DFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 3
        }
        srcView = TextView(this).apply {
            setTextColor(0xFF6E7688.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
        }

        root.addView(statusView)
        root.addView(dstView)
        root.addView(srcView)
        return root
    }

    // ---- translation -------------------------------------------------------

    private fun onTextChanged(text: String) {
        if (text == lastText) return
        lastText = text
        debounceJob?.cancel()

        if (text.isBlank()) {
            clearViews()
            return
        }
        if (attached) statusView?.text = "…"

        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            try {
                val t = Translator.translate(applicationContext, text)
                if (!attached) return@launch
                srcView?.text = text
                dstView?.text = t.text
                statusView?.text = "${t.src}→${t.dst} · ${t.ms}ms"
                OverlayBus.translation.value = t
            } catch (e: Exception) {
                if (attached) statusView?.text = "失败: ${e.message}"
                Log.e(TAG, "translate failed", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        debounceJob?.cancel()
        scope.cancel()
        OverlayBus.rawInput.removeObserver(inputObserver)
        OverlayBus.imeVisible.removeObserver(imeObserver)
        OverlayBus.imeHeightPx.removeObserver(imeHeightObserver)
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LiveTranslate"
        private const val NOTIF_ID = 4421
        private const val CHANNEL_ID = "lt_overlay"
        private const val GAP_ABOVE_IME_DP = 4f
        const val DEBOUNCE_MS = 300L
    }
}
