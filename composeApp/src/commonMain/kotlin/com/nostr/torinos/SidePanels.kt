package com.nostr.torinos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.nostr.torinos.crypto.isWebPlatform

/** PC ブラウザやタブレットの横長画面で、スマホ向けレイアウトを中央の列に収める幅。 */
internal val MaxContentWidth = 640.dp
private val MinSidePanelWidth = 320.dp
private val MaxSidePanelWidth = 480.dp

/** 左右のサイドパネルの幅。null ならサイドパネルを出さず、既存のドロワーと画面遷移を使う。 */
internal val LocalSidePanelWidth = compositionLocalOf<Dp?> { null }

/** Web版で中央の列の左右に [MinSidePanelWidth] 以上の余白が取れるときだけ、パネル幅を返す。 */
internal fun sidePanelWidthFor(screenWidth: Dp): Dp? {
    if (!isWebPlatform) return null
    val available = (screenWidth - MaxContentWidth) / 2
    if (available < MinSidePanelWidth) return null
    return available.coerceAtMost(MaxSidePanelWidth)
}

/**
 * [panelWidth] があれば左・中央・右の3列、なければ [center] だけを全幅で表示する。
 * 幅が変わっても [center] の呼び出し位置を変えず、中央の画面ツリーを作り直さない。
 */
@Composable
internal fun SidePanelLayout(
    panelWidth: Dp?,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
    center: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.Center,
    ) {
        if (panelWidth != null) {
            SidePanelColumn(panelWidth, left)
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Box(
            if (panelWidth != null) {
                Modifier
                    .fillMaxHeight()
                    .width(MaxContentWidth)
                    // 画面外に待機している通知ドロワーなどがサイドパネルに重ならないようにする。
                    .clipToBounds()
                    .background(MaterialTheme.colorScheme.background)
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            center()
        }
        if (panelWidth != null) {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SidePanelColumn(panelWidth, right)
        }
    }
}

@Composable
private fun SidePanelColumn(width: Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .clipToBounds(),
        contentAlignment = Alignment.TopStart,
    ) {
        content()
    }
}

/** 右パネルに表示する画面。 */
internal sealed interface SidePanelDestination {
    data object Settings : SidePanelDestination
    data object MuteList : SidePanelDestination
    data object NgWords : SidePanelDestination
    data object RelaySettings : SidePanelDestination
    data class CustomEmoji(val route: CustomEmojiRoute = CustomEmojiRoute()) : SidePanelDestination
    data class Search(val query: String) : SidePanelDestination
}

internal class SidePanelEntry(
    val id: Int,
    val destination: SidePanelDestination,
) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** 右パネルの履歴。各画面は中央の画面遷移と同じく、履歴から外れたときに ViewModel と保存状態を破棄する。 */
internal class SidePanelNavigator {
    var entries by mutableStateOf<List<SidePanelEntry>>(emptyList())
        private set
    private var nextId = 0
    private val removed = mutableListOf<SidePanelEntry>()

    val current: SidePanelEntry? get() = entries.lastOrNull()

    /** 履歴を置き換えて開く。同じ画面を表示中なら何もしない。 */
    fun openRoot(destination: SidePanelDestination) {
        if (entries.size == 1 && entries.single().destination == destination) return
        replace(listOf(newEntry(destination)))
    }

    fun push(destination: SidePanelDestination) {
        if (current?.destination == destination) return
        entries = entries + newEntry(destination)
    }

    fun back() {
        val last = current ?: return
        discard(last)
        entries = entries.dropLast(1)
    }

    fun clear() {
        replace(emptyList())
    }

    /** パネルを表示していないときに、全画面の ViewModel をすぐ破棄する。 */
    fun disposeAll() {
        (entries + removed).forEach { it.viewModelStore.clear() }
        entries = emptyList()
        removed.clear()
    }

    /** 履歴から外れた画面のうち [keep] 以外を取り出す。表示から外れた後に [SidePanelHost] が破棄する。 */
    fun drainRemoved(keep: SidePanelEntry? = null): List<SidePanelEntry> {
        val drained = removed.filter { it !== keep }
        removed.removeAll(drained)
        return drained
    }

    private fun replace(next: List<SidePanelEntry>) {
        entries.forEach(::discard)
        entries = next
    }

    private fun newEntry(destination: SidePanelDestination) = SidePanelEntry(nextId++, destination)

    private fun discard(entry: SidePanelEntry) {
        removed += entry
    }
}

/** 右パネルの最上位の画面を、その画面専用の ViewModelStore と保存状態で表示する。 */
@Composable
internal fun SidePanelHost(
    navigator: SidePanelNavigator,
    content: @Composable (SidePanelDestination) -> Unit,
) {
    val stateHolder = rememberSaveableStateHolder()
    SlidingSidePanel(value = navigator.current) { displayed ->
        // 閉じるアニメーション中の画面は、表示し終わるまで破棄しない。
        SideEffect {
            navigator.drainRemoved(keep = displayed).forEach { removed ->
                stateHolder.removeState(removed.id)
                removed.viewModelStore.clear()
            }
        }
        if (displayed == null) return@SlidingSidePanel
        stateHolder.SaveableStateProvider(displayed.id) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides displayed) {
                content(displayed.destination)
            }
        }
    }
}

/**
 * [value] があるあいだパネルを表示する。開くときは左から右へ、閉じるときは右から左へスライドし、
 * 閉じるアニメーション中は最後の値を表示し続ける。[content] には表示中の値を渡し、閉じ終わると null を渡す。
 */
@Composable
internal fun <T : Any> SlidingSidePanel(
    value: T?,
    content: @Composable (displayed: T?) -> Unit,
) {
    val lastValue = remember { RetainedValue<T>() }
    if (value != null) lastValue.value = value
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = value != null
    val isHidden = visibleState.isIdle && !visibleState.currentState
    val displayed = if (isHidden) null else value ?: lastValue.value
    if (isHidden) lastValue.value = null
    AnimatedVisibility(
        visibleState = visibleState,
        enter = slideInHorizontally(animationSpec = tween(SidePanelAnimationMillis)) { -it },
        exit = slideOutHorizontally(animationSpec = tween(SidePanelAnimationMillis)) { -it },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            content(displayed)
        }
    }
    if (isHidden) content(null)
}

private class RetainedValue<T : Any> {
    var value: T? = null
}

private const val SidePanelAnimationMillis = 250

@Composable
internal fun rememberSidePanelNavigator(): SidePanelNavigator {
    val navigator = remember { SidePanelNavigator() }
    // セッション終了で破棄されるときに、残っている画面の ViewModel を片付ける。
    DisposableEffect(navigator) {
        onDispose { navigator.disposeAll() }
    }
    return navigator
}
