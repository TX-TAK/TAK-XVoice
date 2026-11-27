package com.atakmap.android.xv.service

import com.atakmap.android.xv.audio.ConcentusOpusDecoder
import com.atakmap.android.xv.audio.ConcentusOpusEncoder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Runs the Opus codec over synthetic voice-like audio so ART compiles the
 * encode/decode hot paths before the first PTT (see the call site in
 * VoicePlant). Input is a voice-band tone with low noise, not silence:
 * silence lets the encoder skip most of the SILK/CELT work, which would
 * leave exactly the paths that matter cold.
 */
internal object CodecWarmup {
    /** ~3 s of 10 ms frames: enough for the JIT to compile the loops. */
    const val FRAMES = 300

    private const val SAMPLE_RATE = 48_000
    private const val FRAME_SAMPLES = 480
    private const val TONE_HZ = 220.0
    private const val TONE_AMPLITUDE = 6_000.0
    private const val NOISE_AMPLITUDE = 800

    /** Returns a short summary for the log, e.g. "300 frames in 2140ms". */
    fun run(frames: Int = FRAMES): String {
        val startNs = System.nanoTime()
        val encoder = ConcentusOpusEncoder()
        val decoder = ConcentusOpusDecoder()
        val pcm = ShortArray(FRAME_SAMPLES)
        val rng = java.util.Random(0x58_56L)
        var phase = 0.0
        val step = 2 * PI * TONE_HZ / SAMPLE_RATE
        try {
            repeat(frames) {
                for (i in pcm.indices) {
                    val noise = rng.nextInt(2 * NOISE_AMPLITUDE + 1) - NOISE_AMPLITUDE
                    pcm[i] = (sin(phase) * TONE_AMPLITUDE + noise).toInt().toShort()
                    phase += step
                }
                val packet = encoder.encode(pcm)
                if (packet.isNotEmpty()) decoder.decode(packet)
            }
        } finally {
            encoder.close()
            decoder.close()
        }
        return "$frames frames in ${(System.nanoTime() - startNs) / 1_000_000}ms"
    }
}
