package com.nostr.torinos.network

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.crypto.generatePrivateKey
import com.nostr.torinos.model.GroupRef
import com.nostr.torinos.model.Nip29
import com.nostr.torinos.model.Nip29GroupCreation
import com.nostr.torinos.model.Nip29GroupMetadata
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.buildNip29CreateGroupTags
import com.nostr.torinos.model.buildNip29DeleteGroupTags
import com.nostr.torinos.model.buildNip29EditMetadataTags
import com.nostr.torinos.model.buildNip29ChatTags
import com.nostr.torinos.model.buildNip29PutUserTags
import com.nostr.torinos.model.toNip29Metadata
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class Nip29CreateStage {
    CREATED,
    ADMIN_FAILED,
    METADATA_FAILED,
    VERIFICATION_FAILED,
    COMPLETE,
}

data class Nip29CreateResult(
    val creation: Nip29GroupCreation,
    val createEventId: String,
    val stage: Nip29CreateStage,
    val metadata: Nip29GroupMetadata? = null,
    val message: String = "",
)

data class Nip29RelayCreationCheck(
    val relayName: String?,
    val supportsNip29: Boolean,
    val authRequired: Boolean,
    val paymentRequired: Boolean,
    val restrictedWrites: Boolean,
    val warnings: List<String>,
)

object Nip29GroupRepository {
    fun generateGroupId(): String = generatePrivateKey().copyOf(16).toHex()

    suspend fun checkRelayForCreation(relayUrl: String): Nip29RelayCreationCheck {
        val refUrl = GroupRef.create(relayUrl, "preflight").relayUrl
        val info = RelayInformationRepository.fetch(refUrl, forceRefresh = true).getOrThrow()
        val supports = Nip29.NIP_NUMBER in info.supportedNips
        val limitation = info.limitation
        return Nip29RelayCreationCheck(
            relayName = info.name,
            supportsNip29 = supports,
            authRequired = limitation?.authRequired == true,
            paymentRequired = limitation?.paymentRequired == true,
            restrictedWrites = limitation?.restrictedWrites == true,
            warnings = buildList {
                if (!supports) add("NIP-11にNIP-29対応が記載されていません")
                if (limitation?.authRequired == true) add("このリレーは認証を要求しています")
                if (limitation?.paymentRequired == true) add("このリレーは支払いを要求しています")
                if (limitation?.restrictedWrites == true) add("このリレーは書き込みを制限しています")
                if (refUrl.startsWith("ws://")) add("暗号化されないws://接続です")
            },
        )
    }

    suspend fun createGroup(
        creation: Nip29GroupCreation,
        privateKeyHex: String? = null,
    ): Nip29CreateResult {
        val key = privateKeyHex ?: requirePrivateKey()
        val ownPubkey = derivePublicKey(key.fromHex()).toHex()

        val createEvent = signEvent(
            privateKeyHex = key,
            content = "",
            kind = Nip29.CREATE_GROUP,
            tags = buildNip29CreateGroupTags(creation.ref.groupId),
        )
        val createResult = NostrRepository.publishToRelayAwaitOk(createEvent, creation.ref.relayUrl)
        if (!createResult.accepted) {
            error(classifyNip29RelayError(createResult.message, "グループ作成が拒否されました"))
        }

        grantCreatorAdmin(
            creation = creation,
            createEventId = createEvent.id,
            privateKeyHex = key,
            ownPubkey = ownPubkey,
        )?.let { return it }

        val metadataEvent = signEvent(
            privateKeyHex = key,
            content = "",
            kind = Nip29.EDIT_METADATA,
            tags = buildNip29EditMetadataTags(creation),
        )
        val metadataResult = runCatching {
            NostrRepository.publishToRelayAwaitOk(metadataEvent, creation.ref.relayUrl)
        }.getOrElse {
            return Nip29CreateResult(
                creation = creation,
                createEventId = createEvent.id,
                stage = Nip29CreateStage.METADATA_FAILED,
                message = it.message ?: "グループは作成されましたが、メタデータ設定に失敗しました",
            )
        }
        if (!metadataResult.accepted) {
            return Nip29CreateResult(
                creation = creation,
                createEventId = createEvent.id,
                stage = Nip29CreateStage.METADATA_FAILED,
                message = classifyNip29RelayError(
                    metadataResult.message,
                    "グループは作成されましたが、メタデータ設定が拒否されました",
                ),
            )
        }

        val metadata = fetchVerifiedMetadata(
            ref = creation.ref,
            timeoutMillis = 12_000L,
        )
        return if (metadata != null) {
            Nip29CreateResult(
                creation = creation,
                createEventId = createEvent.id,
                stage = Nip29CreateStage.COMPLETE,
                metadata = metadata,
                message = "グループを作成しました",
            )
        } else {
            Nip29CreateResult(
                creation = creation,
                createEventId = createEvent.id,
                stage = Nip29CreateStage.COMPLETE,
                message = "グループを作成しました（リレーは公開メタデータを返していません）",
            )
        }
    }

