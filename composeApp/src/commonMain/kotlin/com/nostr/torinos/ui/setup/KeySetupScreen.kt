package com.nostr.torinos.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.StoredAccount
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.generateKeyPair
import com.nostr.torinos.crypto.hexToNpub
import com.nostr.torinos.crypto.hexToNsec
import com.nostr.torinos.crypto.isIosPlatform
import com.nostr.torinos.crypto.normalizePrivateKey
import com.nostr.torinos.crypto.rememberPasswordManagerSaver
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileCache
import com.nostr.torinos.network.RelayListSynchronizer
import com.nostr.torinos.ui.components.EditableImage
import com.nostr.torinos.ui.components.ImageCropperDialog
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.rememberImagePickerLauncher
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.components.rememberSyncedTextFieldValue
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.profile.EditProfileViewModel
import com.nostr.torinos.ui.settings.setPlainText
import com.nostr.torinos.util.loggingExceptionHandler
import com.nostr.torinos.util.logException
import kotlinx.coroutines.awaitCancellation

@Composable
fun KeySetupScreen(onSetupComplete: (pubkeyHex: String) -> Unit, onDismiss: (() -> Unit)? = null) {
    var importKey by remember { mutableStateOf("") }
    var showImportForm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generatedInfo by remember { mutableStateOf<Pair<String, String>?>(null) } // priv, pub
    var initialProfilePubkey by remember { mutableStateOf<String?>(null) }
    var pendingUsageConsentAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var storedAccounts by remember { mutableStateOf<List<StoredAccount>>(emptyList()) }
    var storedProfiles by remember { mutableStateOf<Map<String, NostrProfile>>(emptyMap()) }
    var accountLoginError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uiExceptionHandler = remember {
        loggingExceptionHandler("KeySetupScreen", "Uncaught UI coroutine exception")
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val saveToPasswordManager = rememberPasswordManagerSaver()
    val scrollState = rememberScrollState()

    LaunchedEffect(showImportForm) {
        scrollState.scrollTo(0)
    }

    LaunchedEffect(Unit) {
        storedAccounts = runCatching { KeyStorage.listAccounts() }
            .getOrElse { e ->
                logException("KeySetupScreen", e, "Failed to load stored accounts")
                emptyList()
            }
    }

    LaunchedEffect(Unit) {
        NostrRepository.events(STORED_ACCOUNT_PROFILES_SUBSCRIPTION_ID).collect { event ->
            if (event.kind != 0) return@collect
            ProfileCache.putEvent(event)?.let { profile ->
                storedProfiles = storedProfiles + (event.pubkey to profile)
            }
        }
    }

    LaunchedEffect(storedAccounts) {
        if (storedAccounts.isEmpty()) return@LaunchedEffect

        val pubkeys = storedAccounts.map { it.pubkeyHex }
        storedProfiles = ProfileCache.getAll(pubkeys)
        try {
            NostrRepository.subscribe(
                STORED_ACCOUNT_PROFILES_SUBSCRIPTION_ID,
                NostrFilter(kinds = listOf(0), authors = pubkeys),
            )
            awaitCancellation()
        } finally {
            NostrRepository.closeSuspending(STORED_ACCOUNT_PROFILES_SUBSCRIPTION_ID)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("キャンセル") }
            }
            if (!showImportForm) {
                Text(
                    text = "Nostr の鍵を用意する",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                KeyInfoCard(
                    title = "Nostr とは",
                    body = "• Nostrは、特定の会社に依存しないSNSの仕組み（ルール）です\n• アカウントは「鍵」で管理され、どのアプリでも同じIDで使えます\n• 投稿は複数のサーバーに分散して保存され、消えにくいのが特徴です",
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "ToriNos でポストするには、あなたのアカウントになる鍵が必要です。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))

                if (generatedInfo == null && storedAccounts.isNotEmpty()) {
                    StoredAccountsSection(
                        accounts = storedAccounts,
                        profiles = storedProfiles,
                        error = accountLoginError,
                        onAccountClick = { account ->
                            pendingUsageConsentAction = {
                                scope.launch(uiExceptionHandler) {
                                    val err = runCatching {
                                        KeyStorage.switchAccount(account.pubkeyHex)
                                    }.exceptionOrNull()?.let {
                                        logException("KeySetupScreen", it, "Failed to switch stored account")
                                        "アカウントを選択できませんでした: ${it.message}"
                                    }
                                    if (err == null) onSetupComplete(account.pubkeyHex) else accountLoginError = err
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ---- 新規生成 ----
                if (generatedInfo == null) {
                    Button(
                        onClick = {
                            pendingUsageConsentAction = {
                                val kp = generateKeyPair()
                                error = null
                                generatedInfo = Pair(kp.privateKeyHex, kp.publicKeyHex)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("新しい鍵を生成する")
                    }
                } else {
                    val (priv, pub) = generatedInfo!!
                    val nsec = remember(priv) { runCatching { hexToNsec(priv) }.getOrDefault(priv) }
                    val npub = remember(pub) { runCatching { hexToNpub(pub) }.getOrDefault(pub) }
                    GeneratedKeyValues(npub = npub, nsec = nsec)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            scope.launch(uiExceptionHandler) {
                                val err = runCatching {
                                    savePrivateKeyAndVerify(priv)
                                    saveToPasswordManager(nsec, npub)
                                    FollowRepository.initializeNewAccountFollowList()
                                        .onFailure { e ->
                                            logException(
                                                "KeySetupScreen",
                                                e,
                                                "Failed to publish initial follow list",
                                            )
                                        }
                                    RelayListSynchronizer.initializeNewAccountRelayList()
                                        .onFailure { e ->
                                            logException(
                                                "KeySetupScreen",
                                                e,
                                                "Failed to publish initial relay list",
                                            )
                                        }
                                }.exceptionOrNull()?.let {
                                    logException("KeySetupScreen", it, "Failed to save generated private key")
                                    "秘密鍵を保存できませんでした: ${it.message}"
                                }
                                if (err == null) initialProfilePubkey = pub else error = err
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("この鍵で始める")
                    }
                    error?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { generatedInfo = null }) { Text("やり直す") }
                }
            }

            if (generatedInfo == null) {
                if (!showImportForm) {
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ---- 既存鍵インポート ----
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "既存の秘密鍵をインポート",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (!showImportForm) {
                            Button(
                                onClick = {
                                    error = null
                                    showImportForm = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("すでに持っている鍵で始める")
                            }
                        } else {
                            OutlinedTextField(
                                value = importKey,
                                onValueChange = { importKey = it.trim(); error = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("秘密鍵（nsec1... または hex）") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                isError = error != null,
                                supportingText = error?.let { { Text(it) } },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    errorContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    errorBorderColor = MaterialTheme.colorScheme.error,
                                    cursorColor = MaterialTheme.colorScheme.primary,
                                    errorCursorColor = MaterialTheme.colorScheme.error,
                                    errorSupportingTextColor = MaterialTheme.colorScheme.error,
                                ),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    val keyToImport = importKey
                                    pendingUsageConsentAction = {
                                        scope.launch(uiExceptionHandler) {
                                            val (err, pubkey) = validateAndSave(keyToImport, saveToPasswordManager)
                                            if (err == null && pubkey != null) onSetupComplete(pubkey) else error = err
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = importKey.isNotBlank(),
                            ) {
                                Text("インポートして始める")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingUsageConsentAction?.let { consentAction ->
        UsageConsentDialog(
            onDismiss = { pendingUsageConsentAction = null },
            onAgree = {
                pendingUsageConsentAction = null
                consentAction()
            },
        )
    }

    initialProfilePubkey?.let { pubkey ->
        InitialProfileDialog(
            pubkey = pubkey,
            onComplete = {
                initialProfilePubkey = null
                onSetupComplete(pubkey)
            },
        )
    }
}

@Composable
private fun UsageConsentDialog(
    onDismiss: () -> Unit,
    onAgree: () -> Unit,
) {
    var agreed by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val consentItems = if (isIosPlatform) {
        "• Apple の標準エンドユーザー使用許諾契約（EULA）に従ってアプリを利用します。\n" +
            "• 投稿、プロフィール、チャンネルなどの公開内容は Nostr リレーへ送信され、第三者から閲覧・保存される場合があります。\n" +
            "• 違法な内容、権利侵害、嫌がらせ、脅迫、差別、性的または暴力的な不適切コンテンツ、その他の迷惑行為を投稿しません。ToriNos は不適切なコンテンツや迷惑ユーザーを許容しません。\n" +
            "• 不適切な投稿は通報できます。通報内容は NIP-56 レポートとして送信され、確認できる形で記録されます。\n" +
            "• 迷惑ユーザーはブロックできます。ブロックしたユーザーの投稿は自分のフィードから直ちに非表示になります。\n" +
            "• NG ワード設定により、不適切または見たくない語句を含む投稿をフィルタできます。\n" +
            "• 秘密鍵はアカウントの利用に必要な情報であり、自分の責任で安全に保管します。"
    } else {
        "• 投稿、プロフィール、チャンネルなどの公開内容は Nostr リレーへ送信され、第三者から閲覧・保存される場合があります。\n" +
            "• 違法な内容、権利侵害、嫌がらせ、脅迫、差別、性的または暴力的な不適切コンテンツ、その他の迷惑行為を投稿しません。ToriNos は不適切なコンテンツや迷惑ユーザーを許容しません。\n" +
            "• 不適切な投稿は通報できます。迷惑ユーザーはブロックでき、投稿は自分のフィードから直ちに非表示になります。\n" +
            "• NG ワード設定により、不適切または見たくない語句を含む投稿をフィルタできます。\n" +
            "• 秘密鍵はアカウントの利用に必要な情報であり、自分の責任で安全に保管します。"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("利用条件への同意") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "ToriNos を利用する前に、以下を確認してください。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = consentItems,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isIosPlatform) {
                    TextButton(
                        onClick = {
                            uriHandler.openUri("https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")
                        },
                    ) {
                        Text("Apple 標準 EULA を開く")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = agreed,
                        onCheckedChange = { agreed = it },
                    )
                    Text(
                        text = "上記の内容に同意します",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                enabled = agreed,
            ) {
                Text("同意して続ける")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun InitialProfileDialog(
    pubkey: String,
    onComplete: () -> Unit,
    viewModel: EditProfileViewModel = viewModel(
        key = "initial-profile-$pubkey",
        factory = viewModelFactory { initializer { EditProfileViewModel() } },
    ),
) {
    val state by viewModel.state.collectAsState()
    var displayNameValue by rememberSyncedTextFieldValue(state.displayName)
    var imageToCrop by remember { mutableStateOf<EditableImage?>(null) }
    val dismissKeyboard = rememberDismissKeyboard()
    val pickImage = rememberImagePickerLauncher { bytes, mime ->
        if (bytes != null && mime != null) imageToCrop = EditableImage(bytes, mime)
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.clearSaved()
            onComplete()
        }
    }

    Dialog(onDismissRequest = { if (!state.isSaving) onComplete() }) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("プロフィールを設定", style = MaterialTheme.typography.titleMedium)

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AvatarCircle(
                        pubkey = pubkey,
                        name = state.displayName.ifBlank { state.name }.ifBlank { null },
                        pictureUrl = state.picture.ifBlank { null },
                        size = 80,
                    )
                }

                OutlinedTextField(
                    value = displayNameValue,
                    onValueChange = {
                        displayNameValue = it
                        viewModel.onDisplayNameChange(it.text)
                        viewModel.onNameChange(it.text)
                    },
                    label = { Text("名前") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSaving,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.picture,
                        onValueChange = viewModel::onPictureChange,
                        label = { Text("プロフィール画像URL") },
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
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "画像を選択してアップロード",
                            )
                        }
                    }
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onComplete, enabled = !state.isSaving) { Text("あとで") }
                    Button(
                        onClick = {
                            viewModel.initFrom(NostrProfile())
                            viewModel.onNameChange(state.name)
                            viewModel.onDisplayNameChange(state.displayName)
                            viewModel.onPictureChange(state.picture)
                            viewModel.save()
                        },
                        enabled = !state.isSaving &&
                            !state.isUploadingImage &&
                            (state.displayName.isNotBlank() || state.picture.isNotBlank()),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存して始める")
                        }
                    }
                }
            }
        }
    }

    imageToCrop?.let { image ->
        ImageCropperDialog(
            image = image,
            title = "プロフィール画像を調整",
            aspectRatio = 1f,
            circularMask = true,
            onDismiss = { imageToCrop = null },
            onCropped = { bytes, mime ->
                imageToCrop = null
                viewModel.uploadProfileImage(bytes, mime)
            },
        )
    }
}

@Composable
private fun GeneratedKeyValues(npub: String, nsec: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "公開鍵（npub）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SelectionContainer {
                Text(
                    text = npub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "秘密鍵（ログインに必要です。必ずメモしてください。）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nsec,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    IconButton(onClick = { scope.launch { clipboard.setPlainText(nsec) } }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "コピー",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoredAccountsSection(
    accounts: List<StoredAccount>,
    profiles: Map<String, NostrProfile>,
    error: String?,
    onAccountClick: (StoredAccount) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "保存済みアカウントでログイン",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            accounts.forEach { account ->
                val profile = profiles[account.pubkeyHex]
                Button(
                    onClick = { onAccountClick(account) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AvatarCircle(
                            pubkey = account.pubkeyHex,
                            name = profile?.bestName,
                            pictureUrl = profile?.picture,
                            size = 40,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            ProfileNameText(
                                profile = profile,
                                fallback = account.pubkeyHex.take(8),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = shortNpub(account.npub),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun shortNpub(npub: String): String =
    if (npub.length <= 24) npub else "${npub.take(14)}...${npub.takeLast(8)}"

private const val STORED_ACCOUNT_PROFILES_SUBSCRIPTION_ID = "setup-stored-account-profiles"

@Composable
private fun KeyInfoCard(
    title: String,
    body: String,
    isWarning: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isWarning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isWarning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isWarning) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** (errorMessage, pubkeyHex) を返す。成功時は errorMessage = null、pubkeyHex = 公開鍵 hex */
private suspend fun validateAndSave(
    input: String,
    saveToPasswordManager: suspend (nsec: String, npub: String) -> Unit,
): Pair<String?, String?> {
    return try {
        val hexKey = normalizePrivateKey(input)
        val bytes = hexKey.fromHex()
        val pubkeyHex = derivePublicKey(bytes).toHex()
        val nsec = hexToNsec(hexKey)
        val npub = hexToNpub(pubkeyHex)
        try {
            savePrivateKeyAndVerify(hexKey)
            saveToPasswordManager(nsec, npub)
        } catch (e: Exception) {
            logException("KeySetupScreen", e, "Failed to save imported private key")
            return Pair("秘密鍵を保存できませんでした: ${e.message}", null)
        }
        Pair(null, pubkeyHex)
    } catch (e: Exception) {
        logException("KeySetupScreen", e, "Invalid private key input")
        Pair("無効な秘密鍵です: ${e.message}", null)
    }
}

private suspend fun savePrivateKeyAndVerify(hexKey: String) {
    val normalized = normalizePrivateKey(hexKey)
    KeyStorage.savePrivateKey(normalized)
    val saved = KeyStorage.loadPrivateKey()
        ?: error("保存した秘密鍵を読み戻せませんでした")
    check(normalizePrivateKey(saved) == normalized) {
        "保存した秘密鍵が一致しません"
    }
}
