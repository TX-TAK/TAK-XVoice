// Service → plugin callbacks. The plugin holds one of these in
// XvMapComponent and routes events into its UI / Mumble transport.
package com.atakmap.android.xv.service;

interface IXvVoiceListener {
    // Service has captured + encoded a TX Opus frame. Plugin sends
    // it on the wire via MumbleTransport. [slot] tells the wire
    // layer how to route (VoiceTarget for VS2 etc).
    void onTxOpus(int slot, in byte[] opus);

    // End-of-utterance — plugin sends Mumble's terminator frame for
    // the slot.
    void onTxTerminator(int slot);

    // PTT-state edges, for the Mumble UserState selfMute heartbeat.
    // [slot] identifies which channel is going hot — 0 = primary (VS1),
    // 1 = secondary (VS2). On the transmitting=false edge slot echoes
    // which slot just went idle. Needed on the plugin side so the
    // on-screen "● TRANSMITTING" indicator lights the correct button
    // when TX was initiated from a BT speakermic / BLE PTT button
    // (which doesn't route through the plugin's Controller.startTx).
    void onPttStateChanged(boolean transmitting, int slot);

    // AINA reader connection up/down — plugin updates the UI dot.
    void onAinaConnectionChanged(boolean connected);

    // External button reader connection up/down — plugin updates UI state.
    void onExternalButtonConnectionChanged(boolean connected);


    // RX activity heartbeat — plugin's TX side uses this for mic
    // pre-warm hints (legacy hook). May be a no-op going forward.
    void onRxActivity();

    // Audio state text for diagnostics in the UI ("IDLE", "TX",
    // "PRIMING", etc.).
    void onAudioStateText(String text);

    // Emergency button (PTTE) edge from the AINA. Plugin routes to
    // EmergencyController which talks to ATAK's EmergencyManager
    // (which lives in ATAK's UID, so the dispatch has to happen on
    // the plugin side). Down/up edges so the controller can implement
    // the press-vs-long-hold UX (short fire / long cancel).
    void onEmergencyButton(boolean down);

    // VX direct-call decisions made by the operator on the system
    // Telecom incoming-call UI. Both echo back the (tempChannelId,
    // callerSession) the plugin originally passed to notifyIncomingCall
    // so the plugin can correlate against any in-flight calls.
    //
    // onIncomingCallAnswered: leave the current Mumble channel + join
    // tempChannelId. The implicit-accept (UserState arriving on the
    // temp channel) is the wire signal VX clients observe.
    //
    // onIncomingCallRejected: send a `[TAK MxVx : REJECT_CALL ]<id>`
    // TextMessage targeted at callerSession.
    void onIncomingCallAnswered(int tempChannelId, int callerSession);
    void onIncomingCallRejected(int tempChannelId, int callerSession);

    // Telecom signaled the active call ended — operator hung up via
    // the in-call activity, peer hung up, or the system preempted us.
    // Plugin clears MumbleTransport's private-call state (clears the
    // VoiceTarget, respins VS2, etc.) and — if we were the caller —
    // sends a CANCEL_CALL TextMessage to the peer so they detect
    // our hangup.
    void onPrivateCallEnded();

    // Preferred audio route changed (BT speakermic plug/unplug, wired
    // headset plug/unplug, operator route preference change). The
    // plugin's main view refreshes its audio-route indicator on this
    // edge — without it the indicator would lag the 2s refresh poll.
    // [label] is a short human string ("AINA APTT", "Speaker",
    // "Earpiece", "Wired headset", "Auto").
    void onAudioRouteChanged(String label);

    // AudioCapture (mic) failed to start during a TX burst — almost
    // always RECORD_AUDIO revoked from Settings while the app is
    // running. The plugin surfaces a Toast so the operator sees the
    // failure cause instead of silent dead-air. [reason] is the
    // operator-actionable string from AudioCapture (e.g. "RECORD_AUDIO
    // permission revoked — re-grant in system Settings").
    void onCaptureError(String reason);

    // PTT was suppressed because the cellular telephony stack reports
    // an active or ringing call. XV's self-managed Telecom call would
    // otherwise auto-hold the cellular call, which is almost never what
    // an operator fidgeting with a BT speakermic actually wants. Plugin
    // surfaces [reason] as a Toast so the operator knows why nothing
    // transmitted. The service side already throttles repeated fires
    // during rapid button mashing.
    void onPttBlockedByCellularCall(String reason);

    // A Bluetooth PTT input (speaker-mic or external button) is being
    // rejected by the device for security reasons — a stale or
    // one-sided bond. Retrying cannot fix it; the operator has to
    // forget and re-pair the device. Fired at most once per reader
    // connect. Plugin surfaces [reason] as a Toast.
    oneway void onPttInputNeedsRepair(String reason);

    // A Bluetooth PTT input has a problem the operator must fix that is
    // not a bond issue: the device does not expose the expected buttons
    // (wrong device / protocol), or a needed permission is missing.
    // Fired at most once per condition, throttled per slot.
    oneway void onPttInputProblem(String reason);

    // Raw PTT input edge from any source the service owns (speaker-mic,
    // BLE button, Samsung key, on-screen), before mic priming and the
    // talk-permit tone. onPttStateChanged only fires once audio is on the
    // air; the plugin uses this edge so a channel swap can't start into a
    // press that is still priming. [input] identifies the reader:
    // "<SOURCE>@<reader id>", a bare "<SOURCE>" on an up edge releases every
    // reader of that source, and "*@<reader id>" every key of that reader;
    // slot -1 on an up edge = all slots. Appended after #146's methods.
    oneway void onPttInput(boolean down, int slot, String input);
}
