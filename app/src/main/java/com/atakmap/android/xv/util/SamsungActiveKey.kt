package com.atakmap.android.xv.util

import android.content.Context
import android.os.Build

/**
 * Capability detection for the programmable Active Key found on Samsung
 * ruggedized tablets and phones (Galaxy Tab Active5, Tab Active4/3,
 * Galaxy XCover6 Pro / XCover7, etc.).
 *
 * When present, pressing / releasing the key causes the Samsung
 * framework to broadcast the system intent
 * `com.samsung.android.knox.intent.action.HARD_KEY_REPORT` with the
 * following extras:
 *   - `com.samsung.android.knox.intent.extra.KEY_CODE`   — int
 *       * 1015 = XCover / Active / PTT key (side)
 *       * 1079 = Top / Emergency key
 *   - `com.samsung.android.knox.intent.extra.KEY_REPORT_TYPE` — int
 *       * 1 = pressed
 *       * 2 = released
 *
 * Despite the `knox` prefix in the action string, the broadcast is
 * emitted by the Samsung framework itself on qualifying hardware and
 * does NOT require the Knox SDK, a Knox license, or any Samsung
 * enterprise entitlement to receive. We therefore do NOT depend on
 * the Knox SDK jar.
 *
 * It is NOT a system-wide broadcast, though. The framework addresses
 * it to exactly one package: the app the operator picked under
 * Settings → Advanced features → Active button → "Use Active button
 * with app". An app is only offered there if its manifest carries
 * [META_HARD_KEY_PRESS] and a receiver for [ACTION_HARD_KEY_REPORT]
 * ([com.atakmap.android.xv.ptt.SamsungHardKeyReceiver]). Verified
 * 2026-11-25 on a Tab Active5 (SM-X308U, Android 16): with both
 * declared and XV selected, reports arrive in the foreground, in the
 * background, and with the screen off and locked.
 *
 * Sources (2026-07):
 *   - Samsung Knox docs, "Unmanaged key mappings":
 *     https://docs.samsungknox.com/dev/knox-sdk/features/independent-software-vendors-da/hardware-key-mappings/unmanaged-key-mappings/
 *   - Samsung Knox docs, "Hardware key mapping":
 *     https://docs.samsungknox.com/dev/knox-sdk/features/independent-software-vendors-da/hardware-key-mappings/hardware-key-mapping/
 *
 * ---
 *
 * Curated-hardware policy (per CLAUDE.md README rules): this helper
 * gates a Settings row that only appears on devices that actually have
 * the key. Non-Samsung devices are a zero-cost path — the row is
 * hidden with `View.GONE` in the drawer code so operators never see a
 * "greyed-out feature they can't use". The detection is intentionally
 * conservative — start with the Samsung ruggedized model prefixes we
 * have integrated evidence for, and grow the list as more hardware is
 * validated end-to-end.
 */
object SamsungActiveKey {
    /**
     * Broadcast action fired by the Samsung framework when the Active
     * Key / XCover key is pressed or released. Stable and documented
     * in the Samsung Knox hardware-key-mapping guide referenced above.
     */
    const val ACTION_HARD_KEY_REPORT: String =
        "com.samsung.android.knox.intent.action.HARD_KEY_REPORT"

    /**
     * Int extra — which key fired. `KEYCODE_PTT` = 1015 is the side
     * Active Key on Tab Active5 / XCover6 Pro / etc.;
     * `KEYCODE_EMERGENCY` = 1079 is the top key on XCovers. We
     * currently listen for the PTT keycode only; the emergency
     * button is out of scope for this reader.
     */
    const val EXTRA_KEY_CODE: String =
        "com.samsung.android.knox.intent.extra.KEY_CODE"

    /**
     * Int extra — 1 = press, 2 = release. Anything else is an
     * unexpected value (log at debug and drop).
     */
    const val EXTRA_KEY_REPORT_TYPE: String =
        "com.samsung.android.knox.intent.extra.KEY_REPORT_TYPE"

