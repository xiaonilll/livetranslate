package com.minis.livetranslate

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Tiny in-process event bus. The accessibility service and the overlay live in
 * the same process, so no IPC/Binder plumbing is needed.
 */
object OverlayBus {
    /** Full text of the currently focused editable field. */
    val rawInput = MutableLiveData("")

    /** Package owning the focused window, for logging / rules. */
    val activePackage = MutableLiveData("")

    /** Whether the accessibility service is alive. */
    val connected = MutableLiveData(false)

    /** Latest translation to render. */
    val result = MutableLiveData(Translation("", "", "", 0))

    /** Show/hide the floating bar. */
    val enabled = MutableLiveData(false)
}
