package com.nostr.torinos.util

// kotlinx-datetime は読み込まれた @js-joda/timezone の DB を使って名前付きタイムゾーンを解決する。
// モジュールを import させるため、起動時に一度参照する。
@JsModule("@js-joda/timezone")
external object JsJodaTimeZoneModule : JsAny

internal val timeZoneDatabase: JsAny = JsJodaTimeZoneModule
