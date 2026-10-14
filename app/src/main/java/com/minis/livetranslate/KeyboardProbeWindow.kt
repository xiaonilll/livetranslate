package com.minis.livetranslate

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.max

/**
 * Invisible 1px-wide, full-height window whose only job is to be resized by the
 * soft keyboard.
 *
 * WHY THIS EXISTS
 * ---------------
 * Android exposes no public "is the IME up" callback. The accessibility window
 * list is the only keyboard signal an AccessibilityService gets on its own, and
 * it lags badly, flaps during the open/close animation, and misses keyboards that
 * publish no accessibility tree. An overlay window, by contrast, is laid out by
 * the WindowManager like any other window: it is resized when the keyboard
 * appears, which yields an exact, event-driven signal.
 *
 * Two independent sources are combined because neither alone is reliable:
 *   A. WindowInsets.Type.ime() on this window - exact, but returns NONE on
 *      floating/undocked keyboards.
 *   B. getWindowVisibleDisplayFrame() delta - survives the floating case, but is
 *      polluted by the navigation bar.
 * A "proven" latch prevents false negatives before the first sighting.
 *
 * Technique derived from writingmate/aidictation's KeyboardProbeWindow; the
 * layout API is updated for API 30+ (setFitInsetsTypes replaces the old
 * SOFT_INPUT_ADJUST_RESIZE approach).
 */
class KeyboardProbeWindow(
    private val context: Context,
    private val wm: WindowManager,
    private val onChanged: () -> Unit,
) {
    /** Top edge of the keyboard in screen coordinates, or null when hidden. */
    var keyboardTop: Int? = null
        private set

    var isVisible: Boolean = false
        private set

    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var tallestSeen = 0
    private var layoutSaysVisible = false
    private var insetsSayVisible = false
    private var layoutKeyboardTop: Int? = null
    private var insetsKeyboardTop: Int? = null

    /** Once the keyboard has been seen even once, trust transitions over the latch. */
    private var proven = false

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { onLayout() }
    private var insetsListener: View.OnApplyWindowInsetsListener? = null

    private fun minimumKeyboardPx(): Int = dp(MINIMUM_KEYBOARD_DP)

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    private fun screenY(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[1]
    }

    fun attach() {
        if (view != null) return
        val v = View(context)
        // The probe must be a TALL, FULL-WIDTH window for the IME to shrink it.
        // An earlier version used (1, MATCH_PARENT) and dumpsys showed the result
        // was contentSize=2976x1 - a 1px tall window the keyboard never resizes,
        // so onLayout never fired. Both dimensions are now explicit.
        val screenH = context.resources.displayMetrics.heightPixels
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            screenH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            windowAnimations = 0
            // Not fully transparent: some OEM builds skip layout for alpha==0
            // windows. 0.01 is invisible to the eye but still laid out.
            alpha = 0.01f
        }

        // Let the WindowManager resize us when the keyboard appears, and deliver
        // the IME insets. Without this the probe never shrinks and source A is dead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.fitInsetsTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }

        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            Log.e(TAG, "probe attach failed", e)
            return
        }

        view = v
        layoutParams = lp
        v.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val l = View.OnApplyWindowInsetsListener { _, insets ->
                val bottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                val visible = insets.isVisible(WindowInsets.Type.ime()) && bottom > 0
                insetsSayVisible = visible
                insetsKeyboardTop = if (visible) screenY(v) + v.height - bottom else null
                publish()
                insets
            }
            insetsListener = l
            v.setOnApplyWindowInsetsListener(l)
            // An initial pass so a keyboard that is already open is caught.
            v.requestApplyInsets()
        }
        Log.i(TAG, "probe attached, size=${lp.width}x${lp.height}")
    }

    fun detach() {
        val v = view ?: return
        runCatching { v.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener) }
        runCatching { wm.removeView(v) }
        view = null
        layoutParams = null
        keyboardTop = null
        isVisible = false
    }

    /**
     * The probe is full height; when the keyboard opens the WindowManager shrinks
     * it, and the shortfall against the tallest height ever seen IS the keyboard.
     */
    private fun onLayout() {
        val v = view ?: return
        val height = v.height
        if (height <= 0) return
        tallestSeen = max(tallestSeen, height)
        val visible = isKeyboardHeight(tallestSeen, height, minimumKeyboardPx())
        Log.d(TAG, "onLayout h=$height tallest=$tallestSeen visible=$visible")
        layoutSaysVisible = visible
        layoutKeyboardTop = if (visible) screenY(v) + height else null
        publish()
    }

    /**
     * A soft keyboard is at least 120dp tall, which is what separates it from the
     * few pixels of ordinary layout churn.
     */
    private fun isKeyboardHeight(full: Int, current: Int, minimum: Int): Boolean =
        full > 0 && current > 0 && (full - current) >= minimum

    private fun publish() {
        val visible = layoutSaysVisible || insetsSayVisible
        // Sources B then A: the layout delta handles floating keyboards, the
        // inset is more precise when present.
        val top = layoutKeyboardTop ?: insetsKeyboardTop

        val previousVisible = isVisible
        val previousTop = keyboardTop
        isVisible = visible
        keyboardTop = if (visible) top else null

        // The first sighting proves this device signals at all; before that we
        // stay quiet rather than flapping on startup.
        if (visible) proven = true

        val changed = if (proven) {
            previousVisible != isVisible || previousTop != keyboardTop
        } else {
            false
        }
        if (changed) {
            Log.i(TAG, "ime visible=$isVisible top=$keyboardTop " +
                "(layout=$layoutKeyboardTop insets=$insetsKeyboardTop)")
            onChanged()
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /** Diagnostics for the HyperOS dead-strip problem. */
    fun debugBounds(): Rect? = view?.let { Rect(0, 0, it.width, it.height) }

    companion object {
        private const val TAG = "LiveTranslate"
        private const val MINIMUM_KEYBOARD_DP = 120
    }
}
