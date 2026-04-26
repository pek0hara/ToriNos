package com.nostr.torinos.crypto

/** 書き込み（署名）機能が使えるプラットフォームかどうか */
expect val isWriteSupported: Boolean

/** Material3 の ModalBottomSheet を安定利用できるプラットフォームかどうか */
expect val supportsModalBottomSheet: Boolean
