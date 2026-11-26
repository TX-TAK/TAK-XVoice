package com.atakmap.android.xv.transport

import com.atakmap.android.xv.transport.mumble.FatalMumbleException
import com.atakmap.android.xv.transport.mumble.SelfKickedException
import com.atakmap.android.xv.transport.mumble.UsernameInUseException
import mumble.MumbleProto.Reject.RejectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedIdentityStateTest {
    private fun revokedKick() = SelfKickedException("Voice access changed (certificate revoked).", banned = false, byActorSession = 0)

    @Test
    fun `a revoked-certificate kick on either session rejects the shared identity`() {
        val s = SharedIdentityState()
        assertTrue(s.onFailure(revokedKick()))
        assertEquals("access revoked", s.rejectedReason)
        assertFalse(s.mayRetry())
    }

    @Test
    fun `a credential reject on channel 2 also stops channel 1 retries`() {
        val s = SharedIdentityState()
        // Channel 2's session was refused for its certificate...
        assertTrue(s.onFailure(FatalMumbleException(RejectType.NoCertificate, null)))
        // ...so when channel 1's session later drops it must not retry
        // against the same credential.
        assertFalse(s.mayRetry())
        assertEquals("no certificate", s.rejectedReason)
    }

    @Test
    fun `per-session failures are not identity-wide`() {
        val s = SharedIdentityState()
        assertFalse(s.onFailure(UsernameInUseException("ghost")))
        assertFalse(s.onFailure(FatalMumbleException(RejectType.InvalidUsername, null)))
        assertFalse(s.onFailure(SelfKickedException("moved to AFK", banned = false, byActorSession = 3)))
        assertFalse(s.onFailure(IllegalStateException("network")))
        assertNull(s.rejectedReason)
        assertTrue(s.mayRetry())
    }

    @Test
    fun `a ban is identity-wide whatever the wording`() {
        val s = SharedIdentityState()
        assertTrue(s.onFailure(SelfKickedException(null, banned = true, byActorSession = 1)))
    }

    @Test
    fun `wrong password rejects with that reason`() {
        val s = SharedIdentityState()
        assertTrue(s.onFailure(FatalMumbleException(RejectType.WrongUserPW, "bad")))
        assertEquals("wrong password", s.rejectedReason)
    }

    @Test
    fun `access restored - a successful login clears it for both`() {
        val s = SharedIdentityState()
        s.onFailure(revokedKick())
        s.onOperatorConnect()
        assertTrue(s.mayRetry())
        s.onFailure(revokedKick())
        s.onAuthenticated()
        assertNull(s.rejectedReason)
    }
}
