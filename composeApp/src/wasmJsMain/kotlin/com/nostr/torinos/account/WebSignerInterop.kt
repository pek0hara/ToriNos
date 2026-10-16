package com.nostr.torinos.account

import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * NIP-07 とパスキー（WebAuthn PRF）を呼ぶ JS。
 *
 * どの非同期関数も例外で失敗させず `{ ok, value, error }` で返す。Kotlin/Wasm では JS の例外の
 * 種類やメッセージを取り出しにくいため、判別に必要な情報（`NotAllowedError`、`PRF_UNSUPPORTED` など）を
 * 文字列で受け取る。
 */
private fun installWebSignerHelpers(): Unit = js(
    """
    (() => {
      if (globalThis.__torinosSigner) return;
      const encode = (buffer) => {
        const bytes = new Uint8Array(buffer);
        let text = '';
        for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
        return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
      };
      const decode = (value) => {
        let text = value.replace(/-/g, '+').replace(/_/g, '/');
        while (text.length % 4) text += '=';
        const binary = atob(text);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes;
      };
      const random = (size) => crypto.getRandomValues(new Uint8Array(size));
      const errorName = (e) => {
        if (!e) return 'unknown';
        if (['NotAllowedError', 'AbortError', 'SecurityError', 'OperationError'].includes(e.name)) return e.name;
        return e.message || e.name || String(e);
      };
      const safe = (f) => async (...args) => {
        try { return { ok: true, value: await f(...args) }; }
        catch (e) { return { ok: false, error: errorName(e) }; }
      };
      const deriveAesKey = async (prfOutput) => {
        const base = await crypto.subtle.importKey('raw', prfOutput, 'HKDF', false, ['deriveKey']);
        return crypto.subtle.deriveKey(
          { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(32), info: new TextEncoder().encode('torinos-nostr-private-key-v1') },
          base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
      };
      const nostr = () => {
        if (!globalThis.nostr) throw new Error('NO_EXTENSION');
        return globalThis.nostr;
      };
      globalThis.__torinosSigner = {
        nip07Available: () => !!(globalThis.nostr && typeof globalThis.nostr.signEvent === 'function'),
        nip07HasNip44: () => !!(globalThis.nostr && globalThis.nostr.nip44 && typeof globalThis.nostr.nip44.encrypt === 'function'),
        nip07HasNip04: () => !!(globalThis.nostr && globalThis.nostr.nip04 && typeof globalThis.nostr.nip04.encrypt === 'function'),
        nip07GetPublicKey: safe(async () => String(await nostr().getPublicKey())),
        nip07SignEvent: safe(async (json) => JSON.stringify(await nostr().signEvent(JSON.parse(json)))),
        nip07Nip44Encrypt: safe(async (peer, text) => String(await nostr().nip44.encrypt(peer, text))),
        nip07Nip44Decrypt: safe(async (peer, text) => String(await nostr().nip44.decrypt(peer, text))),
        nip07Nip04Encrypt: safe(async (peer, text) => String(await nostr().nip04.encrypt(peer, text))),
        nip07Nip04Decrypt: safe(async (peer, text) => String(await nostr().nip04.decrypt(peer, text))),
        passkeySupported: () => !!(globalThis.isSecureContext && globalThis.PublicKeyCredential && navigator.credentials),
        newSalt: () => encode(random(32)),
        passkeyCreate: safe(async (userName, salt) => {
          const credential = await navigator.credentials.create({ publicKey: {
            rp: { name: 'ToriNos' },
            user: { id: random(16), name: userName, displayName: userName },
            challenge: random(32),
            pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
            authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
            extensions: { prf: { eval: { first: decode(salt) } } },
          } });
          if (!credential) throw new Error('パスキーを作成できませんでした');
          const prf = credential.getClientExtensionResults().prf;
          const first = prf && prf.results && prf.results.first;
          if (!prf || (prf.enabled !== true && !first)) throw new Error('PRF_UNSUPPORTED');
          return { credentialId: encode(credential.rawId), key: first ? await deriveAesKey(first) : null };
        }),
        passkeyUnlock: safe(async (credentialId, salt) => {
          const assertion = await navigator.credentials.get({ publicKey: {
            challenge: random(32),
            allowCredentials: [{ type: 'public-key', id: decode(credentialId) }],
            userVerification: 'required',
            extensions: { prf: { eval: { first: decode(salt) } } },
          } });
          const prf = assertion && assertion.getClientExtensionResults().prf;
          const first = prf && prf.results && prf.results.first;
          if (!first) throw new Error('PRF_UNSUPPORTED');
          return deriveAesKey(first);
        }),
        aesEncrypt: safe(async (key, text) => {
          const iv = random(12);
          const data = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(text));
          return encode(iv) + '.' + encode(data);
        }),
        aesDecrypt: safe(async (key, iv, data) => {
          const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: decode(iv) }, key, decode(data));
          return new TextDecoder().decode(plain);
        }),
      };
    })()
    """,
)

private fun helpers(): JsAny {
    installWebSignerHelpers()
    return helperObject()
}

private fun helperObject(): JsAny = js("globalThis.__torinosSigner")

/** JS の呼び出しが `{ ok: false }` を返したときの理由。 */
internal class WebSignerCallFailed(val reason: String) : Exception(reason)

