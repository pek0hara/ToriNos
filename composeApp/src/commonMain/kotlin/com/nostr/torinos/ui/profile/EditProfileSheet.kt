package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrProfile
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Card
import androidx.compose.ui.window.Dialog
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.components.rememberImagePickerLauncher
import com.nostr.torinos.ui.components.rememberSyncedTextFieldValue

@Composable
fun EditProfileSheet(
    onDismiss: () -> Unit,
    viewModel: EditProfileViewModel,
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

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Box(modifier = Modifier.padding(top = 8.dp)) {
                EditProfileSheetContent(
                    state = state,
                    onDismiss = onDismiss,
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
    state: EditProfileState,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickBanner: () -> Unit,
    viewModel: EditProfileViewModel,
) {
    var nameValue by rememberSyncedTextFieldValue(state.name)
    var displayNameValue by rememberSyncedTextFieldValue(state.displayName)
    var aboutValue by rememberSyncedTextFieldValue(state.about)
    val dismissKeyboard = rememberDismissKeyboard()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("プロフィール編集", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = nameValue,
            onValueChange = {
                nameValue = it
                viewModel.onNameChange(it.text)
            },
            label = { Text("ユーザー名 (name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = displayNameValue,
            onValueChange = {
                displayNameValue = it
                viewModel.onDisplayNameChange(it.text)
            },
            label = { Text("表示名 (display_name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = aboutValue,
            onValueChange = {
                aboutValue = it
                viewModel.onAboutChange(it.text)
            },
            label = { Text("自己紹介 (about)") },
            modifier = Modifier.fillMaxWidth().height(100.dp),
            maxLines = 4,
        )

        // アイコン画像フィールド + アップロードボタン
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.picture,
                onValueChange = viewModel::onPictureChange,
                label = { Text("アイコンURL (picture)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    if (state.isUploadingImage) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
            )
            IconButton(
                onClick = {
                    dismissKeyboard()
                    onPickImage()
                },
                enabled = !state.isUploadingImage,
            ) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = "画像を選択してアップロード",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.banner,
                onValueChange = viewModel::onBannerChange,
                label = { Text("バナーURL (banner)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    if (state.isUploadingImage) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
            )
            IconButton(
                onClick = {
                    dismissKeyboard()
                    onPickBanner()
                },
                enabled = !state.isUploadingImage,
            ) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = "バナー画像を選択してアップロード",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        OutlinedTextField(
            value = state.nip05,
            onValueChange = viewModel::onNip05Change,
            label = { Text("NIP-05アドレス") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving && !state.isUploadingImage,
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("保存")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun BannerEditDialog(
    currentProfile: NostrProfile?,
    viewModel: EditProfileViewModel,
    onDismiss: () -> Unit,
    onSaved: (NostrProfile) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var url by remember { mutableStateOf(currentProfile?.banner ?: "") }
    val dismissKeyboard = rememberDismissKeyboard()

    val pickImage = rememberImagePickerLauncher { bytes, mime ->
        if (bytes != null && mime != null) viewModel.uploadBannerImage(bytes, mime)
    }

    LaunchedEffect(state.banner) {
        if (state.banner.isNotBlank()) url = state.banner
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            val profile = state.savedProfile
            viewModel.clearSaved()
            if (profile != null) onSaved(profile)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = { if (!state.isSaving) onDismiss() }) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("バナーを変更", style = MaterialTheme.typography.titleMedium)

                if (url.isNotBlank()) {
                    NetworkImage(
                        url = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("バナーURL") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isSaving,
                    )
                    IconButton(
                        onClick = {
                            dismissKeyboard()
                            pickImage()
                        },
                        enabled = !state.isUploadingImage && !state.isSaving,
                    ) {
                        if (state.isUploadingImage) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.AddPhotoAlternate,
                                contentDescription = "バナーをアップロード",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("キャンセル") }
                    Button(
                        onClick = {
                            viewModel.initFrom(currentProfile ?: NostrProfile())
                            viewModel.onBannerChange(url)
                            viewModel.save()
                        },
                        enabled = !state.isSaving && !state.isUploadingImage,
                    ) {
                        if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
fun AvatarEditDialog(
    currentProfile: NostrProfile?,
    pubkey: String,
    viewModel: EditProfileViewModel,
    onDismiss: () -> Unit,
    onSaved: (NostrProfile) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var url by remember { mutableStateOf(currentProfile?.picture ?: "") }
    val dismissKeyboard = rememberDismissKeyboard()

    val pickImage = rememberImagePickerLauncher { bytes, mime ->
        if (bytes != null && mime != null) viewModel.uploadProfileImage(bytes, mime)
    }

    LaunchedEffect(state.picture) {
        if (state.picture.isNotBlank()) url = state.picture
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            val profile = state.savedProfile
            viewModel.clearSaved()
            if (profile != null) onSaved(profile)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = { if (!state.isSaving) onDismiss() }) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("アイコンを変更", style = MaterialTheme.typography.titleMedium)

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AvatarCircle(
                        pubkey = pubkey,
                        name = currentProfile?.bestName,
                        pictureUrl = url.ifBlank { null },
                        size = 80,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("画像URL") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isSaving,
                    )
                    IconButton(
                        onClick = {
                            dismissKeyboard()
                            pickImage()
                        },
                        enabled = !state.isUploadingImage && !state.isSaving,
                    ) {
                        if (state.isUploadingImage) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = "画像をアップロード", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("キャンセル") }
                    Button(
                        onClick = {
                            viewModel.initFrom(currentProfile ?: NostrProfile())
                            viewModel.onPictureChange(url)
                            viewModel.save()
                        },
                        enabled = !state.isSaving && !state.isUploadingImage,
                    ) {
                        if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
fun NameEditDialog(
    currentProfile: NostrProfile?,
    viewModel: EditProfileViewModel,
    onDismiss: () -> Unit,
    onSaved: (NostrProfile) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var name by remember { mutableStateOf(currentProfile?.bestName ?: "") }
    var displayName by remember { mutableStateOf(currentProfile?.displayName ?: "") }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            val profile = state.savedProfile
            viewModel.clearSaved()
            if (profile != null) onSaved(profile)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        title = { Text("名前を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ユーザー名 (name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSaving,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("表示名 (display_name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSaving,
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.initFrom(currentProfile ?: NostrProfile())
                    viewModel.onNameChange(name)
                    viewModel.onDisplayNameChange(displayName)
                    viewModel.save()
                },
                enabled = !state.isSaving,
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("キャンセル") }
        },
    )
}

@Composable
fun AboutEditDialog(
    currentProfile: NostrProfile?,
    viewModel: EditProfileViewModel,
    onDismiss: () -> Unit,
    onSaved: (NostrProfile) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var about by remember { mutableStateOf(currentProfile?.about ?: "") }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            val profile = state.savedProfile
            viewModel.clearSaved()
            if (profile != null) onSaved(profile)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        title = { Text("自己紹介を編集") },
        text = {
            Column {
                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text("自己紹介 (about)") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 6,
                    enabled = !state.isSaving,
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.initFrom(currentProfile ?: NostrProfile())
                    viewModel.onAboutChange(about)
                    viewModel.save()
                },
                enabled = !state.isSaving,
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("キャンセル") }
        },
    )
}
