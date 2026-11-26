package com.atakmap.android.xv.calling

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Posts incoming + ongoing direct-call notifications.
 *
 * These are plain CATEGORY_CALL notifications with Answer / Decline /
 * Hang Up as regular actions. They deliberately do NOT use
 * [Notification.CallStyle] or a full-screen intent:
 *
 *   - `USE_FULL_SCREEN_INTENT` is restricted by Google Play to apps
 *     whose core function is calling or alarms. Direct calling is not
 *     a shipping feature yet, so the permission is not declared and
 *     no notification here sets a full-screen intent.
 *   - Android rejects a CallStyle notification (IllegalArgumentException
 *     from NotificationManager.notify) unless it is the foreground
 *     service's own notification or carries a full-screen intent.
 *     Without FSI, CallStyle would silently fail to post, so these
 *     notifications use the NotificationCompat builder instead.
 *
 * When direct calling ships, restore CallStyle + setFullScreenIntent
 * (with [XvCallActivity] as the target) together with the
 * `USE_FULL_SCREEN_INTENT` manifest entry and the Play declaration.
 *
 * Decision routing: Answer / Decline / Hang Up taps fire the
 * [ACTION_ANSWER] / [ACTION_DECLINE] / [ACTION_HANGUP_REQUESTED]
 * broadcasts that `XvVoiceService`'s `incomingDecisionReceiver` and
 * `activeCallReceiver` listen for.
 *
 * Threading: callers post from `XvVoiceService` on either the binder
 * thread or main looper. [NotificationManager.notify] is thread-safe so
 * no synchronisation is needed.
 */
