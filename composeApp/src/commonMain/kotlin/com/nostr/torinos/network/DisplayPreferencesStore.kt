package com.nostr.torinos.network

import com.nostr.torinos.FooterConfig
import com.nostr.torinos.FooterItem
import com.nostr.torinos.ui.theme.AppFontScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 画像・X投稿の自動プレビュー表示可否、フィードの投稿方法と文字サイズ、フッターの並び。Nostrアカウントには紐付かない端末単位の表示設定。
 */
object DisplayPreferencesStore {
    private const val ImagePreviewKey = "display_pref_image_preview_v1"
    private const val XPreviewKey = "display_pref_x_preview_v1"
    private const val FooterComposerKey = "display_pref_footer_composer_v1"
    private const val FontScaleKey = "display_pref_font_scale_v1"
    private const val FooterItemsKey = "display_pref_footer_items_v1"
    private const val LastOpenedTabKey = "display_pref_last_opened_tab_v1"

    /** フッター投稿を一度も切り替えていないときの既定。全プラットフォームで投稿シートを使う。 */
    private const val defaultUseFooterComposer = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 永続化された値を読み終えるまではfalse(自動読み込みしない)を既定にする。
    // trueを既定にすると、オフに設定済みのユーザーでも読み込み完了までの一瞬だけ
    // 実際に画像取得・プリフェッチが走ってしまう(設定の意図に反する)ため、
    // 未確定の間は安全側に倒す。設定を一度も変更していない場合の本来の既定値(true)は
    // getStringがnullを返したときのフォールバックとして適用する。
    private val _showImagePreviews = MutableStateFlow(false)
    val showImagePreviews: StateFlow<Boolean> = _showImagePreviews.asStateFlow()

    private val _showXPreviews = MutableStateFlow(false)
    val showXPreviews: StateFlow<Boolean> = _showXPreviews.asStateFlow()

    /** フィードと自分のジャーナルの＋で、全画面の投稿シートではなくフッターの簡易投稿欄を開くか。 */
    private val _useFooterComposer = MutableStateFlow(defaultUseFooterComposer)
    val useFooterComposer: StateFlow<Boolean> = _useFooterComposer.asStateFlow()

    private val _fontScale = MutableStateFlow(AppFontScale.Large)
    val fontScale: StateFlow<AppFontScale> = _fontScale.asStateFlow()

    /** フッターの並び順と表示する項目。 */
    private val _footerConfig = MutableStateFlow(FooterConfig.Default)
    internal val footerConfig: StateFlow<FooterConfig> = _footerConfig.asStateFlow()

    /** フッター設定と最後に開いていたタブを読み終えたか。起動時の画面はこれを待って決める。 */
    private val _footerConfigLoaded = MutableStateFlow(false)
    internal val footerConfigLoaded: StateFlow<Boolean> = _footerConfigLoaded.asStateFlow()

    /** 最後に開いていたフッターのタブ。次の起動でこのタブを開く（Web 版は URL で決まるので使わない）。 */
    private val _lastOpenedTab = MutableStateFlow<FooterItem?>(null)
    internal val lastOpenedTab: StateFlow<FooterItem?> = _lastOpenedTab.asStateFlow()

    /** 起動時の画面へ移る処理を、プロセスで1度だけにする（アカウント切り替えや画面の作り直しでは移らない）。 */
    private var startScreenApplied = false

    internal fun consumeStartScreenRequest(): Boolean {
        if (startScreenApplied) return false
        startScreenApplied = true
        return true
    }

    init {
        scope.launch {
            _showImagePreviews.value = LocalSettingsStorage.getString(ImagePreviewKey)
                ?.toBoolean() ?: true
            _showXPreviews.value = LocalSettingsStorage.getString(XPreviewKey)
                ?.toBoolean() ?: true
            _useFooterComposer.value = LocalSettingsStorage.getString(FooterComposerKey)
                ?.toBoolean() ?: defaultUseFooterComposer
            _fontScale.value = AppFontScale.fromStorage(LocalSettingsStorage.getString(FontScaleKey))
            _footerConfig.value = FooterConfig.parse(LocalSettingsStorage.getString(FooterItemsKey))
            _lastOpenedTab.value = LocalSettingsStorage.getString(LastOpenedTabKey)?.let(FooterItem::fromStorageId)
            _footerConfigLoaded.value = true
        }
    }

    fun setShowImagePreviews(value: Boolean) {
        _showImagePreviews.value = value
        scope.launch { LocalSettingsStorage.putString(ImagePreviewKey, value.toString()) }
    }

    fun setUseFooterComposer(value: Boolean) {
        _useFooterComposer.value = value
        scope.launch { LocalSettingsStorage.putString(FooterComposerKey, value.toString()) }
    }

    fun setFontScale(value: AppFontScale) {
        _fontScale.value = value
        scope.launch { LocalSettingsStorage.putString(FontScaleKey, value.storageValue) }
    }

    internal fun setFooterConfig(value: FooterConfig) {
        _footerConfig.value = value
        scope.launch { LocalSettingsStorage.putString(FooterItemsKey, value.serialize()) }
    }

    internal fun setLastOpenedTab(value: FooterItem) {
        if (_lastOpenedTab.value == value) return
        _lastOpenedTab.value = value
        // 保存は並行して走るので、渡された値ではなくその時点の最新を書く（順序が入れ替わっても古い値で上書きしない）。
        scope.launch { _lastOpenedTab.value?.let { LocalSettingsStorage.putString(LastOpenedTabKey, it.storageId) } }
    }

    fun setShowXPreviews(value: Boolean) {
        _showXPreviews.value = value
        scope.launch { LocalSettingsStorage.putString(XPreviewKey, value.toString()) }
    }
}
