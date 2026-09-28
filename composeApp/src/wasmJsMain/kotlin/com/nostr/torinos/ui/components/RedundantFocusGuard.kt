package com.nostr.torinos.ui.components

/**
 * すでにフォーカスがある要素への focus() を無視する。
 *
 * Compose Multiplatform 1.12 は入力欄のタップ中に隠し textarea へ focus() した後、
 * 次のフレーム(requestAnimationFrame)でもう一度 focus() する。iOS Safari 27 以降は後の focus 要求が
 * 先の要求を置き換え、タップ外の要求ではキーボードを出さないため、Compose の入力欄でキーボードが出ない。
 * Compose 側の修正(compose-multiplatform-core #3446、2026-09-25)と同じく、フォーカス済みなら呼ばない。
 * 仕様上もフォーカス済みの要素への focus() は何もしないため、ほかの要素への影響はない。
 * Compose を #3446 を含む版へ上げたら削除する。
 */
internal fun installRedundantFocusGuard() {
    js(
        """
        (() => {
            const focus = HTMLElement.prototype.focus;
            HTMLElement.prototype.focus = function (options) {
                const root = this.getRootNode();
                if (root && root.activeElement === this) return;
                return focus.call(this, options);
            };
        })()
        """,
    )
}
