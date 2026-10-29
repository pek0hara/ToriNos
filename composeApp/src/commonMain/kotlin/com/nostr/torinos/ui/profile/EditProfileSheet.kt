package com.nostr.torinos.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nostr.torinos.MaxContentWidth
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.SheetHeader
import com.nostr.torinos.ui.components.fullScreenDialogProperties
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.components.rememberImagePickerLauncher
import com.nostr.torinos.ui.components.rememberSyncedTextFieldValue

/** プロフィール画面のどの入口から開いたか。文字の項目は、読み込み後にその欄へフォーカスを当てる。 */
enum class EditProfileField {
    /** アイコン・バナー。シート上部のプレビューから選ぶので、キーボードは出さない。 */
    Images,
    DisplayName,
    About,
}

/**
 * 自分のプロフィールを編集する唯一の画面。項目ごとの鉛筆ボタンもここを開く。
 * 開く側が表示の直前に [EditProfileViewModel.reload] を呼ぶ（最新を読み直してから編集させ、古い内容で上書きしないため）。
 * シートの中では読み直さない。読み終わった直後にもう一度読み込むと欄が一瞬無効になり、最初のフォーカスが外れるため。
 */
@Composable
fun EditProfileSheet(
    pubkey: String,
    onDismiss: () -> Unit,
    viewModel: EditProfileViewModel,
    initialField: EditProfileField? = null,
    onSaved: (NostrProfile) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    val pickImage = rememberImagePickerLauncher { bytes, mime ->
        if (bytes != null && mime != null) viewModel.uploadProfileImage(bytes, mime)
    }
    val pickBanner = rememberImagePickerLauncher { bytes, mime ->
        if (bytes != null && mime != null) viewModel.uploadBannerImage(bytes, mime)
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            val profile = state.savedProfile
            viewModel.clearSaved()
            if (profile != null) onSaved(profile)
            onDismiss()
        }
    }

    // 投稿シート（PostSheet）と同じ全画面の形にそろえる。保存中は閉じない（送信の結果を受け取れなくなるため）。
    val requestDismiss = { if (!state.isSaving) onDismiss() }
    Dialog(
        onDismissRequest = requestDismiss,
        properties = fullScreenDialogProperties(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = requestDismiss),
            )
            Surface(
                // PCブラウザなどの横長画面では、アプリ本体と同じ中央の列に合わせる。
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = MaxContentWidth)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                EditProfileSheetContent(
                    pubkey = pubkey,
                    state = state,
                    initialField = initialField,
                    onDismiss = requestDismiss,
                    onPickImage = pickImage,
                    onPickBanner = pickBanner,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun EditProfileSheetContent(
    pubkey: String,
    state: EditProfileState,
    initialField: EditProfileField?,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickBanner: () -> Unit,
    viewModel: EditProfileViewModel,
) {
    DismissKeyboardOnLeave()
    var nameValue by rememberSyncedTextFieldValue(state.name)
    var displayNameValue by rememberSyncedTextFieldValue(state.displayName)
    var aboutValue by rememberSyncedTextFieldValue(state.about)
    val dismissKeyboard = rememberDismissKeyboard()
    val keyboardController = LocalSoftwareKeyboardController.current
    val displayNameFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
    // 読み込みが終わる前に打った内容は、読み込んだプロフィールで上書きされるため、終わるまで入力させない。
    val editable = state.loadState != ProfileLoadState.Loading && !state.isSaving
    val canPickImage = editable && !state.isUploadingImage

    // 押した鉛筆の項目へ、入力できるようになってから一度だけフォーカスを当てる。カーソルは末尾に置く。
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(editable) {
        if (!editable || initialFocusDone) return@LaunchedEffect
        initialFocusDone = true
        val requester = when (initialField) {
            EditProfileField.DisplayName -> {
                displayNameValue = displayNameValue.copy(selection = TextRange(displayNameValue.text.length))
                displayNameFocus
            }
            EditProfileField.About -> {
                aboutValue = aboutValue.copy(selection = TextRange(aboutValue.text.length))
                aboutFocus
            }
            EditProfileField.Images, null -> null
        } ?: return@LaunchedEffect
        withFrameNanos { }
        requester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
    ) {
        SheetHeader(
            title = "編集",
            onCancel = {
                dismissKeyboard()
                onDismiss()
            },
            onConfirm = {
                dismissKeyboard()
                viewModel.save()
            },
            cancelEnabled = !state.isSaving,
            confirmEnabled = !state.isSaving && !state.isUploadingImage && state.loadState != ProfileLoadState.Loading,
            isConfirming = state.isSaving,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.loadState) {
                ProfileLoadState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("プロフィールを読み込んでいます", style = MaterialTheme.typography.bodySmall)
                }
                ProfileLoadState.NotFound -> Text(
                    "今のプロフィールを読み込めませんでした。保存すると、ここに入力した内容だけになります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                ProfileLoadState.Loaded -> Unit
            }

            ProfileImagesPreview(
                pubkey = pubkey,
                name = displayNameValue.text.ifBlank { nameValue.text }.ifBlank { null },
                pictureUrl = state.picture,
                bannerUrl = state.banner,
                isUploading = state.isUploadingImage,
                enabled = canPickImage,
                onPickImage = {
                    dismissKeyboard()
                    onPickImage()
                },
                onPickBanner = {
                    dismissKeyboard()
                    onPickBanner()
                },
            )

            OutlinedTextField(
                value = displayNameValue,
                onValueChange = {
                    displayNameValue = it
                    viewModel.onDisplayNameChange(it.text)
                },
                label = { Text("表示名") },
                singleLine = true,
                enabled = editable,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(displayNameFocus)
                    .avoidStaleAccessibilityFocus(),
            )

            OutlinedTextField(
                value = nameValue,
                onValueChange = {
                    nameValue = it
                    viewModel.onNameChange(it.text)
                },
                label = { Text("ユーザー名") },
                singleLine = true,
                enabled = editable,
                modifier = Modifier.fillMaxWidth().avoidStaleAccessibilityFocus(),
            )

            OutlinedTextField(
                value = aboutValue,
                onValueChange = {
                    aboutValue = it
                    viewModel.onAboutChange(it.text)
                },
                label = { Text("自己紹介") },
                enabled = editable,
                // 項目が3つになって空いた分を自己紹介に回す。長くなったら欄ごと伸び、シート全体がスクロールする。
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .focusRequester(aboutFocus)
                    .avoidStaleAccessibilityFocus(),
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** バナーとアイコンのプレビュー。プロフィール画面と同じ重ね方で、タップすると画像を選ぶ。 */
@Composable
private fun ProfileImagesPreview(
    pubkey: String,
    name: String?,
    pictureUrl: String,
    bannerUrl: String,
    isUploading: Boolean,
    enabled: Boolean,
    onPickImage: () -> Unit,
    onPickBanner: () -> Unit,
) {
    val avatarSize = 72
    val avatarOverlap = 36.dp
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = avatarOverlap)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = enabled, onClick = onPickBanner),
        ) {
            if (bannerUrl.isNotBlank()) {
                NetworkImage(
                    url = bannerUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            PickImageBadge(
                contentDescription = "バナー画像を選択してアップロード",
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 12.dp, y = avatarOverlap)
                .size(avatarSize.dp)
                .clickable(enabled = enabled, onClick = onPickImage),
        ) {
            AvatarCircle(
                pubkey = pubkey,
                name = name,
                pictureUrl = pictureUrl.ifBlank { null },
                size = avatarSize,
                modifier = Modifier.border(3.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
            PickImageBadge(
                contentDescription = "アイコン画像を選択してアップロード",
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        if (isUploading) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(y = avatarOverlap - 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    "アップロード中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PickImageBadge(contentDescription: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(26.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.AddPhotoAlternate,
            contentDescription = contentDescription,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
