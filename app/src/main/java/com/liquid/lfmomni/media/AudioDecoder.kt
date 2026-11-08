package com.liquid.lfmomni.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * 端末内の音声 (mp3 / m4a / wav / mp4 の音声トラックなど) を
 * 16kHz・モノラル・float PCM に変換する。LFM3-Omni の音声入力仕様に合わせる。
 */
object AudioDecoder {

    const val TARGET_RATE = 16_000

    fun decodeToMono16k(
        context: Context,
        uri: Uri,
        maxSeconds: Float = 120f,
        onProgress: ((Float) -> Unit)? = null,
    ): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: IOException) {
            throw IOException("音声を開けません: ${e.message}", e)
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IOException("音声トラックが見つかりません")
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        val limitSamples = (maxSeconds * TARGET_RATE).toLong() * 4 // 余裕を持たせる

        val pcm = ArrayList<Float>(128 * 1024)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var framesDecoded = 0

        fun appendPcm(buffer: ByteArray, offset: Int, size: Int, ch: Int) {
            var i = offset
            val end = offset + size - (size % 2)
            while (i + 1 < end) {
                val lo = buffer[i].toInt() and 0xFF
                val hi = buffer[i + 1].toInt()
                val value = ((hi shl 8) or lo).toShort().toInt()
                pcm.add(value / 32768.0f)
                i += 2
            }
            framesDecoded++
            if (pcm.size > limitSamples) outputDone = true
        }

        val timeoutUs = 10_000L
        var idleCount = 0
        while (!outputDone) {
            var progressed = false
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progressed = true
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(bytes)
                        appendPcm(bytes, 0, info.size, channels)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (durationUs > 0) {
                        onProgress?.invoke((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    progressed = true
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                }

                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) idleCount++ else idleCount = 0
                }
            }
            if (!progressed && inputDone) {
                idleCount++
            }
            // デコーダが何も返さなくなったら抜ける (無限ループ防止)
            if (idleCount > 300) {
                outputDone = true
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val maxOut = (maxSeconds * TARGET_RATE).toInt()
        val mono = downmix(pcm, channels)
        val resampled = resample(mono, sampleRate, TARGET_RATE)
        return if (resampled.size > maxOut) resampled.copyOf(maxOut) else resampled
    }

    private fun downmix(interleaved: List<Float>, channels: Int): FloatArray {
        if (channels <= 1) return interleaved.toFloatArray()
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += interleaved[f * channels + c]
            }
            out[f] = sum / channels
        }
        return out
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from <= 0 || input.isEmpty()) return FloatArray(0)
        if (from == to) return input
        val outLength = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        val step = from.toDouble() / to
        for (i in 0 until outLength) {
            val srcPos = i * step
            val i0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val i1 = min(i0 + 1, input.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    /** float PCM (-1.0..1.0) を 16bit PCM の WAV として保存する */
    fun writeWav(file: File, samples: FloatArray, sampleRate: Int = TARGET_RATE) {
        val dataSize = samples.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize)
            raf.write(header.array())

            val buffer = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                buffer.putShort(v.toShort())
            }
            raf.write(buffer.array())
        }
    }
}
