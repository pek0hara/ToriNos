package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 画像・X投稿の自動プレビュー表示可否。Nostrアカウントには紐付かない端末単位の表示設定。
 */
object DisplayPreferencesStore {
    private const val ImagePreviewKey = "display_pref_image_preview_v1"
    private const val XPreviewKey = "display_pref_x_preview_v1"

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

    init {
        scope.launch {
            _showImagePreviews.value = LocalSettingsStorage.getString(ImagePreviewKey)
                ?.toBoolean() ?: true
            _showXPreviews.value = LocalSettingsStorage.getString(XPreviewKey)
                ?.toBoolean() ?: true
        }
    }

    fun setShowImagePreviews(value: Boolean) {
        _showImagePreviews.value = value
        scope.launch { LocalSettingsStorage.putString(ImagePreviewKey, value.toString()) }
    }

    fun setShowXPreviews(value: Boolean) {
        _showXPreviews.value = value
        scope.launch { LocalSettingsStorage.putString(XPreviewKey, value.toString()) }
    }
}
