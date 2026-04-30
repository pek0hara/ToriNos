package com.nostr.torinos.ui.post

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nostr.torinos.ui.components.rememberImagePickerLauncher

private const val MAX_CHARS = 800

@Composable
fun PostSheet(
    onDismiss: () -> Unit,
    replyToId: String? = null,
    replyToPubkey: String? = null,
    viewModel: PostViewModel? = null,
) {
    val postViewModel = viewModel ?: remember { PostViewModel() }
    val state by postViewModel.state.collectAsStateWithLifecycle()

    val pickImage = rememberImagePickerLauncher { bytes, mime ->
        if (bytes != null && mime != null) postViewModel.uploadAndAppendImage(bytes, mime)
    }

    LaunchedEffect(state.posted) {
        if (state.posted) {
            postViewModel.clearPosted()
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Box(modifier = Modifier.padding(top = 8.dp)) {
                PostSheetContent(
                    state = state,
                    title = if (replyToId != null) "返信" else "新しい投稿",
                    onDismiss = onDismiss,
                    onPickImage = pickImage,
                    onTextChange = postViewModel::onTextChange,
                    onPost = { postViewModel.post(replyToId, replyToPubkey) },
                )
            }
        }
    }
}

@Composable
private fun PostSheetContent(
    state: PostState,
    title: String,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onTextChange: (String) -> Unit,
    onPost: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .imePadding(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.text,
            onValueChange = { if (it.length <= MAX_CHARS) onTextChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            placeholder = { Text("今何してる？") },
            maxLines = 6,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isUploadingImage) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = onPickImage,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = "画像を添付",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = "${state.text.length} / $MAX_CHARS",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.text.length >= MAX_CHARS)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                Button(
                    onClick = onPost,
                    enabled = state.text.isNotBlank() && !state.isPosting && !state.isUploadingImage,
                ) {
                    if (state.isPosting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("投稿")
                    }
                }
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
