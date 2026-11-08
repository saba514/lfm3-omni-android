package com.liquid.lfmomni.engine

/** ネイティブから 1 トークンずつ呼ばれるコールバック */
interface TokenCallback {
    fun onToken(text: String)
}

/**
 * liblfmomni.so (llama.cpp + libmtmd) の JNI 面。
 * ライブラリが同梱されていない ABI では isAvailable = false になる。
 */
object LfmOmniNative {

    val isAvailable: Boolean

    init {
        var ok = false
        try {
            System.loadLibrary("lfmomni")
            ok = true
        } catch (t: Throwable) {
            ok = false
        }
        isAvailable = ok
    }

    /** llama_backend_init()。プロセスで 1 回呼ぶ */
    external fun backendInit()

    /**
     * モデルと mmproj を読み込む。
     * @return 0 = 成功, 1 = モデル読み込み失敗, 2 = コンテキスト生成失敗
     */
    external fun loadModel(
        modelPath: String,
        mmprojPath: String?,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
    ): Int

    external fun unloadModel()

    /** KV キャッシュを消して会話をリセットする */
    external fun resetContext()

    /**
     * 1 ターン生成する。
     * prompt にはメディアの位置に "<__media__>" を入れておき、media を同じ順序で渡す。
     * @return 0 = 正常終了, -1 = 未ロード, -2 = トークナイズ失敗,
     *         -3 = デコード失敗, -4 = 中断, -5 = メディアのエンコード失敗
     */
    external fun generate(
        prompt: String,
        rgb: Array<ByteArray?>,
        audio: Array<FloatArray?>,
        widths: IntArray,
        heights: IntArray,
        kinds: IntArray,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        callback: TokenCallback?,
    ): Int

    /** 生成中のループに中断を伝える */
    external fun stop()

    /** "vision=1,audio=1,audio_rate=16000,video=1,audio_gen=0" */
    external fun capabilities(): String

    external fun contextUsed(): Int

    external fun contextSize(): Int
}
