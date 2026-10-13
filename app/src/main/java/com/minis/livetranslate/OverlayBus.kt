package com.minis.livetranslate

import androidx.lifecycle.MutableLiveData

/**
 * Tiny in-process event bus. The accessibility service and the overlay live in
 * the same process, so no IPC/Binder plumbing is needed.
 *
 * Note: the overlay's running state is deliberately NOT tracked here - a static
 * flag resets to false whenever the process is recreated, which earlier made the
 * start button behave like a stop button. MainActivity asks ActivityManager.
 */
object OverlayBus {
    /** Full text of the currently focused editable field. */
    val rawInput = MutableLiveData("")

    /** Package owning the focused window, for logging / rules. */
    val activePackage = MutableLiveData("")

    /** Whether the accessibility service is alive. */
    val connected = MutableLiveData(false)

    /** Latest translation, mirrored to the activity for status display. */
    val translation = MutableLiveData(Translation("", "", "", 0))

    /** True while the soft keyboard is on screen. Drives overlay visibility. */
    val imeVisible = MutableLiveData(false)

    /** Soft keyboard height in pixels, 0 when hidden. Drives overlay offset. */
    val imeHeightPx = MutableLiveData(0)
}
