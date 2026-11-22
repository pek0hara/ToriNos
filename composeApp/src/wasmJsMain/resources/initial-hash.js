// iOS Chrome は URL に # 付きで開くと、ページ内のその位置へスクロールしようとして、表示だけを
// アドレスバーの高さ分ずらす。JS から見える scrollY などは 0 のままで、ページは overflow: hidden なので
// スクロールしても戻らず、アプリのヘッダーがアドレスバーの裏に隠れたままになる。
// 読み込み中は URL から # を外して、この位置合わせを起こさせない。load 後に戻し、画面遷移
// (bindToBrowserNavigation) は戻った後で URL を読む。replaceState はスクロールを起こさない。
//
// 招待リンク (#join?relay=…&code=…、docs/buzz-invite-link-design.md) は戻さない。
// 招待コードを履歴やアドレスバーに残さないよう、ここで取り出して URL から消し、アプリが一度だけ読む。
(() => {
    const hash = location.hash;
    if (!hash) return;
    history.replaceState(history.state, '', location.pathname + location.search);
    if (/^#join\/?\?/i.test(hash)) {
        window.__torinosLaunchInvite = hash;
        return;
    }
    window.addEventListener('load', () => {
        history.replaceState(history.state, '', location.pathname + location.search + hash);
    }, { once: true });
})();
