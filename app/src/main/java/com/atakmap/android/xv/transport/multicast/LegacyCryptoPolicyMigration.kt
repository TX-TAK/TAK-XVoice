package com.atakmap.android.xv.transport.multicast

/**
 * Once-only rewrite of stored per-channel configs to the mesh-policy
 * invariant ([ChannelMulticastConfig.meshPolicyNormalized]): every
 * XV-native leg REQUIRED, cleartext only on interop legs, convertible
 * interop primaries split into an encrypted primary + cleartext patch.
 *
 * Also retires the `xv_crypto_policy_<channel>` prefs that an earlier
 * build kept as a second, disagreeing source of crypto policy; with the
 * invariant in place they carry no information, so they are deleted.
 */
object LegacyCryptoPolicyMigration {
    const val LEGACY_PREF_PREFIX = "xv_crypto_policy_"

    data class Report(
        /** Configs whose stored form changed (re-persist these). */
        val normalized: List<ChannelMulticastConfig>,
        /**
         * Channels left with a cleartext interop PRIMARY because their
         * patch slot is already taken: not representable as encrypted
         * primary + cleartext patch without dropping an endpoint.
         */
        val unconvertibleInteropPrimaries: List<String>,
    )

    fun plan(stored: List<ChannelMulticastConfig>): Report =
        Report(
            normalized = stored.mapNotNull { cfg -> cfg.meshPolicyNormalized().takeIf { it != cfg } },
            unconvertibleInteropPrimaries =
            stored
                .filter { it.meshPolicyNormalized().wireFormat.isInterop }
                .map { it.channelName },
        )
}
