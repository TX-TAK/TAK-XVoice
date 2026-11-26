package com.atakmap.android.xv.audio

/**
 * What a transmission that just started is actually going out on,
 * compared with what the operator expects. Pure — VoicePlant feeds it
 * the live state when TX begins and raises a warning the UI can show.
 */
enum class TxRouteWarning {
    /** On the expected speaker-mic, or no speaker-mic is expected. */
    NONE,

    /** A speaker-mic is connected but SCO is down: the phone's own mic is live. */
    PHONE_MIC,

    /** SCO is up, but pinned to a different Bluetooth device than the selected one. */
    OTHER_BT_DEVICE,
}

object TxRouteCheck {
    /**
     * @param speakerMicExpected a speaker-mic is selected and its button
     *   link is up, so the operator expects to be talking into it.
     * @param scoConnected the SCO voice link is CONNECTED.
     * @param activeMac the device the live link is pinned to (null when
     *   unknown, e.g. legacy SCO).
     * @param expectedMac the device the link should be on: the audio
     *   override if set, else the speaker-mic's audio MAC (null when
     *   unknown, e.g. a BT02 without a bonded Classic side).
     */
    fun evaluate(
        speakerMicExpected: Boolean,
        scoConnected: Boolean,
        activeMac: String?,
        expectedMac: String?,
    ): TxRouteWarning =
        when {
            !speakerMicExpected -> TxRouteWarning.NONE
            !scoConnected -> TxRouteWarning.PHONE_MIC
            !expectedMac.isNullOrBlank() &&
                !activeMac.isNullOrBlank() &&
                !activeMac.equals(expectedMac, ignoreCase = true) -> TxRouteWarning.OTHER_BT_DEVICE
            else -> TxRouteWarning.NONE
        }

    /** Operator-facing text, or null for [TxRouteWarning.NONE]. */
    fun message(warning: TxRouteWarning): String? =
        when (warning) {
            TxRouteWarning.NONE -> null
            TxRouteWarning.PHONE_MIC -> "Transmitting on the phone mic — speaker-mic audio isn't connected"
            TxRouteWarning.OTHER_BT_DEVICE -> "Transmitting on a different Bluetooth device than the selected speaker-mic"
        }
}
