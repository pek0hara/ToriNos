package com.nostr.torinos.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.rememberImagePickerLauncher
import com.nostr.torinos.ui.profile.AvatarCircle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.network.Nip05Status
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave
import com.nostr.torinos.ui.components.SheetHeader
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.profile.EditProfileViewModel
import com.nostr.torinos.ui.profile.ProfileDetailField
import com.nostr.torinos.ui.profile.ProfileLoadState
import com.nostr.torinos.ui.profile.profileDetail
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 設定画面の「プロフィールの詳細」。画像の URL と NIP-05。Lightning アドレスは Zap の欄に置く。 */
@Composable
internal fun ProfileDetailsSection(ownPubkey: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = "プロフィールの詳細",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ProfileDetailRows(
            ownPubkey = ownPubkey,
            fields = listOf(ProfileDetailField.Picture, ProfileDetailField.Banner, ProfileDetailField.Nip05),
        )
    }
}

/**
 * プロフィールの項目を1行ずつ出し、押すとその1項目だけの編集シートを開く。
 * 保存はプロフィール編集シートと同じ [EditProfileViewModel] を使い、最新のプロフィールに1項目だけ当てて送る。
 * 設定画面の中では同じ ViewModel を共有するので、別の欄（Zap など）に置いた行とも表示がそろう。
 */
@Composable
internal fun ProfileDetailRows(ownPubkey: String, fields: List<ProfileDetailField>) {
    val viewModel = accountSessionViewModel(key = "profile-details-$ownPubkey") { accountSession ->
        EditProfileViewModel(accountSession = accountSession)
    }
    val state by viewModel.state.collectAsState()
    var editingField by remember { mutableStateOf<ProfileDetailField?>(null) }

    fields.forEach { field ->
        ProfileDetailRow(
            title = field.title,
            value = when (state.loadState) {
                ProfileLoadState.Loading -> "読み込み中…"
                else -> state.profileDetail(field).ifBlank { "未設定" }
            },
            onClick = {
                // 開く前に読み込み中へ切り替える。前回の値のまま開くと、その値で入力欄が埋まってしまう。
                viewModel.reload()
                editingField = field
            },
        )
    }

    editingField?.let { field ->
        ProfileDetailEditSheet(
            ownPubkey = ownPubkey,
            field = field,
            viewModel = viewModel,
            onDismiss = {
                editingField = null
                // 保存に失敗したまま閉じたときに、送れていない値を一覧に残さない。
                viewModel.reload()
            },
        )
    }
}

