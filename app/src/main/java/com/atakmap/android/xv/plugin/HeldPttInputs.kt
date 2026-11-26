package com.atakmap.android.xv.plugin

/**
 * Plugin-side mirror of the service's raw PTT inputs, for the channel
 * swap gate ("is anything keyed or priming?").
 *
 * Keyed per input + slot (the service sends "<SOURCE>@<reader>" keys,
 * matching the dispatcher's per-reader held state), so one reader's up
 * edge can't clear another's down. A stale entry must never block Swap
 * for the rest of the session, so entries also clear on:
 *  - a release for the key (any slot), or for the whole source;
 *  - the slot's burst ending;
 *  - the service link resetting;
 *  - age-out past the longest PTT timeout.
 */
class HeldPttInputs(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private data class Key(
        val input: String,
        val slot: Int,
    )

    private val held = mutableMapOf<Key, Long>()

    /** [slot] < 0 on an up edge releases every slot for [input]. */
    @Synchronized
    fun onInput(
        down: Boolean,
        slot: Int,
        input: String,
    ) {
        if (down) {
            held[Key(input, slot)] = now()
            return
        }
        held.keys.removeAll { k -> matches(k.input, input) && (slot < 0 || k.slot == slot) }
    }

    /** The service reports the burst on [slot] ended. */
    @Synchronized
    fun onBurstEnded(slot: Int) {
        held.keys.removeAll { it.slot == slot }
    }

    @Synchronized
    fun clear() = held.clear()

    @Synchronized
    fun anyHeld(): Boolean {
        val cutoff = now() - maxAgeMs
        held.values.removeAll { it < cutoff }
        return held.isNotEmpty()
    }

    // A bare source name ("AINA_V2") releases every reader of that source;
    // "*@<id>" releases every key of one reader.
    private fun matches(
        heldInput: String,
        released: String,
    ): Boolean =
        when {
            heldInput == released -> true
            released.startsWith("*@") -> heldInput.endsWith(released.substring(1))
            !released.contains('@') -> heldInput.substringBefore('@') == released
            else -> false
        }

    companion object {
        // Longest PTT timeout (90 s) plus margin: past this the service
        // has force-released the key anyway.
        const val DEFAULT_MAX_AGE_MS = 100_000L
    }
}
