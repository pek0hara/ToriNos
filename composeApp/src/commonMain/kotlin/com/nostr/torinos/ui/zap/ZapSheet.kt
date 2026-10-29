package com.nostr.torinos.ui.zap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.zap.MAX_ZAP_AMOUNT_SATS
import com.nostr.torinos.zap.PayEndpoint
import com.nostr.torinos.zap.ZapException
import com.nostr.torinos.zap.ZapSettingsStore
import com.nostr.torinos.zap.ZapTarget
import kotlinx.coroutines.CancellationException

private sealed interface EndpointState {
    data object Loading : EndpointState
    data class Ready(val endpoint: PayEndpoint) : EndpointState
    data class Failed(val message: String) : EndpointState
}

/** 長押しで開く、額とコメントを指定して送るシート。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ZapSheet(
    session: AccountSession,
    target: ZapTarget,
    profile: NostrProfile?,
    onDismiss: () -> Unit,
) {
    val amounts by ZapSettingsStore.amounts.collectAsState()
    val lastAmount by ZapSettingsStore.lastAmount.collectAsState()
    var selected by remember { mutableStateOf<Long?>(lastAmount) }
    var customText by remember { mutableStateOf(if (lastAmount in amounts) "" else lastAmount.toString()) }
    var useCustom by remember { mutableStateOf(lastAmount !in amounts) }
    var comment by remember { mutableStateOf("") }
    var endpointState by remember { mutableStateOf<EndpointState>(EndpointState.Loading) }
    var confirmAmount by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(target.authorPubkey) {
        endpointState = try {
            EndpointState.Ready(session.zaps.endpointFor(target))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ZapException) {
            EndpointState.Failed(e.message ?: "受け取り先を確認できません")
        } catch (e: Exception) {
            EndpointState.Failed("受け取り先を確認できません")
        }
    }

    val amount = if (useCustom) customText.trim().toLongOrNull()?.takeIf { it in 1..MAX_ZAP_AMOUNT_SATS } else selected
    val endpoint = (endpointState as? EndpointState.Ready)?.endpoint
    val inRange = amount != null && endpoint?.accepts(amount * 1_000) == true
    // Zap のコメントは Zap リクエストに入るので 280 文字まで。Zap でない支払いは LUD-12 の上限に従う。
    val commentLimit = when {
        endpoint == null -> 280
        endpoint.allowsNostr -> 280
        else -> minOf(280, endpoint.commentAllowed)
    }

    fun send(sats: Long) {
        ZapSettingsStore.setLastAmount(sats)
        session.zaps.send(target, sats, comment.take(commentLimit), allowPlainPayment = true)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("⚡ Zap を送る", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AvatarCircle(target.authorPubkey, profile?.bestName, profile?.picture, size = 36)
                Column {
                    Text(profile?.bestName ?: target.authorPubkey.take(12), style = MaterialTheme.typography.bodyLarge)
                    (profile?.lud16 ?: profile?.lud06?.let { "LNURL" })?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                amounts.forEach { sats ->
                    FilterChip(
                        selected = !useCustom && selected == sats,
                        onClick = { useCustom = false; selected = sats },
                        label = { Text(formatSats(sats)) },
                    )
                }
                FilterChip(selected = useCustom, onClick = { useCustom = true }, label = { Text("その他") })
            }
            if (useCustom) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { text -> customText = text.filter(Char::isDigit).take(7) },
                    label = { Text("額（sats）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it.take(commentLimit) },
                label = { Text("コメント（公開されます）") },
                enabled = commentLimit > 0,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            when (val state = endpointState) {
                EndpointState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("受け取り先を確認しています", style = MaterialTheme.typography.bodySmall)
                }
                is EndpointState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                is EndpointState.Ready -> {
                    val min = (state.endpoint.minSendableMsats + 999) / 1_000
                    val max = state.endpoint.maxSendableMsats / 1_000
                    Text(
                        "送れる額: ${formatSats(min)}〜${formatSats(max)} sats",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (amount != null && !inRange) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.endpoint.allowsNostr) {
                        Text(
                            "この受け取り先は Zap に対応していません。送ることはできますが、件数には出ません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                "支払いはウォレットのアプリで行います。コメントと、あなたが送ったことは誰でも見られます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { amount?.let { if (it > ZAP_CONFIRM_THRESHOLD_SATS) confirmAmount = it else send(it) } },
                enabled = inRange,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(amount?.let { "${formatSats(it)} sats を送る" } ?: "額を選んでください")
            }
        }
    }
    confirmAmount?.let { sats ->
        LargeZapConfirmDialog(sats, onConfirm = { confirmAmount = null; send(sats) }, onDismiss = { confirmAmount = null })
    }
}
