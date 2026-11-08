package com.liquid.lfmomni.engine

import com.liquid.lfmomni.data.AppSettings
import com.liquid.lfmomni.model.ChatMessage
import com.liquid.lfmomni.model.MediaItem
import com.liquid.lfmomni.model.PreparedTurn
import com.liquid.lfmomni.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface EngineEvent {
    data class Token(val text: String) : EngineEvent
    data class Done(
        val fullText: String,
        val charsPerSecond: Double,
        val contextUsed: Int,
    ) : EngineEvent

    data class Failure(val message: String) : EngineEvent
    data class Status(val message: String) : EngineEvent
}

data class GenerationRequest(
    val turn: PreparedTurn,
    /** サーバーモードで会話全体を送るときに使う */
    val history: List<ChatMessage>,
    val systemPrompt: String,
    val maxTokens: Int,
    val temperature: Float,
    val topK: Int,
    /** 動画ターンかどうか (システムプロンプトの切り替えに使う) */
    val videoMode: Boolean = false,
    /** true なら turn.body をそのまま 1 本のプロンプトとして使う (会話の再構築用) */
    val rawPrompt: Boolean = false,
)

interface Engine {
    val label: String

    fun isLoaded(): Boolean

    suspend fun load(onStatus: (String) -> Unit): Result<Unit>

    fun resetConversation()

    fun generate(request: GenerationRequest): Flow<EngineEvent>

    fun cancel()

    fun release()
}

private fun statusText(code: Int): String = when (code) {
    0 -> "正常終了"
    -1 -> "モデルが読み込まれていません"
    -2 -> "プロンプトのトークナイズに失敗しました (メディア数とマーカー数の不一致など)"
    -3 -> "推論 (llama_decode) に失敗しました。メモリ不足の可能性があります"
    -4 -> "生成を中断しました"
    -5 -> "メディアのエンコードに失敗しました。画像サイズや動画フレーム数を減らしてください"
    else -> "不明なエラー ($code)"
}

/**
 * 端末内推論 (llama.cpp + libmtmd を JNI で直接呼ぶ)。
 *
 * プロンプトは LFM3-Omni の ChatML 風テンプレート (<|im_start|>role ... <|im_end|>) に
 * 合わせて組み立てる。メディアは本文中の "<__media__>" の位置に、Kotlin 側から
 * 同じ順序で渡す。視覚/音声の開始・終了トークンは libmtmd が付与するので書かない。
 */
