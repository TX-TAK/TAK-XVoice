package com.atakmap.android.xv.ui

/**
 * Pure rules behind the unified channel list: row ordering / subchannel
 * depth, search filtering, and what a tap on a row's [1] or [2] pill
 * does. Kept free of Android types so it is unit-testable.
 *
 * Operator rules encoded here:
 *  - Channel 1 is never blank: its lit pill can't be tapped off.
 *  - Channel 2 is optional: tapping its lit pill clears it.
 *  - Tapping a channel's pill for the slot the channel does NOT hold,
 *    when it already holds the other slot, swaps the slots.
 *  - PTT1/PTT2 stay fixed to slots 1/2; swapping is the only inversion.
 */
object ChannelListPolicy {
    enum class Access { FULL, LISTEN, NONE }

    /** Input row: one channel from the server directory or the mesh. */
    data class Channel(
        val id: Int,
        val name: String,
        val parentId: Int,
        val access: Access,
        val offline: Boolean = false,
    )

    /** Output row with its indent depth (0 = top level). */
    data class Row(
        val channel: Channel,
        val depth: Int,
    )

    private const val ROOT_ID = 0

    /**
     * Server channels as a tree in display order: the lobby (Root, id 0)
     * first, then top-level channels, each followed by its subchannels
     * (depth + 1). Siblings sort by access tier (full → listen → none),
     * then case-insensitive name. Mumble's top-level channels are Root's
     * children, so they get depth 0 alongside the lobby. Channels whose
     * parent is unknown are treated as top level. Offline/mesh-only
     * channels follow, flat, alphabetically.
     */
    fun order(channels: List<Channel>): List<Row> {
        val server = channels.filter { !it.offline }
        val ids = server.map { it.id }.toSet()
        val byParent =
            server
                .filter { it.id != ROOT_ID }
                .groupBy { if (it.parentId in ids && it.parentId != ROOT_ID) it.parentId else ROOT_ID }
        val sibling =
            compareBy<Channel>({ it.access.ordinal }, { it.name.lowercase() })
        val out = mutableListOf<Row>()
        server.firstOrNull { it.id == ROOT_ID }?.let { out += Row(it, 0) }
        val visited = mutableSetOf<Int>()

        fun walk(
            parent: Int,
            depth: Int,
        ) {
            for (c in byParent[parent].orEmpty().sortedWith(sibling)) {
                if (!visited.add(c.id)) continue
                out += Row(c, depth)
                walk(c.id, depth + 1)
            }
        }
        walk(ROOT_ID, 0)
        channels
            .filter { it.offline }
            .sortedBy { it.name.lowercase() }
            .forEach { out += Row(it, 0) }
        return out
    }

    /**
     * Search filter. A blank query keeps everything. Otherwise keeps rows
     * whose name contains the query (case-insensitive) and flattens them
     * (depth 0) so a matching subchannel isn't shown indented under a
     * parent that was filtered out.
     */
    fun filter(
        rows: List<Row>,
        query: String,
    ): List<Row> {
        val q = query.trim()
        if (q.isEmpty()) return rows
        return rows
            .filter { it.channel.name.contains(q, ignoreCase = true) }
            .map { it.copy(depth = 0) }
    }

    /** What a pill tap should do. */
    sealed class Action {
        data class SetPrimary(
            val name: String,
        ) : Action()

        data class SetSecondary(
            val name: String,
        ) : Action()

        data object ClearSecondary : Action()

        data object Swap : Action()

        /** Do nothing; tell the operator why. */
        data class Explain(
            val message: String,
        ) : Action()
    }

    private fun same(
        a: String?,
        b: String?,
    ): Boolean = a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * Decide the effect of tapping [slot]'s pill (0 = [1], 1 = [2]) on
     * [channel], given the current channel 1 ([ch1]) and channel 2
     * ([ch2], null/blank = off).
     */
    fun onPillTap(
        slot: Int,
        channel: Channel,
        ch1: String?,
        ch2: String?,
    ): Action {
        val name = channel.name
        val ch2On = !ch2.isNullOrBlank()
        val onCh1 = same(name, ch1)
        val onCh2 = ch2On && same(name, ch2)
        return when {
            channel.access == Access.NONE -> Action.Explain("No access to $name")
            slot == 0 && onCh1 -> Action.Explain("Channel 1 is always set — pick another channel to move it")
            slot == 0 && onCh2 -> Action.Swap
            slot == 0 -> Action.SetPrimary(name)
            onCh2 -> Action.ClearSecondary
            onCh1 && ch2On -> Action.Swap
            onCh1 -> Action.Explain("$name is channel 1 — pick a different channel for 2")
            else -> Action.SetSecondary(name)
        }
    }

    /** Pill visual state for a row. */
    enum class Pill { ON, OFF, DISABLED }

    fun pillState(
        slot: Int,
        channel: Channel,
        ch1: String?,
        ch2: String?,
    ): Pill =
        when {
            slot == 0 && same(channel.name, ch1) -> Pill.ON
            slot == 1 && !ch2.isNullOrBlank() && same(channel.name, ch2) -> Pill.ON
            channel.access == Access.NONE -> Pill.DISABLED
            else -> Pill.OFF
        }
}
