package com.atakmap.android.xv.transport

/**
 * What a freshly connected slot does about its configured channel right
 * after ServerSync. Murmur lands every new session in Root (id 0); the
 * slot then self-joins its saved channel. Pure, so the decision is
 * unit-testable without a session.
 */
object AutoJoinPolicy {
    sealed class Action {
        /** Nothing configured: stay where the server put us. */
        data object Stay : Action()

        /** Self-join [id]; stamp it as a self-move so it isn't an "admin move". */
        data class Join(
            val id: Int,
        ) : Action()

        /**
         * The saved channel-2 name isn't on the server any more. Tear the
         * secondary down and forget the saved value rather than leave VS2
         * parked in Root (where it can collide with channel 1 in Lobby).
         */
        data class SecondaryMissing(
            val name: String,
        ) : Action()

        /** Saved channel-1 name is gone: channel 1 stays in Lobby (never blank). */
        data class PrimaryMissing(
            val name: String,
        ) : Action()
    }

    /**
     * @param target the slot's configured channel name (may be blank).
     * @param lookupId resolves a server-side channel name to its id.
     */
    fun decide(
        target: String,
        isSecondary: Boolean,
        lookupId: (String) -> Int?,
    ): Action {
        val t = target.trim()
        val id = if (isLobby(t)) 0 else lookupId(t)
        return when {
            t.isEmpty() -> Action.Stay
            id != null -> Action.Join(id)
            isSecondary -> Action.SecondaryMissing(t)
            else -> Action.PrimaryMissing(t)
        }
    }

    /**
     * True when the channel a slot landed in at ServerSync is transient:
     * an auto-join to a different saved channel is about to follow, so
     * persisting the landing would clobber the operator's saved channel
     * (usually with "Lobby", or with a server-side default placement).
     */
    fun landingIsTransient(
        target: String,
        landingName: String?,
    ): Boolean {
        val t = target.trim()
        return t.isNotEmpty() && !sameChannel(t, landingName)
    }

    /** Case-insensitive name match that treats "Root" and "Lobby" as one. */
    fun sameChannel(
        a: String?,
        b: String?,
    ): Boolean {
        if (a == null || b == null) return false
        return (isLobby(a) && isLobby(b)) || a.trim().equals(b.trim(), ignoreCase = true)
    }

    /** "Lobby" (XV's label) and "Root" (Mumble's name) are channel id 0. */
    fun isLobby(name: String): Boolean {
        val n = name.trim()
        return n.equals(MumbleTransport.LOBBY_DISPLAY_NAME, ignoreCase = true) || n.equals("Root", ignoreCase = true)
    }

    /** Display name for a channel id, with Root always shown as "Lobby". */
    fun displayName(
        id: Int,
        rawName: String?,
    ): String? = if (id == 0) MumbleTransport.LOBBY_DISPLAY_NAME else rawName
}
