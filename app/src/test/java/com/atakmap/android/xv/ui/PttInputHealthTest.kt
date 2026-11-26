package com.atakmap.android.xv.ui

import com.atakmap.android.xv.ui.PttInputHealth.Card
import com.atakmap.android.xv.ui.PttInputHealth.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PttInputHealthTest {
    private val repair = "Bluetooth speaker-mic rejected the connection — forget and re-pair it in Android Bluetooth settings"

    @Test
    fun `repair report lands on the card its reason names`() {
        val h = PttInputHealth()
        assertEquals(Card.SPEAKER_MIC, h.report(Kind.NEEDS_REPAIR, repair))
        assertEquals(Kind.NEEDS_REPAIR, h.issue(Card.SPEAKER_MIC)?.kind)
        assertNull(h.issue(Card.BUTTON))
        assertEquals(Card.BUTTON, h.report(Kind.NEEDS_REPAIR, "Bluetooth external button rejected the connection"))
    }

    @Test
    fun `a reason naming neither input gets no chip`() {
        val h = PttInputHealth()
        assertNull(h.report(Kind.NOT_RECOGNISED, "Bluetooth permission missing"))
        assertNull(h.issue(Card.SPEAKER_MIC))
        assertNull(h.issue(Card.BUTTON))
    }

    @Test
    fun `needs re-pair is not overwritten by not recognised`() {
        val h = PttInputHealth()
        h.report(Kind.NEEDS_REPAIR, repair)
        h.report(Kind.NOT_RECOGNISED, "speaker-mic has no PTT buttons")
        assertEquals(Kind.NEEDS_REPAIR, h.issue(Card.SPEAKER_MIC)?.kind)
    }

    @Test
    fun `not recognised is upgraded to needs re-pair`() {
        val h = PttInputHealth()
        h.report(Kind.NOT_RECOGNISED, "speaker-mic has no PTT buttons")
        h.report(Kind.NEEDS_REPAIR, repair)
        assertEquals(Kind.NEEDS_REPAIR, h.issue(Card.SPEAKER_MIC)?.kind)
    }

    @Test
    fun `a clean connect clears the card`() {
        val h = PttInputHealth()
        h.report(Kind.NEEDS_REPAIR, repair)
        h.clear(Card.SPEAKER_MIC)
        assertNull(h.issue(Card.SPEAKER_MIC))
    }
}