    suspend fun retryMetadataAndVerification(
        creation: Nip29GroupCreation,
        createEventId: String,
        privateKeyHex: String? = null,
    ): Nip29CreateResult {
        val key = privateKeyHex ?: requirePrivateKey()
        val metadataEvent = signEvent(
            privateKeyHex = key,
            content = "",
            kind = Nip29.EDIT_METADATA,
            tags = buildNip29EditMetadataTags(creation),
        )
        val result = NostrRepository.publishToRelayAwaitOk(metadataEvent, creation.ref.relayUrl)
        if (!result.accepted) {
            return Nip29CreateResult(
                creation = creation,
                createEventId = createEventId,
                stage = Nip29CreateStage.METADATA_FAILED,
                message = classifyNip29RelayError(result.message, "メタデータ設定が拒否されました"),
            )
        }
        val metadata = fetchVerifiedMetadata(creation.ref, 12_000L)
        return if (metadata != null) {
            Nip29CreateResult(
                creation = creation,
                createEventId = createEventId,
                stage = Nip29CreateStage.COMPLETE,
                metadata = metadata,
                message = "グループ情報を確認しました",
            )
        } else {
            Nip29CreateResult(
                creation = creation,
                createEventId = createEventId,
                stage = Nip29CreateStage.COMPLETE,
                message = "グループ情報を設定しました（リレーは公開メタデータを返していません）",
            )
        }
    }

    suspend fun retryAdminMetadataAndVerification(
        creation: Nip29GroupCreation,
        createEventId: String,
        privateKeyHex: String? = null,
    ): Nip29CreateResult {
        val key = privateKeyHex ?: requirePrivateKey()
        val ownPubkey = derivePublicKey(key.fromHex()).toHex()
        grantCreatorAdmin(
            creation = creation,
            createEventId = createEventId,
            privateKeyHex = key,
            ownPubkey = ownPubkey,
        )?.let { return it }
        return retryMetadataAndVerification(creation, createEventId, key)
    }

    suspend fun verifyCreatedGroup(
        creation: Nip29GroupCreation,
        createEventId: String,
    ): Nip29CreateResult {
        val metadata = fetchVerifiedMetadata(creation.ref, 12_000L)
        return if (metadata != null) {
            Nip29CreateResult(
                creation = creation,
                createEventId = createEventId,
                stage = Nip29CreateStage.COMPLETE,
                metadata = metadata,
                message = "グループ情報を確認しました",
            )
        } else {
            Nip29CreateResult(
                creation = creation,
                createEventId = createEventId,
                stage = Nip29CreateStage.COMPLETE,
                message = "グループ作成は受理済みです（リレーは公開メタデータを返していません）",
            )
        }
    }

    suspend fun publishChat(
        ref: GroupRef,
        content: String,
        recentEvents: List<NostrEvent>,
        privateKeyHex: String? = null,
    ): PublishResult {
        val key = privateKeyHex ?: requirePrivateKey()
        val event = signEvent(
            privateKeyHex = key,
            content = content,
            kind = Nip29.CHAT_MESSAGE,
            tags = buildNip29ChatTags(
                groupId = ref.groupId,
                recentEvents = recentEvents,
                ownPubkey = derivePublicKey(key.fromHex()).toHex(),
            ),
        )
        return NostrRepository.publishToRelayAwaitOk(event, ref.relayUrl)
    }

    suspend fun editGroup(
        creation: Nip29GroupCreation,
        privateKeyHex: String? = null,
    ): PublishResult {
        val key = privateKeyHex ?: requirePrivateKey()
        val event = signEvent(
            privateKeyHex = key,
            content = "",
            kind = Nip29.EDIT_METADATA,
            tags = buildNip29EditMetadataTags(creation),
        )
        return NostrRepository.publishToRelayAwaitOk(event, creation.ref.relayUrl)
    }

    suspend fun grantSelfAdmin(
        ref: GroupRef,
        privateKeyHex: String? = null,
    ): PublishResult {
        val key = privateKeyHex ?: requirePrivateKey()
        val ownPubkey = derivePublicKey(key.fromHex()).toHex()
        val event = signEvent(
            privateKeyHex = key,
            content = "",
            kind = Nip29.PUT_USER,
            tags = buildNip29PutUserTags(
                groupId = ref.groupId,
                pubkey = ownPubkey,
                roles = listOf("admin"),
            ),
        )
        return NostrRepository.publishToRelayAwaitOk(event, ref.relayUrl)
    }

    suspend fun deleteGroup(
        ref: GroupRef,
        privateKeyHex: String? = null,
    ): PublishResult {
        val key = privateKeyHex ?: requirePrivateKey()
        val event = signEvent(
            privateKeyHex = key,
            content = "",
            kind = Nip29.DELETE_GROUP,
            tags = buildNip29DeleteGroupTags(ref.groupId),
        )
        return NostrRepository.publishToRelayAwaitOk(event, ref.relayUrl)
    }

