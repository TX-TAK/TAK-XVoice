package com.atakmap.android.xv.audio

/**
 * Per-burst Opus encode timing. Encode runs synchronously on the capture
 * read thread, so any frame slower than its real-time budget (10 ms at
 * 480 samples / 48 kHz) delays the next AudioRecord read; a run of them
 * overflows the record buffer and drops audio. Logged once per burst.
 *
 * Diagnostic only: plain fields, written from the capture thread and
 * read/reset at burst edges.
 */
class EncodeTimingStats(
    private val budgetNs: Long = FRAME_BUDGET_NS,
    private val earlyFrames: Int = EARLY_FRAMES,
) {
    private var count = 0
    private var totalNs = 0L
    private var maxNs = 0L
    private var overBudget = 0
    private var overBudgetEarly = 0

    fun reset() {
        count = 0
        totalNs = 0L
        maxNs = 0L
        overBudget = 0
        overBudgetEarly = 0
    }

    fun record(elapsedNs: Long) {
        count++
        totalNs += elapsedNs
        if (elapsedNs > maxNs) maxNs = elapsedNs
        if (elapsedNs > budgetNs) {
            overBudget++
            if (count <= earlyFrames) overBudgetEarly++
        }
    }

    /** e.g. "n=412 avg=2.1ms max=18.4ms over10ms=37 (first100=35)". */
    fun summary(): String {
        if (count == 0) return "n=0"
        val avgMs = totalNs / count / 1e6
        val maxMs = maxNs / 1e6
        return "n=$count avg=${"%.1f".format(avgMs)}ms max=${"%.1f".format(maxMs)}ms " +
            "over${budgetNs / 1_000_000}ms=$overBudget (first$earlyFrames=$overBudgetEarly)"
    }

    companion object {
        /** 10 ms: one 480-sample frame at 48 kHz. */
        const val FRAME_BUDGET_NS = 10_000_000L

        /** First second of a burst at 10 ms frames. */
        const val EARLY_FRAMES = 100
    }
}
