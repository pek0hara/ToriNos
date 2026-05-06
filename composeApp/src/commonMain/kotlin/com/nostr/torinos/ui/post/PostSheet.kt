package com.nostr.torinos.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.ui.components.rememberImagePickerLauncher
import com.nostr.torinos.ui.components.PreviewImage
import com.nostr.torinos.ui.relay.RelaySettingsViewModel

private const val MAX_CHARS = 800

@Composable
fun PostSheet(
    onDismiss: () -> Unit,
    replyToId: String? = null,
    replyToPubkey: String? = null,
    replyToPreview: String? = null,
    viewModel: PostViewModel? = null,
) {
    val postViewModel = viewModel ?: remember { PostViewModel() }
    val state by postViewModel.state.collectAsState()
    var showRelaySettingsDialog by remember { mutableStateOf(false) }

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
                    title = if (replyToId != null) "返信" else "新しいポスト",
                    replyToPreview = replyToPreview,
                    onDismiss = onDismiss,
                    onPickImage = pickImage,
                    onOpenRelaySettings = { showRelaySettingsDialog = true },
                    onTextChange = postViewModel::onTextChange,
                    onRemoveImage = postViewModel::removeImage,
                    onPost = { postViewModel.post(replyToId, replyToPubkey) },
                )
            }
        }
    }

    if (showRelaySettingsDialog) {
        PostRelaySettingsDialog(
            onDismiss = { showRelaySettingsDialog = false },
        )
    }
}

@Composable
private fun PostSheetContent(
    state: PostState,
    title: String,
    replyToPreview: String?,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onTextChange: (String) -> Unit,
    onRemoveImage: (Int) -> Unit,
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

        replyToPreview?.takeIf { it.isNotBlank() }?.let { preview ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "返信先",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = { if (it.length <= MAX_CHARS) onTextChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            placeholder = { Text("今何してる？") },
            maxLines = 6,
        )

        if (state.images.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.images, key = { it.id }) { attachment ->
                    ImageThumbnail(
                        attachment = attachment,
                        onRemove = { onRemoveImage(attachment.id) },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onPickImage,
                        modifier = Modifier.size(36.dp),
                        enabled = state.images.size < 4,
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = "画像を添付",
                            tint = if (state.images.size < 4) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(
                        onClick = onOpenRelaySettings,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.SettingsInputAntenna,
                            contentDescription = "リレー設定",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = state.text.length.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.text.length >= MAX_CHARS)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("キャンセル", maxLines = 1)
                }
                Button(
                    onClick = onPost,
                    enabled = state.canPost,
                ) {
                    if (state.isPosting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("ポスト", maxLines = 1)
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

@Composable
private fun ImageThumbnail(
    attachment: ImageAttachment,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(90.dp)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        val previewData: Any? = attachment.uploadedUrl ?: attachment.previewBytes
        if (previewData != null) {
            PreviewImage(
                data = previewData,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (attachment.isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
        } else {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "削除",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PostRelaySettingsDialog(
    onDismiss: () -> Unit,
    viewModel: RelaySettingsViewModel = viewModel(key = "post-relays") { RelaySettingsViewModel() },
) {
    val relayEntries by viewModel.entries.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リレー設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (relayEntries.isEmpty()) {
                    Text(
                        text = "リレーが設定されていません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        items(relayEntries, key = { it.url }) { entry ->
                            PostRelayRow(
                                entry = entry,
                                onToggle = { enabled -> viewModel.setEnabled(entry.url, enabled) },
                                onDelete = { viewModel.remove(entry.url) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun PostRelayRow(
    entry: RelayEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = entry.enabled,
            onCheckedChange = onToggle,
        )
        Text(
            text = entry.url,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "削除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
