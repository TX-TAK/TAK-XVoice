package com.atakmap.android.xv.ui

/**
 * Pure rules for the on-map PTT overlay (map HUD): when it shows, which
 * buttons it has, and what each looks like. No Android or ATAK types, so
 * the policy — including the stuck-key rules — is unit-testable.
 *
 * Operator rules:
 *  - The HUD shows while the XV panel is collapsed and hides while it's
 *    open (the panel has its own PTT buttons).
 *  - Button 1 keys channel 1 and is always present; button 2 keys
 *    channel 2 and exists only while channel 2 is on. Same fixed mapping
 *    as the speaker-mic and the panel; nothing here remaps it.
 *  - A stuck key is the worst failure this plugin can have: any state
 *    change that hides a held button must release it.
 */
object MapHudPolicy {
    enum class Side { RIGHT, LEFT }

    enum class Look {
        /** Ready to key (Honey outline). */
        IDLE,

        /** Someone is talking on this channel (Honey fill). */
        RECEIVING,

        /** You are transmitting on this slot (Texas Red). */
        TRANSMITTING,

        /** Server has you listen-only, or the slot isn't live (dim). */
        UNAVAILABLE,
    }

    data class SlotState(
        val slot: Int,
        val channel: String?,
        val live: Boolean,
        val listenOnly: Boolean,
        val transmitting: Boolean,
        val talkers: List<String>,
    )

    data class Button(
        val slot: Int,
        val look: Look,
        val label: String,
    )

    fun visible(
        enabled: Boolean,
        panelOpen: Boolean,
    ): Boolean = enabled && !panelOpen

    fun side(leftHanded: Boolean): Side = if (leftHanded) Side.LEFT else Side.RIGHT

    /** Buttons to draw, top to bottom. Channel 2 only while it's on. */
    fun buttons(
        slot1: SlotState,
        slot2: SlotState?,
    ): List<Button> = listOfNotNull(button(slot1), slot2?.let { button(it) })

    fun button(s: SlotState): Button {
        val look =
            when {
                s.transmitting -> Look.TRANSMITTING
                !s.live || s.listenOnly -> Look.UNAVAILABLE
                s.talkers.isNotEmpty() -> Look.RECEIVING
                else -> Look.IDLE
            }
        val label =
            when (look) {
                Look.TRANSMITTING -> "TX"
                else -> shortName(s.channel)
            }
        return Button(s.slot, look, label)
    }

    /** Talker pill text ("1 · Bravo-2"), or null when nobody is talking. */
    fun talkerPill(
        slot1: SlotState,
        slot2: SlotState?,
    ): String? {
        val speaking = listOfNotNull(slot1, slot2).firstOrNull { it.talkers.isNotEmpty() } ?: return null
        return "${speaking.slot + 1} · ${speaking.talkers.joinToString(", ")}"
    }

    /** Fits a 72dp hex: upper-case, at most [max] characters. */
    fun shortName(
        channel: String?,
        max: Int = 7,
    ): String {
        val n = channel?.trim().orEmpty().uppercase()
        return when {
            n.isEmpty() -> "—"
            n.length <= max -> n
            else -> n.take(max - 1) + "…"
        }
    }

    /**
     * Stuck-key guard: the slot to release when visibility or the button
     * set changes, or null. A held button that is no longer drawn (panel
     * opened, HUD disabled, channel 2 turned off) must be released.
     */
    fun releaseOnChange(
        heldSlot: Int?,
        nowVisible: Boolean,
        drawnSlots: Set<Int>,
    ): Int? = heldSlot?.takeIf { !nowVisible || it !in drawnSlots }
}
