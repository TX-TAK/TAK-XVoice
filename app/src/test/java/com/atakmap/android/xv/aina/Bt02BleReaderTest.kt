package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [Bt02BleReader.decodeBt02Code] — pins the Inrico BT02's
 * button-code mapping captured from the on-device probe (2026-11-21).
 *
 * The BT02 sends a single ENUMERATED code byte, not a bitmask, so
 * these tests deliberately also assert that bit-combined values do NOT
 * decode as multiple buttons — that would be the wrong (Zello-style)
 * interpretation.
 */
class Bt02BleReaderTest {
    @Test
    fun `0x00 decodes to nothing held (released)`() {
        assertTrue(Bt02BleReader.decodeBt02Code(0x00).isEmpty())
    }

    @Test
    fun `0x01 decodes to primary PTT`() {
        assertEquals(setOf(AinaButton.PTT), Bt02BleReader.decodeBt02Code(0x01))
    }

    @Test
    fun `0x08 decodes as one-shot emergency pulse, not a held state`() {
        assertEquals(setOf(AinaButton.PTTE), Bt02BleReader.decodeBt02Pulse(0x08))
        assertTrue(Bt02BleReader.decodeBt02Code(0x08).isEmpty())
        assertTrue(Bt02BleReader.decodeBt02Pulse(0x01).isEmpty())
        assertTrue(Bt02BleReader.decodeBt02Pulse(0x00).isEmpty())
    }

    @Test
    fun `0x03 top button is intentionally unmapped`() {
        // The top button (channel/talk-group cycle) has no assigned
        // mission yet and must emit nothing. This pins that decision so
        // wiring it up later is a deliberate change, not accidental.
        assertTrue(Bt02BleReader.decodeBt02Code(0x03).isEmpty())
    }

    @Test
    fun `code byte is enumerated, not a bitmask`() {
        // 0x09 = 0x01 | 0x08. If this were a bitmask it would decode as
        // PTT + emergency held together. It is NOT a bitmask — 0x09 is
        // simply an unrecognized code and holds nothing. This guards
        // against a future refactor "simplifying" the when into bit
        // tests and silently changing the semantics.
        assertTrue(Bt02BleReader.decodeBt02Code(0x09).isEmpty())
        assertTrue(Bt02BleReader.decodeBt02Code(0x0B).isEmpty())
    }

    @Test
    fun `unknown codes decode to nothing held`() {
        assertTrue(Bt02BleReader.decodeBt02Code(0x02).isEmpty())
        assertTrue(Bt02BleReader.decodeBt02Code(0x7F).isEmpty())
        assertTrue(Bt02BleReader.decodeBt02Code(0xFF).isEmpty())
    }

    @Test
    fun `isBt02Name matches the advertised B02PTT prefix`() {
        // Real advert is "B02PTT-<unit>"; we match the prefix so every
        // unit is recognized without baking in a device-specific suffix.
        assertTrue(Bt02BleReader.isBt02Name("B02PTT-0000"))
        assertTrue(Bt02BleReader.isBt02Name("b02ptt-xyz")) // case-insensitive
        assertTrue(Bt02BleReader.isBt02Name("  B02PTT-0001  ")) // space tolerant
    }

    @Test
    fun `isBt02Name rejects other names and blanks`() {
        assertFalse(Bt02BleReader.isBt02Name("PTT-Z01"))
        assertFalse(Bt02BleReader.isBt02Name("Pryme BT-PTT-Z"))
        assertFalse(Bt02BleReader.isBt02Name(null))
        assertFalse(Bt02BleReader.isBt02Name(""))
        assertFalse(Bt02BleReader.isBt02Name("   "))
    }

    @Test
    fun `matchesCompanionBleName matches exact unit name and rejects other units`() {
        assertTrue(Bt02BleReader.matchesCompanionBleName("B02PTT-0000", "B02PTT-0000"))
        assertTrue(Bt02BleReader.matchesCompanionBleName("B02PTT-0000", "b02ptt-0000"))
        assertFalse(Bt02BleReader.matchesCompanionBleName("B02PTT-0000", "B02PTT-9999"))
        assertFalse(Bt02BleReader.matchesCompanionBleName("B02PTT-0000", "APTT304B9F"))
        assertTrue(Bt02BleReader.matchesCompanionBleName(null, "B02PTT-0000"))
    }
}
