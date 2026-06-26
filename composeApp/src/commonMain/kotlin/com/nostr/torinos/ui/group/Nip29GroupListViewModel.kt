package com.nostr.torinos.ui.group

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.model.GroupRef
import com.nostr.torinos.model.Nip29
import com.nostr.torinos.model.Nip29GroupMetadata
import com.nostr.torinos.model.Nip29GroupCreation
import com.nostr.torinos.model.Nip29Membership
import com.nostr.torinos.model.Nip29SupportedKindsMode
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.decodeNaddr
import com.nostr.torinos.model.canViewContent
import com.nostr.torinos.model.canViewInfo
import com.nostr.torinos.model.determineNip29Membership
import com.nostr.torinos.model.isPubliclyDiscoverable
import com.nostr.torinos.model.nip29RelaySignatureWarning
import com.nostr.torinos.model.parseNip29GroupList
import com.nostr.torinos.model.toNip29CreatorMetadata
import com.nostr.torinos.model.toNip29Metadata
import com.nostr.torinos.network.Nip29GroupRepository
import com.nostr.torinos.network.Nip29CreateResult
import com.nostr.torinos.network.Nip29CreateStage
import com.nostr.torinos.network.Nip29RelayCreationCheck
import com.nostr.torinos.network.Nip29GroupStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.network.SavedNip29Group
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

enum class Nip29GroupListMode {
    SAVED,
    PUBLIC,
}

data class Nip29GroupListItem(
    val saved: SavedNip29Group,
    val metadata: Nip29GroupMetadata? = null,
    val latestMessage: NostrEvent? = null,
    val membership: Nip29Membership = Nip29Membership.NOT_JOINED,
    val unreadCount: Int = 0,
    val isLoading: Boolean = true,
    val warning: String? = null,
    val error: String? = null,
)

data class Nip29PublicGroupItem(
    val ref: GroupRef,
    val metadata: Nip29GroupMetadata,
    val isSaved: Boolean,
    val isRelayVerified: Boolean = false,
    val warning: String? = null,
)

class Nip29GroupListViewModel(private val ownPubkey: String?) : SafeViewModel() {
    data class AddDialogState(
        val naddr: String = "",
        val relayUrl: String = "",
        val groupId: String = "",
        val isSaving: Boolean = false,
        val error: String? = null,
    )

    data class CreateDialogState(
        val relayUrl: String = "",
        val groupId: String = "",
        val name: String = "",
        val about: String = "",
        val picture: String = "",
        val isPrivate: Boolean = false,
        val isRestricted: Boolean = true,
        val isHidden: Boolean = false,
        val isClosed: Boolean = false,
        val supportedKindsMode: Nip29SupportedKindsMode = Nip29SupportedKindsMode.TEXT_CHAT,
        val isCheckingRelay: Boolean = false,
        val relayCheck: Nip29RelayCreationCheck? = null,
        val isCreating: Boolean = false,
        val result: Nip29CreateResult? = null,
        val error: String? = null,
    )

