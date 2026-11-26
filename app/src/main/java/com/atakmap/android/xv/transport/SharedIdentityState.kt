package com.atakmap.android.xv.transport

import com.atakmap.android.xv.transport.mumble.FatalMumbleException
import com.atakmap.android.xv.transport.mumble.RejectSummary
import com.atakmap.android.xv.transport.mumble.SelfKickedException
import mumble.MumbleProto

/**
 * Both voice sessions (channel 1 and channel 2) log in to the SAME server
 * with the SAME device certificate. A credential rejection on either one
 * (bad password / no or revoked certificate / authenticator failure, or
 * the server kicking us because our access was revoked) is a verdict on
 * that shared identity, so it applies to both:
 *  - neither session retries against the rejected credential;
 *  - both channels show the same reason;
 *  - a successful login (the operator reconnecting once access is
 *    restored) clears it for both.
 *
 * A session the server is still serving is never torn down on the other's
 * verdict: it carries on until the server drops it, and only then stays
 * down. Per-session failures (a username/suffix clash, an ordinary admin
 * kick of one session, network loss) are NOT identity-wide.
 */
class SharedIdentityState {
    @Volatile
    var rejectedReason: String? = null
        private set

    val rejected: Boolean get() = rejectedReason != null

    /** Record a session failure; true when it rejected the shared identity. */
    @Synchronized
    fun onFailure(error: Throwable): Boolean {
        val reason = credentialReason(error) ?: return false
        rejectedReason = reason
        return true
    }

    /** A session authenticated: the credential is good again. */
    @Synchronized
    fun onAuthenticated() {
        rejectedReason = null
    }

    /** The operator asked to connect: try the credential again. */
    @Synchronized
    fun onOperatorConnect() {
        rejectedReason = null
    }

    /** Whether a dropped / failed session may be retried. */
    fun mayRetry(): Boolean = !rejected

    companion object {
        private val CREDENTIAL_REJECTS =
            setOf(
                MumbleProto.Reject.RejectType.WrongUserPW,
                MumbleProto.Reject.RejectType.WrongServerPW,
                MumbleProto.Reject.RejectType.NoCertificate,
                MumbleProto.Reject.RejectType.AuthenticatorFail,
            )

        // Kick reasons that mean the identity itself lost access (vs. an
        // admin removing one session). Matched loosely; the text is never
        // shown to the operator.
        private val REVOKED_WORDING = listOf("revok", "certificate", "access")

        /** Operator-facing reason when [error] rejects the shared identity, else null. */
        fun credentialReason(error: Throwable): String? =
            when (error) {
                is FatalMumbleException ->
                    if (error.rejectType in CREDENTIAL_REJECTS) RejectSummary.of(error.rejectType) else null
                is SelfKickedException ->
                    if (error.banned || revokedWording(error.reason)) RejectSummary.REVOKED else null
                else -> null
            }

        private fun revokedWording(reason: String?): Boolean {
            val r = reason?.lowercase() ?: return false
            return REVOKED_WORDING.any { it in r }
        }
    }
}
