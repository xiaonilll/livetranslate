package com.minis.livetranslate

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.max

/**
 * Invisible window whose job is to be resized by the soft keyboard.
 *
 * HISTORY / WHY THIS IS SO CONVOLUTED
 * -----------------------------------
 * Four approaches were tried and the first three failed on this device:
 *  1. AccessibilityService.onComputeInsets() - does not exist on that class.
 *  2. TYPE_APPLICATION_OVERLAY at (1, MATCH_PARENT) - the WM resolved the height
 *     to 1px (dumpsys: contentSize=2976x1), so the IME never resized it.
 *  3. Explicit size + OnGlobalLayoutListener - the probe measured 2976 and then
 *     never changed: an overlay window is not re-laid-out when the IME opens.
 *
 * So the probe no longer waits to be resized. It POLLS getWindowVisibleDisplayFrame()
 * on a timer, which reports the frame the window would occupy - and that value
 * does shrink while the keyboard is up, even though no relayout is delivered.
 * Polling is cheap (this is one Rect read per tick) and the interval is short
 * enough to feel instant.
 */
class KeyboardProbeWindow(
    private val context: Context,
    private val wm: WindowManager,
    private val onChanged: () -> Unit,
) {
    var keyboardTop: Int? = null
        private set

    var isVisible: Boolean = false
        private set

    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var tallestSeen = 0

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val poll = object : Runnable {
        override fun run() {
            sample()
            if (polling) handler.postDelayed(this, POLL_MS)
        }
    }

    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { sample() }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    fun attach() {
        if (view != null) return
        val v = View(context)
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
            // Not alpha 0: some builds skip layout entirely for fully
            // transparent windows. 0.01 is invisible but still laid out.
            alpha = 0.01f
        }

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

        polling = true
        handler.post(poll)
        Log.i(TAG, "probe attached ${lp.width}x${lp.height}, polling every ${POLL_MS}ms")
    }

    fun detach() {
        polling = false
        handler.removeCallbacks(poll)
        val v = view ?: return
        runCatching { v.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener) }
        runCatching { wm.removeView(v) }
        view = null
        layoutParams = null
        keyboardTop = null
        isVisible = false
    }

    /**
     * Read the window's usable frame. getWindowVisibleDisplayFrame reports the
     * area not covered by system UI, so it shrinks by exactly the keyboard height
     * while the IME is up - regardless of whether a relayout was delivered.
     */
    private fun sample() {
        val v = view ?: return
        val frame = Rect()
        v.getWindowVisibleDisplayFrame(frame)
        val usable = frame.bottom - frame.top
        if (usable <= 0) return

        val screenH = screenHeight()
        // Height of the dead zone below the usable area.
        val bottomGap = screenH - frame.bottom
        if (bottomGap >= NAV_TOLERANCE_PX) {
            // Something tall is covering the bottom: that is the keyboard.
            val top = frame.bottom
            val changed = !isVisible || keyboardTop != top
            tallestSeen = max(tallestSeen, usable)
            isVisible = true
            keyboardTop = top
            if (changed) {
                Log.i(TAG, "keyboard UP top=$top gap=$bottomGap usable=$usable")
                onChanged()
            }
        } else {
            if (isVisible) {
                Log.i(TAG, "keyboard DOWN (gap=$bottomGap)")
                isVisible = false
                keyboardTop = null
                onChanged()
            }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    companion object {
        private const val TAG = "LiveTranslate"
        /** Anything taller than this below the usable area is a keyboard, not a nav bar. */
        private const val NAV_TOLERANCE_PX = 150
        private const val POLL_MS = 120L
    }
}
