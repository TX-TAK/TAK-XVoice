package com.atakmap.android.xv.ui

/**
 * Pure rules for what a main-panel channel row (and the matching map HUD
 * hex) claims about the link. A remembered channel name is not a live
 * link: the row must never look keyable while voice can't leave.
 */
object SlotRowPolicy {
    /** Channel 1 is live with the server up, or a mesh leg carrying voice. */
    fun channel1Live(
        channel: String?,
        serverConnected: Boolean,
        meshCarrying: Boolean,
    ): Boolean = channel != null && (serverConnected || meshCarrying)

    enum class Idle(
        val text: String,
    ) {
        MESH("mesh"),
        NOT_CONNECTED("not connected"),
        JOINING("joining…"),
        LISTEN_ONLY("listen only"),
        QUIET("quiet"),
    }

    /** What the row's activity line says when nobody is talking. */
    fun idle(
        slot: Int,
        live: Boolean,
        serverConnected: Boolean,
        listenOnly: Boolean,
    ): Idle =
        when {
            !serverConnected && live && slot == 0 -> Idle.MESH
            !serverConnected -> Idle.NOT_CONNECTED
            !live -> Idle.JOINING
            listenOnly -> Idle.LISTEN_ONLY
            else -> Idle.QUIET
        }

    enum class MeshTone { CLEAR, KEY_NEEDED, OK }

    /**
     * Mesh status colour: red only when we'd send in the clear; a caution
     * when peers are encrypted and we hold no key; otherwise system blue.
     */
    fun meshTone(
        cleartext: Boolean,
        keyNeeded: Boolean,
    ): MeshTone =
        when {
            cleartext -> MeshTone.CLEAR
            keyNeeded -> MeshTone.KEY_NEEDED
            else -> MeshTone.OK
        }

    /** A mesh card shows ENCRYPTED or CLEAR, never both. */
    fun showEncryptedChip(hasClearLeg: Boolean): Boolean = !hasClearLeg
}
