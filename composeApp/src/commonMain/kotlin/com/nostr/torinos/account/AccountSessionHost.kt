package com.nostr.torinos.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

val LocalAccountSession = staticCompositionLocalOf<AccountSession?> { null }

private class SessionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    fun clear() {
        viewModelStore.clear()
    }
}

/** sessionId が変わるたびに、Compose state と配下の全 ViewModel を破棄する。 */
@Composable
fun AccountSessionHost(
    sessionId: String,
    session: AccountSession?,
    content: @Composable () -> Unit,
) {
    val owner = remember(sessionId) { SessionViewModelStoreOwner() }
    LaunchedEffect(sessionId, session) {
        session?.startRepositories()
    }
    DisposableEffect(owner) {
        onDispose { owner.clear() }
    }
    CompositionLocalProvider(
        LocalViewModelStoreOwner provides owner,
        LocalAccountSession provides session,
    ) {
        key(sessionId) { content() }
    }
}

/** CompositionLocalから読み取ったセッションをViewModel生成時に固定する。 */
@Composable
inline fun <reified VM : ViewModel> accountSessionViewModel(
    key: String,
    crossinline initializer: (AccountSession?) -> VM,
): VM {
    val session = LocalAccountSession.current
    return viewModel(key = key) { initializer(session) }
}