class NativeEngine(
    private val settings: AppSettings,
    private val modelPath: File,
    private val mmprojPath: File?,
) : Engine {

    override val label: String = "端末内 (JNI)"

    @Volatile
    private var loaded = false

    @Volatile
    private var conversationStarted = false

    @Volatile
    var capabilities: String = ""
        private set

    private companion object {
        @Volatile
        var backendInitialized = false
    }

    override fun isLoaded(): Boolean = loaded

    override suspend fun load(onStatus: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        if (loaded) return@withContext Result.success(Unit)
        if (!LfmOmniNative.isAvailable) {
            return@withContext Result.failure(
                IllegalStateException(
                    "liblfmomni.so を読み込めませんでした。arm64-v8a 用にビルドされた APK か確認してください"
                )
            )
        }
        onStatus("ネイティブ推論エンジンを初期化しています…")
        if (!backendInitialized) {
            LfmOmniNative.backendInit()
            backendInitialized = true
        }

        onStatus("モデルを読み込んでいます (初回は 30 秒ほどかかることがあります)…")
        val rc = LfmOmniNative.loadModel(
            modelPath.absolutePath,
            mmprojPath?.absolutePath,
            settings.nCtx,
            settings.nThreads,
            settings.nBatch,
        )
        if (rc != 0) {
            return@withContext Result.failure(
                IllegalStateException("モデルの読み込みに失敗しました: ${if (rc == 1) "GGUF を開けません" else "メモリ不足の可能性があります"}")
            )
        }
        capabilities = runCatching { LfmOmniNative.capabilities() }.getOrDefault("")
        conversationStarted = false
        loaded = true
        onStatus("準備完了 (${capabilities})")
        Result.success(Unit)
    }

    override fun resetConversation() {
        if (loaded) {
            LfmOmniNative.resetContext()
        }
        conversationStarted = false
    }

    override fun cancel() {
        LfmOmniNative.stop()
    }

    override fun release() {
        if (loaded) {
            LfmOmniNative.unloadModel()
        }
        loaded = false
        conversationStarted = false
    }

    private fun buildPrompt(request: GenerationRequest): String {
        if (request.rawPrompt) {
            // 會話全体を再構築したプロンプト (system ブロックから始まる)
            return request.turn.body
        }
        val system = buildString {
            append(request.systemPrompt.ifBlank { AppSettings.DEFAULT_SYSTEM_PROMPT })
            if (request.videoMode) {
                append('\n')
                append(AppSettings.SYSTEM_PROMPT_VIDEO)
            }
        }
        val body = request.turn.body

        return buildString {
            if (!conversationStarted) {
                append("<|im_start|>system\n").append(system.trim()).append("<|im_end|>\n")
                append("<|im_start|>user\n").append(body).append("<|im_end|>\n")
            } else {
                // 直前の応答は KV に入っている。assistant の終端トークンから続ける
                append("<|im_end|>\n<|im_start|>user\n").append(body).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n<think></think>")
        }
    }

    override fun generate(request: GenerationRequest): Flow<EngineEvent> = callbackFlow {
        if (!loaded) {
            trySend(EngineEvent.Failure("モデルが読み込まれていません"))
            close()
            return@callbackFlow
        }

        val prompt = buildPrompt(request)
        val media = request.turn.media
        val rgb = arrayOfNulls<ByteArray>(media.size)
        val audio = arrayOfNulls<FloatArray>(media.size)
        val widths = IntArray(media.size)
        val heights = IntArray(media.size)
        val kinds = IntArray(media.size)
        media.forEachIndexed { index, item ->
            kinds[index] = item.kind
            widths[index] = item.width
            heights[index] = item.height
            if (item.kind == MediaItem.KIND_AUDIO) {
                audio[index] = item.samples
            } else {
                rgb[index] = item.rgb
            }
        }

        trySend(EngineEvent.Status("推論しています…"))
        val startedAt = System.nanoTime()
        val sb = StringBuilder()

        val job = launch(Dispatchers.Default) {
            try {
                val rc = LfmOmniNative.generate(
                    prompt, rgb, audio, widths, heights, kinds,
                    request.maxTokens, request.temperature, request.topK,
                    object : TokenCallback {
                        override fun onToken(text: String) {
                            sb.append(text)
                            trySend(EngineEvent.Token(text))
                        }
                    },
                )
                val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                when (rc) {
                    0, -4 -> {
                        conversationStarted = true
                        val used = runCatching { LfmOmniNative.contextUsed() }.getOrDefault(0)
                        val cps = if (seconds > 0.1) sb.length / seconds else 0.0
                        trySend(EngineEvent.Done(sb.toString(), cps, used))
                    }

                    else -> trySend(EngineEvent.Failure(statusText(rc)))
                }
            } catch (t: Throwable) {
                trySend(EngineEvent.Failure(t.message ?: "推論中にエラーが発生しました"))
            } finally {
                close()
            }
        }

        awaitClose {
            job.cancel()
            LfmOmniNative.stop()
        }
    }
}

/**
 * Termux などで動かす llama-server (OpenAI 互換 API) に接続するモード。
 * 端末単体で完結させる前の検証用 (仕様書フェーズ 1)。
 */
class ServerEngine(private val settings: AppSettings) : Engine {

    override val label: String = "ローカルサーバー"

    @Volatile
    private var loaded = false

    @Volatile
    private var call: okhttp3.Call? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun baseUrl(): String = settings.serverUrl.trimEnd('/')

    override fun isLoaded(): Boolean = loaded

    override suspend fun load(onStatus: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        onStatus("サーバーに接続しています (${baseUrl()})…")
        val health = runCatching {
            val request = Request.Builder().url("${baseUrl()}/health").build()
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        if (!health) {
            loaded = false
            return@withContext Result.failure(
                IllegalStateException(
                    "llama-server に接続できません。Termux で次を実行してください:\n" +
                        "llama serve -hf LiquidAI/LFM3-Omni-2B-GGUF:Q4_K_M --reasoning off --host 127.0.0.1 --port 8080"
                )
            )
        }
        loaded = true
        onStatus("サーバーに接続しました")
        Result.success(Unit)
    }

    override fun resetConversation() {
        // サーバー側はリクエストごとに履歴を送るので状態を持たない
    }

    override fun cancel() {
        runCatching { call?.cancel() }
    }

    override fun release() {
        loaded = false
    }

    private fun buildMessages(request: GenerationRequest): JSONArray {
        val array = JSONArray()
        if (request.systemPrompt.isNotBlank()) {
            array.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        }
        request.history.forEach { message ->
            if (message.isError) return@forEach
            when (message.role) {
                Role.USER -> array.put(JSONObject().put("role", "user").put("content", message.text))
                Role.ASSISTANT -> if (message.text.isNotBlank()) {
                    array.put(JSONObject().put("role", "assistant").put("content", message.text))
                }

                Role.SYSTEM -> Unit
            }
        }
        return array
    }

    override fun generate(request: GenerationRequest): Flow<EngineEvent> = callbackFlow {
        if (!loaded) {
            trySend(EngineEvent.Failure("サーバーに接続されていません"))
            close()
            return@callbackFlow
        }

        val messages = buildMessages(request)
        val payload = JSONObject()
            .put("model", settings.serverModel)
            .put("messages", messages)
            .put("stream", true)
            .put("max_tokens", request.maxTokens)
            .put("temperature", request.temperature)
            .put("top_k", request.topK)

        val sb = StringBuilder()
        val job = launch(Dispatchers.IO) {
            try {
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val httpRequest = Request.Builder()
                    .url("${baseUrl()}/v1/chat/completions")
                    .post(body)
                    .build()
                val newCall = http.newCall(httpRequest)
                call = newCall
                newCall.execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string().orEmpty()
                        trySend(EngineEvent.Failure("サーバーエラー HTTP ${response.code}: ${errorBody.take(300)}"))
                        return@use
                    }
                    val source = response.body?.source() ?: run {
                        trySend(EngineEvent.Failure("応答がありません"))
                        return@use
                    }
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") {
                            if (data == "[DONE]") break
                            continue
                        }
                        runCatching {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices") ?: return@runCatching
                            if (choices.length() == 0) return@runCatching
                            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return@runCatching
                            val piece = delta.optString("content", "")
                            if (piece.isNotEmpty()) {
                                sb.append(piece)
                                trySend(EngineEvent.Token(piece))
                            }
                        }
                    }
                }
                trySend(EngineEvent.Done(sb.toString(), 0.0, 0))
            } catch (t: Throwable) {
                if (sb.isEmpty()) {
                    trySend(EngineEvent.Failure(t.message ?: "通信に失敗しました"))
                } else {
                    trySend(EngineEvent.Done(sb.toString(), 0.0, 0))
                }
            } finally {
                call = null
                close()
            }
        }

        awaitClose {
            job.cancel()
            runCatching { call?.cancel() }
        }
    }
}

private fun String.ifBlankValue(fallback: String): String = if (isBlank()) fallback else this
