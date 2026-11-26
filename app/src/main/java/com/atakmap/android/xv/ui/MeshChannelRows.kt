package com.atakmap.android.xv.ui

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
import com.atakmap.android.xv.transport.multicast.MulticastMode
import com.atakmap.android.xv.transport.multicast.WireFormat

/**
 * What each row of Settings › Mesh › Channels shows. Pure (no Android),
 * so badges and labels are unit-tested.
 *
 * The XV mesh leg is always encrypted. A channel shows a CLEAR badge only
 * when it also carries a device-local interop patch (OpenMANET / VX),
 * whose traffic has no encryption layer.
 */
object MeshChannelRows {
    data class Row(
        val name: String,
        val serverChannel: Boolean,
        val active: Boolean,
        val mode: MulticastMode,
        /** "OPENMANET" / "VX" when an interop leg is configured, else null. */
        val interopLabel: String?,
        val hasClearLeg: Boolean,
        val detail: String,
    )

    /**
     * Rows in display order: the lobby first, then server channels, then
     * offline-only channels, each alphabetical (case-insensitive).
     */
    fun build(
        candidates: List<String>,
        serverCanonical: Set<String>,
        activeCanonical: String?,
        configFor: (String) -> ChannelMulticastConfig?,
    ): List<Row> =
        candidates
            .map { name ->
                val canonical = MulticastGroupDerivation.canonicalChannelName(name)
                val config = configFor(name) ?: ChannelMulticastConfig.defaultFor(name)
                val interop = interopFormat(config)
                val server = canonical in serverCanonical
                Row(
                    name = name,
                    serverChannel = server,
                    active = canonical == activeCanonical,
                    mode = config.mode,
                    interopLabel = interop?.let { label(it) },
                    hasClearLeg = interop != null,
                    detail = detail(server, canonical == activeCanonical, interop),
                )
            }.sortedWith(
                compareByDescending<Row> {
                    MulticastGroupDerivation.canonicalChannelName(it.name) == MulticastGroupDerivation.LOBBY_CANONICAL
                }.thenByDescending { it.serverChannel }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )

    // The interop / clear wire format a channel carries, if any: an interop
    // primary, or a patch leg that is interop or cleartext. An encrypted
    // XV-native patch is just another encrypted leg: no badge.
    private fun interopFormat(c: ChannelMulticastConfig): WireFormat? =
        when {
            c.wireFormat.isInterop -> c.wireFormat
            c.patchGroup != null &&
                (c.patchWireFormat.isInterop || c.patchCryptoPolicy == CryptoPolicy.CLEARTEXT) -> c.patchWireFormat
            else -> null
        }

    private fun label(format: WireFormat): String =
        when (format) {
            WireFormat.OPENMANET_RTP -> "OPENMANET"
            WireFormat.VX_COMPAT -> "VX"
            WireFormat.XV_NATIVE -> "XV"
        }

    private fun detail(
        server: Boolean,
        active: Boolean,
        interop: WireFormat?,
    ): String {
        val parts = mutableListOf(if (server) "Server channel · mesh leg auto-derived" else "Offline only")
        if (interop != null) parts += "${label(interop).lowercase().replaceFirstChar { it.uppercase() }} patch (this device)"
        if (active) parts += "active"
        return parts.joinToString(" · ")
    }
}
