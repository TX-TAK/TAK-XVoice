package com.atakmap.android.xv.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TxController's SCO hold across a dropped link.
 *
 * Before: after SCO dropped, holdsSco stayed true until the cool-down,
 * so a re-press skipped acquire(), sat in ACQUIRING_SCO for the
 * warm-up timeout and then transmitted on the phone mic. A link that
 * failed synchronously inside acquire() (before holdsSco was set) was
 * also ignored.
 */
@RunWith(RobolectricTestRunner::class)
class TxControllerScoDropTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var linkState = ScoLink.State.DISCONNECTED
    private var acquires = 0
    private var failAcquire = false
    private val listener = slot<ScoLink.StateListener>()

    private fun scoLink(): ScoLink {
        val sco = mockk<ScoLink>(relaxed = true)
        every { sco.state } answers { linkState }
        every { sco.addStateListener(capture(listener)) } answers { }
        every { sco.acquire(any()) } answers {
            acquires++
            linkState = if (failAcquire) ScoLink.State.DISCONNECTED else ScoLink.State.CONNECTING
        }
        return sco
    }

    private fun build(sco: ScoLink): TxController =
        TxController(
            scoLink = sco,
            btPolicy = mockk<BtAudioPolicy>(relaxed = true).also { every { it.classify() } returns BtAudioMode.HFP_ONLY },
            tptPlayer = mockk(relaxed = true),
            audioCaptureFactory = { _ -> mockk(relaxed = true) },
            opusEncoderFactory = { mockk(relaxed = true) },
            audioManager = audioManager,
            audioController = mockk(relaxed = true),
            sendOpus = { _, _ -> },
        )

    @Test
    fun `SCO drop releases the hold so the next press acquires again`() {
        val sco = scoLink()
        val tx = build(sco)
        tx.start(0)
        assertEquals(1, acquires)
        assertEquals(TxController.State.ACQUIRING_SCO, tx.currentStateForTest())

        linkState = ScoLink.State.DISCONNECTED
        listener.captured.onScoStateChanged(ScoLink.State.DISCONNECTED)
        verify(exactly = 1) { sco.release(tx) }
        assertEquals(TxController.State.IDLE, tx.currentStateForTest())

        Thread.sleep(250) // past the start refractory
        tx.start(0)
        assertEquals("re-press must re-acquire the link", 2, acquires)
        tx.shutdown()
    }

    @Test
    fun `SCO suspended mid-acquire also drops the hold`() {
        val sco = scoLink()
        val tx = build(sco)
        tx.start(0)
        linkState = ScoLink.State.SUSPENDED
        listener.captured.onScoStateChanged(ScoLink.State.SUSPENDED)
        verify(exactly = 1) { sco.release(tx) }
        tx.shutdown()
    }

    @Test
    fun `link failing inside acquire is not waited on`() {
        failAcquire = true
        val sco = scoLink()
        val tx = build(sco)
        tx.start(0)
        verify(exactly = 1) { sco.release(tx) }
        assertNotEquals(
            "must not sit in ACQUIRING_SCO for the warm-up timeout",
            TxController.State.ACQUIRING_SCO,
            tx.currentStateForTest(),
        )
        tx.shutdown()
    }
}
