package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 作成のどこまで進んだか。 */
@Serializable
enum class CreationStage {
    /** 作成（kind 9007）の受理待ち。 */
    Create,
    /** 標準の NIP-29: グループは出来ていて、名前など（kind 9002）の設定待ち。 */
    Metadata,
}

/**
 * 終わっていない作成の記録。応答を取りこぼしたときに、同じ ID（Buzz は同じ署名済みイベント）でやり直すために端末へ保存する。
 */
@Serializable
data class PendingCreation(
    val relayUrl: String,
    val buzz: Boolean,
    val groupId: String,
    val name: String,
    val about: String = "",
    val forum: Boolean = false,
    val private: Boolean = false,
    val labels: List<String> = emptyList(),
    val initialMembers: List<String> = emptyList(),
    val stage: CreationStage = CreationStage.Create,
    /** Buzz: 署名済みの kind 9007。同じものを送り直すと、リレーは受理済みとして答える。 */
    val createEvent: NostrEvent? = null,
) {
    val ref: GroupRef get() = GroupRef(relayUrl, groupId)
    val flavor: GroupFlavor get() = if (buzz) GroupFlavor.Buzz else GroupFlavor.Standard
    val spec: NewGroupSpec
        get() = NewGroupSpec(
            groupId = groupId,
            name = name,
            about = about,
            type = if (forum) NewGroupType.Forum else NewGroupType.Chat,
            visibility = if (private) NewGroupVisibility.Private else NewGroupVisibility.Open,
            labels = labels,
            initialMembers = initialMembers,
        )
}

private val creationJson = Json { ignoreUnknownKeys = true }

internal fun encodePendingCreations(pending: List<PendingCreation>): String = creationJson.encodeToString(pending)

internal fun decodePendingCreations(stored: String?): List<PendingCreation> =
    stored?.takeIf { it.isNotBlank() }
        ?.let { runCatching { creationJson.decodeFromString<List<PendingCreation>>(it) }.getOrNull() }
        .orEmpty()

/** 終わっていない作成の保存先（アカウントごと）。 */
interface CreationJournal {
    suspend fun load(): List<PendingCreation>
    suspend fun save(pending: List<PendingCreation>)
}

/** 作成に使うリレーとのやり取り。 */
interface GroupCreationGateway {
    suspend fun sign(draft: GroupEventDraft): NostrEvent
    /** そのグループのリレーだけへ送り、OK を待つ。 */
    suspend fun publish(ref: GroupRef, event: NostrEvent): GroupPublishOutcome
    suspend fun fetch(relayUrl: String, filters: List<NostrFilter>): List<NostrEvent>
    /** リレーの鍵（NIP-11 の `self`）。 */
    suspend fun relayPubkey(relayUrl: String): String?
    /** いまの時刻（秒）。 */
    fun now(): Long
    suspend fun pause(millis: Long)
}

sealed interface GroupCreateResult {
    /**
     * 作成できた。[confirmed] は、リレーのグループ情報（と Buzz では自分がメンバーであること）を読めた。
     * 読めなくても作成は受理されているので成功として扱い、一覧の読み直しに任せる。
     */
    data class Created(val ref: GroupRef, val metadata: GroupMetadata?, val confirmed: Boolean) : GroupCreateResult
    /** リレーに拒否された。記録は残さない。 */
    data class Rejected(val kind: CreateRejection, val reason: String) : GroupCreateResult
    /** 応答が無いなどで終わらなかった。記録が残り、[GroupCreator.resume] でやり直せる。 */
    data class Unfinished(val reason: String) : GroupCreateResult
}

/** 作成を確かめる読み直しの間隔（Buzz のクライアントの参加確認と同じ）。 */
private val CONFIRM_DELAYS_MILLIS = listOf(0L, 250L, 750L, 1500L)

/** Buzz のリレーは15分より古いイベントを受け付けない。それより前に署名し直す。 */
private const val RESIGN_AFTER_SECONDS = 14 * 60L

/**
 * グループ・チャンネルの作成（docs/chat-create-design.md 4.4）。
 * 署名や送信の前に記録を保存し、途中で止まっても同じ ID でやり直せるようにする。
 */
