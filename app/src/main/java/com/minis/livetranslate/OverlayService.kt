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
 * Keyboard-following translation bar.
 *
 * WINDOW TYPE MATTERS
 * -------------------
 * TYPE_APPLICATION_OVERLAY is layer 11; the soft keyboard (TYPE_INPUT_METHOD) is
 * layer 13, so an application overlay is composited *underneath* the keyboard and
 * gets covered by it. TYPE_ACCESSIBILITY_OVERLAY is layer 31 - above the IME -
 * and is available to us because this app already runs an accessibility service.
 *
 * POSITIONING
 * -----------
 * The bar is anchored to the keyboard's TOP EDGE with gravity TOP|START, not to
 * bottom + keyboard height. A height offset is relative to a window origin that
 * itself depends on insets, so the two never agree; the top edge is absolute.
 * This also keeps working for floating/undocked keyboards, where an IME inset of
 * zero would otherwise collapse the bar onto the screen bottom.
 *
 * VISIBILITY
 * ----------
 * The bar is added only while the keyboard is up, so it never lingers over the
 * home screen or an app that is not accepting input.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var probe: KeyboardProbeWindow? = null

    private var bar: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var attached = false

    private var srcView: TextView? = null
    private var dstView: TextView? = null
    private var statusView: TextView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var debounceJob: Job? = null
    private var lastText: String = ""

    private val inputObserver = Observer<String> { onTextChanged(it) }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundSafely()

        OverlayBus.rawInput.observeForever(inputObserver)

        probe = KeyboardProbeWindow(this, wm) { onKeyboardChanged() }.also { it.attach() }
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

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    // ---- react to the keyboard --------------------------------------------

    private fun onKeyboardChanged() {
        val p = probe ?: return
        OverlayBus.imeVisible.value = p.isVisible
        OverlayBus.imeHeightPx.value = p.keyboardTop?.let {
            resources.displayMetrics.heightPixels - it
        } ?: 0

        if (p.isVisible && p.keyboardTop != null) {
            showBarAbove(p.keyboardTop!!)
        } else {
            hideBar()
        }
    }

    private fun showBarAbove(keyboardTop: Int) {
        val existing = bar
        if (existing == null) {
            buildAndAttach(keyboardTop)
            return
        }
        reposition(existing, keyboardTop)
    }

    private fun buildAndAttach(keyboardTop: Int) {
        try {
            val root = buildBar()
            val lp = barParams(keyboardTop, root)
            wm.addView(root, lp)
            bar = root
            barLp = lp
            attached = true
            Log.i(TAG, "bar attached y=${lp.y}")
            // Re-anchor once measured, since height may differ from the estimate.
            root.post { reposition(root, probe?.keyboardTop ?: keyboardTop) }
        } catch (e: Exception) {
            Log.e(TAG, "bar attach failed", e)
        }
    }

    private fun barParams(keyboardTop: Int, view: View): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            accessibilityOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(6f)
            y = computeY(keyboardTop, view.measuredHeight)
            windowAnimations = 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        return lp
    }

    /**
     * Keyboard top edge, minus the bar's own height and a small gap.
     * Falls back to a sane estimate before the first measure pass.
     */
    private fun computeY(keyboardTop: Int, measuredHeight: Int): Int {
        val h = if (measuredHeight > 0) measuredHeight else dp(BAR_HEIGHT_ESTIMATE_DP)
        return (keyboardTop - h - dp(GAP_ABOVE_IME_DP)).coerceAtLeast(dp(4f))
    }

    private fun reposition(view: View, keyboardTop: Int) {
        val lp = barLp ?: return
        val newY = computeY(keyboardTop, view.measuredHeight)
        if (lp.y == newY) return
        lp.y = newY
        runCatching { wm.updateViewLayout(view, lp) }
            .onFailure { Log.w(TAG, "reposition failed", it) }
    }

    private fun hideBar() {
        debounceJob?.cancel()
        lastText = ""
        val b = bar ?: return
        runCatching { wm.removeView(b) }
        bar = null
        barLp = null
        attached = false
        Log.i(TAG, "bar detached")
    }

    /**
     * TYPE_ACCESSIBILITY_OVERLAY (layer 31) so we draw above the IME (layer 13).
     * Falls back to the application overlay type on old releases.
     */
    private fun accessibilityOverlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ---- the bar UI --------------------------------------------------------

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
                // Height changed as text grew; re-anchor.
                val b = bar
                val top = probe?.keyboardTop
                if (b != null && top != null) reposition(b, top)
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
        probe?.detach()
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
        private const val GAP_ABOVE_IME_DP = 6f
        private const val BAR_HEIGHT_ESTIMATE_DP = 64f
        const val DEBOUNCE_MS = 300L
    }
}
