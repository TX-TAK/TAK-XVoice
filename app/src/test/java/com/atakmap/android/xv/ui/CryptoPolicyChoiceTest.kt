package com.atakmap.android.xv.ui

import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoPolicyChoiceTest {
    @Test
    fun `every policy round-trips through the spinner unchanged`() {
        for (policy in CryptoPolicy.values()) {
            assertEquals(policy, cryptoPolicyAt(cryptoPolicyIndex(policy)))
        }
    }

    @Test
    fun `each policy has its own row and label`() {
        val indices = CryptoPolicy.values().map { cryptoPolicyIndex(it) }
        assertEquals(indices.size, indices.toSet().size)
        assertEquals(CRYPTO_POLICY_LABELS.size, CryptoPolicy.values().size)
        assertEquals("Encrypted only", CRYPTO_POLICY_LABELS[cryptoPolicyIndex(CryptoPolicy.REQUIRED)])
        assertEquals("Prefer encrypted", CRYPTO_POLICY_LABELS[cryptoPolicyIndex(CryptoPolicy.PREFERRED)])
    }

    @Test
    fun `invalid spinner position fails safe to REQUIRED`() {
        assertEquals(CryptoPolicy.REQUIRED, cryptoPolicyAt(-1))
        assertEquals(CryptoPolicy.REQUIRED, cryptoPolicyAt(99))
    }
}
