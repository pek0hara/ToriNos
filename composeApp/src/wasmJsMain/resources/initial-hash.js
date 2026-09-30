// iOS Chrome は URL に # 付きで開くと、ページ内のその位置へスクロールしようとして、表示だけを
// アドレスバーの高さ分ずらす。JS から見える scrollY などは 0 のままで、ページは overflow: hidden なので
// スクロールしても戻らず、アプリのヘッダーがアドレスバーの裏に隠れたままになる。
// 読み込み中は URL から # を外して、この位置合わせを起こさせない。load 後に戻し、画面遷移
// (bindToBrowserNavigation) は戻った後で URL を読む。replaceState はスクロールを起こさない。
(() => {
    const hash = location.hash;
    if (!hash) return;
    history.replaceState(history.state, '', location.pathname + location.search);
    window.addEventListener('load', () => {
        history.replaceState(history.state, '', location.pathname + location.search + hash);
    }, { once: true });
})();
