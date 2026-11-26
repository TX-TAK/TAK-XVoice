package com.atakmap.android.xv.transport

/**
 * State machine for one channel swap at a time. Pure bookkeeping: it
 * never touches a session, a handler or a clock. [MumbleTransport]
 * applies the side effects each [Step] asks for (send a rollback join,
 * report an outcome, schedule the next deadline). Thread-safe: every
 * public method synchronises on this instance.
 *
 * Lifecycle:
 *   IDLE ──begin──▶ SWAPPING ──both acks──────────────▶ IDLE (Completed)
 *                     │ deadline, one leg moved
 *                     ▼
 *                  ROLLING_BACK ──rollback ack / deadline──▶ IDLE (RolledBack)
 *                     │
 *   SWAPPING ──deadline, nothing moved──▶ IDLE (Failed)
 *   any phase ──cancel()──▶ IDLE (Cancelled)
 *
 * The swap stays "in flight" through ROLLING_BACK, so a failed swap's
 * rollback ack can't trigger a success cue, and a second swap can't
 * start, until the slots are back where they began.
 */
class ChannelSwapCoordinator {
    private enum class Phase { IDLE, SWAPPING, ROLLING_BACK }

    private var phase = Phase.IDLE
    private var plan: ChannelSwap.Plan? = null
    private var prevSecondaryName: String? = null
    private var rollbackSlot = -1
    private var rollbackOutcome: ChannelSwap.Outcome? = null

    /** Bumped on every begin / phase change / cancel; deadlines carry it. */
    var token: Long = 0L
        private set

    /** What the transport must do after an [evaluate] call. */
    sealed class Step {
        /** Nothing yet (waiting on an ack, or a stale deadline). */
        data object None : Step()

        /** Swap finished (any way); report [outcome] to the plugin. */
        data class Report(
            val outcome: ChannelSwap.Outcome,
            val restoreSecondaryName: String?,
            val restore: Boolean,
        ) : Step()

        /**
         * Send slot [slot] back to [toId] (stamp it as a self-move) and
         * schedule a new deadline for [token]. Restore the secondary name
         * to [restoreSecondaryName].
         */
        data class RollBack(
            val slot: Int,
            val toId: Int,
            val token: Long,
            val restoreSecondaryName: String?,
        ) : Step()
    }

    @Synchronized
    fun inFlight(): Boolean = phase != Phase.IDLE

    /** Start tracking [p]. Returns the deadline token to schedule. */
    @Synchronized
    fun begin(
        p: ChannelSwap.Plan,
        previousSecondaryName: String?,
    ): Long {
        phase = Phase.SWAPPING
        plan = p
        prevSecondaryName = previousSecondaryName
        rollbackSlot = -1
        rollbackOutcome = null
        return ++token
    }

    /**
     * Evaluate against the slots' server-confirmed channel ids.
     * [deadlineToken] is the token a deadline fired with, or null for an
     * ack-driven check (which can only complete, never time out).
     */
    @Synchronized
    fun evaluate(
        observedPrimaryId: Int,
        observedSecondaryId: Int,
        deadlineToken: Long?,
    ): Step {
        val p = plan
        val stale = deadlineToken != null && deadlineToken != token
        if (phase == Phase.IDLE || p == null || stale) return Step.None
        val timedOut = deadlineToken != null
        return when (phase) {
            Phase.SWAPPING -> evaluateSwap(p, observedPrimaryId, observedSecondaryId, timedOut)
            else -> evaluateRollback(p, observedPrimaryId, observedSecondaryId, timedOut)
        }
    }

    private fun evaluateSwap(
        p: ChannelSwap.Plan,
        observedPrimaryId: Int,
        observedSecondaryId: Int,
        timedOut: Boolean,
    ): Step =
        when (val res = ChannelSwap.resolve(p, observedPrimaryId, observedSecondaryId, timedOut)) {
            ChannelSwap.Resolution.Wait -> Step.None
            ChannelSwap.Resolution.Done -> finish(ChannelSwap.Outcome.Completed(null, null), restore = false)
            ChannelSwap.Resolution.NothingMoved -> finish(ChannelSwap.Outcome.Failed, restore = true)
            is ChannelSwap.Resolution.RollBackPrimary -> startRollback(slot = 0, toId = res.toId, failedSlot = 1)
            is ChannelSwap.Resolution.RollBackSecondary -> startRollback(slot = 1, toId = res.toId, failedSlot = 0)
        }

    private fun startRollback(
        slot: Int,
        toId: Int,
        failedSlot: Int,
    ): Step {
        phase = Phase.ROLLING_BACK
        rollbackSlot = slot
        rollbackOutcome = ChannelSwap.Outcome.RolledBack(failedSlot)
        return Step.RollBack(slot, toId, ++token, prevSecondaryName)
    }

    private fun evaluateRollback(
        p: ChannelSwap.Plan,
        observedPrimaryId: Int,
        observedSecondaryId: Int,
        timedOut: Boolean,
    ): Step {
        val home =
            if (rollbackSlot == 0) observedPrimaryId == p.primaryFromId else observedSecondaryId == p.secondaryFromId
        if (!home && !timedOut) return Step.None
        return finish(rollbackOutcome ?: ChannelSwap.Outcome.Failed, restore = true)
    }

    private fun finish(
        outcome: ChannelSwap.Outcome,
        restore: Boolean,
    ): Step {
        val restoreName = prevSecondaryName
        reset()
        return Step.Report(outcome, restoreName, restore)
    }

    /**
     * Abandon any in-flight swap (secondary torn down, a direct call
     * started, another join requested, disconnect). Returns true when a
     * swap was actually cancelled, so the caller reports
     * [ChannelSwap.Outcome.Cancelled] exactly once.
     */
    @Synchronized
    fun cancel(): Boolean {
        if (phase == Phase.IDLE) return false
        reset()
        return true
    }

    private fun reset() {
        phase = Phase.IDLE
        plan = null
        prevSecondaryName = null
        rollbackSlot = -1
        rollbackOutcome = null
        token++
    }
}
