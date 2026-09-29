package com.nostr.torinos.network

import com.nostr.torinos.crypto.isWebPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 画像・X投稿の自動プレビュー表示可否と、フィードの投稿方法。Nostrアカウントには紐付かない端末単位の表示設定。
 */
object DisplayPreferencesStore {
    private const val ImagePreviewKey = "display_pref_image_preview_v1"
    private const val XPreviewKey = "display_pref_x_preview_v1"
    private const val FooterComposerKey = "display_pref_footer_composer_v1"

    /** フッター投稿を一度も切り替えていないときの既定。ネイティブは従来の全画面シート、Webはフッター投稿。 */
    private val defaultUseFooterComposer = isWebPlatform

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

    /** フィードの＋で、全画面の投稿シートではなくフッターの簡易投稿欄を開くか。 */
    private val _useFooterComposer = MutableStateFlow(defaultUseFooterComposer)
    val useFooterComposer: StateFlow<Boolean> = _useFooterComposer.asStateFlow()

    init {
        scope.launch {
            _showImagePreviews.value = LocalSettingsStorage.getString(ImagePreviewKey)
                ?.toBoolean() ?: true
            _showXPreviews.value = LocalSettingsStorage.getString(XPreviewKey)
                ?.toBoolean() ?: true
            _useFooterComposer.value = LocalSettingsStorage.getString(FooterComposerKey)
                ?.toBoolean() ?: defaultUseFooterComposer
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

    fun setShowXPreviews(value: Boolean) {
        _showXPreviews.value = value
        scope.launch { LocalSettingsStorage.putString(XPreviewKey, value.toString()) }
    }
}
