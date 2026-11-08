package com.liquid.lfmomni.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.IOException
import kotlin.math.ceil

/**
 * 動画から 2fps でフレームを切り出す。
 *
 * LFM3-Omni の動画入力は 2fps・4〜384 フレームが既定 (llama.cpp も固定)。
 * 端末のメモリと発熱を考え、既定では先頭 24 フレーム (= 12 秒) までに制限する。
 */
object VideoProcessor {

    const val FPS = 2f
    private const val FRAME_INTERVAL_US = 500_000L

    data class Extraction(
        val durationMs: Long,
        val frames: List<Bitmap>,
        val fps: Float,
    )

    fun durationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun extractFrames(
        context: Context,
        uri: Uri,
        maxFrames: Int,
        maxSize: Int,
        onProgress: ((Float) -> Unit)? = null,
    ): Extraction {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
        } catch (e: Exception) {
            throw IOException("動画を開けません: ${e.message}")
        }

        try {
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val limit = maxFrames.coerceIn(4, 384)
            val totalFrames = if (duration > 0) {
                ceil(duration / 1000.0 * FPS).toInt().coerceAtLeast(1)
            } else {
                limit
            }
            val count = minOf(totalFrames, limit)

            val frames = ArrayList<Bitmap>(count)
            for (i in 0 until count) {
                val timeUs = i * FRAME_INTERVAL_US
                // 同期フレームで十分 (2fps の解析用途)。取れなければ直前を使う
                val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue
                val scaled = scaleDown(raw, maxSize)
                frames.add(scaled)
                onProgress?.invoke((i + 1).toFloat() / count.toFloat())
            }
            if (frames.isEmpty()) {
                throw IOException("フレームを取得できませんでした")
            }
            return Extraction(duration, frames, FPS)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSize) return bitmap
        val scale = maxSize.toFloat() / longest.toFloat()
        val w = (bitmap.width * scale).toInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(2)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
