package com.atakmap.android.xv.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure SCO / route decisions: readiness of the picked device, removal
 * scoping, external-call refusal, AudioRouter's BT pick, and the TX
 * route warning.
 */
class ScoRouteDecisionsTest {
    private val sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private val a2dp = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private val mic = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"

    @Test
    fun `readiness requires the picked device, not any BT device`() {
        assertTrue(ScoLink.isPickedDeviceActive(sco, mic, mic))
        assertTrue("MAC compare is case-insensitive", ScoLink.isPickedDeviceActive(sco, mic.lowercase(), mic))
        assertFalse("another headset is not ready", ScoLink.isPickedDeviceActive(sco, other, mic))
        assertFalse("earpiece is not ready", ScoLink.isPickedDeviceActive(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "", mic))
        assertFalse(ScoLink.isPickedDeviceActive(null, null, mic))
    }

    @Test
    fun `readiness falls back to a type check when a MAC is unknown`() {
        assertTrue("legacy path pins nothing", ScoLink.isPickedDeviceActive(sco, other, null))
        assertTrue("device hides its address", ScoLink.isPickedDeviceActive(a2dp, "", mic))
    }

    @Test
    fun `only removal of the pinned SCO device tears the link down`() {
        assertTrue(ScoLink.shouldTearDownOnRemoval(listOf(ScoLink.Candidate(sco, mic)), mic))
        assertTrue(ScoLink.shouldTearDownOnRemoval(listOf(ScoLink.Candidate(sco, mic.lowercase())), mic))
        assertFalse("another headset leaving", ScoLink.shouldTearDownOnRemoval(listOf(ScoLink.Candidate(sco, other)), mic))
        assertFalse("A2DP side leaving", ScoLink.shouldTearDownOnRemoval(listOf(ScoLink.Candidate(a2dp, mic)), mic))
        assertTrue("nothing pinned: any SCO removal", ScoLink.shouldTearDownOnRemoval(listOf(ScoLink.Candidate(sco, other)), null))
        assertTrue("hidden address: be safe", ScoLink.shouldTearDownOnRemoval(listOf(ScoLink.Candidate(sco, "")), mic))
    }

    @Test
    fun `SCO is refused while a cellular call owns the audio path`() {
        assertTrue(ScoLink.isExternalCallMode(AudioManager.MODE_IN_CALL))
        assertTrue(ScoLink.isExternalCallMode(AudioManager.MODE_RINGTONE))
        assertFalse("may be XV's own call", ScoLink.isExternalCallMode(AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(ScoLink.isExternalCallMode(AudioManager.MODE_NORMAL))
    }

    @Test
    fun `router picks the hinted speaker-mic case-insensitively`() {
        val outs =
            listOf(
                AudioRouter.Companion.DeviceCandidate(a2dp, other),
                AudioRouter.Companion.DeviceCandidate(sco, mic.lowercase()),
            )
        assertEquals(mic.lowercase(), AudioRouter.pickBluetooth(outs, mic)?.address)
    }

    @Test
    fun `router prefers a voice-capable device over A2DP-only headphones without a hint`() {
        val headphones = AudioRouter.Companion.DeviceCandidate(a2dp, other)
        val headsetA2dp = AudioRouter.Companion.DeviceCandidate(a2dp, mic)
        val headsetSco = AudioRouter.Companion.DeviceCandidate(sco, mic)
        // The headset's first-listed entry is kept.
        assertEquals(headsetA2dp, AudioRouter.pickBluetooth(listOf(headphones, headsetA2dp, headsetSco), null))
        assertEquals(headphones, AudioRouter.pickBluetooth(listOf(headphones), null))
        assertNull(AudioRouter.pickBluetooth(emptyList(), mic))
    }

    @Test
    fun `router override matches case-insensitively`() {
        val outs = listOf(AudioRouter.Companion.DeviceCandidate(sco, mic), AudioRouter.Companion.DeviceCandidate(a2dp, other.lowercase()))
        assertEquals(
            other.lowercase(),
            AudioRouter
                .pickPreferredDeviceFromCandidates(outs, OutputRoute.AUTO, overrideMac = other, preferredBtHintMac = mic)
                ?.address,
        )
    }

    @Test
    fun `TX route warning`() {
        assertEquals(TxRouteWarning.NONE, TxRouteCheck.evaluate(false, false, null, mic))
        assertEquals(TxRouteWarning.PHONE_MIC, TxRouteCheck.evaluate(true, false, null, mic))
        assertEquals(TxRouteWarning.NONE, TxRouteCheck.evaluate(true, true, mic.lowercase(), mic))
        assertEquals(TxRouteWarning.OTHER_BT_DEVICE, TxRouteCheck.evaluate(true, true, other, mic))
        assertEquals("unknown pin", TxRouteWarning.NONE, TxRouteCheck.evaluate(true, true, null, mic))
        assertEquals("unknown expectation", TxRouteWarning.NONE, TxRouteCheck.evaluate(true, true, other, null))
        assertNull(TxRouteCheck.message(TxRouteWarning.NONE))
        assertTrue(TxRouteCheck.message(TxRouteWarning.PHONE_MIC)!!.contains("phone mic"))
    }
}
