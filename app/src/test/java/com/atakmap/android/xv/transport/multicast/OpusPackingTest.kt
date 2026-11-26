package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusPackingTest {
    // TOC 0x70 = config 14 (hybrid FB, 10 ms), mono, code 0.
    private fun tenMs(vararg payload: Int) = byteArrayOf(0x70) + ByteArray(payload.size) { payload[it].toByte() }

    private fun tenMsOfSize(n: Int) = byteArrayOf(0x70) + ByteArray(n) { (it and 0x7F).toByte() }

    @Test
    fun `packet duration follows the TOC config and frame count`() {
        assertEquals(480, OpusPacking.packetSamples(byteArrayOf(0x70, 1)))
        assertEquals(960, OpusPacking.packetSamples(byteArrayOf(0x78, 1))) // hybrid FB 20 ms
        assertEquals(960, OpusPacking.packetSamples(byteArrayOf(0x71, 1, 2))) // code 1: 2 x 10 ms
        assertEquals(1920, OpusPacking.packetSamples(byteArrayOf(0x73, 0x04, 1))) // code 3: 4 x 10 ms
        assertEquals(480, OpusPacking.packetSamples(byteArrayOf(0x00.toByte(), 1))) // SILK NB 10 ms
        assertEquals(120, OpusPacking.packetSamples(byteArrayOf(0x80.toByte(), 1))) // CELT NB 2.5 ms
        assertNull(OpusPacking.packetSamples(ByteArray(0)))
        assertNull(OpusPacking.packetSamples(byteArrayOf(0x73))) // code 3 missing count byte
    }

    @Test
    fun `equal-size frames pack as code 1 with no length byte`() {
        val a = tenMs(1, 2, 3)
        val b = tenMs(4, 5, 6)
        val p = OpusPacking.pack(a, b)!!
        assertArrayEquals(byteArrayOf(0x71, 1, 2, 3, 4, 5, 6), p)
        assertEquals(OpusPacking.SAMPLES_20MS, OpusPacking.packetSamples(p))
    }

    @Test
    fun `different-size frames pack as code 2 with a one-byte length`() {
        val p = OpusPacking.pack(tenMs(1, 2), tenMs(3, 4, 5))!!
        assertArrayEquals(byteArrayOf(0x72, 2, 1, 2, 3, 4, 5), p)
        assertEquals(OpusPacking.SAMPLES_20MS, OpusPacking.packetSamples(p))
    }

    @Test
    fun `code 2 uses the two-byte length form at 252 bytes and above`() {
        for (len in intArrayOf(252, 253, 254, 255, 256, 600, 1275)) {
            val a = tenMsOfSize(len)
            val b = tenMsOfSize(len - 1)
            val p = OpusPacking.pack(a, b)!!
            val first = p[1].toInt() and 0xFF
            val second = p[2].toInt() and 0xFF
            assertTrue(first in 252..255)
            assertEquals("len=$len", len, first + 4 * second)
            assertEquals(1 + 2 + len + (len - 1), p.size)
            // Frame bytes survive intact and in order.
            assertArrayEquals(a.copyOfRange(1, a.size), p.copyOfRange(3, 3 + len))
            assertArrayEquals(b.copyOfRange(1, b.size), p.copyOfRange(3 + len, p.size))
        }
    }

    @Test
    fun `mismatched config or non-code-0 frames refuse to pack`() {
        assertNull(OpusPacking.pack(tenMs(1), byteArrayOf(0x78, 1))) // 10 ms hybrid vs 20 ms hybrid
        assertNull(OpusPacking.pack(tenMs(1), byteArrayOf(0x74, 1))) // stereo bit differs
        assertNull(OpusPacking.pack(byteArrayOf(0x71, 1, 2), tenMs(1))) // already code 1
        assertNull(OpusPacking.pack(ByteArray(0), tenMs(1)))
        assertNull(OpusPacking.pack(tenMsOfSize(1276), tenMs(1)))
    }

    @Test
    fun `only single-frame 10 ms packets are mergeable`() {
        assertTrue(OpusPacking.isMergeableTenMs(tenMs(1)))
        assertFalse(OpusPacking.isMergeableTenMs(byteArrayOf(0x78, 1)))
        assertFalse(OpusPacking.isMergeableTenMs(byteArrayOf(0x71, 1, 2)))
        assertFalse(OpusPacking.isMergeableTenMs(ByteArray(0)))
        assertNotNull(OpusPacking.pack(tenMs(), tenMs())) // zero-length (DTX-style) frames still pack
    }
}
