package com.nostr.torinos.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 全プラットフォームで同じ公式ベクタを通し、Web(noble)と Android / iOS(libsecp256k1)の実装を照合する。
 *
 * - BIP340: https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv
 *   ライブラリにより長さ32以外のメッセージを受け付けないため、index 0〜14 だけを使う。
 * - NIP-44: https://github.com/paulmillr/nip44/blob/main/javascript/test/nip44.vectors.json
 */
class CryptoVectorsTest {

    private class Bip340Vector(
        val index: Int,
        val secretKey: String,
        val publicKey: String,
        val auxRand: String,
        val message: String,
        val signature: String,
        val verifies: Boolean,
    )

    private val bip340Vectors = listOf(
        Bip340Vector(
            index = 0,
            secretKey = "0000000000000000000000000000000000000000000000000000000000000003",
            publicKey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            auxRand = "0000000000000000000000000000000000000000000000000000000000000000",
            message = "0000000000000000000000000000000000000000000000000000000000000000",
            signature = "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            verifies = true,
        ),
        Bip340Vector(
            index = 1,
            secretKey = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef",
            publicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            auxRand = "0000000000000000000000000000000000000000000000000000000000000001",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a",
            verifies = true,
        ),
        Bip340Vector(
            index = 2,
            secretKey = "c90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b14e5c9",
            publicKey = "dd308afec5777e13121fa72b9cc1b7cc0139715309b086c960e18fd969774eb8",
            auxRand = "c87aa53824b4d7ae2eb035a2b5bbbccc080e76cdc6d1692c4b0b62d798e6d906",
            message = "7e2d58d8b3bcdf1abadec7829054f90dda9805aab56c77333024b9d0a508b75c",
            signature = "5831aaeed7b44bb74e5eab94ba9d4294c49bcf2a60728d8b4c200f50dd313c1bab745879a5ad954a72c45a91c3a51d3c7adea98d82f8481e0e1e03674a6f3fb7",
            verifies = true,
        ),
        Bip340Vector(
            index = 3,
            secretKey = "0b432b2677937381aef05bb02a66ecd012773062cf3fa2549e44f58ed2401710",
            publicKey = "25d1dff95105f5253c4022f628a996ad3a0d95fbf21d468a1b33f8c160d8f517",
            auxRand = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            message = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            signature = "7eb0509757e246f19449885651611cb965ecc1a187dd51b64fda1edc9637d5ec97582b9cb13db3933705b32ba982af5af25fd78881ebb32771fc5922efc66ea3",
            verifies = true,
        ),
        Bip340Vector(
            index = 4,
            secretKey = "",
            publicKey = "d69c3509bb99e412e68b0fe8544e72837dfa30746d8be2aa65975f29d22dc7b9",
            auxRand = "",
            message = "4df3c3f68fcc83b27e9d42c90431a72499f17875c81a599b566c9889b9696703",
            signature = "00000000000000000000003b78ce563f89a0ed9414f5aa28ad0d96d6795f9c6376afb1548af603b3eb45c9f8207dee1060cb71c04e80f593060b07d28308d7f4",
            verifies = true,
        ),
        Bip340Vector(
            index = 5,
            secretKey = "",
            publicKey = "eefdea4cdb677750a420fee807eacf21eb9898ae79b9768766e4faa04a2d4a34",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "6cff5c3ba86c69ea4b7376f31a9bcb4f74c1976089b2d9963da2e5543e17776969e89b4c5564d00349106b8497785dd7d1d713a8ae82b32fa79d5f7fc407d39b",
            verifies = false,
        ),
        Bip340Vector(
            index = 6,
            secretKey = "",
            publicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "fff97bd5755eeea420453a14355235d382f6472f8568a18b2f057a14602975563cc27944640ac607cd107ae10923d9ef7a73c643e166be5ebeafa34b1ac553e2",
            verifies = false,
        ),
        Bip340Vector(
            index = 7,
            secretKey = "",
            publicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "1fa62e331edbc21c394792d2ab1100a7b432b013df3f6ff4f99fcb33e0e1515f28890b3edb6e7189b630448b515ce4f8622a954cfe545735aaea5134fccdb2bd",
            verifies = false,
        ),
        Bip340Vector(
            index = 8,
            secretKey = "",
            publicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "6cff5c3ba86c69ea4b7376f31a9bcb4f74c1976089b2d9963da2e5543e177769961764b3aa9b2ffcb6ef947b6887a226e8d7c93e00c5ed0c1834ff0d0c2e6da6",
            verifies = false,
        ),
        Bip340Vector(
            index = 9,
            secretKey = "",
            publicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "0000000000000000000000000000000000000000000000000000000000000000123dda8328af9c23a94c1feecfd123ba4fb73476f0d594dcb65c6425bd186051",
            verifies = false,
        ),
        Bip340Vector(
            index = 10,
            secretKey = "",
            publicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "00000000000000000000000000000000000000000000000000000000000000017615fbaf5ae28864013c099742deadb4dba87f11ac6754f93780d5a1837cf197",
            verifies = false,
        ),
        Bip340Vector(
            index = 11,
            secretKey = "",
            publicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "4a298dacae57395a15d0795ddbfd1dcb564da82b0f269bc70a74f8220429ba1d69e89b4c5564d00349106b8497785dd7d1d713a8ae82b32fa79d5f7fc407d39b",
            verifies = false,
        ),
        Bip340Vector(
            index = 12,
            secretKey = "",
            publicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f69e89b4c5564d00349106b8497785dd7d1d713a8ae82b32fa79d5f7fc407d39b",
            verifies = false,
        ),
        Bip340Vector(
            index = 13,
            secretKey = "",
            publicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "6cff5c3ba86c69ea4b7376f31a9bcb4f74c1976089b2d9963da2e5543e177769fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
            verifies = false,
        ),
        Bip340Vector(
            index = 14,
            secretKey = "",
            publicKey = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc30",
            auxRand = "",
            message = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89",
            signature = "6cff5c3ba86c69ea4b7376f31a9bcb4f74c1976089b2d9963da2e5543e17776969e89b4c5564d00349106b8497785dd7d1d713a8ae82b32fa79d5f7fc407d39b",
            verifies = false,
        ),
    )

