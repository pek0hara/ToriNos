// iOS 27.2 の WebKit には、JS から Wasm へ渡す f32 の 0 や 1 が NaN になる不具合がある
// (WebKit Bug 329421)。Compose は WebGL の画面を作るところまで進むが、何も描かずに真っ白のままになる。
// エラーは出ず、報告された最小の再現コードでも検出できないため、症状そのもので判定する:
// WebGL のコンテキストができてから一定時間たっても、描画の呼び出しが一度も来なければ止まっているとみなす。
// そのときは、ToriNos のアプリ（torinos://、招待リンクで開いたなら torinos://join?…）で開くリンクを出す。iOS のブラウザはどれも WebKit なので、
// iOS(iPadOS を含む)だけで見張る。描画が後から来たら、案内は消す。
(() => {
    const isIos = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
        (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    if (!isIos) return;

    // 招待リンク（#join?…）で開いたなら、その招待ごとアプリへ渡す（docs/buzz-invite-link-design.md 5.2）。
    // initial-hash.js が取り出した値は、アプリ（Wasm）が読むと消えるので、ここで写しておく。
    const invite = typeof window.__torinosLaunchInvite === 'string' ? window.__torinosLaunchInvite : null;
    const appLink = invite ? 'torinos://' + invite.replace(/^#/, '') : 'torinos://';

    const WAIT_MS = 6000;
    const DRAW_METHODS = ['drawArrays', 'drawElements', 'drawArraysInstanced', 'drawElementsInstanced', 'drawRangeElements'];
    let drawn = false;
    let watching = false;
    let notice = null;

    const onDraw = () => {
        if (drawn) return;
        drawn = true;
        if (notice) {
            notice.remove();
            notice = null;
        }
    };

    // 最初の描画だけを知りたいので、コンテキストに自前のメソッドを重ね、一度呼ばれたら外して元に戻す。
    const watchContext = (gl) => {
        const own = DRAW_METHODS.filter((name) => typeof gl[name] === 'function');
        for (const name of own) {
            const original = gl[name];
            gl[name] = function () {
                for (const n of own) delete gl[n];
                onDraw();
                return original.apply(this, arguments);
            };
        }
    };

    const showNotice = () => {
        if (drawn || notice) return;
        if (document.visibilityState !== 'visible') {
            // 裏にある間はタイマーも描画も止まるので、表に戻ってから測り直す。
            document.addEventListener('visibilitychange', () => setTimeout(showNotice, WAIT_MS), { once: true });
            return;
        }
        notice = document.createElement('div');
        notice.id = 'blank-render-notice';
        notice.innerHTML = `
            <style>
                #blank-render-notice {
                    position: fixed; inset: 0; z-index: 2147483647;
                    display: flex; align-items: center; justify-content: center;
                    padding: 24px; box-sizing: border-box;
                    background: #fff; color: #1c1b1f;
                    font: 16px/1.6 -apple-system, system-ui, sans-serif; text-align: center;
                }
                #blank-render-notice .box { max-width: 360px; }
                #blank-render-notice h1 { font-size: 19px; margin: 0 0 12px; }
                #blank-render-notice p { margin: 0 0 16px; }
                #blank-render-notice .open {
                    display: block; padding: 14px; margin: 8px 0 16px; border-radius: 12px;
                    background: #3a6a8f; color: #fff; text-decoration: none; font-weight: 600;
                }
                #blank-render-notice .small { font-size: 13px; opacity: .75; }
                #blank-render-notice button {
                    margin-top: 8px; padding: 8px 12px; border: 0; background: none;
                    color: inherit; font: inherit; font-size: 13px; text-decoration: underline; opacity: .75;
                }
                @media (prefers-color-scheme: dark) {
                    #blank-render-notice { background: #141218; color: #e6e1e5; }
                }
            </style>
            <div class="box">
                <h1>このブラウザでは Web 版を表示できません</h1>
                <p>iOS のブラウザ (WebKit) の不具合で、画面が真っ白のままになっています。iOS のアップデートで直る見込みです。</p>
                <a class="open">ToriNos アプリで開く</a>
                <p class="small">アプリが入っていない場合は、App Store で「ToriNos」を検索して入れてください。</p>
                <button type="button">閉じて Web 版の読み込みを待つ</button>
            </div>`;
        // URL 由来の値なので、HTML に埋め込まずに属性として入れる。
        notice.querySelector('a.open').href = appLink;
        notice.querySelector('button').addEventListener('click', () => {
            notice.remove();
            notice = null;
        });
        document.body.appendChild(notice);
    };

    const originalGetContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (type) {
        const ctx = originalGetContext.apply(this, arguments);
        if (ctx && !drawn && (type === 'webgl2' || type === 'webgl' || type === 'experimental-webgl')) {
            watchContext(ctx);
            if (!watching) {
                watching = true;
                setTimeout(showNotice, WAIT_MS);
            }
        }
        return ctx;
    };
})();
