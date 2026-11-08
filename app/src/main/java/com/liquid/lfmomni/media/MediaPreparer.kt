package com.liquid.lfmomni.media

import android.content.Context
import android.net.Uri
import com.liquid.lfmomni.data.AppSettings
import com.liquid.lfmomni.model.Attachment
import com.liquid.lfmomni.model.AttachmentKind
import com.liquid.lfmomni.model.MediaItem
import com.liquid.lfmomni.model.PreparedTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.min

/**
 * 添付ファイルをモデル入力に変換する。
 *
 * 生成する本文にはメディアの位置にマーカー "<__media__>" を埋め込み、
 * 同じ順序で MediaItem を並べる (libmtmd のマーカー置換の仕様)。
 *
 * 動画は LFM3-Omni のプロンプト形式に合わせて
 *   <0.0 seconds> [フレーム][フレーム][その区間の音声]
 *   <1.0 seconds> [フレーム][フレーム][その区間の音声] ...
 * の形に組み立てる (2fps なので 2 フレーム = 1 秒)。
 */
class MediaPreparer(private val context: Context) {

    companion object {
        /** mtmd_default_marker() と同じ文字列 */
        const val MEDIA_MARKER = "<__media__>"
        private const val GROUP_FRAMES = 2
        private const val TARGET_RATE = 16_000
        private const val MAX_AUDIO_SECONDS = 180f
    }

    suspend fun prepare(
        text: String,
        attachments: List<Attachment>,
        settings: AppSettings,
        onStatus: (String) -> Unit,
    ): PreparedTurn = withContext(Dispatchers.IO) {
        val media = ArrayList<MediaItem>()
        val body = StringBuilder()
        var usedVideo = false

        attachments.forEachIndexed { index, attachment ->
            val uri = Uri.parse(attachment.uriString)
            when (attachment.kind) {
                AttachmentKind.IMAGE -> {
                    onStatus("画像を準備しています (${index + 1}/${attachments.size})…")
                    val bitmap = ImageUtils.loadScaled(context, uri, settings.imageMaxSize)
                    val rgb = ImageUtils.toRgb(bitmap)
                    media.add(MediaItem.image(rgb, bitmap.width, bitmap.height))
                    bitmap.recycle()
                    body.append(MEDIA_MARKER).append('\n')
                }

                AttachmentKind.VIDEO -> {
                    usedVideo = true
                    onStatus("動画からフレームを取り出しています…")
                    val extraction = VideoProcessor.extractFrames(
                        context = context,
                        uri = uri,
                        maxFrames = settings.videoMaxFrames,
                        maxSize = settings.imageMaxSize,
                    )
                    onStatus("動画の音声を取り出しています…")
                    val audio = runCatching {
                        AudioDecoder.decodeToMono16k(
                            context = context,
                            uri = uri,
                            maxSeconds = (extraction.durationMs / 1000f).coerceAtMost(MAX_AUDIO_SECONDS),
                        )
                    }.getOrDefault(FloatArray(0))

                    val samplesPerGroup = (TARGET_RATE / VideoProcessor.FPS).toInt() * GROUP_FRAMES
                    val groups = ceil(extraction.frames.size.toDouble() / GROUP_FRAMES).toInt()

                    for (g in 0 until groups) {
                        val seconds = g * GROUP_FRAMES / VideoProcessor.FPS
                        body.append(String.format("<%.1f seconds>", seconds))
                        for (f in 0 until GROUP_FRAMES) {
                            val frameIndex = g * GROUP_FRAMES + f
                            val bitmap = extraction.frames.getOrNull(frameIndex) ?: break
                            val rgb = ImageUtils.toRgb(bitmap)
                            media.add(MediaItem.videoFrame(rgb, bitmap.width, bitmap.height))
                            body.append(MEDIA_MARKER)
                        }
                        val start = g * samplesPerGroup
                        if (start < audio.size) {
                            val end = min(start + samplesPerGroup, audio.size)
                            val slice = audio.copyOfRange(start, end)
                            if (slice.isNotEmpty()) {
                                media.add(MediaItem.audio(slice))
                                body.append(MEDIA_MARKER)
                            }
                        }
                        body.append('\n')
                    }
                    extraction.frames.forEach { it.recycle() }
                }

                AttachmentKind.AUDIO -> {
                    onStatus("音声を 16kHz に変換しています…")
                    val samples = AudioDecoder.decodeToMono16k(
                        context = context,
                        uri = uri,
                        maxSeconds = MAX_AUDIO_SECONDS,
                    )
                    if (samples.isEmpty()) {
                        throw IllegalStateException("音声を読み取れませんでした")
                    }
                    media.add(MediaItem.audio(samples))
                    body.append(MEDIA_MARKER).append('\n')
                }
            }
        }

        if (media.isNotEmpty()) {
            body.append('\n')
        }
        body.append(text)

        PreparedTurn(
            body = body.toString(),
            media = media,
            note = if (usedVideo) "video" else null,
        )
    }
}
