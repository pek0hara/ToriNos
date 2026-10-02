package com.nostr.torinos.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.components.rememberImagePickerLauncher

/** 新規登録でのみ表示する、最初のプロフィール設定。保存失敗時は入力を残して再試行する。 */
@Composable
internal fun RegistrationProfileDialog(
    viewModel: EditProfileViewModel,
    onComplete: () -> Unit,
) {
    DismissKeyboardOnLeave()
    val state by viewModel.state.collectAsState()
    val busy = state.isSaving || state.isUploadingImage
    val dismissKeyboard = rememberDismissKeyboard()
    val pickImage = rememberImagePickerLauncher { bytes, mime ->
        if (bytes != null && mime != null) viewModel.uploadProfileImage(bytes, mime)
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.clearSaved()
            onComplete()
        }
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { if (!busy) onComplete() },
        title = { Text("プロフィールを設定") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("ユーザー名とアイコンを設定しましょう。あとから変更できます。")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AvatarCircle(pubkey = "", name = state.name, pictureUrl = state.picture.ifBlank { null }, size = 64)
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            dismissKeyboard()
                            pickImage()
                        },
                    ) { Text("アイコンを選択") }
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text("ユーザー名") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.picture,
                    onValueChange = viewModel::onPictureChange,
                    label = { Text("アイコンURL（任意）") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isUploadingImage) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::save,
                enabled = !busy && state.name.isNotBlank(),
            ) {
                if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                else Text("保存して次へ")
            }
        },
        dismissButton = {
            TextButton(onClick = onComplete, enabled = !busy) { Text("あとで設定") }
        },
    )
}
