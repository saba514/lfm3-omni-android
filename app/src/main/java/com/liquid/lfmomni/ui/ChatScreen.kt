package com.liquid.lfmomni.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquid.lfmomni.model.Attachment
import com.liquid.lfmomni.model.AttachmentKind
import com.liquid.lfmomni.model.ChatMessage
import com.liquid.lfmomni.model.Role
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    onOpenModels: () -> Unit,
) {
    val state by viewModel.chat.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showAttachMenu by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            val name = uri.lastPathSegment ?: "file"
            val mime = context.contentResolver.getType(uri)
            val kind = attachmentKindOf(name, mime) ?: return@forEach
            viewModel.addAttachment(Attachment(kind, uri.toString(), name))
        }
    }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { uri ->
                viewModel.addAttachment(
                    Attachment(AttachmentKind.IMAGE, uri.toString(), "カメラ撮影", 0L)
                )
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording(scope)
        }
    }

    LaunchedEffect(state.messages.size, state.streamText.length) {
        val target = state.messages.size + if (state.streamText.isNotEmpty()) 1 else 0
        if (target > 0) {
            runCatching { listState.animateScrollToItem(target - 1) }
        }
    }

    Column(Modifier.fillMaxSize()) {

        TopAppBar(
            title = {
                Column {
                    Text("LFM3 Omni Local", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            state.isGenerating -> "推論中… ${state.status}"
                            settings.mode.name == "NATIVE" && state.engineReady -> "端末内推論 • 準備完了"
                            settings.mode.name == "NATIVE" -> "端末内推論 • 未ロード"
                            else -> "サーバー接続モード"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                if (state.messages.isNotEmpty()) {
                    IconButton(onClick = { viewModel.regenerate() }, enabled = !state.isGenerating) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再生成")
                    }
                }
                IconButton(onClick = { viewModel.newChat() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "新しい会話")
                }
            },
        )

        if (state.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { viewModel.clearError() }) { Text("閉じる") }
                        if (state.error?.contains("モデル") == true) {
                            TextButton(onClick = onOpenModels) { Text("モデル画面へ") }
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty() && state.streamText.isEmpty()) {
                item { EmptyState() }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    onSpeak = { viewModel.speak(message.text) },
                )
            }
            if (state.streamText.isNotEmpty()) {
                item {
                    MessageBubble(
                        message = ChatMessage(role = Role.ASSISTANT, text = state.streamText + "▍"),
                        onSpeak = null,
                    )
                }
            }
            if (state.isPreparing) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            state.status.ifBlank { "準備しています…" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (state.isRecording) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("録音中… 話し終えたら停止を押してください", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                val amplitude by viewModel.recorder.amplitude.collectAsStateWithLifecycle()
                LinearProgressIndicator(
                    progress = { amplitude.coerceIn(0.02f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.attachments.take(4).forEach { attachment ->
                    AssistChip(
                        onClick = { viewModel.removeAttachment(attachment) },
                        label = {
                            Text(
                                when (attachment.kind) {
                                    AttachmentKind.IMAGE -> "画像"
                                    AttachmentKind.VIDEO -> "動画"
                                    AttachmentKind.AUDIO -> "音声"
                                } + " ×",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                if (state.attachments.size > 4) {
                    Text("+${state.attachments.size - 4}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.navigationBarsPadding().imePadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box {
                        IconButton(onClick = { showAttachMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "添付")
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("写真・動画・音声を選ぶ") },
                                onClick = {
                                    showAttachMenu = false
                                    pickFiles.launch(arrayOf("image/*", "video/*", "audio/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("カメラで撮影") },
                                onClick = {
                                    showAttachMenu = false
                                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                    val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    cameraUri = uri
                                    takePicture.launch(uri)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.isRecording) "録音を停止" else "マイクで録音") },
                                onClick = {
                                    showAttachMenu = false
                                    if (state.isRecording) {
                                        viewModel.finishRecording()
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) viewModel.startRecording(scope)
                                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.input,
                        onValueChange = { viewModel.setInput(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("メッセージを入力 (日本語/English)") },
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                    )

                    Spacer(Modifier.width(4.dp))

                    if (state.isGenerating) {
                        FilledIconButton(onClick = { viewModel.stopGeneration() }) {
                            Icon(Icons.Filled.Clear, contentDescription = "停止")
                        }
                    } else {
                        FilledIconButton(
                            onClick = { viewModel.send() },
                            enabled = state.input.isNotBlank() || state.attachments.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = "送信")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LFM3-Omni-2B を端末内で実行します", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "テキスト・画像・音声・動画をまとめて扱えます。\n" +
                "例:「この動画で何が起きている？」「10秒付近では何が起きている？」\n" +
                "最初に [モデル] 画面でモデルを取得してください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onSpeak: (() -> Unit)?) {
    val isUser = message.role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 0.95f),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    message.isError -> MaterialTheme.colorScheme.errorContainer
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (message.attachments.isNotEmpty()) {
                    Text(
                        message.attachments.joinToString(" / ") {
                            when (it.kind) {
                                AttachmentKind.IMAGE -> "🖼 画像"
                                AttachmentKind.VIDEO -> "🎞 動画"
                                AttachmentKind.AUDIO -> "🎤 音声"
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = message.text.ifBlank { "(添付のみ)" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!isUser && message.text.isNotBlank() && !message.isError && onSpeak != null) {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onSpeak) {
                            Text("読み上げ", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
