package com.atakmap.android.xv.aina

/**
 * Pure rules for when XV may pick a PTT device on its own, shared by
 * every auto-assign path (launch auto-connect, the Settings picker, a
 * device that connects mid-session) so they agree. An operator's
 * explicit choice — including turning the Bluetooth button off — always
 * wins over an automatic one.
 */
object DeviceAutoSelect {
    /**
     * May XV assign a Bluetooth button to the external-button slot by
     * itself? Not when the operator turned the slot off, not when
     * auto-connect is off, and not when a button is already chosen.
     */
    fun mayAutoAssignButton(
        slotOff: Boolean,
        autoConnectEnabled: Boolean,
        currentMac: String?,
    ): Boolean = !slotOff && autoConnectEnabled && currentMac.isNullOrBlank()

    /**
     * A speaker-mic's Bluetooth link just came up mid-session (newly
     * paired, or powered on). Should XV connect it as the primary input?
     *
     * Only with auto-connect on, only for a speaker-mic, and never while
     * the primary already has a working button link — a second mic
     * powering on must not steal from the one in use. The saved primary
     * itself is handled by the existing reconnect path, so it is not a
     * candidate here.
     */
    fun shouldAutoSelectPrimary(
        connectedMac: String,
        savedPrimaryMac: String?,
        primaryLinkUp: Boolean,
        autoConnectEnabled: Boolean,
        isSpeakerMic: Boolean,
    ): Boolean =
        when {
            !autoConnectEnabled || !isSpeakerMic || primaryLinkUp -> false
            else -> !connectedMac.equals(savedPrimaryMac, ignoreCase = true)
        }

    /**
     * Whether an automatically chosen primary should be saved as the
     * operator's pick. Only when there was none: a fallback used while
     * the saved speaker-mic is off must not overwrite it, so the saved
     * one is restored when it comes back.
     */
    fun shouldPersistAutoPrimary(savedPrimaryMac: String?): Boolean = savedPrimaryMac.isNullOrBlank()
}
