package com.atakmap.android.xv.transport.multicast

/**
 * Per-channel election of the single peer who relays voice between
 * the Mumble server and the multicast group ("the bridge"). Runs on
 * every mesh-enabled client; each client independently reaches the
 * same verdict from the same inputs, so no coordination round-trips
 * are needed and a partition heals itself on the next beacon.
 *
 * Inputs, refreshed continuously:
 *   - [ControlPacket.Message.PeerBeacon]s heard on the channel's
 *     multicast group (peers self-report server connectivity), fed in
 *     via [observePeer]. Beacons are the ONLY production feed —
 *     deliberately. CoT presence crosses network boundaries via the
 *     TAK server, and a candidate we can't reach over multicast can't
 *     relay for us: feeding CoT here made devices defer to lower-uid
 *     candidates on OTHER networks, leaving whole networks bridgeless.
 *     Beacon reachability IS the relay domain, so operator groups on
 *     separate networks each elect their own bridge.
 *   - Our own server connectivity, passed to [evaluate].
 *
 * Scope: per channel. A peer counts for a channel only when its beacon
 * lists that channel (a beacon with no channel list counts for every
 * channel, for older senders). Before this, one global vote let a
 * server-connected peer on ANOTHER channel win, so the device actually on
 * the mesh-only peer's channel stood down and that peer heard nothing.
 *
 * Rule: the bridge is the **lowest UID among server-connected peers**
 * (including ourselves), and the bridge role only activates while at
 * least one known peer LACKS server connectivity — a fully-connected
 * team needs no relay, and relaying anyway would double every burst
 * (server copy + mesh echo) for everyone, wasting battery and airtime
 * on the constrained mesh.
 *
 * Lowest-uid-wins mirrors [KeyElection]'s convergence trick: a total
 * order with no extra messages. Two clients can disagree for at most
 * one beacon interval after a topology change; the dedup layer
 * absorbs the brief double-relay that overlap can produce.
 *
 * Pure logic: no clocks, no IO. Not thread-safe — confine to the
 * mesh manager's tick thread.
 */
class BridgeElection(
    private val ourUid: String,
    private val peerStaleMs: Long = PEER_STALE_MS,
) {
    private data class PeerView(
        val uid: String,
        val mumbleConnected: Boolean,
        val lastSeenMs: Long,
        // Canonical channels from the beacon; null = not listed (any channel).
        val channels: Set<String>? = null,
        val bridging: Boolean = false,
    ) {
        fun on(channel: String?): Boolean = channel == null || channels == null || channel in channels
    }

    private val peers = HashMap<String, PeerView>()

    /**
     * Record a peer sighting from either a mesh beacon or CoT
     * presence. Later observations replace earlier ones wholesale —
     * connectivity is a live fact, not an accumulating history.
     */
    fun observePeer(
        uid: String,
        mumbleConnected: Boolean,
        nowMs: Long,
        channels: Set<String>? = null,
        bridging: Boolean = false,
    ) {
        if (uid == ourUid) return
        peers[uid] = PeerView(uid, mumbleConnected, nowMs, channels?.takeIf { it.isNotEmpty() }, bridging)
    }

    /** Explicit departure signal (Mumble UserRemove, presence purge). */
    fun observePeerDeparted(uid: String) {
        peers.remove(uid)
    }

    /**
     * Should WE be relaying right now?
     *
     * @param ourMumbleConnected live server connectivity of this client.
     * @param channel canonical channel to elect for (null = every peer).
     */
    fun evaluate(
        nowMs: Long,
        ourMumbleConnected: Boolean,
        channel: String? = null,
    ): Boolean {
        pruneStale(nowMs)
        if (!ourMumbleConnected) return false
        val onChannel = peers.values.filter { it.on(channel) }
        if (onChannel.none { !it.mumbleConnected }) return false
        val connectedUids = onChannel.filter { it.mumbleConnected }.map { it.uid } + ourUid
        return connectedUids.min() == ourUid
    }

    /** A fresh beacon from [uid] says it is connected to the server. */
    fun peerOnServer(
        uid: String,
        nowMs: Long,
    ): Boolean {
        pruneStale(nowMs)
        return peers[uid]?.mumbleConnected == true
    }

    /** Another fresh peer reports it is bridging [channel]. */
    fun otherBridgeOn(
        channel: String,
        nowMs: Long,
    ): Boolean {
        pruneStale(nowMs)
        return peers.values.any { it.bridging && it.on(channel) }
    }

    /** True when a fresh peer without server connectivity is known. */
    fun anyPeerOffline(nowMs: Long): Boolean {
        pruneStale(nowMs)
        return peers.values.any { !it.mumbleConnected }
    }

    fun reset() {
        peers.clear()
    }

    /** For tests / diagnostics. */
    internal fun knownPeerCount(): Int = peers.size

    private fun pruneStale(nowMs: Long) {
        peers.entries.removeAll { nowMs - it.value.lastSeenMs > peerStaleMs }
    }

    companion object {
        /**
         * A peer unheard-from for this long stops influencing the
         * election. Three beacon intervals (see the mesh manager's
         * BEACON_INTERVAL_MS = 5 s) plus slack: tolerate two lost
         * beacons on a lossy mesh before re-electing.
         */
        const val PEER_STALE_MS: Long = 17_000
    }
}
