package com.minis.livetranslate

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max

/**
 * Watches every editable text field on screen and reports the IME geometry.
 *
 * onComputeInsets is the only reliable way to learn the soft keyboard's height:
 * Android exposes no public "is the keyboard open" callback, and the
 * TYPE_WINDOW_STATE_CHANGED events do not carry it.
 */
class InputWatcherService : AccessibilityService() {

    companion object {
        /** Height of the IME in pixels; 0 when the keyboard is hidden. */
        @Volatile
        var imeHeight: Int = 0
            private set

        /** Bottom edge of the IME in screen coordinates. */
        @Volatile
        var imeTop: Int = 0
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        OverlayBus.connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                extractText(event)?.let { OverlayBus.rawInput.value = it }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                OverlayBus.activePackage.value = event.packageName?.toString().orEmpty()
            }
        }
    }

    /**
     * Called by the framework whenever system UI geometry changes. The IME
     * appears here as a bottom inset of type TYPE_IME.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)

        val screen = resources.displayMetrics.heightPixels
        // outInsets.contentInsets.top is roughly the top of the content area;
        // the IME, when up, occupies the bottom portion of the screen.
        val contentTop = outInsets.contentInsets.top
        val contentBottom = screen - outInsets.contentInsets.bottom

        // The keyboard's top edge is the bottom of the content region when the
        // IME is showing. insetHeight==0 means nothing is up.
        val bottomInset = outInsets.contentInsets.bottom
        val newImeHeight = max(0, bottomInset)

        if (newImeHeight != imeHeight) {
            imeHeight = newImeHeight
            imeTop = screen - newImeHeight
            OverlayBus.imeVisible.value = newImeHeight > 0
            OverlayBus.imeHeightPx.value = newImeHeight
        }
        // Silence unused warnings while keeping the values documented.
        @Suppress("UNUSED_EXPRESSION") contentTop
        @Suppress("UNUSED_EXPRESSION") contentBottom
    }

    private fun extractText(event: AccessibilityEvent): String? {
        val node = event.source
        if (node != null) {
            val fromNode = readEditable(node)
            node.recycle()
            if (!fromNode.isNullOrEmpty()) return fromNode
        }
        return event.text.joinToString("").ifEmpty { null }
    }

    private fun readEditable(node: AccessibilityNodeInfo): String? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isEditable) {
                n.text?.toString()?.let { if (it.isNotEmpty()) return it }
            }
            val parent = n.parent
            if (n !== node) n.recycle()
            n = parent
            depth++
        }
        return node.text?.toString()
    }

    /** Kept so the bounds are observable by the overlay if it needs them. */
    fun currentImeBounds(): Rect = Rect(0, imeTop, resources.displayMetrics.widthPixels,
        resources.displayMetrics.heightPixels)

    override fun onInterrupt() {
        OverlayBus.connected.value = false
    }

    override fun onDestroy() {
        OverlayBus.connected.value = false
        imeHeight = 0
        OverlayBus.imeVisible.value = false
        super.onDestroy()
    }
}
