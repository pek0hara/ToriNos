package com.nostr.torinos.ui.group

import com.nostr.torinos.model.GroupRef
import com.nostr.torinos.model.Nip29
import com.nostr.torinos.model.Nip29Admin
import com.nostr.torinos.model.Nip29GroupMetadata
import com.nostr.torinos.model.Nip29GroupCreation
import com.nostr.torinos.model.Nip29Membership
import com.nostr.torinos.model.Nip29Role
import com.nostr.torinos.model.Nip29SupportedKindsMode
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.canViewContent
import com.nostr.torinos.model.canViewInfo
import com.nostr.torinos.model.determineNip29Membership
import com.nostr.torinos.model.nip29RelaySignatureWarning
import com.nostr.torinos.model.toNip29Admins
import com.nostr.torinos.model.toNip29CreatorMetadata
import com.nostr.torinos.model.toNip29Members
import com.nostr.torinos.model.toNip29Metadata
import com.nostr.torinos.model.toNip29Roles
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NgWordStore
import com.nostr.torinos.network.Nip29GroupRepository
import com.nostr.torinos.network.Nip29GroupStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.network.SavedNip29Group
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Nip29GroupViewModel(
    private val ref: GroupRef,
    private val ownPubkey: String?,
) : SafeViewModel() {
    data class UiState(
        val isLoading: Boolean = true,
        val metadata: Nip29GroupMetadata? = null,
        val admins: List<Nip29Admin> = emptyList(),
        val members: List<String>? = null,
        val roles: List<Nip29Role> = emptyList(),
        val messages: List<NostrEvent> = emptyList(),
        val profiles: Map<String, NostrProfile> = emptyMap(),
        val membership: Nip29Membership = Nip29Membership.NOT_JOINED,
        val draft: String = "",
        val isPosting: Boolean = false,
        val canLoadMore: Boolean = false,
        val error: String? = null,
        val actionMessage: String? = null,
        val showJoinDialog: Boolean = false,
        val joinReason: String = "",
        val inviteCode: String = "",
        val showInfoDialog: Boolean = false,
        val metadataWarning: String? = null,
        val isOwner: Boolean = false,
        val showEditDialog: Boolean = false,
        val editName: String = "",
        val editAbout: String = "",
        val editPicture: String = "",
        val editIsPrivate: Boolean = false,
        val editIsRestricted: Boolean = false,
        val editIsHidden: Boolean = false,
        val editIsClosed: Boolean = false,
        val editSupportedKindsMode: Nip29SupportedKindsMode = Nip29SupportedKindsMode.TEXT_CHAT,
        val isEditing: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val isDeleting: Boolean = false,
        val deleted: Boolean = false,
        val canRepairAdmin: Boolean = false,
        val isRepairingAdmin: Boolean = false,
        val canManageGroup: Boolean = false,
        val metadataIsCreatorFallback: Boolean = false,
        val creatorPubkey: String? = null,
    ) {
        val canViewGroupInfo: Boolean
            get() = metadata?.canViewInfo(membership) != false || canManageGroup

        val canViewMessages: Boolean
            get() = metadata?.canViewContent(membership) != false || canManageGroup

        val canPost: Boolean
            get() = metadata?.supportedKinds?.let { Nip29.CHAT_MESSAGE in it } != false &&
                (metadata?.isRestricted != true || membership == Nip29Membership.JOINED)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val suffix = ref.key.hashCode().toUInt().toString(16)
    private val metadataSub = "nip29-detail-meta-$suffix"
    private val creatorMetadataSub = "nip29-detail-creator-meta-$suffix"
    private val messagesSub = "nip29-detail-msg-$suffix"
    private val membershipSub = "nip29-detail-member-$suffix"
    private val ownershipSub = "nip29-detail-owner-$suffix"
    private val profilesSub = "nip29-detail-prof-$suffix"
    private val messages = linkedMapOf<String, NostrEvent>()
    private val moderationEvents = mutableListOf<NostrEvent>()
    private var oldestCreatedAt: Long? = null
    private var profileJob: Job? = null
    private var relaySelf: String? = null
    private var hasLocalAdminGrant: Boolean = false
    private var messagesSubscribed: Boolean = false

    init {
        start()
    }

    fun onDraftChange(value: String) {
        _state.value = _state.value.copy(draft = value, error = null, actionMessage = null)
    }

    fun sendMessage() {
        val current = _state.value
        val text = current.draft.trim()
        if (text.isEmpty() || current.isPosting || !current.canPost) return
        _state.value = current.copy(isPosting = true, error = null, actionMessage = null)
        launch {
            runCatching {
                Nip29GroupRepository.publishChat(ref, text, messages.values.sortedByDescending { it.createdAt })
            }.onSuccess { result ->
                if (result.accepted) {
                    _state.value = _state.value.copy(draft = "", isPosting = false)
                } else {
                    _state.value = _state.value.copy(
                        isPosting = false,
                        error = result.message.ifBlank { "リレーが投稿を拒否しました" },
                    )
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    isPosting = false,
                    error = it.message ?: "投稿に失敗しました",
                )
            }
        }
    }

    fun showJoinDialog() {
        _state.value = _state.value.copy(showJoinDialog = true, actionMessage = null)
    }

    fun dismissJoinDialog() {
        _state.value = _state.value.copy(showJoinDialog = false)
    }

    fun onJoinReasonChange(value: String) {
        _state.value = _state.value.copy(joinReason = value)
    }

    fun onInviteCodeChange(value: String) {
        _state.value = _state.value.copy(inviteCode = value)
    }

    fun requestJoin() {
        val current = _state.value
        if (current.metadata?.isClosed == true && current.inviteCode.isBlank()) {
            _state.value = current.copy(error = "このグループへの参加には招待コードが必要です")
            return
        }
        launch {
            runCatching {
                Nip29GroupRepository.requestJoin(
                    ref = ref,
                    reason = current.joinReason,
                    inviteCode = current.inviteCode,
                )
            }.onSuccess { result ->
                val membership = when {
                    result.accepted -> Nip29Membership.JOINED
                    "duplicate:" in result.message.lowercase() -> Nip29Membership.JOINED
                    "invite" in result.message.lowercase() || "code" in result.message.lowercase() ->
                        Nip29Membership.INVITE_REQUIRED
                    "pending" in result.message.lowercase() -> Nip29Membership.PENDING
                    else -> Nip29Membership.REJECTED
                }
                _state.value = _state.value.copy(
                    membership = membership,
                    showJoinDialog = false,
                    actionMessage = result.message.ifBlank {
                        if (result.accepted) "参加申請を送信しました" else "参加申請が拒否されました"
                    },
                    error = if (membership == Nip29Membership.REJECTED) result.message else null,
                )
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "参加申請に失敗しました")
            }
        }
    }

    fun leave() {
        launch {
            runCatching { Nip29GroupRepository.leave(ref) }
                .onSuccess { result ->
                    if (result.accepted) {
                        val remaining = Nip29GroupStore.load(ownPubkey).filterNot { it.ref == ref }
                        Nip29GroupStore.save(ownPubkey, remaining)
                        runCatching { Nip29GroupRepository.publishGroupList(remaining) }
                        _state.value = _state.value.copy(
                            membership = Nip29Membership.NOT_JOINED,
                            actionMessage = "グループから退出しました",
                        )
                    } else {
                        _state.value = _state.value.copy(
                            error = result.message.ifBlank { "退出要求が拒否されました" },
                        )
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(error = it.message ?: "退出に失敗しました")
                }
        }
    }

    fun loadMore() {
        val until = oldestCreatedAt?.minus(1) ?: return
        launch {
            NostrRepository.subscribeTemporaryRelay(
                messagesSub,
                NostrFilter(
                    kinds = listOf(Nip29.CHAT_MESSAGE),
                    hTags = listOf(ref.groupId),
                    until = until,
                    limit = PAGE_SIZE,
                ),
                ref.relayUrl,
            )
        }
    }

    fun showInfo() {
        _state.value = _state.value.copy(showInfoDialog = true)
    }

    fun dismissInfo() {
        _state.value = _state.value.copy(showInfoDialog = false)
    }

    fun repairAdmin() {
        val current = _state.value
        if (!current.canRepairAdmin || current.isRepairingAdmin || ownPubkey == null) return
        _state.value = current.copy(isRepairingAdmin = true, error = null, actionMessage = null)
        launch {
            runCatching { Nip29GroupRepository.grantSelfAdmin(ref) }
                .onSuccess { result ->
                    if (result.accepted) {
                        persistLocalAdminGrant()
                        _state.value = _state.value.copy(
                            isRepairingAdmin = false,
                            canRepairAdmin = false,
                            canManageGroup = true,
                            admins = upsertOwnAdmin(_state.value.admins),
                            actionMessage = "管理者権限の修復イベントを送信しました",
                        )
                    } else {
                        _state.value = _state.value.copy(
                            isRepairingAdmin = false,
                            error = result.message.ifBlank { "管理者権限の修復がリレーに拒否されました" },
                        )
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isRepairingAdmin = false,
                        error = it.message ?: "管理者権限を修復できません",
                    )
                }
        }
    }

    fun showEdit() {
        val current = _state.value
        if (!current.canEditMetadata()) return
        val metadata = current.metadata ?: return
        _state.value = _state.value.copy(
            showInfoDialog = false,
            showEditDialog = true,
            editName = metadata.name,
            editAbout = metadata.about,
            editPicture = metadata.picture.orEmpty(),
            editIsPrivate = metadata.isPrivate,
            editIsRestricted = metadata.isRestricted,
            editIsHidden = metadata.isHidden,
            editIsClosed = metadata.isClosed,
            editSupportedKindsMode = metadata.supportedKinds.toMode(),
            error = null,
        )
    }

    fun dismissEdit() {
        if (!_state.value.isEditing) {
            _state.value = _state.value.copy(showEditDialog = false)
        }
    }

    fun updateEdit(
        name: String? = null,
        about: String? = null,
        picture: String? = null,
        isPrivate: Boolean? = null,
        isRestricted: Boolean? = null,
        isHidden: Boolean? = null,
        isClosed: Boolean? = null,
        supportedKindsMode: Nip29SupportedKindsMode? = null,
    ) {
        val current = _state.value
        if (!current.showEditDialog || current.isEditing) return
        _state.value = current.copy(
            editName = name ?: current.editName,
            editAbout = about ?: current.editAbout,
            editPicture = picture ?: current.editPicture,
            editIsPrivate = isPrivate ?: current.editIsPrivate,
            editIsRestricted = isRestricted ?: current.editIsRestricted,
            editIsHidden = isHidden ?: current.editIsHidden,
            editIsClosed = isClosed ?: current.editIsClosed,
            editSupportedKindsMode = supportedKindsMode ?: current.editSupportedKindsMode,
            error = null,
        )
    }

    fun saveEdit() {
        val current = _state.value
        if (!current.canEditMetadata() || current.isEditing) return
        val creation = runCatching { current.toEditedCreation() }.getOrElse {
            _state.value = current.copy(error = it.message ?: "入力内容を確認してください")
            return
        }
        _state.value = current.copy(isEditing = true, error = null)
        launch {
            runCatching { Nip29GroupRepository.editGroup(creation) }
                .onSuccess { result ->
                    if (result.accepted) {
                        val metadata = creation.toMetadata()
                        val groups = Nip29GroupStore.load(ownPubkey).map {
                            if (it.ref == ref) it.copy(name = creation.name) else it
                        }
                        Nip29GroupStore.save(ownPubkey, groups)
                        runCatching { Nip29GroupRepository.publishGroupList(groups) }
                        _state.value = _state.value.copy(
                            metadata = metadata,
                            metadataIsCreatorFallback = true,
                            metadataWarning = "作成者が公開したグループ情報です",
                            showEditDialog = false,
                            isEditing = false,
                            actionMessage = "グループ情報を更新しました",
                        )
                    } else {
                        _state.value = _state.value.copy(
                            isEditing = false,
                            error = result.message.ifBlank {
                                "グループ情報の更新が拒否されました。リレー側で管理者として扱われていない可能性があります。"
                            },
                        )
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isEditing = false,
                        error = it.message ?: "グループ情報を更新できません",
                    )
                }
        }
    }

    fun showDelete() {
        if (_state.value.canManageGroup) {
            _state.value = _state.value.copy(showInfoDialog = false, showDeleteDialog = true, error = null)
        }
    }

    fun dismissDelete() {
        if (!_state.value.isDeleting) {
            _state.value = _state.value.copy(showDeleteDialog = false)
        }
    }

    fun deleteGroup() {
        val current = _state.value
        if (!current.canManageGroup || current.isDeleting) return
        _state.value = current.copy(isDeleting = true, error = null)
        launch {
            runCatching { Nip29GroupRepository.deleteGroup(ref) }
                .onSuccess { result ->
                    if (result.accepted) {
                        val remaining = Nip29GroupStore.remove(ownPubkey, ref)
                        runCatching { Nip29GroupRepository.publishGroupList(remaining) }
                        _state.value = _state.value.copy(
                            isDeleting = false,
                            showDeleteDialog = false,
                            deleted = true,
                        )
                    } else {
                        _state.value = _state.value.copy(
                            isDeleting = false,
                            error = result.message.ifBlank { "グループの削除が拒否されました" },
                        )
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isDeleting = false,
                        error = it.message ?: "グループを削除できません",
                    )
                }
        }
    }

    private fun start() {
        launch {
            if (ownPubkey != null) {
                val saved = Nip29GroupStore.load(ownPubkey).firstOrNull { it.ref == ref }
                hasLocalAdminGrant = saved?.selfAdminGranted == true
                saved?.creatorPubkey?.let { creator ->
                    _state.value = _state.value.copy(creatorPubkey = creator)
                }
                if (saved?.creatorPubkey == ownPubkey || saved?.createEventId != null) {
                    markAsOwner()
                }
                if (hasLocalAdminGrant) {
                    _state.value = _state.value.copy(
                        admins = upsertOwnAdmin(_state.value.admins),
                        canRepairAdmin = false,
                        canManageGroup = true,
                    )
                }
            }
            relaySelf = RelayInformationRepository.fetch(ref.relayUrl, forceRefresh = true)
                .getOrNull()?.self
            collectEvents()
            NostrRepository.subscribeTemporaryRelay(
                metadataSub,
                NostrFilter(
                    kinds = listOf(Nip29.METADATA, Nip29.ADMINS, Nip29.MEMBERS, Nip29.ROLES),
                    dTags = listOf(ref.groupId),
                ),
                ref.relayUrl,
            )
            if (ownPubkey != null) {
                NostrRepository.subscribeTemporaryRelay(
                    creatorMetadataSub,
                    NostrFilter(
                        kinds = listOf(Nip29.EDIT_METADATA),
                        authors = listOf(ownPubkey),
                        hTags = listOf(ref.groupId),
                        limit = 1,
                    ),
                    ref.relayUrl,
                )
            }
            if (ownPubkey != null) {
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
                ownershipSub,
                NostrFilter(
                    kinds = listOf(Nip29.CREATE_GROUP),
                    hTags = listOf(ref.groupId),
                    limit = 1,
                ),
                ref.relayUrl,
            )
        }
    }

    private fun collectEvents() {
        launch {
            NostrRepository.events(metadataSub).collect { event ->
                when (event.kind) {
                    Nip29.METADATA -> {
                        _state.value = _state.value.copy(
                            metadata = event.toNip29Metadata(),
                            metadataIsCreatorFallback = false,
                            metadataWarning = nip29RelaySignatureWarning(event, relaySelf),
                            error = null,
                        )
                        updateMessageSubscription()
                    }
                    Nip29.ADMINS -> {
                        val relayAdmins = event.toNip29Admins()
                        val admins = if (hasLocalAdminGrant) upsertOwnAdmin(relayAdmins) else relayAdmins
                        _state.value = _state.value.copy(
                            admins = admins,
                            canRepairAdmin = _state.value.canRepairAdmin && !admins.hasOwnAdmin() && !hasLocalAdminGrant,
                            canManageGroup = _state.value.canManageGroup || admins.hasOwnAdmin(),
                        )
                        scheduleProfiles()
                    }
                    Nip29.MEMBERS -> _state.value = _state.value.copy(members = event.toNip29Members())
                    Nip29.ROLES -> _state.value = _state.value.copy(roles = event.toNip29Roles())
                }
            }
        }
        launch {
            NostrRepository.events(creatorMetadataSub).collect { event ->
                val metadata = event.toNip29CreatorMetadata() ?: return@collect
                if (metadata.groupId != ref.groupId) return@collect
                _state.value = _state.value.copy(
                    metadata = metadata,
                    metadataIsCreatorFallback = true,
                    metadataWarning = "作成者が公開したグループ情報です",
                    error = null,
                )
                updateMessageSubscription()
            }
        }
        launch {
            NostrRepository.events(messagesSub).collect { event ->
                messages[event.id] = event
                oldestCreatedAt = minOf(oldestCreatedAt ?: event.createdAt, event.createdAt)
                syncMessages()
                scheduleProfiles()
                Nip29GroupStore.markRead(ownPubkey, ref, event.createdAt)
            }
        }
        launch {
            NostrRepository.endOfStoredEvents(messagesSub).collect {
                _state.value = _state.value.copy(
                    isLoading = false,
                    canLoadMore = messages.size >= PAGE_SIZE,
                )
            }
        }
        launch {
            NostrRepository.closedMessages(messagesSub).collect { closed ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = closed.message.ifBlank { "グループメッセージを取得できません" },
                )
            }
        }
        if (ownPubkey != null) {
            launch {
                NostrRepository.events(membershipSub).collect { event ->
                    moderationEvents += event
                    if (event.grantsOwnAdmin()) {
                        persistLocalAdminGrant()
                    }
                    _state.value = _state.value.copy(
                        membership = determineNip29Membership(moderationEvents, ownPubkey),
                        admins = if (hasLocalAdminGrant) upsertOwnAdmin(_state.value.admins) else _state.value.admins,
                        canRepairAdmin = _state.value.canRepairAdmin && !hasLocalAdminGrant,
                        canManageGroup = _state.value.canManageGroup || hasLocalAdminGrant,
                    )
                    updateMessageSubscription()
                    if (hasLocalAdminGrant) scheduleProfiles()
                }
            }
        }
        launch {
            NostrRepository.events(ownershipSub).collect { event ->
                _state.value = _state.value.copy(creatorPubkey = event.pubkey)
                scheduleProfiles()
                if (ownPubkey != null && event.pubkey.equals(ownPubkey, ignoreCase = true)) {
                    markAsOwner()
                }
            }
        }
        launch {
            NostrRepository.events(profilesSub).collect { event ->
                val profile = event.toProfile() ?: return@collect
                _state.value = _state.value.copy(
                    profiles = _state.value.profiles + (event.pubkey to profile),
                )
            }
        }
        launch {
            MuteStore.mutedPubkeys.collect { syncMessages() }
        }
        launch {
            NgWordStore.ngWords.collect { syncMessages() }
        }
    }

    private fun syncMessages() {
        val filtered = messages.values
            .filterNot { MuteStore.isMuted(it.pubkey) || NgWordStore.matches(it.content) }
            .sortedByDescending { it.createdAt }
        _state.value = _state.value.copy(messages = filtered)
    }

    private fun updateMessageSubscription() {
        val current = _state.value
        val metadata = current.metadata ?: return
        if (metadata.canViewContent(current.membership) || current.canManageGroup) {
            if (!messagesSubscribed) {
                messagesSubscribed = true
                launch {
                    NostrRepository.subscribeTemporaryRelay(
                        messagesSub,
                        NostrFilter(
                            kinds = listOf(Nip29.CHAT_MESSAGE),
                            hTags = listOf(ref.groupId),
                            limit = PAGE_SIZE,
                        ),
                        ref.relayUrl,
                    )
                }
            }
        } else {
            if (messagesSubscribed) {
                NostrRepository.closeTemporaryRelay(messagesSub)
                messagesSubscribed = false
            }
            messages.clear()
            oldestCreatedAt = null
            _state.value = current.copy(messages = emptyList(), isLoading = false, canLoadMore = false)
        }
    }

    private fun scheduleProfiles() {
        profileJob?.cancel()
        profileJob = launch {
            delay(250)
            val missing = (
                messages.values.map { it.pubkey } +
                    listOfNotNull(_state.value.creatorPubkey) +
                    _state.value.admins.map { it.pubkey }
                ).toSet() - _state.value.profiles.keys
            if (missing.isNotEmpty()) {
                NostrRepository.subscribe(
                    profilesSub,
                    NostrFilter(kinds = listOf(0), authors = missing.toList()),
                )
            }
        }
    }

    private suspend fun markAsOwner() {
        if (_state.value.isOwner || ownPubkey == null) return
        val hasOwnAdmin = _state.value.admins.hasOwnAdmin()
        _state.value = _state.value.copy(
            isOwner = true,
            canRepairAdmin = !hasOwnAdmin && !hasLocalAdminGrant,
            canManageGroup = true,
        )
        updateMessageSubscription()
        val current = Nip29GroupStore.load(ownPubkey)
        val groups = if (current.any { it.ref == ref }) {
            current.map { if (it.ref == ref) it.copy(creatorPubkey = ownPubkey) else it }
        } else {
            current + SavedNip29Group(ref = ref, creatorPubkey = ownPubkey)
        }
        Nip29GroupStore.save(ownPubkey, groups)
    }

    private suspend fun persistLocalAdminGrant() {
        val pubkey = ownPubkey ?: return
        hasLocalAdminGrant = true
        val current = Nip29GroupStore.load(pubkey)
        val groups = if (current.any { it.ref == ref }) {
            current.map {
                if (it.ref == ref) {
                    it.copy(creatorPubkey = it.creatorPubkey ?: pubkey, selfAdminGranted = true)
                } else {
                    it
                }
            }
        } else {
            current + SavedNip29Group(
                ref = ref,
                creatorPubkey = pubkey,
                selfAdminGranted = true,
            )
        }
        Nip29GroupStore.save(pubkey, groups)
    }

    private fun UiState.toEditedCreation(): Nip29GroupCreation {
        require(editName.trim().isNotEmpty()) { "グループ名を入力してください" }
        val imageUrl = editPicture.trim().takeIf { it.isNotEmpty() }
        if (imageUrl != null) {
            require(imageUrl.startsWith("https://") || imageUrl.startsWith("http://")) {
                "画像URLはhttp://またはhttps://で指定してください"
            }
        }
        return Nip29GroupCreation(
            ref = ref,
            name = editName.trim(),
            about = editAbout.trim(),
            picture = imageUrl,
            isPrivate = editIsPrivate,
            isRestricted = editIsRestricted,
            isHidden = editIsHidden,
            isClosed = editIsClosed,
            supportedKindsMode = editSupportedKindsMode,
        )
    }

    private fun UiState.canEditMetadata(): Boolean =
        canManageGroup || hasLocalAdminGrant ||
            isOwner ||
            (ownPubkey != null && admins.any { it.pubkey.equals(ownPubkey, ignoreCase = true) })

    private fun List<Nip29Admin>.hasOwnAdmin(): Boolean =
        ownPubkey != null && any { it.pubkey.equals(ownPubkey, ignoreCase = true) }

    private fun upsertOwnAdmin(admins: List<Nip29Admin>): List<Nip29Admin> {
        val pubkey = ownPubkey ?: return admins
        if (admins.hasOwnAdmin()) return admins
        return admins + Nip29Admin(pubkey, listOf("admin"))
    }

    private fun NostrEvent.grantsOwnAdmin(): Boolean {
        val pubkey = ownPubkey ?: return false
        if (kind != Nip29.PUT_USER) return false
        return tags.any { tag ->
            tag.firstOrNull() == "p" &&
                tag.getOrNull(1)?.equals(pubkey, ignoreCase = true) == true &&
                tag.drop(2).any { it.equals("admin", ignoreCase = true) }
        }
    }

    private fun Nip29GroupCreation.toMetadata() = Nip29GroupMetadata(
        groupId = ref.groupId,
        name = name,
        picture = picture,
        about = about,
        isPrivate = isPrivate,
        isRestricted = isRestricted,
        isHidden = isHidden,
        isClosed = isClosed,
        supportedKinds = when (supportedKindsMode) {
            Nip29SupportedKindsMode.ALL -> null
            Nip29SupportedKindsMode.TEXT_CHAT -> setOf(Nip29.CHAT_MESSAGE)
            Nip29SupportedKindsMode.NONE -> emptySet()
        },
    )

    private fun Set<Int>?.toMode(): Nip29SupportedKindsMode = when {
        this == null -> Nip29SupportedKindsMode.ALL
        Nip29.CHAT_MESSAGE in this -> Nip29SupportedKindsMode.TEXT_CHAT
        else -> Nip29SupportedKindsMode.NONE
    }

    override fun onCleared() {
        NostrRepository.closeTemporaryRelay(metadataSub)
        NostrRepository.closeTemporaryRelay(creatorMetadataSub)
        NostrRepository.closeTemporaryRelay(messagesSub)
        NostrRepository.closeTemporaryRelay(membershipSub)
        NostrRepository.closeTemporaryRelay(ownershipSub)
        NostrRepository.close(profilesSub)
        super.onCleared()
    }

    companion object {
        private const val PAGE_SIZE = 50
    }
}
