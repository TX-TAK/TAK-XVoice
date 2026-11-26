package com.atakmap.android.xv.transport

/**
 * Pure decision logic for swapping the channels of the two Mumble
 * sessions (slot 0 = VS1 / channel 1, slot 1 = VS2 / channel 2).
 *
 * A swap is the only way an operator "inverts" the speaker-mic buttons:
 * PTT1 is permanently bound to slot 0 and PTT2 to slot 1, so moving the
 * channels between the slots is what changes which button keys which
 * net. The swap is two back-to-back `UserState` self-moves on the two
 * already-live sessions — no TLS teardown, no re-auth — so it is fast,
 * but it is also two independent server decisions. Either join can be
 * refused (ACL, channel full, channel removed between the plan and the
 * ack), which leaves the slots half-swapped and, worse, both sessions
 * sitting in the same channel. This object decides whether a swap may
 * start, and how to resolve whatever the server actually did.
 *
 * Kept free of Android and session types so the whole policy is unit-
 * testable off-device; [MumbleTransport.swapChannels] is the adapter.
 */
object ChannelSwap {
    /** Why a swap was not started. */
    enum class Refusal {
        /** Primary session not connected / not in a channel. */
        NOT_CONNECTED,

        /** Channel 2 is off — nothing to swap with. */
        NO_CHANNEL_2,

        /** Channel 2 is configured but its session has not joined yet. */
        CHANNEL_2_NOT_JOINED,

        /** Both slots already report the same channel id. */
        SAME_CHANNEL,

        /** A direct (private) call is active on either slot. */
        IN_CALL,

        /** Operator is keyed on either slot. */
        TRANSMITTING,

        /** A previous swap has not resolved yet. */
        SWAP_IN_PROGRESS,
    }

    /**
     * The channel ids each slot occupied when the swap was planned.
     * Targets are the mirror image: primary → [secondaryFromId],
     * secondary → [primaryFromId].
     */
    data class Plan(
        val primaryFromId: Int,
        val secondaryFromId: Int,
    ) {
        val primaryToId: Int get() = secondaryFromId
        val secondaryToId: Int get() = primaryFromId
    }

    sealed class Decision {
        data class Proceed(
            val plan: Plan,
        ) : Decision()

        data class Refuse(
            val reason: Refusal,
        ) : Decision()
    }

    /**
     * Snapshot of the inputs a swap decision needs. Channel ids are the
     * server-confirmed ids (-1 = not joined).
     */
    data class State(
        val primaryConnected: Boolean,
        val primaryChannelId: Int,
        val secondaryPresent: Boolean,
        val secondaryConnected: Boolean,
        val secondaryChannelId: Int,
        val inPrivateCall: Boolean,
        val transmitting: Boolean,
        val swapInFlight: Boolean,
    )

    fun decide(s: State): Decision {
        // Order matters only for which reason the operator sees; the
        // most actionable one wins (keyed / in a call before topology).
        val refusal =
            when {
                s.swapInFlight -> Refusal.SWAP_IN_PROGRESS
                s.transmitting -> Refusal.TRANSMITTING
                s.inPrivateCall -> Refusal.IN_CALL
                !s.primaryConnected || s.primaryChannelId < 0 -> Refusal.NOT_CONNECTED
                !s.secondaryPresent -> Refusal.NO_CHANNEL_2
                !s.secondaryConnected || s.secondaryChannelId < 0 -> Refusal.CHANNEL_2_NOT_JOINED
                s.primaryChannelId == s.secondaryChannelId -> Refusal.SAME_CHANNEL
                else -> null
            }
        return refusal?.let { Decision.Refuse(it) }
            ?: Decision.Proceed(Plan(primaryFromId = s.primaryChannelId, secondaryFromId = s.secondaryChannelId))
    }

    /** What the adapter should do after observing the slots' channels. */
    sealed class Resolution {
        /** Both moves acked — swap complete. */
        data object Done : Resolution()

        /** Still waiting on at least one ack and the deadline hasn't passed. */
        data object Wait : Resolution()

        /** Only primary moved — send it back to [toId]. */
        data class RollBackPrimary(
            val toId: Int,
        ) : Resolution()

        /** Only secondary moved — send it back to [toId]. */
        data class RollBackSecondary(
            val toId: Int,
        ) : Resolution()

        /** Neither slot moved — nothing to undo, report failure. */
        data object NothingMoved : Resolution()
    }

    /**
     * Resolve a pending swap given the slots' currently-confirmed channel
     * ids. Before [timedOut] only completion is reported ([Done]) — a
     * single ack is normal mid-flight. Once timed out, a half-swap is
     * rolled back rather than left with both sessions on one channel
     * (which would double every received frame and break PTT2's net).
     */
    fun resolve(
        plan: Plan,
        observedPrimaryId: Int,
        observedSecondaryId: Int,
        timedOut: Boolean,
    ): Resolution {
        val primaryMoved = observedPrimaryId == plan.primaryToId
        val secondaryMoved = observedSecondaryId == plan.secondaryToId
        return when {
            primaryMoved && secondaryMoved -> Resolution.Done
            !timedOut -> Resolution.Wait
            primaryMoved -> Resolution.RollBackPrimary(plan.primaryFromId)
            secondaryMoved -> Resolution.RollBackSecondary(plan.secondaryFromId)
            else -> Resolution.NothingMoved
        }
    }

    /**
     * Duplicate-RX guard. While both sessions briefly share a channel
     * (mid-swap, or after a half-swap before rollback lands) the server
     * delivers every frame to both identities; forwarding both would play
     * each talker twice. The secondary's copy is the one dropped — the
     * primary is always the authoritative listener for its channel.
     */
    fun shouldDropSecondaryRx(
        slotIdx: Int,
        slotChannelId: Int,
        primaryChannelId: Int,
    ): Boolean = slotIdx == 1 && slotChannelId >= 0 && slotChannelId == primaryChannelId

    /** Operator-facing one-liner for a refusal. */
    fun refusalMessage(reason: Refusal): String =
        when (reason) {
            Refusal.NOT_CONNECTED -> "Not connected to a channel"
            Refusal.NO_CHANNEL_2 -> "Add channel 2 first"
            Refusal.CHANNEL_2_NOT_JOINED -> "Channel 2 is still connecting"
            Refusal.SAME_CHANNEL -> "Channels 1 and 2 are the same"
            Refusal.IN_CALL -> "Can't swap during a direct call"
            Refusal.TRANSMITTING -> "Can't swap while transmitting"
            Refusal.SWAP_IN_PROGRESS -> "Swap already in progress"
        }

    /** Final result reported to the plugin. */
    sealed class Outcome {
        data class Completed(
            val primaryName: String?,
            val secondaryName: String?,
        ) : Outcome()

        /** Server refused one leg; the other was undone. */
        data class RolledBack(
            val failedSlot: Int,
        ) : Outcome()

        /** Server refused both legs; nothing changed. */
        data object Failed : Outcome()
    }
}
