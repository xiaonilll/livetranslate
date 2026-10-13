package com.minis.livetranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
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
import kotlin.math.abs

/**
 * Keyboard-triggered translation bar.
 *
 * HOW THE KEYBOARD IS DETECTED
 * ---------------------------
 * Android has no public "is the soft keyboard visible" callback, and
 * AccessibilityService offers nothing usable for this (onComputeInsets is a
 * View/ViewGroup API, not an accessibility one - an earlier attempt used it and
 * did not compile).
 *
 * The technique used here is the one proven by Crysis21/KeyboardHeightProvider:
 * attach a full-height, fully transparent window and watch its own visible
 * display frame. When the IME appears the system shrinks that frame, and the
 * difference against the real screen height IS the keyboard height. The window
 * measures itself, so no privileged API is involved.
 *
 * The bar itself is only *added* while the keyboard is up, so it can never sit
 * over the home screen or an app that is not accepting input.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var probe: View? = null
    private lateinit var probeLp: WindowManager.LayoutParams

    private var bar: LinearLayout? = null
    private lateinit var barLp: WindowManager.LayoutParams
    private var attached = false

    private var srcView: TextView? = null
    private var dstView: TextView? = null
    private var statusView: TextView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var debounceJob: Job? = null
    private var lastText: String = ""

    private var lastImeHeight = -1
    private var screenHeightPx = 0

    private val inputObserver = Observer<String> { onTextChanged(it) }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { measureKeyboard() }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenHeightPx = resources.displayMetrics.heightPixels
        startForegroundSafely()
        installProbe()
        OverlayBus.rawInput.observeForever(inputObserver)
    }

    private fun startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = getSystemService(NotificationManager::class.java)
                if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                    mgr.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID, "同步翻译", NotificationManager.IMPORTANCE_MIN
                        )
                    )
                }
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(this)
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

    /**
     * A 1px wide, full height, invisible window that exists only to be resized
     * by the IME. It is NOT_TOUCHABLE and NOT_FOCUSABLE so it can never
     * intercept a tap or steal focus.
     */
    private fun installProbe() {
        val v = View(this)
        probeLp = WindowManager.LayoutParams(
            1,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            wm.addView(v, probeLp)
            probe = v
            v.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            // Measure once in case a keyboard is already open.
            Handler(Looper.getMainLooper()).postDelayed({ measureKeyboard() }, 300)
            Log.i(TAG, "probe installed")
        } catch (e: Exception) {
            Log.e(TAG, "probe failed", e)
        }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /** Compare the probe's visible frame against the screen to derive IME height. */
    private fun measureKeyboard() {
        val v = probe ?: return
        val visible = Rect()
        v.getWindowVisibleDisplayFrame(visible)
        val usableBottom = visible.bottom
        // Anything below the visible frame's bottom is taken by the IME.
        val imeHeight = (screenHeightPx - usableBottom).coerceAtLeast(0)
        // Ignore the navigation bar sized noise: only react to real changes.
        if (abs(imeHeight - lastImeHeight) < NAV_TOLERANCE_PX) return
        lastImeHeight = imeHeight

        val visibleNow = imeHeight > NAV_TOLERANCE_PX
        Log.i(TAG, "ime height=$imeHeight visible=$visibleNow")
        OverlayBus.imeHeightPx.value = imeHeight
        OverlayBus.imeVisible.value = visibleNow

        if (visibleNow) attachAndShow() else hideAndDetach()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    // ---- the bar -----------------------------------------------------------

    private fun attachAndShow() {
        if (attached) {
            reposition(lastImeHeight)
            return
        }
        try {
            val root = buildBar()
            barLp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = dp(6f)
                y = lastImeHeight + dp(GAP_ABOVE_IME_DP)
            }
            wm.addView(root, barLp)
            bar = root
            attached = true
            Log.i(TAG, "bar attached y=${barLp.y}")
        } catch (e: Exception) {
            Log.e(TAG, "bar attach failed", e)
        }
    }

    private fun hideAndDetach() {
        debounceJob?.cancel()
        lastText = ""
        if (!attached) return
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        attached = false
        Log.i(TAG, "bar detached")
    }

    private fun reposition(imeHeight: Int) {
        if (!attached) return
        barLp.y = imeHeight + dp(GAP_ABOVE_IME_DP)
        runCatching { wm.updateViewLayout(bar, barLp) }
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
            visibility = View.GONE
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
            statusView?.text = "等待输入…"
            dstView?.text = ""
            srcView?.text = ""
            return
        }
        if (!attached) return
        statusView?.text = "翻译中…"
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            try {
                val t = Translator.translate(applicationContext, text)
                if (!attached) return@launch
                srcView?.text = text
                srcView?.visibility = View.VISIBLE
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
        probe?.viewTreeObserver?.let { vt ->
            runCatching { vt.removeOnGlobalLayoutListener(layoutListener) }
        }
        probe?.let { runCatching { wm.removeView(it) } }
        probe = null
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
        /** Below this, treat the difference as nav-bar noise rather than an IME. */
        private const val NAV_TOLERANCE_PX = 120
        const val DEBOUNCE_MS = 300L
    }
}
