package com.nostr.torinos.ui.components

/**
 * IME の確定直後に届く、同じ文字列の insertText を捨てる。
 *
 * Firefox などの Gecko は IME の確定時に compositionend を送った後、同じ文字列で
 * beforeinput(inputType: "insertText", isComposing: false) も送る。Compose Multiplatform 1.12 の
 * NativeInputEventsProcessor はその両方で確定するため、確定した単語が二重に入る(Issue #4)。
 * Safari の insertFromComposition は Compose 側で無視されるが、Gecko 向けの抑止はない。
 *
 * 捨てるのは compositionend の次に届く beforeinput が同じ文字列の insertText だったときだけ。
 * 間にキー入力やほかの入力が挟まったら待つのをやめ、直接入力した文字は消さない。
 * Compose より先に受け取るため、window のキャプチャ段階で止める。
 * Compose 側で Gecko の重複が直ったら削除する。
 */
internal fun installDuplicateCompositionCommitGuard() {
    js(
        """
        (() => {
            let committed = null;
            window.addEventListener('compositionstart', () => { committed = null; }, true);
            window.addEventListener('compositionend', (event) => {
                committed = event.data || null;
            }, true);
            window.addEventListener('keydown', (event) => {
                if (!event.isComposing && event.keyCode !== 229) committed = null;
            }, true);
            window.addEventListener('beforeinput', (event) => {
                const expected = committed;
                committed = null;
                if (
                    expected !== null &&
                    event.inputType === 'insertText' &&
                    !event.isComposing &&
                    event.data === expected
                ) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                }
            }, true);
        })()
        """,
    )
}
