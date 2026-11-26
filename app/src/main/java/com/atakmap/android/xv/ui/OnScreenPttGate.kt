package com.atakmap.android.xv.ui

/**
 * Which on-screen PTT button (if any) is keyed. Only one at a time.
 *
 * Stuck-key rules (a stuck key is the worst failure this plugin has):
 *  - A second button pressed while one is held is ignored, and so are its
 *    UP/CANCEL. With split touches, a brush across PTT2 while PTT1 is
 *    held must not end PTT1's transmission.
 *  - Release is accepted only for the slot that pressed, and returns that
 *    slot so the caller ends TX for exactly what it started.
 *  - [releaseAny] is the escape hatch for a hidden, closed, detached or
 *    disabled panel: whatever is held gets released.
 *
 * Main-thread only (touch dispatch and view lifecycle callbacks).
 */
class OnScreenPttGate {
    var heldSlot: Int? = null
        private set

    /** Finger down on [slot]. True = key it; false = ignore (another is held). */
    fun down(slot: Int): Boolean {
        if (heldSlot != null) return false
        heldSlot = slot
        return true
    }

    /** Finger up / cancel / slide-off on [slot]. Returns the slot to un-key, or null. */
    fun release(slot: Int): Int? = heldSlot?.takeIf { it == slot }?.also { heldSlot = null }

    /** Panel hidden/closed/detached, or the held button is being disabled. */
    fun releaseAny(): Int? = heldSlot?.also { heldSlot = null }

    companion object {
        /**
         * Slide-off: true once the touch point has left the button by more
         * than [slopPx] in any direction.
         */
        fun slidOff(
            x: Float,
            y: Float,
            width: Int,
            height: Int,
            slopPx: Float,
        ): Boolean = x < -slopPx || y < -slopPx || x > width + slopPx || y > height + slopPx
    }
}
