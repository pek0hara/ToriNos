package com.nostr.torinos.crypto

/** 書き込み（署名）機能が使えるプラットフォームかどうか */
expect val isWriteSupported: Boolean

/** iOS 向けの表示分岐が必要なプラットフォームかどうか */
expect val isIosPlatform: Boolean

/** ブラウザで動く Web 版かどうか。秘密鍵の保存先の説明などを切り替える。 */
expect val isWebPlatform: Boolean
