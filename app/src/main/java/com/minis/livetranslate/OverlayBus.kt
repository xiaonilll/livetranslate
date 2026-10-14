package com.minis.livetranslate

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Tiny in-process event bus shared by the accessibility service, the overlay and
 * the activity.
 *
 * Overlay liveness is tracked as a LiveData flag that OverlayService sets in
 * onCreate and clears in onDestroy. The earlier implementation asked
 * ActivityManager.getRunningServices() instead, which on Android 8+ only reports
 * the caller's own services and is unreliable at targetSdk 34 - the service was
 * running while the UI claimed it was not.
 */
object OverlayBus {
    /** Full text of the currently focused editable field. */
    val rawInput = MutableLiveData("")

    /** Package owning the focused window. */
    val activePackage = MutableLiveData("")

    /** Whether the accessibility service is alive. */
    val connected = MutableLiveData(false)

    /** True while OverlayService is created and not yet destroyed. */
    val overlayRunning = MutableLiveData(false)

    /** Latest translation, mirrored to the activity for status display. */
    val translation = MutableLiveData(Translation("", "", "", 0))

    /** True while the soft keyboard is on screen. */
    val imeVisible = MutableLiveData(false)

    /** Soft keyboard height in pixels, 0 when hidden. */
    val imeHeightPx = MutableLiveData(0)
}
