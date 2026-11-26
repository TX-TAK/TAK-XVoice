package com.atakmap.android.xv.transport.multicast

/**
 * Minimal, allocation-light Opus packet inspection and 2-frame packing
 * per RFC 6716 §3 (TOC byte + frame-count codes). Pure Kotlin so it
 * runs in JVM unit tests with no codec on the classpath.
 *
 * Why this exists: the Mumble path deliberately encodes 10 ms Opus frames
 * (matching Mumla), but OpenMANET's voice daemon encodes and plays 20 ms
 * packets. For the OpenMANET RTP leg we merge two consecutive 10 ms
 * frames into one 20 ms packet WITHOUT re-encoding (a repacketize, not a
 * transcode): code 1 when both frames are the same size, code 2 when
 * they differ.
 */
object OpusPacking {
    /** 10 ms at 48 kHz. */
    const val SAMPLES_10MS = 480

    /** 20 ms at 48 kHz. */
    const val SAMPLES_20MS = 960

    private const val MAX_FRAME_BYTES = 1275

    /** Frame duration in 48 kHz samples for a TOC config number (0..31). */
    private fun frameSamples(config: Int): Int =
        when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3] // SILK: 10/20/40/60 ms
            config < 16 -> if (config and 1 == 0) 480 else 960 // Hybrid: 10/20 ms
            else -> intArrayOf(120, 240, 480, 960)[config and 3] // CELT: 2.5/5/10/20 ms
        }

    /**
     * Total duration of an Opus packet in 48 kHz samples, or null when
     * the packet is empty or malformed (so callers can fall back to a
     * safe default instead of guessing).
     */
    fun packetSamples(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val perFrame = frameSamples(toc shr 3)
        val frames =
            when (toc and 0x03) {
                0 -> 1
                1, 2 -> 2
                else -> {
                    if (packet.size < 2) return null
                    packet[1].toInt() and 0x3F
                }
            }
        if (frames == 0) return null
        val total = frames * perFrame
        // RFC 6716 §3.2.5: a packet never exceeds 120 ms.
        return if (total > 5760) null else total
    }

    /**
     * True when [packet] is a single-frame (code 0) 10 ms packet that
     * [pack] can merge: the only shape our 10 ms encoder emits.
     */
    fun isMergeableTenMs(packet: ByteArray): Boolean =
        packet.isNotEmpty() &&
            (packet[0].toInt() and 0x03) == 0 &&
            packetSamples(packet) == SAMPLES_10MS

    /**
     * Merge two single-frame packets into one two-frame packet.
     * Returns null when they can't be merged (different TOC
     * config/channel bits, not code 0, or an oversized frame) — the
     * caller then sends them separately.
     */
    fun pack(
        first: ByteArray,
        second: ByteArray,
    ): ByteArray? {
        if (first.isEmpty() || second.isEmpty()) return null
        val tocA = first[0].toInt() and 0xFF
        val tocB = second[0].toInt() and 0xFF
        if ((tocA and 0x03) != 0 || (tocB and 0x03) != 0) return null
        // Config (5 bits) + stereo bit must match; only the code differs.
        if ((tocA and 0xFC) != (tocB and 0xFC)) return null
        val lenA = first.size - 1
        val lenB = second.size - 1
        if (lenA > MAX_FRAME_BYTES || lenB > MAX_FRAME_BYTES) return null

        return if (lenA == lenB) {
            // Code 1: two frames, equal compressed size, no length bytes.
            val out = ByteArray(1 + lenA + lenB)
            out[0] = ((tocA and 0xFC) or 0x01).toByte()
            System.arraycopy(first, 1, out, 1, lenA)
            System.arraycopy(second, 1, out, 1 + lenA, lenB)
            out
        } else {
            // Code 2: two frames, different sizes; length of frame 1 is
            // coded up front (1 byte if < 252, else 2 bytes), frame 2
            // takes the remainder.
            val lenBytes = if (lenA < 252) 1 else 2
            val out = ByteArray(1 + lenBytes + lenA + lenB)
            out[0] = ((tocA and 0xFC) or 0x02).toByte()
            if (lenBytes == 1) {
                out[1] = lenA.toByte()
            } else {
                out[1] = (252 + (lenA and 0x03)).toByte()
                out[2] = ((lenA - (out[1].toInt() and 0xFF)) shr 2).toByte()
            }
            System.arraycopy(first, 1, out, 1 + lenBytes, lenA)
            System.arraycopy(second, 1, out, 1 + lenBytes + lenA, lenB)
            out
        }
    }
}