class GroupCreator(
    private val gateway: GroupCreationGateway,
    private val journal: CreationJournal,
    private val pubkey: String,
) {
    private val mutex = Mutex()

    suspend fun pending(): List<PendingCreation> = journal.load()

    /** 端末の記録だけを消す。リレーに作られたものは消さない。 */
    suspend fun discard(pending: PendingCreation) = remove(pending.ref)

    suspend fun create(relayUrl: String, flavor: GroupFlavor, spec: NewGroupSpec): GroupCreateResult {
        val record = PendingCreation(
            relayUrl = relayUrl,
            buzz = flavor == GroupFlavor.Buzz,
            groupId = spec.groupId,
            name = canonicalGroupName(spec.name),
            about = spec.about.trim(),
            forum = spec.type == NewGroupType.Forum,
            private = spec.visibility == NewGroupVisibility.Private,
            labels = spec.labels,
            initialMembers = spec.initialMembers,
        )
        return if (record.buzz) createBuzz(record) else createStandard(record)
    }

    suspend fun resume(pending: PendingCreation): GroupCreateResult =
        if (pending.buzz) resumeBuzz(pending) else createStandard(pending)

    // ---- Buzz ----

    private suspend fun createBuzz(record: PendingCreation): GroupCreateResult {
        val signed = record.copy(createEvent = gateway.sign(buildBuzzCreateChannel(record.spec)))
        put(signed)
        return sendBuzz(signed)
    }

    private suspend fun resumeBuzz(record: PendingCreation): GroupCreateResult {
        val event = record.createEvent ?: return createBuzz(record)
        if (gateway.now() - event.createdAt < RESIGN_AFTER_SECONDS) return sendBuzz(record)
        // 古い署名は受け付けられない。前の送信が届いていたかを先に確かめ、無ければ同じ ID で署名し直す。
        confirm(record, CONFIRM_DELAYS_MILLIS.take(1))?.let { metadata ->
            remove(record.ref)
            return GroupCreateResult.Created(record.ref, metadata, confirmed = true)
        }
        return createBuzz(record)
    }

    private suspend fun sendBuzz(record: PendingCreation): GroupCreateResult {
        val event = requireNotNull(record.createEvent)
        return when (val outcome = gateway.publish(record.ref, event)) {
            // 同じイベントの送り直しは `OK true "duplicate:"` で受理される。
            is GroupPublishOutcome.Published -> finishCreated(record)
            is GroupPublishOutcome.Rejected -> {
                val kind = classifyCreateRejection(outcome.reason)
                // 同じ ID が使われている。前の送信が別のイベントで届いていた場合は自分のチャンネル。
                val mine = if (kind == CreateRejection.IdTaken) confirm(record, CONFIRM_DELAYS_MILLIS) else null
                remove(record.ref)
                if (mine != null) GroupCreateResult.Created(record.ref, mine, confirmed = true)
                else GroupCreateResult.Rejected(kind, outcome.reason)
            }
            is GroupPublishOutcome.Failed -> GroupCreateResult.Unfinished(outcome.reason)
        }
    }

    // ---- 標準の NIP-29 ----

    private suspend fun createStandard(record: PendingCreation): GroupCreateResult {
        var current = record
        if (current.stage == CreationStage.Create) {
            put(current)
            val create = gateway.sign(buildStandardCreateGroup(current.groupId))
            when (val outcome = gateway.publish(current.ref, create)) {
                is GroupPublishOutcome.Published -> Unit
                is GroupPublishOutcome.Rejected -> {
                    // 前の送信が届いていた（応答だけ取りこぼした）なら、作成済みとして名前の設定へ進む。
                    if (classifyCreateRejection(outcome.reason) != CreateRejection.IdTaken) {
                        remove(current.ref)
                        return GroupCreateResult.Rejected(classifyCreateRejection(outcome.reason), outcome.reason)
                    }
                }
                is GroupPublishOutcome.Failed -> return GroupCreateResult.Unfinished(outcome.reason)
            }
            current = current.copy(stage = CreationStage.Metadata)
            put(current)
        }
        val edit = gateway.sign(buildStandardEditMetadata(current.spec))
        return when (val outcome = gateway.publish(current.ref, edit)) {
            is GroupPublishOutcome.Published -> finishCreated(current)
            is GroupPublishOutcome.Rejected -> GroupCreateResult.Unfinished("チャンネルは作成されましたが、名前を設定できませんでした: ${outcome.reason}")
            is GroupPublishOutcome.Failed -> GroupCreateResult.Unfinished("チャンネルは作成されましたが、名前を設定できませんでした: ${outcome.reason}")
        }
    }

    // ---- 共通 ----

    private suspend fun finishCreated(record: PendingCreation): GroupCreateResult {
        val metadata = confirm(record, CONFIRM_DELAYS_MILLIS)
        remove(record.ref)
        return GroupCreateResult.Created(record.ref, metadata, confirmed = metadata != null)
    }

    /**
     * リレーが出すグループ情報（kind 39000）を読む。Buzz ではメンバー一覧（kind 39002）に自分が居ることも確かめる。
     * すぐには出ないことがあるので [delays] の間隔で読み直す。
     */
    private suspend fun confirm(record: PendingCreation, delays: List<Long>): GroupMetadata? {
        val relayKey = gateway.relayPubkey(record.relayUrl)
        // Buzz のメンバー一覧はリレーの鍵で署名されたものだけを信じる。鍵が分からなければ確かめられない。
        if (record.buzz && relayKey == null) return null
        val authors = relayKey?.let(::listOf)
        val filters = buildList {
            add(NostrFilter(kinds = listOf(GROUP_METADATA_KIND), authors = authors, dTags = listOf(record.groupId), limit = 1))
            if (record.buzz) add(NostrFilter(kinds = listOf(GROUP_MEMBERS_KIND), authors = authors, dTags = listOf(record.groupId), limit = 1))
        }
        delays.forEach { delay ->
            if (delay > 0) gateway.pause(delay)
            val events = try {
                gateway.fetch(record.relayUrl, filters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
            val metadata = latestGroupMetadata(events, relayKey)[record.groupId]
            val member = !record.buzz || buzzMembershipOf(pubkey, record.groupId, events, relayKey) == GroupMembership.Member
            if (metadata != null && member) return metadata
        }
        return null
    }

    private suspend fun put(record: PendingCreation) = mutex.withLock {
        journal.save(journal.load().filterNot { it.ref == record.ref } + record)
    }

    private suspend fun remove(ref: GroupRef) = mutex.withLock {
        journal.save(journal.load().filterNot { it.ref == ref })
    }
}
