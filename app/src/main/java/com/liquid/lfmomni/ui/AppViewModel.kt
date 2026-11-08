package com.liquid.lfmomni.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liquid.lfmomni.data.AppSettings
import com.liquid.lfmomni.data.ChatStore
import com.liquid.lfmomni.data.InferenceMode
import com.liquid.lfmomni.data.SettingsStore
import com.liquid.lfmomni.engine.Engine
import com.liquid.lfmomni.engine.EngineEvent
import com.liquid.lfmomni.engine.GenerationRequest
import com.liquid.lfmomni.engine.NativeEngine
import com.liquid.lfmomni.engine.ServerEngine
import com.liquid.lfmomni.media.AudioRecorder
import com.liquid.lfmomni.media.MediaPreparer
import com.liquid.lfmomni.media.SpeechOutput
import com.liquid.lfmomni.model.Attachment
import com.liquid.lfmomni.model.AttachmentKind
import com.liquid.lfmomni.model.ChatMessage
import com.liquid.lfmomni.model.MediaItem
import com.liquid.lfmomni.model.ModelCatalog
import com.liquid.lfmomni.model.ModelRepository
import com.liquid.lfmomni.model.PreparedTurn
import com.liquid.lfmomni.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ModelFileInfo(
    val name: String,
    val sizeBytes: Long,
    val isModel: Boolean,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val attachments: List<Attachment> = emptyList(),
    val isGenerating: Boolean = false,
    val isPreparing: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val streamText: String = "",
    val engineReady: Boolean = false,
    val engineLabel: String = "",
    val contextUsed: Int = 0,
    val contextSize: Int = 0,
    val lastReply: String? = null,
    val recordedAudio: File? = null,
    val isRecording: Boolean = false,
)