private fun isOk(result: JsAny): Boolean = js("result.ok === true")
private fun errorOf(result: JsAny): String = js("String(result.error)")
private fun valueOf(result: JsAny): JsAny? = js("result.value ?? null")
private fun stringOf(value: JsAny): String = js("String(value)")
private fun field(value: JsAny, name: String): JsAny? = js("value[name] ?? null")

private suspend fun Promise<JsAny>.awaitValue(): JsAny? {
    val result = await<JsAny>()
    if (!isOk(result)) throw WebSignerCallFailed(errorOf(result))
    return valueOf(result)
}

private suspend fun Promise<JsAny>.awaitString(): String =
    awaitValue()?.let(::stringOf) ?: throw WebSignerCallFailed("empty")

private fun callNip07Available(h: JsAny): Boolean = js("h.nip07Available()")
private fun callNip07HasNip44(h: JsAny): Boolean = js("h.nip07HasNip44()")
private fun callNip07HasNip04(h: JsAny): Boolean = js("h.nip07HasNip04()")
private fun callNip07GetPublicKey(h: JsAny): Promise<JsAny> = js("h.nip07GetPublicKey()")
private fun callNip07SignEvent(h: JsAny, json: String): Promise<JsAny> = js("h.nip07SignEvent(json)")
private fun callNip07Nip44Encrypt(h: JsAny, peer: String, text: String): Promise<JsAny> = js("h.nip07Nip44Encrypt(peer, text)")
private fun callNip07Nip44Decrypt(h: JsAny, peer: String, text: String): Promise<JsAny> = js("h.nip07Nip44Decrypt(peer, text)")
private fun callNip07Nip04Encrypt(h: JsAny, peer: String, text: String): Promise<JsAny> = js("h.nip07Nip04Encrypt(peer, text)")
private fun callNip07Nip04Decrypt(h: JsAny, peer: String, text: String): Promise<JsAny> = js("h.nip07Nip04Decrypt(peer, text)")
private fun callPasskeySupported(h: JsAny): Boolean = js("h.passkeySupported()")
private fun callNewSalt(h: JsAny): String = js("h.newSalt()")
private fun callPasskeyCreate(h: JsAny, userName: String, salt: String): Promise<JsAny> = js("h.passkeyCreate(userName, salt)")
private fun callPasskeyUnlock(h: JsAny, credentialId: String, salt: String): Promise<JsAny> = js("h.passkeyUnlock(credentialId, salt)")
private fun callAesEncrypt(h: JsAny, key: JsAny, text: String): Promise<JsAny> = js("h.aesEncrypt(key, text)")
private fun callAesDecrypt(h: JsAny, key: JsAny, iv: String, data: String): Promise<JsAny> = js("h.aesDecrypt(key, iv, data)")

/** `window.nostr`（NIP-07）の呼び出し。 */
internal object Nip07Js {
    fun isAvailable(): Boolean = callNip07Available(helpers())
    fun hasNip44(): Boolean = callNip07HasNip44(helpers())
    fun hasNip04(): Boolean = callNip07HasNip04(helpers())
    suspend fun getPublicKey(): String = callNip07GetPublicKey(helpers()).awaitString()
    suspend fun signEvent(unsignedJson: String): String = callNip07SignEvent(helpers(), unsignedJson).awaitString()
    suspend fun nip44Encrypt(peer: String, text: String): String = callNip07Nip44Encrypt(helpers(), peer, text).awaitString()
    suspend fun nip44Decrypt(peer: String, text: String): String = callNip07Nip44Decrypt(helpers(), peer, text).awaitString()
    suspend fun nip04Encrypt(peer: String, text: String): String = callNip07Nip04Encrypt(helpers(), peer, text).awaitString()
    suspend fun nip04Decrypt(peer: String, text: String): String = callNip07Nip04Decrypt(helpers(), peer, text).awaitString()
}

/** パスキーで作った作成直後の結果。PRF の出力を作成時に返さない環境では [key] が null。 */
internal class CreatedPasskey(val credentialId: String, val key: JsAny?)

/** WebAuthn PRF と WebCrypto（HKDF・AES-256-GCM）の呼び出し。 */
internal object PasskeyJs {
    fun isSupported(): Boolean = callPasskeySupported(helpers())
    fun newSalt(): String = callNewSalt(helpers())

    suspend fun create(userName: String, salt: String): CreatedPasskey {
        val value = callPasskeyCreate(helpers(), userName, salt).awaitValue() ?: throw WebSignerCallFailed("empty")
        val credentialId = field(value, "credentialId")?.let(::stringOf) ?: throw WebSignerCallFailed("empty")
        return CreatedPasskey(credentialId, field(value, "key"))
    }

    /** 生体認証などでパスキーを使い、暗号化に使う鍵（CryptoKey）を得る。 */
    suspend fun unlock(credentialId: String, salt: String): JsAny =
        callPasskeyUnlock(helpers(), credentialId, salt).awaitValue() ?: throw WebSignerCallFailed("empty")

    /** `iv.暗号文` の形で返す。 */
    suspend fun encrypt(key: JsAny, text: String): Pair<String, String> {
        val joined = callAesEncrypt(helpers(), key, text).awaitString()
        val (iv, data) = joined.split('.', limit = 2)
        return iv to data
    }

    suspend fun decrypt(key: JsAny, iv: String, data: String): String =
        callAesDecrypt(helpers(), key, iv, data).awaitString()
}
