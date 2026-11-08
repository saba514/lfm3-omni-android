package com.liquid.lfmomni.media

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale

/**
 * 音声の再生。2 種類を明確に分けて扱う。
 *
 *  - speak():       Android 標準の TextToSpeech。端末内蔵の日本語音声で読み上げる。
 *  - playWav():     WAV ファイル (録音・外部生成音声) の再生。
 *
 * LFM3-Omni 自身の 24kHz 音声生成は、現行の llama.cpp の公開 C API
 * (llama.h / mtmd.h) では音声出力パイプラインが実験段階で、端末内 JNI から
 * 安定して呼べる入り口が用意されていない。このアプリでは標準 TTS を
 * 「読み上げ」として使い、モデル自身の音声生成とは区別して表示する。
 */
class SpeechOutput(context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                runCatching {
                    tts?.language = Locale.JAPANESE
                    tts?.setSpeechRate(1.0f)
                }
            }
        }
    }

    val isTtsReady: Boolean get() = ttsReady

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        runCatching { tts?.stop() }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lfm3-${System.currentTimeMillis()}")
    }

    fun playWav(file: File) {
        if (!file.exists()) return
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stop() }
            prepare()
            start()
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }
}
