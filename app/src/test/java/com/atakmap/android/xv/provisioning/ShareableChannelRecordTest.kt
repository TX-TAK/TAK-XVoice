package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareableChannelRecordTest {
    private val cfg = ChannelMulticastConfig.defaultFor("ops-1")
    private val held = ByteArray(32) { 0x11 }
    private val minted = ByteArray(32) { 0x22 }
    private val live = ByteArray(32) { 0x33 }

    @Test
    fun `re-saving a channel whose key is held keeps that key and name`() {
        val prior = CommsPlan.Channel("Ops One", cfg, held)
        val updated = cfg.copy(cryptoPolicy = CryptoPolicy.PREFERRED)
        val rec = ShareableChannelRecord.updated(prior, updated, newKey = null)
        assertArrayEquals("held key must not be dropped from the vault", held, rec.preSharedKey)
        assertEquals("Ops One", rec.displayName)
        assertEquals(updated, rec.config)
    }

    @Test
    fun `a freshly minted key wins`() {
        val rec = ShareableChannelRecord.updated(CommsPlan.Channel("Ops One", cfg, held), cfg, newKey = minted, liveKey = live)
        assertArrayEquals(minted, rec.preSharedKey)
    }

    @Test
    fun `with no prior record the live mesh key is carried`() {
        val rec = ShareableChannelRecord.updated(prior = null, config = cfg, newKey = null, liveKey = live)
        assertArrayEquals(live, rec.preSharedKey)
        assertEquals("ops-1", rec.displayName)
    }

    @Test
    fun `cleartext channel with nothing held stays keyless`() {
        assertNull(ShareableChannelRecord.updated(prior = null, config = cfg).preSharedKey)
    }

    @Test
    fun `a live rotated key wins over the prior record's stale key`() {
        val rec = ShareableChannelRecord.updated(CommsPlan.Channel("Ops One", cfg, held), cfg, newKey = null, liveKey = live)
        assertArrayEquals(live, rec.preSharedKey)
        assertEquals("Ops One", rec.displayName)
    }

    @Test
    fun `keyless re-import with the mesh down keeps the held key`() {
        val rec = ShareableChannelRecord.updated(CommsPlan.Channel("Ops One", cfg, held), cfg, newKey = null, liveKey = null)
        assertArrayEquals(held, rec.preSharedKey)
    }
}
