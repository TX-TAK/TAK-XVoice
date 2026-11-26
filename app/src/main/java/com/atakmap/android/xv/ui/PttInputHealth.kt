package com.atakmap.android.xv.ui

/**
 * Per-card Bluetooth PTT input problems for Settings › Devices.
 *
 * The service reports two kinds (IXvVoiceListener, Bluetooth reader
 * health): the device rejects the bond and must be forgotten and
 * re-paired, or the device is reachable but isn't the expected device
 * (wrong protocol / no PTT buttons / missing permission). Reports carry
 * only a reason string; its wording names the input ("speaker-mic" or
 * "external button"), which places the chip on the right card. A reason
 * that names neither stays a toast only: a chip on the wrong card would
 * send the operator to re-pair the wrong device.
 */
class PttInputHealth {
    enum class Card { SPEAKER_MIC, BUTTON }

    enum class Kind(
        val chip: String,
    ) {
        NEEDS_REPAIR("NEEDS RE-PAIR"),
        NOT_RECOGNISED("NOT RECOGNISED"),
    }

    data class Issue(
        val kind: Kind,
        val reason: String,
    )

    private val issues = mutableMapOf<Card, Issue>()

    /** Record a report; returns the card it landed on, or null. */
    @Synchronized
    fun report(
        kind: Kind,
        reason: String,
    ): Card? {
        val card = cardFor(reason) ?: return null
        val current = issues[card]
        // A bond rejection outranks "not recognised": re-pairing is the
        // first fix either way.
        if (current == null || kind == Kind.NEEDS_REPAIR || current.kind == kind) {
            issues[card] = Issue(kind, reason)
        }
        return card
    }

    /** The input on [card] connected cleanly: its problem is gone. */
    @Synchronized
    fun clear(card: Card) {
        issues.remove(card)
    }

    @Synchronized
    fun issue(card: Card): Issue? = issues[card]

    companion object {
        fun cardFor(reason: String): Card? {
            val r = reason.lowercase()
            return when {
                "external button" in r || "ptt button" in r -> Card.BUTTON
                "speaker-mic" in r || "speaker mic" in r || "speakermic" in r -> Card.SPEAKER_MIC
                else -> null
            }
        }
    }
}
