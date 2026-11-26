package com.atakmap.android.xv.transport.multicast

/**
 * Per-channel TX/RX framing between Opus voice frames and multicast
 * datagrams, selected by [WireFormat]. The transport layer stays
 * format-agnostic: it hands raw Opus down and gets datagram bytes back
 * (TX), hands datagram bytes up and gets attributed Opus back (RX).
 *
 * Instances hold per-burst TX state (RTP sequence/timestamp) and are
 * confined to the transport's TX thread + RX thread respectively; the
 * only cross-thread state is the [ChannelKeyRegistry], whose access
 * contract is documented on that class.
 */
sealed interface MulticastWireCodec {
    /**
     * Reset burst-relative TX state. Called on the PTT-down edge — the
     * same edge that resets the Mumble voice sequence — so both legs'
     * sequence numbers stay aligned for cross-leg RX dedup.
     */
    fun beginBurst()

    /**
     * Frame one Opus payload for the wire. Returns null when policy
     * forbids sending (e.g. [CryptoPolicy.REQUIRED] with no channel
     * key yet) — the caller drops the frame and counts it.
     */
    fun encodeTx(opus: ByteArray): ByteArray?

    /**
     * True when the most recent [encodeTx] returned null because the
     * codec is deliberately holding the frame to pack it with the next
     * one (not a policy drop). Lets the transport keep its
     * policy-drop diagnostics honest. Only meaningful right after an
     * [encodeTx] call on the same thread.
     */
    val lastEncodeBuffered: Boolean get() = false

    /**
     * Classify + unwrap one received datagram.
     *
     * @param sourceHost the datagram's source IP literal; identity of
     *   last resort for formats with no in-band speaker id.
     */
    fun decodeRx(
        datagram: ByteArray,
        sourceHost: String,
    ): RxResult

    sealed class RxResult {
        /**
         * A voice frame.
         *
         * @param speakerKey stable per-speaker attribution key, format
         *   `ssrc:<hex8>` (XV native) or `ip:<addr>` (OpenMANET
         *   compat). Distinct keys ⇒ distinct decoder states upstream.
         * @param seqInBurst burst-relative sequence when the wire
         *   carries one (XV native); null for raw-Opus interop frames.
         */
        data class Voice(
            val speakerKey: String,
            val seqInBurst: Int?,
            val opus: ByteArray,
        ) : RxResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Voice) return false
                return speakerKey == other.speakerKey &&
                    seqInBurst == other.seqInBurst &&
                    opus.contentEquals(other.opus)
            }

            override fun hashCode(): Int {
                var h = speakerKey.hashCode()
                h = 31 * h + (seqInBurst ?: -1)
                h = 31 * h + opus.contentHashCode()
                return h
            }
        }

        /** An XVMC control-plane message (key exchange, elections). */
        data class Control(
            val message: ControlPacket.Message,
        ) : RxResult()

        /** Not playable; [reason] feeds diagnostics counters. */
        data class Dropped(
            val reason: DropReason,
        ) : RxResult()
    }

    enum class DropReason {
        EMPTY,
        NOT_RTP,
        WRONG_PAYLOAD_TYPE,
        CLEARTEXT_FORBIDDEN,
        UNKNOWN_EPOCH,

        /**
         * An AEAD frame arrived but this receiver holds NO key at all —
         * peers are encrypted and we can't join in. Operator-actionable
         * (import a plan / reconnect to the server), so it must never
         * masquerade as line noise: a keyless device sat "MESH ACTIVE"
         * while deaf, its drops miscounted as NOT_RTP (field repro
         * 2026-07-16, offline restart lost the in-memory channel key).
         */
        ENCRYPTED_NO_KEY,
        BAD_TAG,
        MALFORMED,
    }
}