data class ModelUiState(
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val currentFile: String = "",
    val message: String = "",
    val error: String? = null,
    val files: List<ModelFileInfo> = emptyList(),
    val ready: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val chatStore = ChatStore(appContext)
    private val modelRepo = ModelRepository(appContext)
    private val preparer = MediaPreparer(appContext)
    val speech = SpeechOutput(appContext)
    val recorder = AudioRecorder(appContext)

    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()

    private val _model = MutableStateFlow(ModelUiState())
    val model: StateFlow<ModelUiState> = _model.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private var engine: Engine? = null
    private var generationJob: Job? = null
    private var recordScope: CoroutineScope? = null

    init {
        _chat.update { it.copy(messages = chatStore.load()) }
        refreshModelFiles()
    }

    // ---------------------------------------------------------------- settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        settingsStore.save(next)
        // 推論設定が変わったらエンジンを作り直す
        if (next.mode != engine.modeOf()) {
            releaseEngine()
        }
    }

    fun resetSystemPrompt() {
        val s = _settings.value
        val next = s.copy(systemPrompt = AppSettings.DEFAULT_SYSTEM_PROMPT)
        _settings.value = next
        settingsStore.save(next)
    }

    private fun Engine?.modeOf(): InferenceMode? = when (this) {
        is NativeEngine -> InferenceMode.NATIVE
        is ServerEngine -> InferenceMode.SERVER
        else -> null
    }

    // ------------------------------------------------------------------ models

    fun refreshModelFiles() {
        val files = modelRepo.installedFiles().map {
            ModelFileInfo(it.name, it.length(), it.name == ModelCatalog.MODEL_FILE)
        }
        _model.update { it.copy(files = files, ready = modelRepo.isReady()) }
    }

    fun downloadModels() {
        if (_model.value.downloading) return
        viewModelScope.launch(Dispatchers.IO) {
            _model.update { it.copy(downloading = true, error = null, message = "接続しています…", progress = 0f) }
            try {
                for (fileName in listOf(ModelCatalog.MODEL_FILE, ModelCatalog.MMPROJ_FILE)) {
                    if (modelRepo.exists(fileName)) continue
                    _model.update { it.copy(currentFile = fileName, message = "ダウンロード中: $fileName") }
                    modelRepo.download(fileName) { p ->
                        _model.update {
                            it.copy(
                                progress = p.fraction,
                                message = "ダウンロード中: ${p.fileName} " +
                                    "${formatBytes(p.bytes)} / ${formatBytes(p.total)}",
                            )
                        }
                    }
                }
                _model.update { it.copy(message = "モデルの取得が完了しました", downloading = false) }
            } catch (t: Throwable) {
                _model.update {
                    it.copy(
                        downloading = false,
                        error = "ダウンロードに失敗しました: ${t.message}",
                    )
                }
            } finally {
                refreshModelFiles()
            }
        }
    }

    fun importModel(uri: Uri, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _model.update { it.copy(downloading = true, error = null, message = "取り込んでいます: $fileName") }
            try {
                modelRepo.importFrom(uri, fileName) { copied ->
                    _model.update { it.copy(message = "取り込み中: ${formatBytes(copied)}") }
                }
                _model.update { it.copy(message = "取り込みました: $fileName", downloading = false) }
            } catch (t: Throwable) {
                _model.update { it.copy(downloading = false, error = "取り込みに失敗しました: ${t.message}") }
            } finally {
                refreshModelFiles()
            }
        }
    }

    fun deleteModel(fileName: String) {
        releaseEngine()
        modelRepo.delete(fileName)
        refreshModelFiles()
        _model.update { it.copy(message = "$fileName を削除しました") }
    }

    // -------------------------------------------------------------------- chat

    fun setInput(text: String) {
        _chat.update { it.copy(input = text) }
    }

    fun addAttachment(attachment: Attachment) {
        _chat.update { it.copy(attachments = it.attachments + attachment) }
    }

    fun removeAttachment(attachment: Attachment) {
        _chat.update { it.copy(attachments = it.attachments - attachment) }
    }

    fun clearError() {
        _chat.update { it.copy(error = null) }
    }

    fun newChat() {
        engine?.resetConversation()
        chatStore.clear()
        _chat.update {
            it.copy(messages = emptyList(), streamText = "", status = "", error = null, contextUsed = 0)
        }
    }

    fun stopGeneration() {
        engine?.cancel()
    }

    fun startRecording(scope: CoroutineScope) {
        recordScope = scope
        val started = recorder.start(scope)
        _chat.update { it.copy(isRecording = started, error = if (started) null else "マイクを開始できませんでした") }
    }

    fun finishRecording() {
        viewModelScope.launch {
            val result = recorder.stop()
            recordScope = null
            if (result == null) {
                _chat.update { it.copy(isRecording = false) }
                return@launch
            }
            _chat.update {
                it.copy(
                    isRecording = false,
                    recordedAudio = result.wavFile,
                    attachments = it.attachments + Attachment(
                        kind = AttachmentKind.AUDIO,
                        uriString = Uri.fromFile(result.wavFile).toString(),
                        displayName = "録音 ${result.durationMs / 1000}秒",
                        durationMs = result.durationMs,
                    ),
                )
            }
        }
    }

    fun speak(text: String) = speech.speak(text)

    fun playAudioFile(file: File) = speech.playWav(file)

    fun stopSpeaking() = speech.stop()

    fun send() {
        val state = _chat.value
        if (state.isGenerating) return
        if (state.input.isBlank() && state.attachments.isEmpty()) return

        val userMessage = ChatMessage(
            role = Role.USER,
            text = state.input.trim(),
            attachments = state.attachments,
        )
        _chat.update {
            it.copy(
                messages = it.messages + userMessage,
                input = "",
                attachments = emptyList(),
                recordedAudio = null,
                error = null,
                isGenerating = true,
                isPreparing = true,
                streamText = "",
                status = "入力を準備しています…",
            )
        }
        chatStore.save(_chat.value.messages)
        runGeneration(userMessage, fullTranscript = false)
    }

    fun regenerate() {
        if (_chat.value.isGenerating) return
        val messages = _chat.value.messages.toMutableList()
        while (messages.isNotEmpty() && messages.last().role != Role.USER) {
            messages.removeAt(messages.size - 1)
        }
        if (messages.isEmpty()) return
        _chat.update {
            it.copy(
                messages = messages,
                isGenerating = true,
                isPreparing = true,
                streamText = "",
                error = null,
                status = "会話を再構築しています…",
            )
        }
        runGeneration(messages.last(), fullTranscript = true)
    }

    private fun runGeneration(lastUserMessage: ChatMessage, fullTranscript: Boolean) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            try {
                if (!ensureEngine()) {
                    finishError(_chat.value.error ?: "エンジンを準備できませんでした")
                    return@launch
                }
                val activeEngine = engine ?: return@launch

                val turn: PreparedTurn = if (fullTranscript) {
                    activeEngine.resetConversation()
                    buildTranscript(_chat.value.messages)
                } else {
                    preparer.prepare(
                        text = lastUserMessage.text,
                        attachments = lastUserMessage.attachments,
                        settings = _settings.value,
                    ) { message -> _chat.update { it.copy(status = message) } }
                }

                _chat.update { it.copy(isPreparing = false, status = "推論しています…") }

                val request = GenerationRequest(
                    turn = turn,
                    history = _chat.value.messages,
                    systemPrompt = _settings.value.systemPrompt,
                    maxTokens = _settings.value.maxTokens,
                    temperature = _settings.value.temperature,
                    topK = _settings.value.topK,
                    videoMode = turn.note == "video",
                    rawPrompt = fullTranscript,
                )

                var buffer = StringBuilder()
                activeEngine.generate(request).collect { event ->
                    when (event) {
                        is EngineEvent.Token -> {
                            buffer.append(event.text)
                            _chat.update { it.copy(streamText = buffer.toString()) }
                        }

                        is EngineEvent.Status -> _chat.update { it.copy(status = event.message) }

                        is EngineEvent.Done -> {
                            val text = event.fullText.ifBlank { buffer.toString() }
                            val assistant = ChatMessage(role = Role.ASSISTANT, text = text.trim())
                            _chat.update {
                                it.copy(
                                    messages = it.messages + assistant,
                                    isGenerating = false,
                                    isPreparing = false,
                                    streamText = "",
                                    status = "",
                                    contextUsed = event.contextUsed,
                                    contextSize = if (it.contextSize == 0) _settings.value.nCtx else it.contextSize,
                                    lastReply = text,
                                )
                            }
                            chatStore.save(_chat.value.messages)
                            if (_settings.value.autoSpeak && text.isNotBlank()) {
                                speech.speak(text)
                            }
                        }

                        is EngineEvent.Failure -> finishError(event.message)
                    }
                }
            } catch (t: Throwable) {
                finishError(t.message ?: "生成に失敗しました")
            }
        }
    }

    private fun finishError(message: String) {
        _chat.update {
            it.copy(
                isGenerating = false,
                isPreparing = false,
                status = "",
                streamText = "",
                error = message,
                messages = it.messages + ChatMessage(
                    role = Role.ASSISTANT,
                    text = "⚠ $message",
                    isError = true,
                ),
            )
        }
        chatStore.save(_chat.value.messages)
    }

    /** 会話全体を 1 本のプロンプトに再構築する (再生成用) */
    private suspend fun buildTranscript(messages: List<ChatMessage>): PreparedTurn {
        val media = ArrayList<MediaItem>()
        val transcript = StringBuilder()
        transcript.append("<|im_start|>system\n")
            .append(_settings.value.systemPrompt.ifBlank { AppSettings.DEFAULT_SYSTEM_PROMPT })
            .append("<|im_end|>\n")

        var videoSeen = false
        for (message in messages) {
            when (message.role) {
                Role.USER -> {
                    if (message.isError) continue
                    val turn = preparer.prepare(
                        text = message.text,
                        attachments = message.attachments,
                        settings = _settings.value,
                    ) { status -> _chat.update { it.copy(status = status) } }
                    if (turn.note == "video") videoSeen = true
                    transcript.append("<|im_start|>user\n").append(turn.body).append("<|im_end|>\n")
                    media.addAll(turn.media)
                }

                Role.ASSISTANT -> {
                    if (message.isError || message.text.isBlank()) continue
                    transcript.append("<|im_start|>assistant\n").append(message.text).append("<|im_end|>\n")
                }

                Role.SYSTEM -> Unit
            }
        }
        if (videoSeen) {
            transcript.append("\n")
        }
        transcript.append("<|im_start|>assistant\n<think></think>")
        return PreparedTurn(transcript.toString(), media, if (videoSeen) "video" else null)
    }

    private suspend fun ensureEngine(): Boolean {
        val current = engine
        if (current != null && current.isLoaded()) return true

        if (!modelRepo.isReady() && _settings.value.mode == InferenceMode.NATIVE) {
            _chat.update {
                it.copy(error = "モデルファイルがありません。[モデル] 画面から LFM3-Omni-2B をダウンロードしてください。")
            }
            return false
        }

        val created: Engine = current ?: when (_settings.value.mode) {
            InferenceMode.NATIVE -> NativeEngine(
                _settings.value,
                modelRepo.modelFile(),
                modelRepo.mmprojFile().takeIf { it.exists() },
            )

            InferenceMode.SERVER -> ServerEngine(_settings.value)
        }.also { engine = it }

        val result = created.load { message -> _chat.update { it.copy(status = message) } }
        return result.fold(
            onSuccess = {
                val caps = (created as? NativeEngine)?.capabilities.orEmpty()
                _chat.update {
                    it.copy(
                        engineReady = true,
                        engineLabel = created.label,
                        status = "",
                        contextSize = _settings.value.nCtx,
                        error = null,
                    )
                }
                if (caps.isNotEmpty()) {
                    _chat.update { it.copy(status = "") }
                }
                true
            },
            onFailure = { error ->
                _chat.update { it.copy(engineReady = false, status = "", error = error.message) }
                false
            },
        )
    }

    fun releaseEngine() {
        engine?.release()
        engine = null
        _chat.update { it.copy(engineReady = false, contextUsed = 0) }
    }

    override fun onCleared() {
        generationJob?.cancel()
        engine?.release()
        speech.shutdown()
        super.onCleared()
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}

/** 添付の種類を拡張子から推定する */
fun attachmentKindOf(name: String, mimeType: String?): AttachmentKind? {
    val mime = mimeType.orEmpty()
    if (mime.startsWith("image/")) return AttachmentKind.IMAGE
    if (mime.startsWith("video/")) return AttachmentKind.VIDEO
    if (mime.startsWith("audio/")) return AttachmentKind.AUDIO
    val lower = name.lowercase()
    return when {
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp") -> AttachmentKind.IMAGE

        lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp") || lower.endsWith(".mov") -> AttachmentKind.VIDEO

        lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
            lower.endsWith(".ogg") || lower.endsWith(".flac") || lower.endsWith(".aac") -> AttachmentKind.AUDIO

        else -> null
    }
}

suspend fun queryDisplayName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
}
