package com.liquid.lfmomni.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liquid.lfmomni.data.AppSettings
import com.liquid.lfmomni.data.InferenceMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val s by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("設定") })

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("推論モード", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.mode == InferenceMode.NATIVE,
                    onClick = {
                        viewModel.releaseEngine()
                        viewModel.updateSettings { it.copy(mode = InferenceMode.NATIVE) }
                    },
                    label = { Text("端末内 (JNI)") },
                )
                FilterChip(
                    selected = s.mode == InferenceMode.SERVER,
                    onClick = {
                        viewModel.releaseEngine()
                        viewModel.updateSettings { it.copy(mode = InferenceMode.SERVER) }
                    },
                    label = { Text("ローカルサーバー") },
                )
            }
            Text(
                "端末内: llama.cpp をアプリ内で実行します。\n" +
                    "ローカルサーバー: Termux 等で起動した llama-server (localhost:8080) に接続します。検証用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (s.mode == InferenceMode.SERVER) {
                OutlinedTextField(
                    value = s.serverUrl,
                    onValueChange = { value -> viewModel.updateSettings { it.copy(serverUrl = value) } },
                    label = { Text("サーバー URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.serverModel,
                    onValueChange = { value -> viewModel.updateSettings { it.copy(serverModel = value) } },
                    label = { Text("モデル名 (API の model フィールド)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()

            Text("システムプロンプト", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = s.systemPrompt,
                onValueChange = { value -> viewModel.updateSettings { it.copy(systemPrompt = value) } },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                label = { Text("指示") },
            )
            TextButton(onClick = { viewModel.resetSystemPrompt() }) { Text("既定に戻す") }

            HorizontalDivider()

            Text("生成", style = MaterialTheme.typography.titleSmall)
            SliderRow(
                label = "最大トークン数: ${s.maxTokens}",
                value = s.maxTokens.toFloat(),
                range = 64f..2048f,
                steps = 30,
                onChange = { v -> viewModel.updateSettings { it.copy(maxTokens = v.toInt()) } },
            )
            SliderRow(
                label = "温度: ${"%.2f".format(s.temperature)} (0 = 決定的)",
                value = s.temperature,
                range = 0f..1.2f,
                steps = 11,
                onChange = { v -> viewModel.updateSettings { it.copy(temperature = v) } },
            )

            HorizontalDivider()

            Text("性能 (端末内モード)", style = MaterialTheme.typography.titleSmall)
            SliderRow(
                label = "CPU スレッド: ${s.nThreads}",
                value = s.nThreads.toFloat(),
                range = 2f..8f,
                steps = 5,
                onChange = { v ->
                    viewModel.releaseEngine()
                    viewModel.updateSettings { it.copy(nThreads = v.toInt()) }
                },
            )
            SliderRow(
                label = "コンテキスト: ${s.nCtx} トークン",
                value = s.nCtx.toFloat(),
                range = 2048f..16384f,
                steps = 6,
                onChange = { v ->
                    viewModel.releaseEngine()
                    viewModel.updateSettings { it.copy(nCtx = v.toInt()) }
                },
            )
            Text(
                "メモリ 12GB の端末では 8192 前後が安定します。大きすぎると強制終了の原因になります。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("メディア", style = MaterialTheme.typography.titleSmall)
            Text(
                "動画は 2fps で切り出し、${s.videoMaxFrames} フレーム (約 ${"%.1f".format(s.videoMaxFrames / 2f)} 秒) まで解析します。",
                style = MaterialTheme.typography.bodySmall,
            )
            SliderRow(
                label = "動画フレーム数: ${s.videoMaxFrames}",
                value = s.videoMaxFrames.toFloat(),
                range = 8f..96f,
                steps = 10,
                onChange = { v -> viewModel.updateSettings { it.copy(videoMaxFrames = v.toInt().let { n -> (n / 2) * 2 }) } },
            )
            SliderRow(
                label = "画像の最大辺: ${s.imageMaxSize}px",
                value = s.imageMaxSize.toFloat(),
                range = 384f..1280f,
                steps = 6,
                onChange = { v -> viewModel.updateSettings { it.copy(imageMaxSize = v.toInt()) } },
            )

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("回答を自動で読み上げ", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Android 標準の TTS を使います (LFM3-Omni 自身の音声生成ではありません)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = s.autoSpeak,
                    onCheckedChange = { v -> viewModel.updateSettings { it.copy(autoSpeak = v) } },
                )
            }

            HorizontalDivider()

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("このアプリについて", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "LFM3-Omni-2B (Liquid AI) を llama.cpp / libmtmd で端末内実行します。\n" +
                            "推論エンジンは llama.cpp b11960 (v0.8.0) 系の公開 API を使用。\n" +
                            "画像・音声・動画は外部送信しません。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