@Composable
private fun ProfileDetailRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 1項目だけの編集シート。開くたびに最新のプロフィールを読み直し、読み込み終わってから入力させる。
 * 入力中の値は保存を押すまで ViewModel に入れないので、キャンセルしても他の画面に残らない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDetailEditSheet(
    ownPubkey: String,
    field: ProfileDetailField,
    viewModel: EditProfileViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var text by remember(field) { mutableStateOf("") }
    var initialized by remember(field) { mutableStateOf(false) }
    var nip05Status by remember(field) { mutableStateOf(Nip05Status.Unchecked) }
    var nip05Job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val dismissKeyboard = rememberDismissKeyboard()
    val isImageField = field == ProfileDetailField.Picture || field == ProfileDetailField.Banner
    val pickImage = rememberImagePickerLauncher { bytes, mime ->
        if (bytes == null || mime == null) return@rememberImagePickerLauncher
        when (field) {
            ProfileDetailField.Picture -> viewModel.uploadProfileImage(bytes, mime)
            ProfileDetailField.Banner -> viewModel.uploadBannerImage(bytes, mime)
            else -> Unit
        }
    }
    // アップロードが終わったら、その URL を入力欄に入れる（保存を押すまでは送らない）。
    var wasUploading by remember { mutableStateOf(false) }
    LaunchedEffect(state.isUploadingImage) {
        if (wasUploading && !state.isUploadingImage && state.error == null) {
            text = state.profileDetail(field)
        }
        wasUploading = state.isUploadingImage
    }

    LaunchedEffect(state.loadState) {
        if (!initialized && state.loadState == ProfileLoadState.Loaded) {
            text = state.profileDetail(field)
            initialized = true
        }
    }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.clearSaved()
            onDismiss()
        }
    }

    // 今のプロフィールを読めたときだけ保存できる。読めないまま1項目だけで保存すると、名前や自己紹介が消えてしまう。
    val editable = initialized && state.loadState == ProfileLoadState.Loaded && !state.isSaving
    // 保存中・アップロード中は閉じない。アップロード中に閉じると、終わった URL が保存前なのに一覧へ出てしまう。
    val isBusy = state.isSaving || state.isUploadingImage
    val isBusyNow by rememberUpdatedState(isBusy)
    ModalBottomSheet(
        onDismissRequest = { if (!isBusy) onDismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !isBusyNow },
        ),
    ) {
        DismissKeyboardOnLeave()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(
                title = field.shortTitle,
                onCancel = {
                    dismissKeyboard()
                    onDismiss()
                },
                onConfirm = {
                    dismissKeyboard()
                    viewModel.onProfileDetailChange(field, text)
                    viewModel.save()
                },
                cancelEnabled = !isBusy,
                confirmEnabled = editable && !state.isUploadingImage,
                isConfirming = state.isSaving,
            )

            when (state.loadState) {
                ProfileLoadState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("プロフィールを読み込んでいます", style = MaterialTheme.typography.bodySmall)
                }
                ProfileLoadState.NotFound -> Column {
                    Text(
                        "今のプロフィールを読み込めなかったため、保存できません。" +
                            "まだプロフィールがないときは、先にプロフィール編集で名前などを保存してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = viewModel::reload) { Text("もう一度読み込む") }
                }
                ProfileLoadState.Loaded -> Unit
            }

            if (isImageField) {
                ProfileImageUploadPreview(
                    ownPubkey = ownPubkey,
                    isBanner = field == ProfileDetailField.Banner,
                    url = text.trim(),
                    isUploading = state.isUploadingImage,
                    enabled = editable && !state.isUploadingImage,
                    onPick = {
                        dismissKeyboard()
                        pickImage()
                    },
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (field == ProfileDetailField.Nip05) {
                        nip05Job?.cancel()
                        nip05Status = Nip05Status.Unchecked
                    }
                },
                label = { Text(field.title) },
                placeholder = { Text(field.placeholder) },
                supportingText = field.description?.let { { Text(it) } },
                singleLine = true,
                enabled = editable,
                modifier = Modifier.fillMaxWidth().avoidStaleAccessibilityFocus(),
            )

            if (field == ProfileDetailField.Nip05) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            nip05Job?.cancel()
                            nip05Job = scope.launch {
                                nip05Status = Nip05Status.Checking
                                nip05Status = viewModel.verifyNip05(text)
                            }
                        },
                        enabled = editable && text.isNotBlank() && nip05Status != Nip05Status.Checking,
                    ) { Text("確認") }
                    Text(
                        text = nip05Status.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (nip05Status) {
                            Nip05Status.Verified -> MaterialTheme.colorScheme.primary
                            Nip05Status.InvalidAddress, Nip05Status.Mismatch -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

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

/** アイコン・バナーの1項目シートのプレビュー。入力中の URL をそのまま映し、タップかボタンで写真を選んでアップロードする。 */
@Composable
private fun ProfileImageUploadPreview(
    ownPubkey: String,
    isBanner: Boolean,
    url: String,
    isUploading: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isBanner) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled, onClick = onPick),
                contentAlignment = Alignment.Center,
            ) {
                if (url.isNotBlank()) {
                    NetworkImage(
                        url = url,
                        contentDescription = "バナーのプレビュー",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "画像なし",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            AvatarCircle(
                pubkey = ownPubkey,
                name = null,
                pictureUrl = url.ifBlank { null },
                size = 96,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onPick),
            )
        }
        OutlinedButton(onClick = onPick, enabled = enabled) {
            if (isUploading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(8.dp))
                Text("アップロード中")
            } else {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text("写真を選んでアップロード")
            }
        }
    }
}