    data class UiState(
        val isLoading: Boolean = true,
        val listMode: Nip29GroupListMode = Nip29GroupListMode.SAVED,
        val groups: List<Nip29GroupListItem> = emptyList(),
        val publicGroups: List<Nip29PublicGroupItem> = emptyList(),
        val isLoadingPublic: Boolean = false,
        val publicRelayErrors: Map<String, String> = emptyMap(),
        val showAddChoice: Boolean = false,
        val addDialog: AddDialogState? = null,
        val createDialog: CreateDialogState? = null,
        val createdRefToOpen: GroupRef? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val items = linkedMapOf<String, Nip29GroupListItem>()
    private val publicItems = linkedMapOf<String, Nip29PublicGroupItem>()
    private val unreadMessageIds = mutableMapOf<String, MutableSet<String>>()
    private val locallyRemovedKeys = mutableSetOf<String>()
    private val publicDiscoveryJobs = mutableListOf<Job>()
    private val publicDiscoverySubIds = mutableSetOf<String>()
    private var publicDiscoveryRelayUrls: List<String> = emptyList()
    private val listSubId = "nip29-list-${ownPubkey?.take(12) ?: "guest"}"

    init {
        launch {
            Nip29GroupStore.load(ownPubkey).forEach(::mergeSaved)
            emit()
            ownPubkey?.let { subscribeRemoteList(it) }
            items.values.forEach { refresh(it.saved.ref) }
            _state.value = _state.value.copy(isLoading = false)
        }
        launch {
            Nip29GroupStore.removedGroups.collect { ref ->
                removeSavedLocal(ref)
            }
        }
    }

    fun selectListMode(mode: Nip29GroupListMode) {
        _state.value = _state.value.copy(listMode = mode)
    }

    fun refreshPublicGroups() {
        startPublicDiscovery(publicDiscoveryRelayUrls)
    }

    fun selectPublicRelay(relayUrl: String?) {
        val relayUrls = listOfNotNull(relayUrl)
        if (relayUrls == publicDiscoveryRelayUrls) return
        publicDiscoveryRelayUrls = relayUrls
        startPublicDiscovery(relayUrls)
    }

    fun savePublicGroup(ref: GroupRef) {
        val public = publicItems[ref.key] ?: return
        if (ref.key in items) return
        mergeSaved(
            SavedNip29Group(
                ref = ref,
                name = public.metadata.name.takeIf { it.isNotBlank() },
                metadataVerified = public.isRelayVerified,
            ),
        )
        publicItems[ref.key] = public.copy(isSaved = true)
        emit()
        launch {
            persistAndPublish()
            refresh(ref)
        }
    }

    fun showAddChoice() {
        _state.value = _state.value.copy(showAddChoice = true)
    }

    fun dismissAddChoice() {
        _state.value = _state.value.copy(showAddChoice = false)
    }

    fun showAddDialog() {
        _state.value = _state.value.copy(showAddChoice = false, addDialog = AddDialogState())
    }

    fun showCreateDialog(relayUrl: String?) {
        if (ownPubkey == null) {
            _state.value = _state.value.copy(showAddChoice = false, error = "グループ作成にはログインが必要です")
            return
        }
        val generatedId = runCatching { Nip29GroupRepository.generateGroupId() }.getOrDefault("")
        _state.value = _state.value.copy(
            showAddChoice = false,
            addDialog = null,
            createDialog = CreateDialogState(
                relayUrl = relayUrl.orEmpty(),
                groupId = generatedId,
            ),
        )
    }

    fun dismissAddDialog() {
        if (_state.value.addDialog?.isSaving != true) {
            _state.value = _state.value.copy(addDialog = null)
        }
    }

    fun updateAddDialog(naddr: String? = null, relayUrl: String? = null, groupId: String? = null) {
        val dialog = _state.value.addDialog ?: return
        _state.value = _state.value.copy(
            addDialog = dialog.copy(
                naddr = naddr ?: dialog.naddr,
                relayUrl = relayUrl ?: dialog.relayUrl,
                groupId = groupId ?: dialog.groupId,
                error = null,
            ),
        )
    }

    fun updateCreateDialog(
        relayUrl: String? = null,
        groupId: String? = null,
        name: String? = null,
        about: String? = null,
        picture: String? = null,
        isPrivate: Boolean? = null,
        isRestricted: Boolean? = null,
        isHidden: Boolean? = null,
        isClosed: Boolean? = null,
        supportedKindsMode: Nip29SupportedKindsMode? = null,
    ) {
        val dialog = _state.value.createDialog ?: return
        if (dialog.isCreating) return
        _state.value = _state.value.copy(
            createDialog = dialog.copy(
                relayUrl = relayUrl ?: dialog.relayUrl,
                groupId = groupId ?: dialog.groupId,
                name = name ?: dialog.name,
                about = about ?: dialog.about,
                picture = picture ?: dialog.picture,
                isPrivate = isPrivate ?: dialog.isPrivate,
                isRestricted = isRestricted ?: dialog.isRestricted,
                isHidden = isHidden ?: dialog.isHidden,
                isClosed = isClosed ?: dialog.isClosed,
                supportedKindsMode = supportedKindsMode ?: dialog.supportedKindsMode,
                relayCheck = if (relayUrl != null) null else dialog.relayCheck,
                result = null,
                error = null,
            ),
        )
    }

    fun checkCreateRelay() {
        val dialog = _state.value.createDialog ?: return
        if (dialog.isCreating || dialog.isCheckingRelay) return
        if (dialog.relayUrl.isBlank()) {
            _state.value = _state.value.copy(
                createDialog = dialog.copy(error = "作成先リレーを入力してください"),
            )
            return
        }
        _state.value = _state.value.copy(
            createDialog = dialog.copy(isCheckingRelay = true, error = null, relayCheck = null),
        )
        launch {
            runCatching { Nip29GroupRepository.checkRelayForCreation(dialog.relayUrl) }
                .onSuccess { check ->
                    val current = _state.value.createDialog ?: return@onSuccess
                    _state.value = _state.value.copy(
                        createDialog = current.copy(isCheckingRelay = false, relayCheck = check),
                    )
                }
                .onFailure {
                    val current = _state.value.createDialog ?: return@onFailure
                    _state.value = _state.value.copy(
                        createDialog = current.copy(
                            isCheckingRelay = false,
                            error = it.message ?: "リレー情報を確認できません",
                        ),
                    )
                }
        }
    }

    fun regenerateGroupId() {
        val dialog = _state.value.createDialog ?: return
        if (dialog.isCreating) return
        val generated = runCatching { Nip29GroupRepository.generateGroupId() }
            .getOrElse {
                _state.value = _state.value.copy(
                    createDialog = dialog.copy(error = "グループIDを生成できません"),
                )
                return
            }
        _state.value = _state.value.copy(createDialog = dialog.copy(groupId = generated, error = null))
    }

    fun dismissCreateDialog() {
        if (_state.value.createDialog?.isCreating != true) {
            _state.value = _state.value.copy(createDialog = null)
        }
    }

    fun createGroup() {
        val dialog = _state.value.createDialog ?: return
        if (dialog.isCreating) return
        val creation = runCatching { dialog.toCreation() }
            .getOrElse {
                _state.value = _state.value.copy(
                    createDialog = dialog.copy(error = it.message ?: "入力内容を確認してください"),
                )
                return
            }
        _state.value = _state.value.copy(
            createDialog = dialog.copy(isCreating = true, error = null, result = null),
        )
        launch {
            runCatching { Nip29GroupRepository.createGroup(creation) }
                .onSuccess(::handleCreateResult)
                .onFailure {
                    val current = _state.value.createDialog ?: return@onFailure
                    _state.value = _state.value.copy(
                        createDialog = current.copy(
                            isCreating = false,
                            error = it.message ?: "グループを作成できません",
                        ),
                    )
                }
        }
    }

    fun retryCreateCompletion() {
        val dialog = _state.value.createDialog ?: return
        val previous = dialog.result ?: return
        if (dialog.isCreating || previous.stage == Nip29CreateStage.COMPLETE) return
        _state.value = _state.value.copy(
            createDialog = dialog.copy(isCreating = true, error = null),
        )
        launch {
            val action = when (previous.stage) {
                Nip29CreateStage.ADMIN_FAILED -> suspend {
                    Nip29GroupRepository.retryAdminMetadataAndVerification(
                        creation = previous.creation,
                        createEventId = previous.createEventId,
                    )
                }
                Nip29CreateStage.METADATA_FAILED -> suspend {
                    Nip29GroupRepository.retryMetadataAndVerification(
                        creation = previous.creation,
                        createEventId = previous.createEventId,
                    )
                }
                else -> suspend {
                    Nip29GroupRepository.verifyCreatedGroup(
                        creation = previous.creation,
                        createEventId = previous.createEventId,
                    )
                }
            }
            runCatching { action() }
                .onSuccess(::handleCreateResult)
                .onFailure {
                    val current = _state.value.createDialog ?: return@onFailure
                    _state.value = _state.value.copy(
                        createDialog = current.copy(
                            isCreating = false,
                            error = it.message ?: "グループ情報を確認できません",
                        ),
                    )
                }
        }
    }

    fun consumeCreatedNavigation() {
        _state.value = _state.value.copy(createdRefToOpen = null)
    }

    fun addGroup() {
        val dialog = _state.value.addDialog ?: return
        if (dialog.isSaving) return
        _state.value = _state.value.copy(addDialog = dialog.copy(isSaving = true, error = null))
        launch {
            runCatching {
                val address = dialog.naddr.trim().takeIf { it.isNotEmpty() }?.let {
                    decodeNaddr(it) ?: error("naddrを解析できません")
                }
                val ref = if (address != null) {
                    require(address.kind == Nip29.METADATA) { "NIP-29グループのnaddrではありません" }
                    val relay = address.relayUrls.firstOrNull() ?: error("naddrにリレー情報がありません")
                    GroupRef.create(relay, address.identifier)
                } else {
                    GroupRef.create(dialog.relayUrl, dialog.groupId)
                }
                val metadata = fetchMetadata(ref)
                SavedNip29Group(ref, metadata.name.takeIf { it.isNotBlank() })
            }.onSuccess { ref ->
                mergeSaved(ref)
                persistAndPublish()
                _state.value = _state.value.copy(addDialog = null)
                refresh(ref.ref)
            }.onFailure { error ->
                val current = _state.value.addDialog ?: return@onFailure
                _state.value = _state.value.copy(
                    addDialog = current.copy(
                        isSaving = false,
                        error = error.message ?: "グループを追加できません",
                    ),
                )
            }
        }
    }

    fun removeGroup(ref: GroupRef) {
        removeSavedLocal(ref)
        launch {
            Nip29GroupStore.remove(ownPubkey, ref)
            runCatching { Nip29GroupRepository.publishGroupList(items.values.map { it.saved }) }
        }
    }

    fun markOpened(ref: GroupRef) {
        val item = items[ref.key] ?: return
        val readAt = item.latestMessage?.createdAt ?: item.saved.lastReadAt
        unreadMessageIds.remove(ref.key)
        items[ref.key] = item.copy(
            saved = item.saved.copy(lastReadAt = readAt),
            unreadCount = 0,
        )
        emit()
        launch { Nip29GroupStore.save(ownPubkey, items.values.map { it.saved }) }
    }

    private suspend fun subscribeRemoteList(pubkey: String) {
        launch {
            NostrRepository.events(listSubId).collect { event ->
                parseNip29GroupList(event).forEach { (ref, name) ->
                    if (ref.key !in items && ref.key !in locallyRemovedKeys) {
                        mergeSaved(SavedNip29Group(ref, name))
                        refresh(ref)
                    }
                }
                Nip29GroupStore.save(ownPubkey, items.values.map { it.saved })
                emit()
            }
        }
        NostrRepository.subscribe(
            listSubId,
            NostrFilter(kinds = listOf(Nip29.GROUP_LIST), authors = listOf(pubkey), limit = 1),
        )
    }

    private fun mergeSaved(saved: SavedNip29Group) {
        locallyRemovedKeys.remove(saved.ref.key)
        val existing = items[saved.ref.key]
        items[saved.ref.key] = existing?.copy(saved = saved) ?: Nip29GroupListItem(saved)
        publicItems[saved.ref.key]?.let { public ->
            publicItems[saved.ref.key] = public.copy(isSaved = true)
        }
    }

    private fun handleCreateResult(result: Nip29CreateResult) {
        val current = _state.value.createDialog ?: return
        if (result.stage != Nip29CreateStage.COMPLETE) {
            _state.value = _state.value.copy(
                createDialog = current.copy(isCreating = false, result = result, error = null),
            )
            return
        }
        val saved = SavedNip29Group(
            ref = result.creation.ref,
            name = result.metadata?.name?.takeIf { it.isNotBlank() } ?: result.creation.name,
            createEventId = result.createEventId,
            createdAt = Clock.System.now().epochSeconds,
            metadataVerified = false,
            creatorPubkey = ownPubkey,
            selfAdminGranted = true,
        )
        mergeSaved(saved)
        emit()
        launch {
            Nip29GroupStore.save(ownPubkey, items.values.map { it.saved })
            runCatching { Nip29GroupRepository.publishGroupList(items.values.map { it.saved }) }
            refresh(saved.ref)
            _state.value = _state.value.copy(
                createDialog = null,
                createdRefToOpen = saved.ref,
            )
        }
    }

    private fun refresh(ref: GroupRef) {
        val suffix = ref.key.hashCode().toUInt().toString(16)
        val metadataSub = "nip29-meta-$suffix"
        val latestSub = "nip29-latest-$suffix"
        val membershipSub = "nip29-member-$suffix"
        launch {
            val relaySelf = RelayInformationRepository.fetch(ref.relayUrl, forceRefresh = true)
                .getOrNull()?.self
            var latestSubscribed = false

            fun updateLatestSubscription(item: Nip29GroupListItem?) {
                val current = item ?: items[ref.key] ?: return
                val metadata = current.metadata ?: return
                if (metadata.canViewContent(current.membership)) {
                    if (!latestSubscribed) {
                        latestSubscribed = true
                        launch {
                            NostrRepository.subscribeTemporaryRelay(
                                latestSub,
                                NostrFilter(kinds = listOf(Nip29.CHAT_MESSAGE), hTags = listOf(ref.groupId), limit = 50),
                                ref.relayUrl,
                            )
                        }
                    }
                } else {
                    if (latestSubscribed) {
                        NostrRepository.closeTemporaryRelay(latestSub)
                        latestSubscribed = false
                    }
                    unreadMessageIds.remove(ref.key)
                    update(ref) {
                        it.copy(latestMessage = null, unreadCount = 0)
                    }
                }
            }

            launch {
                NostrRepository.events(metadataSub).collect { event ->
                    val metadata = event.toNip29Metadata()
                    if (metadata?.groupId == ref.groupId) {
                        val warning = nip29RelaySignatureWarning(event, relaySelf)
                        update(ref) {
                            it.copy(
                                metadata = metadata,
                                saved = it.saved.copy(
                                    name = metadata.name.ifBlank { it.saved.name },
                                    metadataVerified = relaySelf != null && warning == null,
                                ),
                                isLoading = false,
                                warning = warning,
                                error = null,
                            )
                        }
                        updateLatestSubscription(items[ref.key])
                        Nip29GroupStore.save(ownPubkey, items.values.map { it.saved })
                    }
                }
            }
            launch {
                NostrRepository.eose(metadataSub).first()
                update(ref) { it.copy(isLoading = false) }
            }
            launch {
                NostrRepository.events(latestSub).collect { event ->
                    val unread = unreadMessageIds.getOrPut(ref.key) { linkedSetOf() }
                    val saved = items[ref.key]?.saved
                    if (saved != null && event.createdAt > saved.lastReadAt) unread += event.id
                    update(ref) {
                        it.copy(
                            latestMessage = listOfNotNull(it.latestMessage, event).maxBy { message -> message.createdAt },
                            unreadCount = unread.size,
                        )
                    }
                }
            }
            if (ownPubkey != null) {
                val moderationEvents = mutableListOf<NostrEvent>()
                launch {
                    NostrRepository.events(membershipSub).collect { event ->
                        moderationEvents += event
                        update(ref) {
                            it.copy(membership = determineNip29Membership(moderationEvents, ownPubkey))
                        }
                        updateLatestSubscription(items[ref.key])
                    }
                }
                NostrRepository.subscribeTemporaryRelay(
                    membershipSub,
                    NostrFilter(
                        kinds = listOf(Nip29.PUT_USER, Nip29.REMOVE_USER),
                        hTags = listOf(ref.groupId),
                        pTags = listOf(ownPubkey),
                    ),
                    ref.relayUrl,
                )
            }
            NostrRepository.subscribeTemporaryRelay(
                metadataSub,
                NostrFilter(kinds = listOf(Nip29.METADATA), dTags = listOf(ref.groupId), limit = 1),
                ref.relayUrl,
            )
        }
    }

    private suspend fun fetchMetadata(ref: GroupRef): Nip29GroupMetadata = coroutineScope {
        val subId = "nip29-verify-${ref.key.hashCode().toUInt().toString(16)}"
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(10_000L) {
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
                ?: error("グループメタデータを確認できません")
        } finally {
            result.cancel()
            NostrRepository.closeTemporaryRelay(subId)
        }
    }

    private fun update(ref: GroupRef, transform: (Nip29GroupListItem) -> Nip29GroupListItem) {
        val current = items[ref.key] ?: return
        items[ref.key] = transform(current)
        emit()
    }

    private fun removeSavedLocal(ref: GroupRef) {
        locallyRemovedKeys += ref.key
        items.remove(ref.key)
        unreadMessageIds.remove(ref.key)
        publicItems[ref.key]?.let { public ->
            publicItems[ref.key] = public.copy(isSaved = false)
        }
        emit()
    }

    private fun emit() {
        _state.value = _state.value.copy(
            groups = items.values
                .filter(::canShowSavedGroup)
                .sortedByDescending { it.latestMessage?.createdAt ?: 0 },
            publicGroups = publicItems.values.sortedWith(
                compareBy<Nip29PublicGroupItem> { it.metadata.name.ifBlank { it.ref.groupId }.lowercase() }
                    .thenBy { it.ref.relayUrl },
            ),
        )
    }

    private fun canShowSavedGroup(item: Nip29GroupListItem): Boolean {
        val metadata = item.metadata ?: return true
        return metadata.canViewInfo(item.membership) ||
            (ownPubkey != null && item.saved.creatorPubkey == ownPubkey) ||
            item.saved.selfAdminGranted
    }

    private fun startPublicDiscovery(relayUrls: List<String>) {
        publicDiscoveryJobs.forEach(Job::cancel)
        publicDiscoveryJobs.clear()
        publicDiscoverySubIds.forEach(NostrRepository::closeTemporaryRelay)
        publicDiscoverySubIds.clear()
        publicItems.clear()
        _state.value = _state.value.copy(
            isLoadingPublic = relayUrls.isNotEmpty(),
            publicRelayErrors = emptyMap(),
        )
        emit()
        if (relayUrls.isEmpty()) return

        val completedRelays = mutableSetOf<String>()
        relayUrls.distinct().forEach { relayUrl ->
            val suffix = relayUrl.hashCode().toUInt().toString(16)
            val subId = "nip29-public-$suffix"
            publicDiscoverySubIds += subId
            publicDiscoveryJobs += launch {
                val relayInfo = RelayInformationRepository.fetch(relayUrl, forceRefresh = true).getOrNull()
                val relaySelf = relayInfo?.self
                val createKeys = mutableSetOf<String>()
                val pendingCreatorMetadata = mutableMapOf<String, Pair<NostrEvent, Nip29GroupMetadata>>()

                fun addPublicGroup(
                    event: NostrEvent,
                    metadata: Nip29GroupMetadata,
                    creatorFallback: Boolean,
                ) {
                    if (!metadata.isPubliclyDiscoverable()) return
                    val ref = runCatching { GroupRef.create(relayUrl, metadata.groupId) }.getOrNull() ?: return
                    val signatureWarning = nip29RelaySignatureWarning(event, relaySelf)
                    publicItems[ref.key] = Nip29PublicGroupItem(
                        ref = ref,
                        metadata = metadata,
                        isSaved = ref.key in items,
                        isRelayVerified = !creatorFallback && relaySelf != null && signatureWarning == null,
                        warning = if (creatorFallback) {
                            "作成者が公開したグループ情報です"
                        } else {
                            signatureWarning
                        },
                    )
                    emit()
                }

                launch(start = CoroutineStart.UNDISPATCHED) {
                    NostrRepository.events(subId).collect { event ->
                        when (event.kind) {
                            Nip29.METADATA -> event.toNip29Metadata()?.let {
                                addPublicGroup(event, it, creatorFallback = false)
                            }
                            Nip29.CREATE_GROUP -> {
                                val groupId = event.tags.firstOrNull { it.firstOrNull() == "h" }
                                    ?.getOrNull(1) ?: return@collect
                                val key = "${event.pubkey}|$groupId"
                                createKeys += key
                                pendingCreatorMetadata.remove(key)?.let { (metadataEvent, metadata) ->
                                    addPublicGroup(metadataEvent, metadata, creatorFallback = true)
                                }
                            }
                            Nip29.EDIT_METADATA -> {
                                val metadata = event.toNip29CreatorMetadata() ?: return@collect
                                val key = "${event.pubkey}|${metadata.groupId}"
                                if (key in createKeys) {
                                    addPublicGroup(event, metadata, creatorFallback = true)
                                } else {
                                    pendingCreatorMetadata[key] = event to metadata
                                }
                            }
                        }
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val completed = withTimeoutOrNull(PUBLIC_DISCOVERY_TIMEOUT_MILLIS) {
                        NostrRepository.eose(subId).first()
                    } != null
                    val error = when {
                        !completed -> "公開グループの取得がタイムアウトしました"
                        else -> null
                    }
                    markPublicRelayCompleted(
                        relayUrl = relayUrl,
                        error = error,
                        completedRelays = completedRelays,
                        expectedCount = relayUrls.distinct().size,
                    )
                }
                NostrRepository.subscribeTemporaryRelay(
                    subscriptionId = subId,
                    filter = NostrFilter(
                        kinds = listOf(Nip29.METADATA, Nip29.CREATE_GROUP, Nip29.EDIT_METADATA),
                        limit = PUBLIC_GROUP_LIMIT,
                    ),
                    relayUrl = relayUrl,
                )
            }
        }
    }

    private fun markPublicRelayCompleted(
        relayUrl: String,
        error: String?,
        completedRelays: MutableSet<String>,
        expectedCount: Int,
    ) {
        completedRelays += relayUrl
        _state.value = _state.value.copy(
            isLoadingPublic = completedRelays.size < expectedCount,
            publicRelayErrors = if (error == null) {
                _state.value.publicRelayErrors
            } else {
                _state.value.publicRelayErrors + (relayUrl to error)
            },
        )
    }

    private suspend fun persistAndPublish() {
        val saved = items.values.map { it.saved }
        Nip29GroupStore.save(ownPubkey, saved)
        if (ownPubkey != null && KeyStorage.loadPrivateKey() != null) {
            runCatching { Nip29GroupRepository.publishGroupList(saved) }
        }
    }

    override fun onCleared() {
        NostrRepository.close(listSubId)
        publicDiscoveryJobs.forEach(Job::cancel)
        publicDiscoverySubIds.forEach(NostrRepository::closeTemporaryRelay)
        items.keys.forEach { key ->
            val suffix = key.hashCode().toUInt().toString(16)
            NostrRepository.closeTemporaryRelay("nip29-meta-$suffix")
            NostrRepository.closeTemporaryRelay("nip29-latest-$suffix")
            NostrRepository.closeTemporaryRelay("nip29-member-$suffix")
        }
        super.onCleared()
    }

    private fun CreateDialogState.toCreation(): Nip29GroupCreation {
        require(name.trim().isNotEmpty()) { "グループ名を入力してください" }
        val ref = GroupRef.create(relayUrl, groupId)
        val imageUrl = picture.trim().takeIf { it.isNotEmpty() }
        if (imageUrl != null) {
            require(imageUrl.startsWith("https://") || imageUrl.startsWith("http://")) {
                "画像URLはhttp://またはhttps://で指定してください"
            }
        }
        return Nip29GroupCreation(
            ref = ref,
            name = name.trim(),
            about = about.trim(),
            picture = imageUrl,
            isPrivate = isPrivate,
            isRestricted = isRestricted,
            isHidden = isHidden,
            isClosed = isClosed,
            supportedKindsMode = supportedKindsMode,
        )
    }

    companion object {
        private const val PUBLIC_DISCOVERY_TIMEOUT_MILLIS = 12_000L
        private const val PUBLIC_GROUP_LIMIT = 200
    }
}
