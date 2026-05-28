package com.nostr.torinos.crypto

/** 書き込み（署名）機能が使えるプラットフォームかどうか */
expect val isWriteSupported: Boolean

/** iOS 向けの表示分岐が必要なプラットフォームかどうか */
expect val isIosPlatform: Boolean
