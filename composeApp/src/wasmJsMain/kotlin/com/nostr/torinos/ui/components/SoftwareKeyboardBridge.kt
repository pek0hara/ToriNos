package com.nostr.torinos.ui.components

/**
 * iOS Safari で、自動フォーカスされた Compose の入力欄をタップしてもキーボードが出ない問題を回避する。
 *
 * Compose は入力欄の裏に隠し textarea を置き、入力欄にフォーカスが来るとそれへ focus() する。
 * iOS Safari はタップ中に呼ばれた focus() でしかキーボードを出さない。そのため投稿画面のように
 * 画面を開いた時点で自動フォーカスする入力欄は、textarea にフォーカスはあるのにキーボードが出ない。
 * さらに Compose は入力中の textarea へ focus() し直さないので、その後タップしても出ないままになる。
 *
 * そこで touchend の時点で、タップ位置が Compose のアクセシビリティ DOM 上の入力欄(role="textbox")で、
 * タップ前からフォーカスがあるのにキーボードが出ていなければ、タップ中に focus し直す。
 * タップで新しくフォーカスした入力欄は Compose がタップ中に focus() するため、ここでは触らない。
 * Compose は textarea の blur を監視していないため、状態は変わらない。
 * 次フレームの focus() が上書きしないよう、[installRedundantFocusGuard] と併せて使う。
 */
internal fun installSoftwareKeyboardBridge() {
    installSoftwareKeyboardBridgeJs(TAP_SLOP_PX, KEYBOARD_MIN_HEIGHT_PX)
}

// これ以上指が動いたらスクロールとみなし、入力欄のタップとして扱わない。
private const val TAP_SLOP_PX = 10

// 表示領域がこれ以上狭まっていれば、キーボードが出ているとみなす。
private const val KEYBOARD_MIN_HEIGHT_PX = 100

private fun installSoftwareKeyboardBridgeJs(tapSlop: Int, keyboardMinHeight: Int) {
    js(
        """
        (() => {
            const isIos = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
                (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
            if (!isIos) return;

            const isTextInput = (el) => !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
            const deepActiveElement = () => {
                let el = document.activeElement;
                while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
                return el;
            };
            const isTextboxAt = (x, y) => {
                // Compose は Dialog / BottomSheet ごとに Semantics の Shadow Root を入れ子にすることがある。
                // document 直下の Shadow Root だけでなく、すべての階層を辿って入力欄を探す。
                const roots = [document];
                for (let index = 0; index < roots.length; index += 1) {
                    const root = roots[index];
                    for (const box of root.querySelectorAll('[role="textbox"]')) {
                        const r = box.getBoundingClientRect();
                        if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true;
                    }
                    for (const host of root.querySelectorAll('*')) {
                        if (host.shadowRoot) roots.push(host.shadowRoot);
                    }
                }
                return false;
            };
            // iOS はキーボード表示で window.innerHeight を変えず、visualViewport だけが縮む。
            const isKeyboardVisible = () =>
                !!window.visualViewport && window.innerHeight - window.visualViewport.height > keyboardMinHeight;

            let start = null;
            document.addEventListener('touchstart', (e) => {
                const t = e.touches[0];
                start = e.touches.length === 1 && t ? { x: t.clientX, y: t.clientY, active: deepActiveElement() } : null;
            }, { capture: true, passive: true });

            document.addEventListener('touchend', (e) => {
                const t = e.changedTouches[0];
                const from = start;
                start = null;
                if (!from || !t || e.touches.length > 0) return;
                if (Math.hypot(t.clientX - from.x, t.clientY - from.y) > tapSlop) return;
                // ブラウザ標準の入力欄(秘密鍵の入力欄など)はブラウザに任せる。
                if (isTextInput(e.composedPath()[0])) return;
                const active = deepActiveElement();
                if (!isTextInput(active) || active !== from.active || isKeyboardVisible()) return;
                if (!isTextboxAt(t.clientX, t.clientY)) return;
                active.blur();
                active.focus({ preventScroll: true });
            }, { capture: false, passive: true });
        })()
        """,
    )
}
