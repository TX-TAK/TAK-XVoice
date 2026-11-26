package com.atakmap.android.xv.transport.mumble

import mumble.MumbleProto.Reject.RejectType
import org.junit.Assert.assertEquals
import org.junit.Test

class RejectSummaryTest {
    @Test
    fun `password rejects read as wrong password`() {
        assertEquals("wrong password", RejectSummary.of(RejectType.WrongUserPW))
        assertEquals("wrong password", RejectSummary.of(RejectType.WrongServerPW))
    }

    @Test
    fun `certificate and sign-in rejects are distinct`() {
        assertEquals("no certificate", RejectSummary.of(RejectType.NoCertificate))
        assertEquals("sign-in failed", RejectSummary.of(RejectType.AuthenticatorFail))
    }

    @Test
    fun `unknown or missing type falls back to a generic refusal`() {
        assertEquals("connection refused", RejectSummary.of(null))
        assertEquals("connection refused", RejectSummary.of(RejectType.None))
    }
}
