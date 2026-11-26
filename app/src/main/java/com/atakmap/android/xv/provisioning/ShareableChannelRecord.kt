package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig

/**
 * Builds the shareable (exportable / vault-sealed) record for a channel
 * that was just saved or edited. The record must never LOSE a key the
 * device holds: the key vault is re-sealed from these records, so writing
 * `preSharedKey = null` over a held key drops it from disk (and from any
 * later comms-plan export) while the mesh is down.
 *
 * Key precedence: a key minted by this save, else the live key the mesh
 * holds for the channel (it may have rotated since the record was made),
 * else the key the prior record carried (mesh down / not yet keyed).
 */
object ShareableChannelRecord {
    fun updated(
        prior: CommsPlan.Channel?,
        config: ChannelMulticastConfig,
        newKey: ByteArray? = null,
        liveKey: ByteArray? = null,
    ): CommsPlan.Channel =
        CommsPlan.Channel(
            displayName = prior?.displayName ?: config.channelName,
            config = config,
            preSharedKey = newKey ?: liveKey ?: prior?.preSharedKey,
        )
}