    @Test
    fun derivePublicKeyMatchesBip340Vectors() {
        bip340Vectors.filter { it.secretKey.isNotEmpty() }.forEach { v ->
            assertEquals(v.publicKey, derivePublicKey(v.secretKey.fromHex()).toHex(), "index ${v.index}")
        }
    }

    @Test
    fun schnorrSignMatchesBip340VectorsWithZeroAuxRand() {
        // 全プラットフォームで aux_rand をゼロとして署名するため、ゼロの行は署名が完全に一致する。
        val zeroAux = bip340Vectors.filter { it.secretKey.isNotEmpty() && it.auxRand == "0".repeat(64) }
        assertTrue(zeroAux.isNotEmpty())
        zeroAux.forEach { v ->
            assertEquals(v.signature, schnorrSign(v.message.fromHex(), v.secretKey.fromHex()).toHex(), "index ${v.index}")
        }
    }

    @Test
    fun schnorrSignaturesVerifyForEveryBip340SecretKey() {
        bip340Vectors.filter { it.secretKey.isNotEmpty() }.forEach { v ->
            val signature = schnorrSign(v.message.fromHex(), v.secretKey.fromHex())
            assertTrue(schnorrVerify(signature, v.message.fromHex(), v.publicKey.fromHex()), "index ${v.index}")
        }
    }

    @Test
    fun schnorrVerifyMatchesBip340Vectors() {
        bip340Vectors.forEach { v ->
            val actual = runCatching {
                schnorrVerify(v.signature.fromHex(), v.message.fromHex(), v.publicKey.fromHex())
            }.getOrDefault(false)
            assertEquals(v.verifies, actual, "index ${v.index}")
        }
    }