class CallStyleNotifier(
    private val context: Context,
) {
    private val nm: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /** Show the incoming-call heads-up with Answer / Decline actions. */
    fun postIncoming(
        callerCallsign: String,
        tempChannelId: Int,
        callerSession: Int,
    ) {
        Log.i(
            TAG,
            "postIncoming caller='$callerCallsign' tempChannelId=$tempChannelId " +
                "callerSession=$callerSession",
        )
        val answer = answerPendingIntent(callerCallsign, tempChannelId, callerSession)
        val decline = declinePendingIntent(callerCallsign, tempChannelId, callerSession)
        val notification = buildIncoming(callerCallsign, answer, decline)
        try {
            nm.notify(NOTIFICATION_ID_INCOMING, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "postIncoming notify threw", t)
        }
    }

    /**
     * Post the ongoing-call notification with a Hang Up action.
     *
     * Does NOT launch XvCallActivity — that's a separate call to
     * [launchActiveCallActivity] gated on the Telecom connection being
     * registered (otherwise Android 14+ BAL denies the startActivity).
     */
    fun postActive(
        peerCallsign: String,
        isIncoming: Boolean,
    ) {
        Log.i(TAG, "postActive peer='$peerCallsign' isIncoming=$isIncoming")
        val hangup = hangupPendingIntent()
        val notification = buildActive(peerCallsign, hangup)
        try {
            // Dismiss any lingering incoming-ring surface first — the
            // operator just answered (or this is an outgoing place) so
            // the ring should be gone before the active surface shows.
            nm.cancel(NOTIFICATION_ID_INCOMING)
            nm.notify(NOTIFICATION_ID_ACTIVE, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "postActive notify threw", t)
        }
    }

    /**
     * Launch [XvCallActivity] to the foreground so the in-call controls
     * (mute / route / hang up) are visible the moment a call goes
     * active. This is a direct startActivity, not a full-screen intent.
     *
     * MUST be called after the Telecom Connection is registered (i.e.
     * the call is ACTIVE). On Android 14+, a foreground service can
     * launch activities only when there's an active self-managed phone
     * call OR a phoneCall foreground type. Both conditions need to be
     * in force; the caller is responsible for ensuring phoneCall fg
     * type is in effect and that a Connection has reached the registry.
     */
    fun launchActiveCallActivity(peerCallsign: String) {
        try {
            val launch =
                Intent(context, XvCallActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                    putExtra(XvCallActivity.EXTRA_MODE, XvCallActivity.MODE_ACTIVE)
                    putExtra(XvCallActivity.EXTRA_CALLSIGN, peerCallsign)
                }
            context.startActivity(launch)
            Log.i(TAG, "launchActiveCallActivity peer='$peerCallsign' OK")
        } catch (t: Throwable) {
            Log.w(TAG, "launchActiveCallActivity: startActivity threw", t)
        }
    }

    /** Cancel both incoming + active notifications. Idempotent. */
    fun dismissAll() {
        try {
            nm.cancel(NOTIFICATION_ID_INCOMING)
            nm.cancel(NOTIFICATION_ID_ACTIVE)
        } catch (t: Throwable) {
            Log.w(TAG, "dismissAll threw", t)
        }
    }

    // ---------- Notification builders ----------

    private fun buildIncoming(
        callerCallsign: String,
        answer: PendingIntent,
        decline: PendingIntent,
    ): Notification =
        NotificationCompat
            .Builder(context, NotificationChannels.INCOMING)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("XV — incoming call")
            .setContentText(callerCallsign)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                android.R.drawable.sym_action_call,
                "Answer",
                answer,
            ).addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Decline",
                decline,
            ).build()

    private fun buildActive(
        peerCallsign: String,
        hangup: PendingIntent,
    ): Notification =
        NotificationCompat
            .Builder(context, NotificationChannels.ACTIVE)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("XV — in call")
            .setContentText(peerCallsign)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setUsesChronometer(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Hang up",
                hangup,
            ).build()

    // ---------- PendingIntents ----------

    private fun answerPendingIntent(
        callerCallsign: String,
        tempChannelId: Int,
        callerSession: Int,
    ): PendingIntent {
        val intent =
            Intent(ACTION_ANSWER).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CALLER_CALLSIGN, callerCallsign)
                putExtra(EXTRA_TEMP_CHANNEL_ID, tempChannelId)
                putExtra(EXTRA_CALLER_SESSION, callerSession)
            }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_ANSWER + (tempChannelId and 0xFFFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun declinePendingIntent(
        callerCallsign: String,
        tempChannelId: Int,
        callerSession: Int,
    ): PendingIntent {
        val intent =
            Intent(ACTION_DECLINE).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CALLER_CALLSIGN, callerCallsign)
                putExtra(EXTRA_TEMP_CHANNEL_ID, tempChannelId)
                putExtra(EXTRA_CALLER_SESSION, callerSession)
            }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_DECLINE + (tempChannelId and 0xFFFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun hangupPendingIntent(): PendingIntent {
        val intent =
            Intent(ACTION_HANGUP_REQUESTED).apply {
                setPackage(context.packageName)
            }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_HANGUP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "CallStyleNotifier"

        /**
         * Action strings + extras kept byte-identical to the legacy
         * [com.atakmap.android.xv.calling.XvIncomingCallActivity] /
         * [com.atakmap.android.xv.calling.XvActiveCallActivity] companion
         * constants so the receivers inside [XvVoiceService] continue
         * to listen for the same broadcasts without modification.
         */
        const val ACTION_ANSWER = "com.atakmap.android.xv.calling.ANSWER"
        const val ACTION_DECLINE = "com.atakmap.android.xv.calling.DECLINE"
        const val ACTION_HANGUP_REQUESTED = "com.atakmap.android.xv.calling.HANGUP_REQUESTED"
        const val ACTION_SET_MUTED = "com.atakmap.android.xv.calling.SET_MUTED"
        const val ACTION_SET_SPEAKER = "com.atakmap.android.xv.calling.SET_SPEAKER"

        // Audio-route picker from the in-call activity. Carries
        // EXTRA_AUDIO_ROUTE matching CallAudioState.ROUTE_* values
        // (EARPIECE=1, BLUETOOTH=2, WIRED_HEADSET=4, SPEAKER=8).
        // XvVoiceService dispatches via Connection.setAudioRoute()
        // so the system call-audio state machine stays consistent
        // with what the activity displays.
        const val ACTION_SET_AUDIO_ROUTE = "com.atakmap.android.xv.calling.SET_AUDIO_ROUTE"
        const val EXTRA_AUDIO_ROUTE = "com.atakmap.android.xv.audioRoute"

        const val EXTRA_CALLER_CALLSIGN = "com.atakmap.android.xv.callerCallsign"
        const val EXTRA_TEMP_CHANNEL_ID = "com.atakmap.android.xv.tempChannelId"
        const val EXTRA_CALLER_SESSION = "com.atakmap.android.xv.callerSession"
        const val EXTRA_MUTED = "com.atakmap.android.xv.muted"
        const val EXTRA_SPEAKER_ON = "com.atakmap.android.xv.speakerOn"

        // Notification IDs. Re-using existing service IDs would collide
        // with the foreground status notification (which lives on the
        // SERVICE channel via XvVoiceService.NOTIFICATION_ID = 4711).
        // Pick distinct integers in the same neighbourhood for easy
        // identification in dumpsys notification.
        private const val NOTIFICATION_ID_INCOMING = 4712
        private const val NOTIFICATION_ID_ACTIVE = 4713

        // PendingIntent request codes. Offset by tempChannelId so
        // concurrent rings don't share the same request code (which
        // would lead to FLAG_UPDATE_CURRENT silently mutating an
        // earlier-posted ring's extras).
        private const val REQUEST_ANSWER = 0x10_0000
        private const val REQUEST_DECLINE = 0x20_0000
        private const val REQUEST_HANGUP = 0x30_0000
    }
}
