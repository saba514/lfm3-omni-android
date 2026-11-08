package com.liquid.lfmomni.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 16kHz / モノラル / 16bit でマイク録音する。
 * LFM3-Omni の音声入力サンプルレート (16kHz) にそのまま合わせている。
 */
class AudioRecorder(private val context: Context) {

    data class Result(
        val samples: FloatArray,
        val wavFile: File,
        val durationMs: Long,
    )

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val buffer = ByteArrayOutputStream()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    private val _recording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _recording

    fun start(scope: CoroutineScope): Boolean {
        if (_recording.value) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (t: Throwable) {
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }

        buffer.reset()
        record = recorder
        _recording.value = true

        job = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(bufferSize)
            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    synchronized(buffer) { buffer.write(chunk, 0, read) }
                    _amplitude.value = rms(chunk, read)
                }
            } catch (_: Throwable) {
                // 録音停止時の例外は無視
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                _amplitude.value = 0f
                _recording.value = false
            }
        }
        return true
    }

    suspend fun stop(): Result? {
        val recorder = record ?: return null
        _recording.value = false
        runCatching { recorder.stop() }
        job?.cancel()
        job = null
        val bytes = synchronized(buffer) { buffer.toByteArray() }
        if (bytes.size < 2) return null

        val samples = pcm16ToFloat(bytes)
        val wavFile = File(context.filesDir, "recordings").apply { mkdirs() }
            .resolve("rec_${System.currentTimeMillis()}.wav")
        AudioDecoder.writeWav(wavFile, samples, SAMPLE_RATE)
        return Result(samples, wavFile, samples.size * 1000L / SAMPLE_RATE)
    }

    fun cancel() {
        _recording.value = false
        job?.cancel()
        job = null
        synchronized(buffer) { buffer.reset() }
    }

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val count = bytes.size / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val value = ((hi shl 8) or lo).toShort().toInt()
            out[i] = value / 32768f
        }
        return out
    }

    private fun rms(data: ByteArray, length: Int): Float {
        var sum = 0.0
        val count = length / 2
        if (count == 0) return 0f
        for (i in 0 until count) {
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt()
            val v = ((hi shl 8) or lo).toShort().toInt() / 32768f
            sum += (v * v).toDouble()
        }
        return sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
