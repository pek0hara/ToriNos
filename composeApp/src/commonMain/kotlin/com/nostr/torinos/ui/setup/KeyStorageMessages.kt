package com.nostr.torinos.ui.setup

import com.nostr.torinos.crypto.isIosPlatform
import com.nostr.torinos.crypto.isWebPlatform

/** 秘密鍵の保存先の呼び方。Web 版はブラウザに保存する。 */
internal val privateKeyStorageLocation: String
    get() = if (isWebPlatform) "このブラウザ" else "この端末"

/** 保存済みアカウントの削除確認ダイアログの本文。秘密鍵の保存先がプラットフォームで異なる。 */
internal fun storedAccountDeletionMessage(shortNpub: String): String = when {
    isIosPlatform ->
        "$shortNpub の秘密鍵を、この端末とiCloudキーチェーンから削除します。同じApple Accountの端末にも反映されます。Nostr上のアカウントや投稿は削除されません。"
    isWebPlatform ->
        "$shortNpub の秘密鍵を、このブラウザから削除します。Nostr上のアカウントや投稿は削除されません。"
    else ->
        "$shortNpub の秘密鍵を、この端末の保存済みアカウントから削除します。Nostr上のアカウントや投稿は削除されません。"
}
