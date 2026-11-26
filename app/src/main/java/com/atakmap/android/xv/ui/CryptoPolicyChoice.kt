package com.atakmap.android.xv.ui

import com.atakmap.android.xv.transport.multicast.CryptoPolicy

/**
 * The one spinner mapping for a channel's encryption posture, shared by
 * both channel dialogs. Every [CryptoPolicy] has its own row, so opening
 * a dialog and saving without touching the spinner round-trips the
 * stored policy exactly — no row collapses REQUIRED into PREFERRED.
 */
internal val CRYPTO_POLICY_LABELS: List<String> =
    listOf("Encrypted only", "Prefer encrypted", "Clear (unencrypted)")

private val CRYPTO_POLICY_ROWS: List<CryptoPolicy> =
    listOf(CryptoPolicy.REQUIRED, CryptoPolicy.PREFERRED, CryptoPolicy.CLEARTEXT)

internal fun cryptoPolicyIndex(policy: CryptoPolicy): Int = CRYPTO_POLICY_ROWS.indexOf(policy)

/** Out-of-range positions (e.g. Spinner.INVALID_POSITION) fail safe to REQUIRED. */
internal fun cryptoPolicyAt(index: Int): CryptoPolicy = CRYPTO_POLICY_ROWS.getOrElse(index) { CryptoPolicy.REQUIRED }
