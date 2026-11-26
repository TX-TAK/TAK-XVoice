package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ReaderReuse]: background reconnect triggers (ACL_CONNECTED, adapter
 * STATE_ON) must not replace a BLE reader that is connected or still
 * working on the same device with the same protocol.
 */
class ReaderReuseTest {
    @Test
    fun `connected reader on the same device and protocol is kept`() {
        assertEquals(ReaderReuse.Action.KEEP_CONNECTED, ReaderReuse.decide(sameKind = true, connected = true, inProgress = false))
    }

    @Test
    fun `reader still connecting or backing off is left alone`() {
        assertEquals(ReaderReuse.Action.KEEP_IN_PROGRESS, ReaderReuse.decide(sameKind = true, connected = false, inProgress = true))
    }

    @Test
    fun `idle reader is replaced`() {
        assertEquals(ReaderReuse.Action.REPLACE, ReaderReuse.decide(sameKind = true, connected = false, inProgress = false))
    }

    @Test
    fun `protocol change always replaces`() {
        assertEquals(ReaderReuse.Action.REPLACE, ReaderReuse.decide(sameKind = false, connected = true, inProgress = true))
    }
}