    suspend fun requestJoin(
        ref: GroupRef,
        reason: String,
        inviteCode: String?,
        privateKeyHex: String? = null,
    ): PublishResult {
        val key = privateKeyHex ?: requirePrivateKey()
        val tags = buildList {
            add(listOf("h", ref.groupId))
            inviteCode?.trim()?.takeIf { it.isNotEmpty() }?.let { add(listOf("code", it)) }
        }
        val event = signEvent(key, reason.trim(), Nip29.JOIN_REQUEST, tags)
        return NostrRepository.publishToRelayAwaitOk(event, ref.relayUrl)
    }

    suspend fun leave(
        ref: GroupRef,
        reason: String = "",
        privateKeyHex: String? = null,
    ): PublishResult {
        val key = privateKeyHex ?: requirePrivateKey()
        val event = signEvent(
            privateKeyHex = key,
            content = reason.trim(),
            kind = Nip29.LEAVE_REQUEST,
            tags = listOf(listOf("h", ref.groupId)),
        )
        return NostrRepository.publishToRelayAwaitOk(event, ref.relayUrl)
    }

    suspend fun publishGroupList(
        groups: List<SavedNip29Group>,
        privateKeyHex: String? = null,
    ) {
        val key = privateKeyHex ?: requirePrivateKey()
        val tags = buildList {
            groups.distinctBy { it.ref.key }.forEach { group ->
                add(
                    buildList {
                        add("group")
                        add(group.ref.groupId)
                        add(group.ref.relayUrl)
                        group.name?.takeIf { it.isNotBlank() }?.let(::add)
                    },
                )
            }
            groups.map { it.ref.relayUrl }.distinct().forEach { add(listOf("r", it)) }
        }
        val event = signEvent(key, "", Nip29.GROUP_LIST, tags)
        NostrRepository.publishToRelays(event, RelayStore.enabledRelayUrlsSnapshot())
    }

    private suspend fun requirePrivateKey(): String =
        KeyStorage.loadPrivateKey() ?: error("秘密鍵が設定されていません")

    private suspend fun grantCreatorAdmin(
        creation: Nip29GroupCreation,
        createEventId: String,
        privateKeyHex: String,
        ownPubkey: String,
    ): Nip29CreateResult? {
        val adminEvent = signEvent(
            privateKeyHex = privateKeyHex,
            content = "",
            kind = Nip29.PUT_USER,
            tags = buildNip29PutUserTags(
                groupId = creation.ref.groupId,
                pubkey = ownPubkey,
                roles = listOf("admin"),
            ),
        )
        val result = runCatching {
            NostrRepository.publishToRelayAwaitOk(adminEvent, creation.ref.relayUrl)
        }.getOrElse {
            return Nip29CreateResult(
                creation = creation,
                createEventId = createEventId,
                stage = Nip29CreateStage.ADMIN_FAILED,
                message = it.message ?: "グループは作成されましたが、管理者権限の付与に失敗しました",
            )
        }
        if (!result.accepted) {
            return Nip29CreateResult(
                creation = creation,
                createEventId = createEventId,
                stage = Nip29CreateStage.ADMIN_FAILED,
                message = classifyNip29RelayError(
                    result.message,
                    "グループは作成されましたが、管理者権限の付与が拒否されました",
                ),
            )
        }
        return null
    }

    private suspend fun fetchVerifiedMetadata(
        ref: GroupRef,
        timeoutMillis: Long,
    ): Nip29GroupMetadata? = coroutineScope {
        val subId = "nip29-create-verify-${ref.key.hashCode().toUInt().toString(16)}"
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeoutMillis) {
                NostrRepository.events(subId).first { event ->
                    event.toNip29Metadata()?.groupId == ref.groupId
                }
            }
        }
        try {
            NostrRepository.subscribeTemporaryRelay(
                subId,
                NostrFilter(kinds = listOf(Nip29.METADATA), dTags = listOf(ref.groupId), limit = 1),
                ref.relayUrl,
            )
            result.await()?.toNip29Metadata()
        } finally {
            result.cancel()
            NostrRepository.closeTemporaryRelay(subId)
        }
    }
}

fun nip29PublishTargets(ref: GroupRef): List<String> = listOf(ref.relayUrl)

fun classifyNip29RelayError(message: String, fallback: String): String {
    val text = message.trim()
    val lower = text.lowercase()
    val label = when {
        lower.startsWith("duplicate:") -> "同じグループIDが既に存在します"
        lower.startsWith("auth-required:") -> "リレー認証が必要です"
        lower.startsWith("payment-required:") -> "リレーへの支払いが必要です"
        lower.startsWith("restricted:") -> "このリレーでグループを作成する権限がありません"
        lower.startsWith("rate-limited:") -> "送信頻度が制限されています"
        lower.startsWith("invalid:") -> "作成イベントがリレーに受理されませんでした"
        text.isEmpty() -> fallback
        else -> fallback
    }
    return if (text.isNotEmpty() && label != text) "$label: $text" else label
}
