package com.liquid.lfmomni.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquid.lfmomni.model.ModelCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(viewModel: AppViewModel, ready: Boolean) {
    val state by viewModel.model.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()

    val importModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importModel(it, ModelCatalog.MODEL_FILE) } }

    val importMmproj = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importModel(it, ModelCatalog.MMPROJ_FILE) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("モデル管理") })

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (ready) "モデルの準備ができています" else "モデルが未取得です",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "LiquidAI/LFM3-Omni-2B-GGUF (Q4_K_M)\n" +
                            "本体 ${ModelCatalog.MODEL_FILE}\n" +
                            "視覚・音声 ${ModelCatalog.MMPROJ_FILE}\n" +
                            "合計 約 2.3GB。Wi-Fi 接続での取得を推奨します。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (state.downloading) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(state.message, style = MaterialTheme.typography.labelSmall)
            } else {
                Button(
                    onClick = { viewModel.downloadModels() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.ready,
                ) {
                    Text(if (state.ready) "取得済み" else "Hugging Face からワンタップ取得")
                }
            }

            HorizontalDivider()

            Text("手動インポート", style = MaterialTheme.typography.titleSmall)
            Text(
                "PC 等で取得した GGUF を端末にコピーして取り込めます。ファイル名は自動で正規化します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importModel.launch(arrayOf("*/*")) }) {
                    Text("本体 GGUF")
                }
                OutlinedButton(onClick = { importMmproj.launch(arrayOf("*/*")) }) {
                    Text("mmproj")
                }
            }

            HorizontalDivider()

            Text("端末内のファイル", style = MaterialTheme.typography.titleSmall)
            if (state.files.isEmpty()) {
                Text("まだありません", style = MaterialTheme.typography.bodySmall)
            } else {
                state.files.forEach { file ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodySmall)
                            Text(
                                AppViewModel.formatBytes(file.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewModel.deleteModel(file.name) }) { Text("削除") }
                    }
                }
            }

            HorizontalDivider()

            Text("エンジン", style = MaterialTheme.typography.titleSmall)
            Text(
                chat.engineLabel.ifBlank { "未ロード" } +
                    if (chat.contextUsed > 0) " • コンテキスト ${chat.contextUsed}/${chat.contextSize}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.releaseEngine() }) { Text("メモリから解放") }
            }

            if (state.error != null) {
                Text(state.error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.message.isNotBlank() && !state.downloading) {
                Text(state.message, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "取得したモデルは端末内 (files/models) にのみ保存され、外部へ送信されません。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
