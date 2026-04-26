package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrProfile
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.ui.components.rememberImagePickerLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileSheet(
    onDismiss: () -> Unit,
    onSaved: (NostrProfile) -> Unit = {},
    viewModel: EditProfileViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val pickImage = rememberImagePickerLauncher { bytes, mime ->
        if (bytes != null && mime != null) viewModel.uploadProfileImage(bytes, mime)
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            val profile = state.savedProfile
            viewModel.clearSaved()
            if (profile != null) onSaved(profile)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
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
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("ユーザー名 (name)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = { Text("表示名 (display_name)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.about,
                onValueChange = viewModel::onAboutChange,
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
                    onClick = pickImage,
                    enabled = !state.isUploadingImage,
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = "画像を選択してアップロード",
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

            if (state.error != null) {
                Text(
                    text = state.error!!,
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
}
