package com.atakmap.android.xv.aina

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceAutoSelect]: an operator's choice — including turning the
 * Bluetooth button off — always beats an automatic one, and a second
 * speaker-mic never steals from the one in use.
 */
class DeviceAutoSelectTest {
    private val mic = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"

    @Test
    fun `button slot turned off is never auto-assigned`() {
        assertFalse(DeviceAutoSelect.mayAutoAssignButton(slotOff = true, autoConnectEnabled = true, currentMac = null))
    }

    @Test
    fun `button auto-assign needs auto-connect and an empty slot`() {
        assertTrue(DeviceAutoSelect.mayAutoAssignButton(slotOff = false, autoConnectEnabled = true, currentMac = null))
        assertTrue(DeviceAutoSelect.mayAutoAssignButton(slotOff = false, autoConnectEnabled = true, currentMac = ""))
        assertFalse(DeviceAutoSelect.mayAutoAssignButton(slotOff = false, autoConnectEnabled = false, currentMac = null))
        assertFalse(DeviceAutoSelect.mayAutoAssignButton(slotOff = false, autoConnectEnabled = true, currentMac = mic))
    }

    @Test
    fun `mid-session speaker-mic is selected only when the primary has no working link`() {
        assertTrue(
            DeviceAutoSelect.shouldAutoSelectPrimary(mic, null, primaryLinkUp = false, autoConnectEnabled = true, isSpeakerMic = true)
        )
        assertTrue(
            "fallback while the saved mic is off",
            DeviceAutoSelect.shouldAutoSelectPrimary(mic, other, primaryLinkUp = false, autoConnectEnabled = true, isSpeakerMic = true),
        )
        assertFalse(
            "never steal from a working mic",
            DeviceAutoSelect.shouldAutoSelectPrimary(mic, other, primaryLinkUp = true, autoConnectEnabled = true, isSpeakerMic = true),
        )
        assertFalse(
            "auto-connect off",
            DeviceAutoSelect.shouldAutoSelectPrimary(mic, null, primaryLinkUp = false, autoConnectEnabled = false, isSpeakerMic = true),
        )
        assertFalse(
            "a button puck is not a speaker-mic",
            DeviceAutoSelect.shouldAutoSelectPrimary(mic, null, primaryLinkUp = false, autoConnectEnabled = true, isSpeakerMic = false),
        )
        assertFalse(
            "the saved mic has its own reconnect path",
            DeviceAutoSelect.shouldAutoSelectPrimary(
                mic,
                mic.lowercase(),
                primaryLinkUp = false,
                autoConnectEnabled = true,
                isSpeakerMic = true
            ),
        )
    }

    @Test
    fun `a fallback primary never replaces the saved pick`() {
        assertTrue(DeviceAutoSelect.shouldPersistAutoPrimary(null))
        assertTrue(DeviceAutoSelect.shouldPersistAutoPrimary(""))
        assertFalse(DeviceAutoSelect.shouldPersistAutoPrimary(other))
    }
}
