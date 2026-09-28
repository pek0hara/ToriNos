package com.nostr.torinos.ui.post

import androidx.lifecycle.viewModelScope
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.ui.SafeCoroutineLauncher
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.ui.timeline.SignedPublishResult
import com.nostr.torinos.ui.timeline.StateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

data class DraftDeleteDialogState(
    val draft: DraftMemo,
    val isDeleting: Boolean = false,
    val error: String? = null,
)

data class DraftListState(
    val drafts: List<DraftMemo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val deleteDialog: DraftDeleteDialogState? = null,
)

/** 下書き一覧シート。ジャーナルとは状態を共有しない。 */
class DraftListViewModel internal constructor(
    private val repository: DraftMemoRepository,
) : SafeViewModel() {
    constructor(accountSession: AccountSession?) : this(DraftMemoRepository(accountSession?.signer))

    private val launcher = SafeCoroutineLauncher(viewModelScope, "DraftListViewModel")
    private val store = StateStore(DraftListState())
    val state: StateFlow<DraftListState> = store.state
    private var loadJob: Job? = null

    fun load(relayUrl: String?) {
        loadJob?.cancel()
        store.dispatch { it.copy(isLoading = true, error = null) }
        loadJob = launcher.launch {
            try {
                val drafts = repository.loadAll(relayUrl)
                store.dispatch { current ->
                    if (drafts == null) {
                        current.copy(drafts = emptyList(), isLoading = false, error = "秘密鍵が設定されていません")
                    } else {
                        current.copy(drafts = drafts, isLoading = false, error = null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                store.dispatch {
                    it.copy(isLoading = false, error = e.message ?: "下書きの読み込みに失敗しました")
                }
            }
        }
    }

    fun showDeleteDialog(draft: DraftMemo) {
        store.dispatch { it.copy(deleteDialog = DraftDeleteDialogState(draft)) }
    }

    fun dismissDeleteDialog() {
        store.dispatch { if (it.deleteDialog?.isDeleting == true) it else it.copy(deleteDialog = null) }
    }

    fun deleteSelected() {
        val dialog = store.value.deleteDialog ?: return
        if (dialog.isDeleting) return
        store.dispatch { it.copy(deleteDialog = dialog.copy(isDeleting = true, error = null)) }
        launcher.launch {
            val result = repository.delete(dialog.draft)
            store.dispatch { current ->
                when (result) {
                    is SignedPublishResult.Published -> current.copy(
                        drafts = current.drafts.filterNot { it.eventId == dialog.draft.eventId },
                        deleteDialog = null,
                    )
                    SignedPublishResult.MissingSigner -> current.withDeleteError("秘密鍵が設定されていません")
                    is SignedPublishResult.Failed -> current.withDeleteError(
                        result.cause.message ?: "下書きの削除要求を送信できませんでした",
                    )
                }
            }
        }
    }

    private fun DraftListState.withDeleteError(message: String): DraftListState =
        copy(deleteDialog = deleteDialog?.copy(isDeleting = false, error = message))
}