/**
 * XV-native framing: `[RTP header || Opus]`, optionally AEAD-wrapped
 * (`[epoch || nonce || ct+tag]` around the whole RTP frame) when a
 * channel key is live and policy allows.
 *
 * RX disambiguation between the three things that share the group:
 *   1. XVMC magic → control packet.
 *   2. First byte matches an epoch the key registry knows → AEAD frame
 *      (decrypt, then RTP-decode the plaintext).
 *   3. First byte 0x80 (RTP v2, no padding/ext/CSRC — the only byte-0
 *      our encoder emits) → cleartext RTP.
 * Key epochs are allocated 0..127 by the keying layer, so a cleartext
 * RTP frame (byte 0 = 0x80 = 128) can never collide with a known epoch.
 *
 * TX sequence numbers reset to 0 on [beginBurst] (unusual for general
 * RTP, deliberate for XV↔XV: cross-leg dedup matches Mumble's per-burst
 * sequence, which also resets on the PTT edge). The RTP timestamp keeps
 * running across bursts so receivers still see silence gaps.
 */
class XvNativeWireCodec(
    private val ssrc: Long,
    private val cryptoPolicy: CryptoPolicy,
    private val keyRegistry: ChannelKeyRegistry?,
) : MulticastWireCodec {
    private var seq = 0
    private var timestamp = 0L
    private var burstStart = true

    override fun beginBurst() {
        seq = 0
        burstStart = true
    }

    override fun encodeTx(opus: ByteArray): ByteArray? {
        val rtp =
            RtpFraming.encode(
                sequenceNumber = seq,
                timestamp = timestamp,
                ssrc = ssrc,
                opusPayload = opus,
                marker = burstStart,
            )
        seq = (seq + 1) and 0xFFFF
        timestamp = (timestamp + RtpFraming.TIMESTAMP_INCREMENT_20MS) and 0xFFFFFFFFL
        burstStart = false

        val keyed = keyRegistry?.hasKey() == true
        return when (cryptoPolicy) {
            CryptoPolicy.CLEARTEXT -> rtp
            CryptoPolicy.PREFERRED -> if (keyed) keyRegistry!!.encrypt(rtp) else rtp
            CryptoPolicy.REQUIRED -> if (keyed) keyRegistry!!.encrypt(rtp) else null
        }
    }

    override fun decodeRx(
        datagram: ByteArray,
        sourceHost: String,
    ): MulticastWireCodec.RxResult {
        if (datagram.isEmpty()) return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.EMPTY)

        if (ControlPacket.isControl(datagram)) {
            val msg = ControlPacket.decode(datagram)
            // Magic matched but the body didn't decode: 4-byte magic
            // collision from ciphertext (~2^-32) or a truncated control
            // frame. Either way it isn't voice.
            if (msg != null) return MulticastWireCodec.RxResult.Control(msg)
            return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.MALFORMED)
        }

        val registry = keyRegistry
        if (registry != null && registry.hasKey()) {
            when (val r = registry.decryptDetailed(datagram)) {
                is ChannelKeyRegistry.DecryptResult.Ok -> return decodeRtp(r.plaintext)
                is ChannelKeyRegistry.DecryptResult.BadTag ->
                    return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.BAD_TAG)
                is ChannelKeyRegistry.DecryptResult.UnknownEpoch -> {
                    // Not an epoch we hold a key for — fall through to
                    // the cleartext-RTP branch below (byte 0x80 lands
                    // here by design).
                }
                ChannelKeyRegistry.DecryptResult.Malformed ->
                    return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.MALFORMED)
            }
        }

        if (cryptoPolicy == CryptoPolicy.REQUIRED) {
            return MulticastWireCodec.RxResult.Dropped(
                if (registry?.hasKey() == true) {
                    MulticastWireCodec.DropReason.CLEARTEXT_FORBIDDEN
                } else {
                    // No key yet: everything is undecryptable, cleartext
                    // or not. UNKNOWN_EPOCH tells diagnostics "keying
                    // hasn't converged" instead of implying a peer is
                    // violating policy.
                    MulticastWireCodec.DropReason.UNKNOWN_EPOCH
                },
            )
        }
        // Byte 0 below 0x80 is an epoch byte, never RTP v2: this is an
        // AEAD frame we hold no usable key for. Classify it as the
        // keying gap it is — ENCRYPTED_NO_KEY when we have no key at
        // all (operator must import a plan or re-enroll), UNKNOWN_EPOCH
        // when we're keyed but behind — instead of letting it fall into
        // decodeRtp and miscount as NOT_RTP line noise.
        if ((datagram[0].toInt() and 0xFF) < 0x80) {
            return MulticastWireCodec.RxResult.Dropped(
                if (registry?.hasKey() == true) {
                    MulticastWireCodec.DropReason.UNKNOWN_EPOCH
                } else {
                    MulticastWireCodec.DropReason.ENCRYPTED_NO_KEY
                },
            )
        }
        return decodeRtp(datagram)
    }

    private fun decodeRtp(frame: ByteArray): MulticastWireCodec.RxResult {
        val decoded =
            RtpFraming.decode(frame)
                ?: return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.NOT_RTP)
        val (header, payload) = decoded
        if (header.payloadType != RtpFraming.PAYLOAD_TYPE_OPUS) {
            return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.WRONG_PAYLOAD_TYPE)
        }
        return MulticastWireCodec.RxResult.Voice(
            speakerKey = "ssrc:%08x".format(header.ssrc),
            seqInBurst = header.sequenceNumber,
            opus = payload,
        )
    }
}

