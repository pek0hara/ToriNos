package com.nostr.torinos.account

internal actual fun createPlatformSigner(
    pubkey: String,
    credential: SignerCredential,
    lease: AccountSessionLease,
): AccountSigner? = null
