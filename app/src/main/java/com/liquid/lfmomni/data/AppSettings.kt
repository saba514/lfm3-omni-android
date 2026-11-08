package com.liquid.lfmomni.data

import android.content.Context
import com.liquid.lfmomni.model.Role

enum class InferenceMode { NATIVE, SERVER }

data class AppSettings(
    val mode: InferenceMode = InferenceMode.NATIVE,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val nCtx: Int = 8192,
    val nThreads: Int = 6,
    val nBatch: Int = 512,
    val maxTokens: Int = 512,
    val temperature: Float = 0f,
    val topK: Int = 40,
    val serverUrl: String = "http://127.0.0.1:8080",
    val serverModel: String = "lfm3-omni",
    val autoSpeak: Boolean = false,
    val videoMaxFrames: Int = 24,
    val imageMaxSize: Int = 768,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "あなたは端末内で動く親切なアシスタントです。日本語で、簡潔に答えてください。" +
                "画像・音声・動画について聞かれたときは、見聞きした内容に基づいて答えてください。"

        const val SYSTEM_PROMPT_VIDEO =
            "Answer questions about the video using what you see and hear."
    }
}

/** 設定の永続化 (SharedPreferences)。値は小さく、頻繁に読むので十分 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("lfm3_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            mode = runCatching {
                InferenceMode.valueOf(prefs.getString(KEY_MODE, d.mode.name)!!)
            }.getOrDefault(d.mode),
            systemPrompt = prefs.getString(KEY_SYSTEM, d.systemPrompt) ?: d.systemPrompt,
            nCtx = prefs.getInt(KEY_NCTX, d.nCtx),
            nThreads = prefs.getInt(KEY_THREADS, d.nThreads),
            nBatch = prefs.getInt(KEY_BATCH, d.nBatch),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, d.maxTokens),
            temperature = prefs.getFloat(KEY_TEMP, d.temperature),
            topK = prefs.getInt(KEY_TOPK, d.topK),
            serverUrl = prefs.getString(KEY_SERVER_URL, d.serverUrl) ?: d.serverUrl,
            serverModel = prefs.getString(KEY_SERVER_MODEL, d.serverModel) ?: d.serverModel,
            autoSpeak = prefs.getBoolean(KEY_AUTO_SPEAK, d.autoSpeak),
            videoMaxFrames = prefs.getInt(KEY_VIDEO_FRAMES, d.videoMaxFrames),
            imageMaxSize = prefs.getInt(KEY_IMAGE_SIZE, d.imageMaxSize),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_SYSTEM, settings.systemPrompt)
            .putInt(KEY_NCTX, settings.nCtx)
            .putInt(KEY_THREADS, settings.nThreads)
            .putInt(KEY_BATCH, settings.nBatch)
            .putInt(KEY_MAX_TOKENS, settings.maxTokens)
            .putFloat(KEY_TEMP, settings.temperature)
            .putInt(KEY_TOPK, settings.topK)
            .putString(KEY_SERVER_URL, settings.serverUrl)
            .putString(KEY_SERVER_MODEL, settings.serverModel)
            .putBoolean(KEY_AUTO_SPEAK, settings.autoSpeak)
            .putInt(KEY_VIDEO_FRAMES, settings.videoMaxFrames)
            .putInt(KEY_IMAGE_SIZE, settings.imageMaxSize)
            .apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_SYSTEM = "system_prompt"
        const val KEY_NCTX = "n_ctx"
        const val KEY_THREADS = "n_threads"
        const val KEY_BATCH = "n_batch"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TEMP = "temperature"
        const val KEY_TOPK = "top_k"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SERVER_MODEL = "server_model"
        const val KEY_AUTO_SPEAK = "auto_speak"
        const val KEY_VIDEO_FRAMES = "video_frames"
        const val KEY_IMAGE_SIZE = "image_size"
    }
}

fun Role.isUser(): Boolean = this == Role.USER
