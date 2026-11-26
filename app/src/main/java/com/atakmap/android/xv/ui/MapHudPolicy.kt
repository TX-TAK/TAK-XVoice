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

    /**
     * Operator size choice. Each is a share of the screen's shorter side,
     * so buttons scale with the device; [minDp] keeps every size a usable
     * (gloved) touch target and [maxDp] stops a huge display from
     * producing a hex that hides the map.
     */
    enum class Size(
        val fraction: Float,
    ) {
        SMALL(0.08f),
        MEDIUM(0.10f),
        LARGE(0.12f),
        ;

        companion object {
            fun parse(name: String?): Size = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
        }
    }

    const val MIN_HEX_WIDTH_DP = 48f
    const val MAX_HEX_WIDTH_DP = 96f

    /** Hex aspect from the brand badge (width : height = 72 : 82). */
    const val HEX_ASPECT = 82f / 72f

    fun hexWidthDp(
        size: Size,
        shortSideDp: Float,
    ): Float = (shortSideDp * size.fraction).coerceIn(MIN_HEX_WIDTH_DP, MAX_HEX_WIDTH_DP)

    enum class Look {
        /** Ready to key (Honey outline). */
        IDLE,

        /** Someone is talking on this channel (Honey fill). */
        RECEIVING,

        /** Finger down, not yet confirmed on the air (neutral, no red). */
        PRESSED,

        /** You are transmitting on this slot (Texas Red) — service-confirmed only. */
        TRANSMITTING,

        /** The press was refused (listen-only, channel 2 not live, swap in flight). */
        DENIED,

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
        heldSlot: Int? = null,
        deniedSlot: Int? = null,
    ): List<Button> =
        listOfNotNull(
            button(slot1, heldSlot == 0, deniedSlot == 0),
            slot2?.let { button(it, heldSlot == 1, deniedSlot == 1) },
        )

    /**
     * [held] = the operator's finger is on this button right now: a
     * neutral PRESSED look, never red. Red + "TX" only once the service
     * confirms audio is on the air ([SlotState.transmitting]), so a
     * refused or still-priming press never looks like a transmission.
     * [denied] = the press was refused; shown briefly by the caller.
     */
    fun button(
        s: SlotState,
        held: Boolean = false,
        denied: Boolean = false,
    ): Button {
        val look =
            when {
                s.transmitting -> Look.TRANSMITTING
                denied -> Look.DENIED
                held -> Look.PRESSED
                !s.live || s.listenOnly -> Look.UNAVAILABLE
                s.talkers.isNotEmpty() -> Look.RECEIVING
                else -> Look.IDLE
            }
        val label =
            when (look) {
                Look.TRANSMITTING -> "TX"
                Look.DENIED -> "DENIED"
                else -> labelName(s.channel)
            }
        return Button(s.slot, look, label)
    }

    /**
     * Talker pill text: every talking slot, e.g. "1 · Bravo-2   2 · Charlie-1";
     * null when nobody is talking.
     */
    fun talkerPill(
        slot1: SlotState,
        slot2: SlotState?,
    ): String? =
        listOfNotNull(slot1, slot2)
            .filter { it.talkers.isNotEmpty() }
            .joinToString("   ") { "${it.slot + 1} · ${it.talkers.joinToString(", ")}" }
            .ifEmpty { null }

    /** Full upper-case channel name (never truncated; long names scroll). */
    fun labelName(channel: String?): String = channel?.trim().orEmpty().uppercase().ifEmpty { "—" }

    /** Label text sizes tried, largest first, before falling back to scrolling. */
    val LABEL_SIZES_SP = listOf(13f, 12f, 11f, 10f, 9f)

    /** Text size for a scrolling label. */
    const val SCROLL_SIZE_SP = 11f

    /** Ticks a scrolling label rests at its start and at its end. */
    const val SCROLL_PAUSE_TICKS = 3

    data class LabelLayout(
        val lines: List<String>,
        val sizeSp: Float,
    )

    /**
     * Fit [label] inside [maxWidth] on at most two lines, word-wrapped,
     * trying [LABEL_SIZES_SP] from largest down. Null when it doesn't fit
     * even at the smallest size — the caller then scrolls it instead of
     * cutting it off. [measure] returns the drawn width of a string at a
     * size (injected so this stays testable).
     */
    fun layoutLabel(
        label: String,
        maxWidth: Float,
        measure: (text: String, sizeSp: Float) -> Float,
    ): LabelLayout? =
        LABEL_SIZES_SP.firstNotNullOfOrNull { size ->
            wrapTwoLines(label, maxWidth) { measure(it, size) }?.let { LabelLayout(it, size) }
        }

    private fun wrapTwoLines(
        label: String,
        maxWidth: Float,
        width: (String) -> Float,
    ): List<String>? {
        val words = label.split(' ').filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            when {
                width(candidate) <= maxWidth -> current = candidate
                current.isNotEmpty() && width(word) <= maxWidth -> {
                    lines += current
                    current = word
                }
                else -> return null // a single word wider than the hex
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.takeIf { it.size <= 2 }
    }

    /** How many leading characters of [label] fit in [maxWidth] (at least 1). */
    fun fitChars(
        label: String,
        maxWidth: Float,
        measure: (String) -> Float,
    ): Int = (label.length downTo 1).firstOrNull { measure(label.take(it)) <= maxWidth } ?: 1

    /**
     * The visible slice of a scrolling label at [tick]: rests at the start
     * for [SCROLL_PAUSE_TICKS], steps one character per tick to the end,
     * rests there, then starts over. [window] = characters that fit.
     */
    fun scrollWindow(
        label: String,
        window: Int,
        tick: Long,
    ): String {
        val travel = (label.length - window).coerceAtLeast(0)
        if (travel == 0) return label
        val cycle = travel + 2 * SCROLL_PAUSE_TICKS
        val phase = (tick % cycle).toInt()
        val offset = (phase - SCROLL_PAUSE_TICKS).coerceIn(0, travel)
        return label.substring(offset, offset + window)
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
