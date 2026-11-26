package com.atakmap.android.xv.emergency

/**
 * Which ATAK alert the speaker-mic emergency button sends, or OFF.
 *
 * Choices are ATAK's own emergency types (Alert Tool), stored by their
 * enum name so this stays free of ATAK SDK types. Default is the 911
 * alert. OFF disables the button entirely; the Settings UI makes the
 * operator confirm that twice.
 *
 * Custom is left out: it sends whatever custom text was last set in the
 * Alert Tool, which an operator can't see from XV.
 */
object EmergencyAlertSetting {
    const val OFF = "OFF"

    /** ATAK EmergencyType.NineOneOne. */
    const val DEFAULT = "NineOneOne"

    /** Selectable alerts, in picker order: enum name to label. */
    val CHOICES: List<Pair<String, String>> =
        listOf(
            DEFAULT to "911 Alert",
            "RingTheBell" to "Ring The Bell",
            "TroopsInContact" to "Troops In Contact",
            "GeoFenceBreach" to "Geo-fence Breached",
        )

    /** A stored value made safe: unknown / missing means the default. */
    fun normalize(stored: String?): String =
        when {
            stored == OFF -> OFF
            CHOICES.any { it.first == stored } -> stored!!
            else -> DEFAULT
        }

    /** The ATAK alert to fire, or null when the button is disabled. */
    fun alertToFire(stored: String?): String? = normalize(stored).takeIf { it != OFF }

    fun label(stored: String?): String {
        val n = normalize(stored)
        return if (n == OFF) "Off" else CHOICES.first { it.first == n }.second
    }
}
