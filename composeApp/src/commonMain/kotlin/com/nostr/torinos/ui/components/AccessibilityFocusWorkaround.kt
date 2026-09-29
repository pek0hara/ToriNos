package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 入力欄のフォーカスを、支援技術が動いていないときだけアクセシビリティへ伝えない。
 *
 * iOSのCompose（1.12時点、jb-mainも同じ）は、キーボードを閉じた直後などにアクセシビリティ同期を
 * 一時的に動かす。その間にフォーカス中の入力欄を見つけると「フォーカスを維持する要素」として記録し、
 * 入力欄が画面から消えても記録を消さない。記録がある間は同期を止めないため、以後スクロールのたびに
 * 意味ツリー全体を同期し続け、フィードのスクロールが重くなる（`AccessibilityMediator`）。
 * VoiceOverなどが動いているときは、フォーカスを伝えないと読み上げ位置が追従しないので何もしない。
 */
@Composable
internal expect fun Modifier.avoidStaleAccessibilityFocus(): Modifier
