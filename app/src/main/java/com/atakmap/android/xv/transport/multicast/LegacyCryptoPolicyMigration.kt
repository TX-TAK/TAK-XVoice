package com.atakmap.android.xv.transport.multicast

/**
 * One-time fold of the retired per-channel `xv_crypto_policy_<channel>`
 * preference into [ChannelMulticastConfig.cryptoPolicy], which is now
 * the single source of truth for a channel's encryption posture.
 *
 * The two stores used to disagree: the config (default PREFERRED) is
 * what the multicast leg actually enforced on the wire, while the
 * separate pref (default REQUIRED when absent) drove the channel dialog
 * and the VX-interop prompt. Every writer of the pref also wrote the
 * config with the same value, so the two only diverge when the
 * per-channel gear dialog later re-saved the config — and that dialog
 * could only offer PREFERRED for an encrypted channel, silently
 * downgrading REQUIRED. Rules:
 *
 *  - Stored config present, pref says REQUIRED, config says PREFERRED on
 *    an XV-native wire format: that is the silent downgrade — restore
 *    REQUIRED (the operator's last explicit choice).
 *  - Any other disagreement: the config wins. It is what was on the
 *    wire, and the gear dialog's explicit Cleartext choice is newer
 *    than the pref.
 *  - Pref with no stored config: an orphan (the config was forgotten or
 *    never stored); the channel already ran on its default, so the pref
 *    is dropped rather than materialized into a new override.
 *
 * Every legacy key is removed afterwards regardless of outcome.
 */
object LegacyCryptoPolicyMigration {
    const val LEGACY_PREF_PREFIX = "xv_crypto_policy_"

    /**
     * @param legacy legacy pref values keyed by canonical channel name
     *   (prefix already stripped); unparseable values are ignored.
     * @param configs the stored per-channel configs.
     * @return the configs that must be re-persisted with a corrected policy.
     */
    fun plan(
        legacy: Map<String, String>,
        configs: List<ChannelMulticastConfig>,
    ): List<ChannelMulticastConfig> {
        val byName = configs.associateBy { it.channelName }
        return legacy.mapNotNull { (channel, raw) ->
            val legacyPolicy = runCatching { CryptoPolicy.valueOf(raw) }.getOrNull() ?: return@mapNotNull null
            val cfg = byName[MulticastGroupDerivation.canonicalChannelName(channel)] ?: return@mapNotNull null
            val silentlyDowngraded =
                legacyPolicy == CryptoPolicy.REQUIRED &&
                    cfg.cryptoPolicy == CryptoPolicy.PREFERRED &&
                    cfg.wireFormat == WireFormat.XV_NATIVE
            if (silentlyDowngraded) cfg.copy(cryptoPolicy = CryptoPolicy.REQUIRED) else null
        }
    }

    /**
     * One-time upgrade for the 2026-11-25 default change (PREFERRED →
     * REQUIRED for XV-native channels). Stored configs were mostly
     * materialized from the old default (patch apply, pinning, plan
     * import) or saved by a gear dialog that could not offer REQUIRED, so
     * a stored XV-native PREFERRED is treated as "the old default", not
     * an operator choice. Interop formats are CLEARTEXT and untouched.
     * An operator who really wants PREFERRED re-selects it afterwards;
     * the upgrade never runs twice.
     *
     * @param explicitlyPreferred canonical names whose legacy
     *   `xv_crypto_policy_<channel>` pref said PREFERRED — proof of a
     *   deliberate operator choice (only the channel dialog wrote it), so
     *   those channels are left alone.
     */
    fun upgradeOldDefault(
        configs: List<ChannelMulticastConfig>,
        explicitlyPreferred: Set<String> = emptySet(),
    ): List<ChannelMulticastConfig> =
        configs
            .filter { it.wireFormat == WireFormat.XV_NATIVE && it.cryptoPolicy == CryptoPolicy.PREFERRED }
            .filter { it.channelName !in explicitlyPreferred }
            .map { it.copy(cryptoPolicy = CryptoPolicy.REQUIRED) }

    /** Canonical channel names whose legacy pref value is PREFERRED. */
    fun explicitlyPreferred(legacy: Map<String, String>): Set<String> =
        legacy
            .filterValues { it == CryptoPolicy.PREFERRED.name }
            .keys
            .map { MulticastGroupDerivation.canonicalChannelName(it) }
            .toSet()
}