    /** Samsung `KEYCODE_PTT` (side Active Key on Tab Active5 etc.). */
    const val KEY_CODE_PTT: Int = 1015

    /** Samsung `KEY_REPORT_TYPE` "pressed". */
    const val KEY_REPORT_TYPE_PRESSED: Int = 1

    /** Samsung `KEY_REPORT_TYPE` "released". */
    const val KEY_REPORT_TYPE_RELEASED: Int = 2

    // Model-prefix allow-list for hardware that carries the
    // programmable Active Key. Populated conservatively — extend as
    // additional ruggedized Samsung models are validated end-to-end
    // per CLAUDE.md's curated-hardware policy.
    //
    // Tab Active5 (SM-X30x): X300 = Wi-Fi, X306 = 5G B/N variants,
    // X308 = 5G U variant.
    // Tab Active4 Pro: SM-T63x.
    // Tab Active3: SM-T57x.
    // XCover7: SM-G556.
    // XCover6 Pro: SM-G736.
    private val ACTIVE_KEY_MODEL_PREFIXES: List<String> =
        listOf(
            // Tab Active5 series
            "SM-X300",
            "SM-X306",
            "SM-X308",
            // Tab Active4 Pro
            "SM-T630",
            "SM-T636",
            // Tab Active3
            "SM-T570",
            "SM-T575",
            "SM-T577",
            // XCover7
            "SM-G556",
            // XCover6 Pro
            "SM-G736",
        )

    /**
     * True when the current device is a Samsung ruggedized model that
     * carries the programmable Active Key. Consulted at Settings-row
     * inflation time to decide whether to show the "Samsung Active Key
     * PTT" row at all. On any non-Samsung device this
     * is a fast constant `false`.
     *
     * [context] is accepted for symmetry with the Android-runtime
     * shape (a future extension may consult
     * [android.content.pm.PackageManager.hasSystemFeature] if Samsung
     * ever exposes a feature flag), but the current check reads only
     * [android.os.Build.MANUFACTURER] + [android.os.Build.MODEL] and
     * is safe to call from any thread.
     */
    fun isSupported(
        @Suppress("UNUSED_PARAMETER") context: Context,
    ): Boolean =
        isSupportedInternal(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            features = emptySet(),
        )

    /**
     * Pure test seam for [isSupported]. Takes the interesting Build
     * fields + system-feature set as parameters so it can be exercised
     * without an Android runtime. Robolectric is NOT required.
     */
    fun isSupportedInternal(
        manufacturer: String,
        model: String,
        features: Set<String>,
    ): Boolean {
        if (!manufacturer.equals("samsung", ignoreCase = true)) {
            return false
        }
        // Model prefix match. Model strings from Samsung firmware are
        // upper-cased (e.g. "SM-X306B") but be defensive — trim + case-
        // insensitive compare so a lowercase Build.MODEL from some
        // future firmware doesn't mis-detect.
        val normalizedModel = model.trim()
        if (normalizedModel.isEmpty()) return false
        val prefixMatch =
            ACTIVE_KEY_MODEL_PREFIXES.any { prefix ->
                normalizedModel.startsWith(prefix, ignoreCase = true)
            }
        if (prefixMatch) return true
        // Feature-flag path. Samsung does not currently publish an
        // official system feature for the Active Key, but if a future
        // firmware exposes one via PackageManager.hasSystemFeature, we
        // honour it as an additional positive signal so operator
        // hardware we haven't hard-listed still lights up the toggle.
        // The exact string is speculative — we accept two commonly-
        // referenced candidates so a real Samsung feature flag lands
        // in either form without an XV update.
        return features.any { feat ->
            feat.equals("com.samsung.hardware.active_key", ignoreCase = true) ||
                feat.equals("com.samsung.feature.active_key", ignoreCase = true)
        }
    }

