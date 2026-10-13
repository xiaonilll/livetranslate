package com.minis.livetranslate

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches editable text fields and forwards their contents to the overlay.
 *
 * Keyboard geometry is NOT measured here. AccessibilityService has no
 * onComputeInsets hook (that API belongs to View/ViewGroup), and the framework
 * exposes no "is the IME showing" callback. [OverlayService] measures the IME
 * from its own overlay window instead - see the long comment there.
 */
class InputWatcherService : AccessibilityService() {

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

    override fun onInterrupt() {
        OverlayBus.connected.value = false
    }

    override fun onDestroy() {
        OverlayBus.connected.value = false
        OverlayBus.imeVisible.value = false
        super.onDestroy()
    }
}
