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

    // "Root" is Mumble's name for the lobby; XV shows it as "Lobby".
    // Treat them as one channel so a slot persisted as "Root" still
    // lights the Lobby row (and Lobby can't also be put on the other slot).
    private fun canonical(name: String): String {
        val n = name.trim()
        return if (n.equals("root", ignoreCase = true)) "lobby" else n.lowercase()
    }

    private fun same(
        a: String?,
        b: String?,
    ): Boolean = a != null && b != null && canonical(a) == canonical(b)

    /**
     * Decide the effect of tapping [slot]'s pill (0 = [1], 1 = [2]) on
     * [channel], given the current channel 1 ([ch1]), channel 2 ([ch2],
     * null/blank = off), and whether the voice server is connected
     * ([online]).
     *
     * Offline / mesh-only rows: channel 2 is a second server identity, so
     * it can never be a mesh-only channel. Channel 1 can be one only while
     * the server is down (the mesh leg then carries it); while online the
     * server channel drives the mesh leg, so the tap explains instead of
     * silently failing a server join.
     */
    fun onPillTap(
        slot: Int,
        channel: Channel,
        ch1: String?,
        ch2: String?,
        online: Boolean = true,
    ): Action {
        val ch2On = !ch2.isNullOrBlank()
        val onCh1 = same(channel.name, ch1)
        val onCh2 = ch2On && same(channel.name, ch2)
        return when {
            channel.access == Access.NONE -> Action.Explain("No access to ${channel.name}")
            slot == 0 -> tapOne(channel, onCh1, onCh2, online)
            else -> tapTwo(channel, onCh1, onCh2, ch2On)
        }
    }

    // [1] pill. Mesh-only rows can't be channel 1 while the server is up;
    // channel 1 can't be tapped off; swap needs the server.
    private fun tapOne(
        channel: Channel,
        onCh1: Boolean,
        onCh2: Boolean,
        online: Boolean,
    ): Action {
        val name = channel.name
        return when {
            channel.offline && online && !onCh1 ->
                Action.Explain("$name is mesh-only — it carries channel 1 when the server is unreachable")
            onCh1 -> Action.Explain("Channel 1 is always set — pick another channel to move it")
            // Swap is two server moves: only possible online. Offline, say
            // how to get there instead of a swap that can only fail.
            onCh2 && online -> Action.Swap
            onCh2 -> Action.Explain("$name is channel 2 — swapping needs the server; tap its lit 2 to clear it first")
            else -> Action.SetPrimary(name)
        }
    }

    // [2] pill. A lit [2] can always be cleared — even on a mesh-only row
    // while offline (otherwise channel 2 is trapped there) — so that check
    // comes before the mesh-only explanation.
    private fun tapTwo(
        channel: Channel,
        onCh1: Boolean,
        onCh2: Boolean,
        ch2On: Boolean,
    ): Action {
        val name = channel.name
        return when {
            onCh2 -> Action.ClearSecondary
            channel.offline -> Action.Explain("Channel 2 needs a server channel — $name is mesh-only")
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
        online: Boolean = true,
    ): Pill =
        when {
            slot == 0 && same(channel.name, ch1) -> Pill.ON
            slot == 1 && !ch2.isNullOrBlank() && same(channel.name, ch2) -> Pill.ON
            channel.access == Access.NONE -> Pill.DISABLED
            channel.offline && (slot == 1 || online) -> Pill.DISABLED
            else -> Pill.OFF
        }

    /**
     * Member count shown on a channel row. An assigned channel (1 or 2)
     * uses the main panel's roster count, which also counts mesh-only
     * peers; other channels use the server's user count.
     */
    fun memberCount(
        serverCount: Int,
        assignedSlotCount: Int?,
    ): Int = assignedSlotCount ?: serverCount
}
