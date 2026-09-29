package com.nostr.torinos.ui.components

/**
 * ソフトウェアキーボード表示中の Visual Viewport に Compose のルートを合わせる。
 *
 * モバイルブラウザの既定動作では、キーボードが Layout Viewport を変えずに Visual Viewport だけを
 * 縮めることがある。ComposeViewport はホスト要素の clientWidth/clientHeight をレイアウト領域に使うため、
 * そのままでは Scaffold の bottomBar がキーボードの裏に残る。
 *
 * `interactive-widget=resizes-content` を解釈するブラウザでは Layout Viewport 自体が縮むので、この処理は
 * 何もしない。解釈しない iOS Safari などでは、テキスト入力にフォーカスがあり、Visual Viewport が
 * 十分縮んでいる間だけホスト要素を可視領域へ合わせる。通常のブラウザUI開閉やピンチズームでは
 * アプリを再レイアウトしない。
 */
internal fun installVisualViewportBridge(viewportContainerId: String) {
    installVisualViewportBridgeJs(viewportContainerId, SOFTWARE_KEYBOARD_MIN_HEIGHT_PX)
}

private fun installVisualViewportBridgeJs(viewportContainerId: String, keyboardMinHeight: Int) {
    js(
        """
        (() => {
            const container = document.getElementById(viewportContainerId);
            const viewport = window.visualViewport;
            if (!container || !viewport) return;

            const isTextInput = (el) => !!el &&
                (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
            const deepActiveElement = () => {
                let el = document.activeElement;
                while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
                return el;
            };

            let animationFrame = 0;
            let lastPosition = '';
            let lastSize = '';

            const applyLayout = () => {
                animationFrame = 0;

                // Compose の backing textarea は Shadow DOM 内にあるため、activeElement を深く辿る。
                // フォーカス条件を付け、ピンチズームによる Visual Viewport の縮小とは区別する。
                // iOS Safari はキーボード表示中に window.innerHeight も Visual Viewport と同じだけ縮めるため、
                // 縮まない Layout Viewport の高さ(documentElement.clientHeight)と比べる。
                const keyboardVisible = isTextInput(deepActiveElement()) &&
                    document.documentElement.clientHeight - viewport.height > keyboardMinHeight;
                const layout = keyboardVisible
                    ? [viewport.offsetLeft, viewport.offsetTop, viewport.width, viewport.height]
                    : [0, 0, null, null];
                // #compose-root は position: fixed なので、Layout Viewport 基準の offsetLeft/offsetTop をそのまま使える。
                const position = layout[0] + ',' + layout[1];
                const size = layout[2] + ',' + layout[3];

                if (position !== lastPosition) {
                    lastPosition = position;
                    container.style.left = layout[0] + 'px';
                    container.style.top = layout[1] + 'px';
                }

                // 位置だけの変化(キーボード表示中の Visual Viewport のパン)では再レイアウトしない。
                if (size === lastSize) return;
                lastSize = size;
                container.style.width = layout[2] === null ? '100%' : layout[2] + 'px';
                container.style.height = layout[3] === null ? '100%' : layout[3] + 'px';

                // ComposeViewport 1.12 は window.resize でのみホスト要素の clientSize を読み直す。
                // VisualViewport.resize は別イベントなので、サイズ反映後に再計測を依頼する。
                window.dispatchEvent(new Event('resize'));
            };

            const scheduleLayout = () => {
                if (animationFrame !== 0) return;
                animationFrame = window.requestAnimationFrame(applyLayout);
            };

            viewport.addEventListener('resize', scheduleLayout, { passive: true });
            viewport.addEventListener('scroll', scheduleLayout, { passive: true });
            scheduleLayout();
        })()
        """,
    )
}
