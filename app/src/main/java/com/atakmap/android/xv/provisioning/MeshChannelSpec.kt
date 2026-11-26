package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
import com.atakmap.android.xv.transport.multicast.MulticastMode
import com.atakmap.android.xv.transport.multicast.WireFormat

/**
 * Turns operator-typed strings from the "configure a channel manually"
 * form (provisioning path 3) into a validated [ChannelMulticastConfig].
 * This is the escape hatch from the zero-config default: name a channel
 * yourself, and optionally pin its group/port. The channel is always
 * XV-native and encrypted (REQUIRED); interop with an external system
 * (OpenMANET, an ATAK VX talkgroup) goes on the channel's cleartext
 * patch leg instead.
 *
 * The parsing + cross-field validation (port range, group/port must be
 * pinned together, OpenMANET-compat demands a pin + cleartext) is the
 * error-prone part, so it lives here as pure logic the UI just feeds
 * strings into. [build] never throws — it returns either a config or an
 * operator-readable [Result.error].
 *
 * Leaving group + port blank keeps the deterministic derivation (no
 * manual pinning — the whole point), so the common "I just want to name
 * it" case still needs zero address entry.
 */
object MeshChannelSpec {
    data class Result(
        val config: ChannelMulticastConfig?,
        val error: String?,
        /** Generate + install a fresh pre-shared key for this channel? True for encrypted postures. */
        val autoKey: Boolean,
    ) {
        companion object {
            fun ok(
                config: ChannelMulticastConfig,
                autoKey: Boolean,
            ) = Result(config, null, autoKey)

            fun err(message: String) = Result(null, message, false)
        }
    }

    /**
     * New channel: always XV-native, always [CryptoPolicy.REQUIRED] (the
     * mesh-policy invariant, see
     * [ChannelMulticastConfig.meshPolicyNormalized]); external interop goes
     * on the channel's cleartext patch leg, never on this primary.
     *
     * @param name channel name (required). Blank is an error.
     * @param group pinned multicast group, or null/blank to derive.
     * @param port pinned UDP port as text, or null/blank to derive.
     */
    fun build(
        name: String,
        group: String?,
        port: String?,
    ): Result {
        if (name.isBlank()) return Result.err("Channel name is required.")
        val pin = parsePin(group, port)
        val config =
            ChannelMulticastConfig(
                // Canonicalized like ChannelMulticastConfig.defaultFor so
                // config lookups and the derivation can't fork on case /
                // Unicode-encoding differences in an operator-typed name.
                channelName = MulticastGroupDerivation.canonicalChannelName(name),
                mode = MulticastMode.FAILOVER,
                wireFormat = WireFormat.XV_NATIVE,
                cryptoPolicy = CryptoPolicy.REQUIRED,
                pinnedGroup = pin.group,
                pinnedPort = pin.port,
            )
        // A pin parse error wins; cross-field rules (port range, class-D
        // group) are the config's own, so this stays the one source of truth.
        val error = pin.error ?: config.validate()
        return if (error != null) Result.err(error) else Result.ok(config, autoKey = true)
    }

    /**
     * Edit an EXISTING channel from the same form. Only the pin is
     * editable; mode, patch leg, wire format and key are kept, and the
     * result is normalized to the mesh-policy invariant (an XV-native
     * primary is always REQUIRED). [Result.autoKey] is always false: an
     * edit never mints a pre-shared key. A channel still waiting for its
     * key is left to the key election, because a freshly minted key would
     * fork it from peers that already hold one. Rotation stays the
     * explicit Rotate action.
     *
     * @param existing the channel's stored config, or its
     *   [ChannelMulticastConfig.defaultFor] when none is stored.
     */
    fun edit(
        existing: ChannelMulticastConfig,
        group: String?,
        port: String?,
    ): Result {
        val pin = parsePin(group, port)
        val config = existing.copy(pinnedGroup = pin.group, pinnedPort = pin.port).meshPolicyNormalized()
        val error = pin.error ?: config.validate()
        return if (error != null) Result.err(error) else Result.ok(config, autoKey = false)
    }

    private data class Pin(
        val group: String? = null,
        val port: Int? = null,
        val error: String? = null,
    )

    private fun parsePin(
        group: String?,
        port: String?,
    ): Pin {
        val groupTrim = group?.trim().orEmpty()
        val portTrim = port?.trim().orEmpty()
        val parsedPort = portTrim.toIntOrNull()
        return when {
            groupTrim.isEmpty() != portTrim.isEmpty() ->
                Pin(error = "Pin the group and port together, or leave both blank to derive them automatically.")
            groupTrim.isEmpty() -> Pin()
            parsedPort == null -> Pin(error = "Port “$portTrim” is not a number.")
            else -> Pin(group = groupTrim, port = parsedPort)
        }
    }
}
