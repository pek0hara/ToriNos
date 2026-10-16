package com.nostr.torinos.account

/** 保存されたアカウントの情報から、そのセッションの署名器を作る。 */
internal fun createAccountSigner(credentials: AccountCredentials, lease: AccountSessionLease): AccountSigner =
    when (val secret = credentials.secret) {
        is AccountSecret.PrivateKey -> PrivateKeyAccountSigner(secret.hex, lease)
        is AccountSecret.External -> when (val credential = secret.credential) {
            is SignerCredential.Nip46 -> com.nostr.torinos.account.nip46.Nip46AccountSigner(credentials.pubkey, credential, lease)
            else -> createPlatformSigner(credentials.pubkey, credential, lease)
                ?: throw SignerException.Unavailable("この環境では使えない署名方式のアカウントです")
        }
    }

/** プラットフォームに固有の署名方式（Web の NIP-07・パスキーなど）。対応しない方式は null。 */
internal expect fun createPlatformSigner(
    pubkey: String,
    credential: SignerCredential,
    lease: AccountSessionLease,
): AccountSigner?
