package com.nostr.torinos.crypto

import com.nostr.torinos.crypto.interop.KeychainCopyMatching
import com.nostr.torinos.crypto.interop.KeychainItemAdd
import com.nostr.torinos.crypto.interop.KeychainItemDelete
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.numberWithBool
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val KEYCHAIN_SERVICE = "com.nostr.torinos"
private const val KEYCHAIN_ACCOUNT = "private_key"
private const val LEGACY_DEFAULTS_KEY = "torinos_private_key"

/**
 * Bridge a CF constant (CFStringRef) to NSString.
 * All Security attribute key/value constants are CFStringRef which is
 * toll-free bridged to NSString.
 */
@OptIn(ExperimentalForeignApi::class)
private fun cfStr(ptr: CPointer<*>?): NSString? =
    ptr?.let { interpretObjCPointerOrNull<NSString>(it.rawValue) }

/**
 * Build a base Keychain query as NSMutableDictionary.
 *
 * The Security functions are called via ObjC ARC wrappers (KeychainHelper.def)
 * that use (__bridge CFDictionaryRef) to properly toll-free bridge the dict.
 * This avoids the Kotlin/Native interpretCPointer cast that causes
 * Security's internal COW (SecCFDictionaryCOWGetMutable / objc_retain) to crash.
 */
@OptIn(ExperimentalForeignApi::class)
private fun buildBaseDict(): NSMutableDictionary {
    val d = NSMutableDictionary()
    d.setObject(cfStr(kSecClassGenericPassword)!!, forKey = cfStr(kSecClass)!!)
    d.setObject(NSString.create(string = KEYCHAIN_SERVICE), forKey = cfStr(kSecAttrService)!!)
    d.setObject(NSString.create(string = KEYCHAIN_ACCOUNT), forKey = cfStr(kSecAttrAccount)!!)
    d.setObject(NSNumber.numberWithBool(true), forKey = cfStr(kSecAttrSynchronizable)!!)
    return d
}

@OptIn(ExperimentalForeignApi::class)
actual object KeyStorage {

    actual suspend fun savePrivateKey(hexKey: String) {
        val normalized = normalizePrivateKey(hexKey)
        derivePublicKey(normalized.fromHex())

        val bytes = normalized.encodeToByteArray()
        val cfData = bytes.usePinned { pinned ->
            CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), bytes.size.toLong())
        }!!

        try {
            KeychainItemDelete(buildBaseDict())

            val addDict = buildBaseDict()
            addDict.setObject(
                interpretObjCPointerOrNull<NSData>(cfData.rawValue)!!,
                forKey = cfStr(kSecValueData)!!,
            )
            val status = KeychainItemAdd(addDict)
            check(status == 0) { "Keychain add failed (OSStatus=$status)" }
        } finally {
            CFRelease(cfData)
        }
    }

    actual suspend fun loadPrivateKey(): String? {
        // Migrate from NSUserDefaults if a legacy key exists.
        val defaults = NSUserDefaults.standardUserDefaults
        val legacy = defaults.stringForKey(LEGACY_DEFAULTS_KEY)
        if (legacy != null) {
            savePrivateKey(legacy)
            defaults.removeObjectForKey(LEGACY_DEFAULTS_KEY)
            defaults.synchronize()
        }

        val queryDict = buildBaseDict()
        queryDict.setObject(cfStr(kSecMatchLimitOne)!!, forKey = cfStr(kSecMatchLimit)!!)
        queryDict.setObject(NSNumber.numberWithBool(true), forKey = cfStr(kSecReturnData)!!)

        return memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = KeychainCopyMatching(queryDict, result.ptr)
            if (status != 0) return@memScoped null  // errSecSuccess = 0

            val nsData = result.value?.let { ptr ->
                interpretObjCPointerOrNull<NSData>(ptr.rawValue)
            } ?: return@memScoped null

            val bytePtr: CPointer<ByteVar> = nsData.bytes?.reinterpret()
                ?: return@memScoped null
            val str = ByteArray(nsData.length.toInt()) { i -> bytePtr[i] }.decodeToString()

            runCatching {
                val norm = normalizePrivateKey(str)
                derivePublicKey(norm.fromHex())
                norm
            }.getOrElse {
                // Stored value is corrupted — delete it.
                KeychainItemDelete(buildBaseDict())
                null
            }
        }
    }

    actual suspend fun hasKey(): Boolean = loadPrivateKey() != null

    actual suspend fun deleteKey() {
        KeychainItemDelete(buildBaseDict())
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.removeObjectForKey(LEGACY_DEFAULTS_KEY)
        defaults.synchronize()
    }
}
