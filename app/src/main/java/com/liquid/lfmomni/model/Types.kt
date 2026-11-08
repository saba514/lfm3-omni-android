package com.liquid.lfmomni.model

import java.util.UUID

enum class Role { SYSTEM, USER, ASSISTANT }

enum class AttachmentKind { IMAGE, VIDEO, AUDIO }

/** 端末内の 1 ファイルへの参照 (SAF の content URI / FileProvider URI) */
data class Attachment(
    val kind: AttachmentKind,
    val uriString: String,
    val displayName: String,
    val durationMs: Long = 0L,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val spokenAudioPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
)

/**
 * モデルへ渡すメディア 1 個。プロンプト中の "<__media__>" マーカー 1 個に対応する。
 *
 * kind: 0 = 静止画, 1 = 動画フレーム, 2 = 音声 (16kHz mono PCM float)
 */
class MediaItem(
    val kind: Int,
    val rgb: ByteArray?,
    val samples: FloatArray?,
    val width: Int,
    val height: Int,
) {
    companion object {
        const val KIND_IMAGE = 0
        const val KIND_VIDEO_FRAME = 1
        const val KIND_AUDIO = 2

        fun image(rgb: ByteArray, width: Int, height: Int) =
            MediaItem(KIND_IMAGE, rgb, null, width, height)

        fun videoFrame(rgb: ByteArray, width: Int, height: Int) =
            MediaItem(KIND_VIDEO_FRAME, rgb, null, width, height)

        fun audio(samples: FloatArray) = MediaItem(KIND_AUDIO, null, samples, 0, 0)
    }
}

/** 1 ターン分の入力。body には既にメディアマーカーが埋め込まれている */
data class PreparedTurn(
    val body: String,
    val media: List<MediaItem>,
    val note: String? = null,
)
