package com.nostr.torinos

/**
 * 設定画面に表示するアプリのバージョン。
 * androidApp/build.gradle.kts の versionName と iosApp の MARKETING_VERSION に合わせる。
 * Web 版にはストア側のバージョンがないため、共通コードに持つ。
 */
object AppVersion {
    const val NAME = "1.1.0"
}
