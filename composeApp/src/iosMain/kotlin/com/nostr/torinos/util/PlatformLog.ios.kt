package com.nostr.torinos.util

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSCachesDirectory
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData

actual fun platformLog(message: String) {
    println("[ToriNos] $message")
    // TODO(調査用・一時): ジャーナルのログを端末内のファイルにも残す。ホーム画面から起動した分も後で回収できるように。
    if (message.startsWith("[Journal]")) JournalTraceFile.append(message)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private object JournalTraceFile {
    private val path: String? by lazy {
        val dir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        dir?.let { "$it/journal_trace.log" }
    }
    private val formatter = NSDateFormatter().apply { dateFormat = "HH:mm:ss.SSS" }

    fun append(message: String) {
        val path = path ?: return
        val line = "${formatter.stringFromDate(NSDate())} $message\n"
        val data = NSString.create(string = line).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) {
            manager.createFileAtPath(path, data, null)
            return
        }
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return
        handle.seekToEndOfFile()
        handle.writeData(data)
        handle.closeFile()
    }
}