/**
 * ATAK VX interop framing: each datagram is ONE raw Opus frame — no RTP
 * header, no sequence numbers. Matches legacy VX / OpenVLM peers.
 *
 * This is NOT OpenMANET's wire format (openmanetd requires RTP and drops
 * anything else); use [OpenManetRtpWireCodec] / [WireFormat.OPENMANET_RTP]
 * for that.
 */
class VxWireCodec(private val ssrc: Long) : MulticastWireCodec {
    private var seq = 0
    private var timestamp = 0L
    private var burstStart = true

    override fun beginBurst() {
        seq = 0
        burstStart = true
    }

    override fun encodeTx(opus: ByteArray): ByteArray? = opus

    override fun decodeRx(
        datagram: ByteArray,
        sourceHost: String,
    ): MulticastWireCodec.RxResult {
        if (datagram.isEmpty()) return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.EMPTY)

        // Ignore any XV control packets if they accidentally bleed over.
        if (ControlPacket.isControl(datagram)) {
            return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.MALFORMED)
        }

        return MulticastWireCodec.RxResult.Voice(
            speakerKey = "ip:$sourceHost",
            seqInBurst = null,
            opus = datagram,
        )
    }

    companion object {
        const val VX_SAMPLE_RATE_HZ = 48_000
        const val VX_CHANNELS = 1
        const val VX_BITRATE_BPS = 12_000
        const val VX_FRAME_MS = 20
        const val VX_FRAME_SAMPLES = 960
        const val VX_FEC = true
        const val VX_DTX = false
    }
}

/**
 * OpenMANET (openmanetd) interop framing: cleartext `[RTP || Opus]`,
 * payload type 111, 48 kHz clock, one 20 ms Opus packet per datagram,
 * no XVMC control packets on the group.
 *
 * Differences from [XvNativeWireCodec], each driven by openmanetd's
 * receive path (`internal/comms/rtp`):
 *  - **Sequence and timestamp are monotonic across bursts.** The jitter
 *    buffer only re-syncs on an SSRC change or ~2 s of idle, so a
 *    per-burst `seq = 0` reset on a quick re-key looks like a stale
 *    packet and is dropped. Initial values are random (RFC 3550 §5.1).
 *  - **Two consecutive 10 ms Opus frames are merged into one 20 ms
 *    packet** ([OpusPacking]) because the XV capture path encodes 10 ms
 *    (to match Mumla on the Mumble leg) while openmanetd encodes/plays
 *    20 ms. The first frame of a pair is held ([lastEncodeBuffered]);
 *    the second emits the packet. Frames that are already 20 ms (or
 *    anything not mergeable) pass straight through.
 *  - **Timestamp advances by the actual packet duration**, parsed from
 *    the Opus TOC, not a fixed 960.
 *  - A held half-pair at PTT release is dropped on the next
 *    [beginBurst] (at most 10 ms of trailing audio) rather than leaking
 *    into the following burst.
 *
 * RX accepts only RTP with PT 111; non-RTP and other payload types are
 * dropped. Speaker attribution is by SSRC.
 */
