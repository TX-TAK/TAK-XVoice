package com.atakmap.android.xv.audio

// Identifies which physical input drove a given PTT edge. Used by the
// OR-gate in [PttDispatcher] to support concurrent presses from multiple
// inputs (e.g. a motorcyclist holding the AINA-V2 button on their
// handlebar AND tapping the on-screen PTT card — second press must not
// start a fresh TX, and releasing one button must not end TX while the
// other is still held).
//
// `dropsTrailingClick` flags inputs whose physical button emits an
// audible click on release. The XvAudioCapture trailing-frame trim is
// gated on this flag so a release click that the operator actually
// wants to hear (the on-screen PTT tap "thud" — there is none) doesn't
// get suppressed. AINA hardware buttons click; the on-screen PTT does
// not. Pryme MFB clicks. Debug intents don't.
enum class PttSource(val dropsTrailingClick: Boolean) {
    ON_SCREEN(dropsTrailingClick = false),
    AINA_V1(dropsTrailingClick = true),
    AINA_V2(dropsTrailingClick = true),
    PRYME_BLE(dropsTrailingClick = true),

    // Inrico BT02 Zello speaker-mic (companion BLE button peripheral).
    // Its own source rather than PRYME_BLE so a Pryme puck in the
    // External Button slot and a BT02 in the primary slot are
    // distinguishable in logs and in forgetSource() cascades.
    INRICO_BT02(dropsTrailingClick = true),

    // Samsung ruggedized-device programmable Active Key (Tab Active5,
    // XCover6 Pro / 7, etc.). Emits `HARD_KEY_REPORT` broadcasts that
    // [com.atakmap.android.xv.ptt.SamsungHardKeyReceiver] converts into
    // PTT down/up edges. The
    // key is a bare rubber-dome side button on the tablet chassis with
    // no audible click at release — same as ON_SCREEN, so we do NOT
    // trim the trailing frame on release. Independent PTT source; runs
    // in parallel with AINA / External Button via the dispatcher's
    // OR-gate.
    SAMSUNG_ACTIVE_KEY(dropsTrailingClick = false),

    DEBUG(dropsTrailingClick = false),
    ;

    companion object {
        // Fallback for call sites that don't know (or don't care) which
        // input drove the edge. Used by the call-button/auto-engage
        // paths inside [VoicePlant] where the source has already been
        // gated by other state and isn't strictly a button.
        val DEFAULT: PttSource = ON_SCREEN
    }
}

// Human-readable log prefix per PTT source. Used by [VoicePlant]'s
// primary-AINA and external-button handler factories so a Pryme puck
// wired into the External Button slot doesn't get logged as
// "primary AINA button" (which it isn't). Pure function — no state,
// no side effects — so it can be unit-tested in isolation.
//
// The `when` is exhaustive over the enum today. If a future enum value
// is added without a matching branch here, the Kotlin compiler will
// flag it and the operator will pick a real label rather than
// shipping a misleading default.
fun logPrefixForPttSource(source: PttSource): String =
    when (source) {
        PttSource.ON_SCREEN -> "on-screen PTT"
        PttSource.AINA_V1 -> "primary AINA V1 button"
        PttSource.AINA_V2 -> "primary AINA V2 button"
        PttSource.PRYME_BLE -> "external button"
        PttSource.INRICO_BT02 -> "primary Inrico BT02 button"
        PttSource.SAMSUNG_ACTIVE_KEY -> "Samsung Active Key"
        PttSource.DEBUG -> "debug PTT"
    }

// Identity of one physical input currently holding PTT down.
// [owner] is the reader instance (a [PttInputOwner] minted per
// connect) or, for single-instance inputs like the on-screen card,
// the [PttSource] itself. [slot] separates PTT1 from PTT2 on the same
// device. PttDispatcher's OR-gate keys its held set on this triple.
data class PttHoldKey(
    val owner: Any,
    val source: PttSource,
    val slot: Int,
)

// Opaque per-reader-instance token. Identity equality on purpose: two
// connects to the same MAC get two distinct owners, so a stale reader's
// late disconnect edge can only forget its own held keys.
class PttInputOwner(
    private val label: String,
) {
    // Set by PttDispatcher.forgetOwner(). A retired owner's reader has
    // been torn down; any down edge it still delivers (a read thread
    // that was mid-dispatch when the slot was swapped) is dropped so it
    // can never strand a held key that nothing will release.
    @Volatile
    var retired: Boolean = false
        internal set

    override fun toString(): String = "$label#${Integer.toHexString(System.identityHashCode(this))}"
}
