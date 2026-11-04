package com.nostr.torinos.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.ui.components.DefaultReactionPickerMode
import com.nostr.torinos.ui.components.ReactionOptionIcon
import com.nostr.torinos.ui.components.StandardEmojiPickerSheet
import com.nostr.torinos.ui.components.defaultReactionLabel
import com.nostr.torinos.ui.components.rememberDefaultReaction

/** ハートをタップしたときに送るリアクション。ログイン中だけ出す。 */
@Composable
internal fun DefaultReactionSettingsSection() {
    val current by rememberDefaultReaction()
    var showPicker by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = "リアクション",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPicker = true }
                .padding(vertical = 10.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "ハートをタップしたときに送る、${current.defaultReactionLabel()}"
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ハートをタップしたときに送る",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            ReactionOptionIcon(current, emojiSize = 20.sp, imageSize = 22.dp)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (showPicker) {
        StandardEmojiPickerSheet(
            onDismiss = { showPicker = false },
            onSelect = { showPicker = false },
            defaultReactionMode = DefaultReactionPickerMode.SelectOnly,
        )
    }
}