class OpenManetRtpWireCodec(
    private val ssrc: Long,
    initialSeq: Int = kotlin.random.Random.nextInt(0x10000),
    initialTimestamp: Long = kotlin.random.Random.nextLong(0x1_0000_0000L),
) : MulticastWireCodec {
    private var seq = initialSeq and 0xFFFF
    private var timestamp = initialTimestamp and 0xFFFFFFFFL
    private var burstStart = true
    private var pending: ByteArray? = null

    override var lastEncodeBuffered: Boolean = false
        private set

    /** Frames discarded unsent (stranded half-pair, unmergeable mismatch). Diagnostics only. */
    var droppedFrames: Long = 0
        private set

    override fun beginBurst() {
        if (pending != null) {
            droppedFrames++
            pending = null
        }
        burstStart = true
    }

    override fun encodeTx(opus: ByteArray): ByteArray? {
        lastEncodeBuffered = false
        if (opus.isEmpty()) return null

        if (!OpusPacking.isMergeableTenMs(opus)) {
            // Already a full-size (or unusual) packet: send as-is. A held
            // 10 ms frame can't precede it on the wire without breaking
            // order, so it is dropped.
            if (pending != null) {
                droppedFrames++
                pending = null
            }
            return frame(opus)
        }

        val held = pending
        if (held == null) {
            pending = opus
            lastEncodeBuffered = true
            return null
        }
        pending = null
        val merged = OpusPacking.pack(held, opus)
        if (merged == null) {
            // Config mismatch between consecutive frames (encoder was
            // reconfigured mid-burst): drop the older, hold the newer.
            droppedFrames++
            pending = opus
            lastEncodeBuffered = true
            return null
        }
        return frame(merged)
    }

    private fun frame(opusPacket: ByteArray): ByteArray {
        val rtp =
            RtpFraming.encode(
                sequenceNumber = seq,
                timestamp = timestamp,
                ssrc = ssrc,
                opusPayload = opusPacket,
                marker = burstStart,
            )
        seq = (seq + 1) and 0xFFFF
        val samples = OpusPacking.packetSamples(opusPacket) ?: RtpFraming.TIMESTAMP_INCREMENT_20MS
        timestamp = (timestamp + samples) and 0xFFFFFFFFL
        burstStart = false
        return rtp
    }

    override fun decodeRx(
        datagram: ByteArray,
        sourceHost: String,
    ): MulticastWireCodec.RxResult {
        if (datagram.isEmpty()) return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.EMPTY)
        val decoded =
            RtpFraming.decode(datagram)
                ?: return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.NOT_RTP)
        val (header, payload) = decoded
        if (header.payloadType != RtpFraming.PAYLOAD_TYPE_OPUS) {
            return MulticastWireCodec.RxResult.Dropped(MulticastWireCodec.DropReason.WRONG_PAYLOAD_TYPE)
        }
        return MulticastWireCodec.RxResult.Voice(
            speakerKey = "ssrc:%08x".format(header.ssrc),
            // The RTP sequence is stream-wide here, not burst-relative.
            seqInBurst = null,
            opus = payload,
        )
    }
}

/** Factory keyed off a channel's config. */
fun ChannelMulticastConfig.newWireCodec(
    ssrc: Long,
    keyRegistry: ChannelKeyRegistry?,
): MulticastWireCodec =
    when (wireFormat) {
        WireFormat.XV_NATIVE -> XvNativeWireCodec(ssrc, cryptoPolicy, keyRegistry)
        WireFormat.VX_COMPAT -> VxWireCodec(ssrc)
        WireFormat.OPENMANET_RTP -> OpenManetRtpWireCodec(ssrc)
    }
