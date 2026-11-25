package com.atakmap.android.xv.ptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.atakmap.android.xv.service.XvVoiceService
import com.atakmap.android.xv.util.SamsungActiveKey

/**
 * Manifest-declared receiver for Samsung's unmanaged hardware-key
 * mapping ("Control XCover / Active key with app").
 *
 * Samsung's documented contract for this path has two manifest halves,
 * both of which this APK now declares:
 *
 *  1. `<meta-data>` [SamsungActiveKey.META_HARD_KEY_PRESS] = `true`,
 *     which makes the app selectable under Settings → Advanced features
 *     → XCover / Active key.
 *  2. An exported `<receiver>` filtering on
 *     [SamsungActiveKey.ACTION_HARD_KEY_REPORT] — this class.
 *
 * Once the operator assigns the key to XV, the framework reports each
 * press (report type 1) and release (type 2) of the side key (1015).
 *
 * Why this exists alongside [SamsungActiveKeyReader]: that reader
 * registers at runtime only, and without the meta-data the APK was
 * never eligible to be chosen in Settings — which is why an earlier
 * validation concluded the firmware "does not emit" the broadcast.
 * This receiver is the documented shape. Verified 2026-11-25 on a
 * Tab Active5 (SM-X308U, Android 16): reports arrive and key PTT in
 * the foreground, with ATAK backgrounded, and with the screen off
 * and locked. It replaced the accessibility service XV used to ship
 * for background PTT.
 *
 * Delivery: this receiver runs in XV's own APK process — the same
 * process as [XvVoiceService] — so the edge is an in-process call into
 * the live dispatcher, tagged
 * [com.atakmap.android.xv.audio.PttSource.SAMSUNG_ACTIVE_KEY]. If the
 * runtime reader or the foreground KeyEvent path also fires for the
 * same press, the dispatcher's source-keyed OR-gate collapses the
 * duplicate.
 *
 * Exposure: the receiver must be exported (the sender is the Samsung
 * framework, and Samsung documents no sender permission we could
 * require), so any app on the device can send this action. The blast
 * radius is bounded three ways: the edge is dropped on hardware that
 * fails [SamsungActiveKey.isSupported]; it is dropped when no voice
 * service is running; and a delivered edge can do nothing the physical
 * key cannot — it keys PTT on the operator's current channel, subject
 * to every existing TX gate (cellular call, timeouts).
 */
class SamsungHardKeyReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val keyCode = intent.getIntExtra(SamsungActiveKey.EXTRA_KEY_CODE, -1)
        val reportType = intent.getIntExtra(SamsungActiveKey.EXTRA_KEY_REPORT_TYPE, -1)
        // Logged for every report (not just PTT edges) on purpose: this
        // line is the on-device evidence for whether the framework
        // delivers at all, foreground / background / screen-off.
        Log.i(TAG, "HARD_KEY_REPORT received keyCode=$keyCode reportType=$reportType")
        val isDown =
            when (SamsungActiveKey.classifyHardKeyReport(intent.action, keyCode, reportType)) {
                SamsungActiveKey.FallbackAction.PTT_DOWN -> true
                SamsungActiveKey.FallbackAction.PTT_UP -> false
                SamsungActiveKey.FallbackAction.IGNORE -> return
            }
        if (!isSupported(context)) {
            Log.w(TAG, "HARD_KEY_REPORT on unsupported hardware — dropping")
            return
        }
        try {
            deliver(isDown)
        } catch (t: Throwable) {
            Log.w(TAG, "deliver(isDown=$isDown) threw", t)
        }
    }

    companion object {
        private const val TAG = "XvSamsungHardKeyRx"

        // Test seams. Production values are the real device gate and the
        // in-process voice-service delivery; unit tests swap them so the
        // receiver can be exercised without Samsung Build fields or a
        // running service.
        @Volatile
        internal var isSupported: (Context) -> Boolean = SamsungActiveKey::isSupported

        @Volatile
        internal var deliver: (Boolean) -> Unit = XvVoiceService::deliverSamsungActiveKeyEdge
    }
}
