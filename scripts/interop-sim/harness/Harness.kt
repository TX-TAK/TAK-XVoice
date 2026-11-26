import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.MulticastWireCodec
import com.atakmap.android.xv.transport.multicast.OpenManetRtpWireCodec
import com.atakmap.android.xv.transport.multicast.VxWireCodec
import com.atakmap.android.xv.transport.multicast.XvNativeWireCodec
import java.io.File

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun main(args: Array<String>) {
    val frames = File(args[0]).readLines().map { it.split(" ").let { p -> p[0].toInt() to unhex(p[1]) } }
    val ssrc = 0x1234ABCDL
    val scenarios =
        linkedMapOf<String, MulticastWireCodec>(
            // The fix: what XV now puts on an OPENMANET_RTP leg.
            "openmanet_rtp" to OpenManetRtpWireCodec(ssrc),
            // Old behavior: patch leg forced to VX_COMPAT (bare Opus).
            "old_vx_raw_opus" to VxWireCodec(ssrc),
            // Old per-burst seq reset + 960 ticks per 10 ms frame, had it been RTP.
            "old_perburst_seq" to XvNativeWireCodec(ssrc, CryptoPolicy.CLEARTEXT, null),
        )
    File(args[1]).printWriter().use { out ->
        for ((name, codec) in scenarios) {
            var lastBurst = -1
            for ((burst, opus) in frames) {
                if (burst != lastBurst) {
                    codec.beginBurst()
                    lastBurst = burst
                }
                codec.encodeTx(opus)?.let { out.println("$name $burst ${hex(it)}") }
            }
        }
    }
}