    @Test
    fun sha256MatchesKnownDigests() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            sha256(ByteArray(1_000_000) { 'a'.code.toByte() }).toHex(),
        )
    }

    @Test
    fun generatePrivateKeyReturnsDistinctUsableKeys() {
        val first = generatePrivateKey()
        val second = generatePrivateKey()
        assertEquals(32, first.size)
        assertTrue(!first.contentEquals(second))
        assertEquals(32, derivePublicKey(first).size)
    }

    @Test
    fun signedEventIsValid() {
        val privateKey = bip340Vectors.first { it.index == 1 }.secretKey
        val event = signEvent(
            privateKeyHex = privateKey,
            content = "Web から投稿 🐦",
            kind = 1,
            tags = listOf(listOf("t", "torinos")),
            createdAt = 1_700_000_000L,
        )
        assertEquals(bip340Vectors.first { it.index == 1 }.publicKey, event.pubkey)
        assertTrue(isValidEvent(event))
        assertTrue(!isValidEvent(event.copy(content = "改ざん")))
    }

    private class ConversationKeyVector(val sec1: String, val pub2: String, val conversationKey: String)

    private val conversationKeyVectors = listOf(
        ConversationKeyVector(
            sec1 = "315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268",
            pub2 = "c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133",
            conversationKey = "3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1",
        ),
        ConversationKeyVector(
            sec1 = "a1e37752c9fdc1273be53f68c5f74be7c8905728e8de75800b94262f9497c86e",
            pub2 = "03bb7947065dde12ba991ea045132581d0954f042c84e06d8c00066e23c1a800",
            conversationKey = "4d14f36e81b8452128da64fe6f1eae873baae2f444b02c950b90e43553f2178b",
        ),
        ConversationKeyVector(
            sec1 = "98a5902fd67518a0c900f0fb62158f278f94a21d6f9d33d30cd3091195500311",
            pub2 = "aae65c15f98e5e677b5050de82e3aba47a6fe49b3dab7863cf35d9478ba9f7d1",
            conversationKey = "9c00b769d5f54d02bf175b7284a1cbd28b6911b06cda6666b2243561ac96bad7",
        ),
        ConversationKeyVector(
            sec1 = "86ae5ac8034eb2542ce23ec2f84375655dab7f836836bbd3c54cefe9fdc9c19f",
            pub2 = "59f90272378089d73f1339710c02e2be6db584e9cdbe86eed3578f0c67c23585",
            conversationKey = "19f934aafd3324e8415299b64df42049afaa051c71c98d0aa10e1081f2e3e2ba",
        ),
        ConversationKeyVector(
            sec1 = "2528c287fe822421bc0dc4c3615878eb98e8a8c31657616d08b29c00ce209e34",
            pub2 = "f66ea16104c01a1c532e03f166c5370a22a5505753005a566366097150c6df60",
            conversationKey = "c833bbb292956c43366145326d53b955ffb5da4e4998a2d853611841903f5442",
        ),
        ConversationKeyVector(
            sec1 = "49808637b2d21129478041813aceb6f2c9d4929cd1303cdaf4fbdbd690905ff2",
            pub2 = "74d2aab13e97827ea21baf253ad7e39b974bb2498cc747cdb168582a11847b65",
            conversationKey = "4bf304d3c8c4608864c0fe03890b90279328cd24a018ffa9eb8f8ccec06b505d",
        ),
        ConversationKeyVector(
            sec1 = "af67c382106242c5baabf856efdc0629cc1c5b4061f85b8ceaba52aa7e4b4082",
            pub2 = "bdaf0001d63e7ec994fad736eab178ee3c2d7cfc925ae29f37d19224486db57b",
            conversationKey = "a3a575dd66d45e9379904047ebfb9a7873c471687d0535db00ef2daa24b391db",
        ),
        ConversationKeyVector(
            sec1 = "0e44e2d1db3c1717b05ffa0f08d102a09c554a1cbbf678ab158b259a44e682f1",
            pub2 = "1ffa76c5cc7a836af6914b840483726207cb750889753d7499fb8b76aa8fe0de",
            conversationKey = "a39970a667b7f861f100e3827f4adbf6f464e2697686fe1a81aeda817d6b8bdf",
        ),
    )

    @Test
    fun nip44ConversationKeyMatchesVectors() {
        conversationKeyVectors.forEachIndexed { i, v ->
            assertEquals(v.conversationKey, Nip44.conversationKey(v.sec1, v.pub2).toHex(), "vector $i")
        }
    }

    private class EncryptDecryptVector(
        val sec1: String,
        val sec2: String,
        val conversationKey: String,
        val nonce: String,
        val plaintext: String,
        val payload: String,
    )

    private val encryptDecryptVectors = listOf(
        EncryptDecryptVector(
            sec1 = "0000000000000000000000000000000000000000000000000000000000000001",
            sec2 = "0000000000000000000000000000000000000000000000000000000000000002",
            conversationKey = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d",
            nonce = "0000000000000000000000000000000000000000000000000000000000000001",
            plaintext = "a",
            payload = "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb",
        ),
        EncryptDecryptVector(
            sec1 = "0000000000000000000000000000000000000000000000000000000000000002",
            sec2 = "0000000000000000000000000000000000000000000000000000000000000001",
            conversationKey = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d",
            nonce = "f00000000000000000000000000000f00000000000000000000000000000000f",
            plaintext = "🍕🫃",
            payload = "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj",
        ),
        EncryptDecryptVector(
            sec1 = "5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a",
            sec2 = "4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d",
            conversationKey = "3e2b52a63be47d34fe0a80e34e73d436d6963bc8f39827f327057a9986c20a45",
            nonce = "b635236c42db20f021bb8d1cdff5ca75dd1a0cc72ea742ad750f33010b24f73b",
            plaintext = "表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀",
            payload = "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs=",
        ),
        EncryptDecryptVector(
            sec1 = "8f40e50a84a7462e2b8d24c28898ef1f23359fff50d8c509e6fb7ce06e142f9c",
            sec2 = "b9b0a1e9cc20100c5faa3bbe2777303d25950616c4c6a3fa2e3e046f936ec2ba",
            conversationKey = "d5a2f879123145a4b291d767428870f5a8d9e5007193321795b40183d4ab8c2b",
            nonce = "b20989adc3ddc41cd2c435952c0d59a91315d8c5218d5040573fc3749543acaf",
            plaintext = "ability🤝的 ȺȾ",
            payload = "ArIJia3D3cQc0sQ1lSwNWakTFdjFIY1QQFc/w3SVQ6yvbG2S0x4Yu86QGwPTy7mP3961I1XqB6SFFTzqDZZavhxoWMj7mEVGMQIsh2RLWI5EYQaQDIePSnXPlzf7CIt+voTD",
        ),
        EncryptDecryptVector(
            sec1 = "875adb475056aec0b4809bd2db9aa00cff53a649e7b59d8edcbf4e6330b0995c",
            sec2 = "9c05781112d5b0a2a7148a222e50e0bd891d6b60c5483f03456e982185944aae",
            conversationKey = "3b15c977e20bfe4b8482991274635edd94f366595b1a3d2993515705ca3cedb8",
            nonce = "8d4442713eb9d4791175cb040d98d6fc5be8864d6ec2f89cf0895a2b2b72d1b1",
            plaintext = "pepper👀їжак",
            payload = "Ao1EQnE+udR5EXXLBA2Y1vxb6IZNbsL4nPCJWisrctGxY3AduCS+jTUgAAnfvKafkmpy15+i9YMwCdccisRa8SvzW671T2JO4LFSPX31K4kYUKelSAdSPwe9NwO6LhOsnoJ+",
        ),
        EncryptDecryptVector(
            sec1 = "eba1687cab6a3101bfc68fd70f214aa4cc059e9ec1b79fdb9ad0a0a4e259829f",
            sec2 = "dff20d262bef9dfd94666548f556393085e6ea421c8af86e9d333fa8747e94b3",
            conversationKey = "4f1538411098cf11c8af216836444787c462d47f97287f46cf7edb2c4915b8a5",
            nonce = "2180b52ae645fcf9f5080d81b1f0b5d6f2cd77ff3c986882bb549158462f3407",
            plaintext = "( ͡° ͜ʖ ͡°)",
            payload = "AiGAtSrmRfz59QgNgbHwtdbyzXf/PJhogrtUkVhGLzQHv4qhKQwnFQ54OjVMgqCea/Vj0YqBSdhqNR777TJ4zIUk7R0fnizp6l1zwgzWv7+ee6u+0/89KIjY5q1wu6inyuiv",
        ),
    )

    @Test
    fun nip44EncryptDecryptMatchesVectors() {
        encryptDecryptVectors.forEachIndexed { i, v ->
            val pub2 = derivePublicKey(v.sec2.fromHex()).toHex()
            val conversationKey = Nip44.conversationKey(v.sec1, pub2)
            assertEquals(v.conversationKey, conversationKey.toHex(), "vector $i")
            assertEquals(v.payload, Nip44.encryptWithConversationKey(v.plaintext, conversationKey, v.nonce.fromHex()), "vector $i")
            assertEquals(v.plaintext, Nip44.decryptWithConversationKey(v.payload, conversationKey), "vector $i")
        }
    }

    @Test
    fun nip44RoundTripsBetweenTwoKeys() {
        val alice = generateKeyPair()
        val bob = generateKeyPair()
        val payload = Nip44.encrypt("秘密のメモ", alice.privateKeyHex, bob.publicKeyHex)
        assertEquals("秘密のメモ", Nip44.decrypt(payload, bob.privateKeyHex, alice.publicKeyHex))
    }
}
