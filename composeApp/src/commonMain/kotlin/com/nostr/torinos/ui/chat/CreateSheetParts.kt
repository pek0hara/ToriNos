package com.nostr.torinos.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.GroupFlavor
import com.nostr.torinos.ui.components.HeaderDropdownMenu
import com.nostr.torinos.ui.components.HeaderDropdownMenuItem

/** チャットのシートの見出し（丸いアイコン・タイトル・説明）。 */
@Composable
internal fun ChatSheetHeader(icon: ImageVector, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 入力欄などの上に置く小見出し。 */
@Composable
internal fun ChatSheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/** 作成先のカード。複数あるときだけ押して選べる。 */
@Composable
internal fun CreateTargetCard(
    targets: List<ChatRelaySummary>,
    target: ChatRelaySummary,
    enabled: Boolean,
    onTarget: (ChatRelaySummary) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selectable = enabled && targets.size > 1
    ChatSheetLabel("作成先")
    Box {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = selectable, onClickLabel = "作成先を選ぶ") { open = true },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                ChatRelayHeadline(label = target.label, host = target.host, flavor = target.flavor, modifier = Modifier.weight(1f))
                if (targets.size > 1) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HeaderDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            targets.forEach { item ->
                HeaderDropdownMenuItem(
                    label = item.label,
                    supportingText = item.host.takeIf { it != item.label },
                    badge = if (item.flavor == GroupFlavor.Buzz) "Buzz" else null,
                    leadingIcon = { ChatRelayIcon(item.label, item.flavor, size = 28.dp) },
                    selected = item.relayUrl == target.relayUrl,
                    onClick = { open = false; onTarget(item) },
                )
            }
        }
    }
}

/** 公開範囲などの、アイコン・名前・説明のある選択肢のカード。 */
@Composable
internal fun RowScope.ChoiceCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
    ) {
        Column(modifier = Modifier.padding(12.dp).heightIn(min = 72.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (selected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 補足の案内（情報アイコン付きの枠）。 */
@Composable
internal fun ChatSheetNote(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** エラーの枠。 */
@Composable
internal fun ChatSheetError(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** シートの下の「キャンセル」と確定のボタン（同じ幅で並べる）。 */
@Composable
internal fun ChatSheetActions(
    confirmLabel: String,
    confirmIcon: ImageVector,
    enabled: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
            Text("キャンセル")
        }
        Button(onClick = onConfirm, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(confirmIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(confirmLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