    /**
     * Classification of a single Samsung `HARD_KEY_REPORT` broadcast,
     * returned by [classifyHardKeyReport]. Kept pure so the decision is
     * unit-testable without the Android runtime; the receiver maps it
     * to [com.atakmap.android.xv.audio.PttSource.SAMSUNG_ACTIVE_KEY]
     * edges.
     */
    enum class FallbackAction {
        /** Fire a PTT down edge tagged [com.atakmap.android.xv.audio.PttSource.SAMSUNG_ACTIVE_KEY]. */
        PTT_DOWN,

        /** Fire a PTT up edge tagged [com.atakmap.android.xv.audio.PttSource.SAMSUNG_ACTIVE_KEY]. */
        PTT_UP,

        /** Not an Active Key event we care about — pass through to the next handler. */
        IGNORE,
    }

    /**
     * Manifest `<meta-data>` name that opts the APK in to Samsung's
     * unmanaged key mapping. With it set to `true`, the app is offered
     * under Settings → Advanced features → XCover / Active key →
     * "Control … key with app"; once the operator picks it, the
     * framework reports press / release via [ACTION_HARD_KEY_REPORT].
     * Kept here only as the single documented reference for the string
     * — the manifest cannot read a Kotlin constant.
     */
    const val META_HARD_KEY_PRESS: String =
        "com.samsung.android.knox.intent.action.HARD_KEY_PRESS"

    /**
     * Action that opens Samsung's Active button settings screen, where
     * the operator assigns the key to an app. Observed on Tab Active5
     * (SM-X308U, Android 16); not a documented public API, so callers
     * must tolerate it being absent.
     */
    const val ACTION_ACTIVE_KEY_SETTINGS: String = "com.samsung.ACTIVE_KEY_SETTINGS"

    // Samsung `Settings.System` keys behind "Use Active button with
    // app". Observed on SM-X308U (Android 16); undocumented, so every
    // read is best-effort and a failure reads as "not assigned".
    private const val SETTING_DEDICATED_APP = "dedicated_app_xcover"
    private const val SETTING_DEDICATED_APP_SWITCH = "dedicated_app_xcover_switch"

    /**
     * True when Samsung's "Use Active button with app" setting is on
     * and names [packageName]. Drives the read-only settings row; it is
     * NOT a gate on PTT — the framework simply stops sending reports
     * when the operator unassigns the key.
     */
    fun isAssignedTo(
        context: Context,
        packageName: String,
    ): Boolean =
        try {
            val cr = context.contentResolver
            isAssignedInternal(
                switchValue = android.provider.Settings.System.getInt(cr, SETTING_DEDICATED_APP_SWITCH, 0),
                dedicatedApp = android.provider.Settings.System.getString(cr, SETTING_DEDICATED_APP),
                packageName = packageName,
            )
        } catch (_: Throwable) {
            false
        }

    /** Pure test seam for [isAssignedTo]. */
    fun isAssignedInternal(
        switchValue: Int,
        dedicatedApp: String?,
        packageName: String,
    ): Boolean = switchValue == 1 && !dedicatedApp.isNullOrBlank() && dedicatedApp.trim() == packageName

    /**
     * Pure classification of one `HARD_KEY_REPORT` broadcast, for the
     * manifest-declared receiver
     * ([com.atakmap.android.xv.ptt.SamsungHardKeyReceiver]). Returns
     * [FallbackAction.PTT_DOWN] / [FallbackAction.PTT_UP] for the side
     * key (1015) with report type 1 / 2 and [FallbackAction.IGNORE] for
     * anything else — wrong action, the Top key (1079), a missing extra,
     * or an unknown report type.
     */
    fun classifyHardKeyReport(
        action: String?,
        keyCode: Int,
        reportType: Int,
    ): FallbackAction {
        if (action != ACTION_HARD_KEY_REPORT) return FallbackAction.IGNORE
        if (keyCode != KEY_CODE_PTT) return FallbackAction.IGNORE
        return when (reportType) {
            KEY_REPORT_TYPE_PRESSED -> FallbackAction.PTT_DOWN
            KEY_REPORT_TYPE_RELEASED -> FallbackAction.PTT_UP
            else -> FallbackAction.IGNORE
        }
    }
}
