// iOS Chrome はアドレス欄から開いたときなど、表示だけをアドレスバーの高さ分ずらしたまま戻さないことがある。
// JS から見える scrollY などは 0 のままなので、ずれは検出できない。ページは overflow: hidden で
// スクロールもできないため、アプリのヘッダーがアドレスバーの裏に隠れたままになる。
// 読み込み後に数回、文書を一瞬だけスクロール可能にして 1px 動かして戻し、表示位置を揃え直す。
// initial-hash.js は URL の # による位置合わせを防ぐが、それ以外の経路でも起きるため併用する。
(() => {
    const realign = () => {
        const html = document.documentElement.style;
        const body = document.body.style;
        html.overflow = 'auto';
        body.overflow = 'auto';
        body.height = 'calc(100% + 1px)';
        window.scrollTo(0, 1);
        requestAnimationFrame(() => {
            window.scrollTo(0, 0);
            html.overflow = '';
            body.overflow = '';
            body.height = '';
        });
    };
    const schedule = () => requestAnimationFrame(realign);
    // ずれが起きる時点は読み込みの進み具合で変わるため、起動直後の数回に分けて揃え直す。
    window.addEventListener('load', () => {
        schedule();
        setTimeout(schedule, 500);
        setTimeout(schedule, 2000);
        setTimeout(schedule, 5000);
    });
    window.addEventListener('pageshow', schedule);
    window.addEventListener('popstate', schedule);
})();
