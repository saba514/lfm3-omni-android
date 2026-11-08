package com.liquid.lfmomni

import android.app.Application
import com.liquid.lfmomni.engine.LfmOmniNative

class LfmOmniApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // ネイティブ (llama.cpp) の初期化はここでは行わない。
        // モデル読み込み時にエンジン側で backendInit() を 1 回呼ぶ。
        // ライブラリが無い環境 (未対応 ABI) でも起動できるようにする。
        runCatching { if (LfmOmniNative.isAvailable) null }
    }
}
