// ---------------------------------------------------------------------------
// LFM3 Omni Local for Android - JNI bridge
//
// llama.cpp (b11960 / v0.8.0) の公開 C API だけを使用する。
//   * llama.h        : モデル読み込み / コンテキスト / トークナイズ / サンプリング / 推論
//   * mtmd.h         : マルチモーダル (画像・音声・動画フレーム) の入力チャンク
//   * mtmd-helper.h  : チャンク評価 (encode + decode) のヘルパ
//
// ここで使っている関数はすべて上記ヘッダに実在するものだけ。
// 独自の推測 API は使っていない。
// ---------------------------------------------------------------------------
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "LfmOmniNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 戻り値コード (Kotlin 側と共有)
enum Status : jint {
    OK = 0,
    ERR_NOT_LOADED = -1,
    ERR_TOKENIZE = -2,
    ERR_DECODE = -3,
    ERR_ABORTED = -4,
    ERR_ENCODE = -5,
};

struct EngineState {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * mtmd = nullptr;
    const llama_vocab * vocab = nullptr;

    int32_t n_past = 0;
    int32_t n_ctx = 0;
    int32_t n_batch = 512;
    int32_t n_threads = 4;

    std::atomic<bool> abort_flag{false};
    std::mutex mutex;
};

EngineState g;

bool abort_callback(void * /*data*/) {
    return g.abort_flag.load(std::memory_order_relaxed);
}

void log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) {
        return;
    }
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, text);
            break;
        case GGML_LOG_LEVEL_WARN:
            __android_log_write(ANDROID_LOG_WARN, LOG_TAG, text);
            break;
        case GGML_LOG_LEVEL_DEBUG:
            __android_log_write(ANDROID_LOG_DEBUG, LOG_TAG, text);
            break;
        default:
            __android_log_write(ANDROID_LOG_INFO, LOG_TAG, text);
            break;
    }
}

// 途中で切れたマルチバイト文字を送らないための補助。
// 末尾から遡り、完全な UTF-8 シーケンスの終端を返す。
size_t utf8_complete_len(const std::string & s) {
    if (s.empty()) {
        return 0;
    }
    size_t len = s.size();
    // 末尾の継続バイト (10xxxxxx) を数える
    size_t cont = 0;
    size_t i = len;
    while (i > 0) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) {
            ++cont;
            --i;
            if (cont > 3) {
                break;
            }
        } else {
            break;
        }
    }
    if (i == 0) {
        return len; // 先頭から継続バイト: 壊れているのでそのまま返す
    }
    unsigned char lead = static_cast<unsigned char>(s[i - 1]);
    size_t need = 0;
    if ((lead & 0x80) == 0x00) {
        need = 1;
    } else if ((lead & 0xE0) == 0xC0) {
        need = 2;
    } else if ((lead & 0xF0) == 0xE0) {
        need = 3;
    } else if ((lead & 0xF8) == 0xF0) {
        need = 4;
    } else {
        return len;
    }
    const size_t have = cont + 1; // リードバイト + 継続バイト
    if (have >= need) {
        return len;
    }
    return i - 1; // 不完全な最後の文字を保留
}

void release_all() {
    if (g.mtmd != nullptr) {
        mtmd_free(g.mtmd);
        g.mtmd = nullptr;
    }
    if (g.ctx != nullptr) {
        llama_free(g.ctx);
        g.ctx = nullptr;
    }
    if (g.model != nullptr) {
        llama_model_free(g.model);
        g.model = nullptr;
    }
    g.vocab = nullptr;
    g.n_past = 0;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_liquid_lfmomni_engine_LfmOmniNative_backendInit(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g.mutex);
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    LOGI("llama.cpp backend initialized");
}

// 0 = 成功, 1 = モデル読み込み失敗, 2 = コンテキスト生成失敗, 3 = mmproj 読み込み失敗
JNIEXPORT jint JNICALL
Java_com_liquid_lfmomni_engine_LfmOmniNative_loadModel(JNIEnv * env, jobject /*thiz*/,
                                                       jstring j_model_path,
                                                       jstring j_mmproj_path,
                                                       jint n_ctx,
                                                       jint n_threads,
                                                       jint n_batch) {
    g.abort_flag = true; // 直前の推論を止める (ロック取得前に立てる)
    std::lock_guard<std::mutex> lock(g.mutex);
    release_all();
    g.abort_flag = false;

    const char * model_path = env->GetStringUTFChars(j_model_path, nullptr);
    if (model_path == nullptr) {
        return 1;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;      // CPU 推論
    mparams.use_extra_bufts = false;
    mparams.progress_callback = nullptr;

    LOGI("loading model: %s", model_path);
    g.model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(j_model_path, model_path);

    if (g.model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        return 1;
    }
    g.vocab = llama_model_get_vocab(g.model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : 8192);
    const int32_t batch = n_batch > 0 ? n_batch : 512;
    cparams.n_batch = static_cast<uint32_t>(batch);
    cparams.n_ubatch = static_cast<uint32_t>(batch > 1024 ? 1024 : batch);
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = n_threads > 0 ? n_threads : 4;
    cparams.abort_callback = abort_callback;
    cparams.abort_callback_data = nullptr;
    cparams.no_perf = true;

    g.ctx = llama_init_from_model(g.model, cparams);
    if (g.ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        release_all();
        return 2;
    }

    g.n_ctx = static_cast<int32_t>(llama_n_ctx(g.ctx));
    g.n_batch = batch;
    g.n_threads = cparams.n_threads;
    g.n_past = 0;

    if (j_mmproj_path != nullptr) {
        const char * mmproj_path = env->GetStringUTFChars(j_mmproj_path, nullptr);
        if (mmproj_path != nullptr && mmproj_path[0] != '\0') {
            mtmd_context_params tparams = mtmd_context_params_default();
            tparams.use_gpu = false;
            tparams.print_timings = false;
            tparams.n_threads = cparams.n_threads;
            tparams.media_marker = mtmd_default_marker();
            tparams.warmup = true;

            LOGI("loading mmproj: %s", mmproj_path);
            g.mtmd = mtmd_init_from_file(mmproj_path, g.model, tparams);
            if (g.mtmd == nullptr) {
                LOGE("mtmd_init_from_file failed (text-only mode)");
                // mmproj なしでもテキスト推論は可能なので致命的扱いにしない
            }
        }
        if (mmproj_path != nullptr) {
            env->ReleaseStringUTFChars(j_mmproj_path, mmproj_path);
        }
    }

    LOGI("model ready: n_ctx=%d n_batch=%d threads=%d multimodal=%s",
         g.n_ctx, g.n_batch, g.n_threads, g.mtmd != nullptr ? "yes" : "no");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_liquid_lfmomni_engine_LfmOmniNative_unloadModel(JNIEnv * /*env*/, jobject /*thiz*/) {
    g.abort_flag = true;
    std::lock_guard<std::mutex> lock(g.mutex);
    release_all();
    LOGI("model unloaded");
}

JNIEXPORT void JNICALL
Java_com_liquid_lfmomni_engine_LfmOmniNative_resetContext(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (g.ctx == nullptr) {
        return;
    }
    llama_memory_clear(llama_get_memory(g.ctx), true);
    g.n_past = 0;
}

JNIEXPORT void JNICALL
Java_com_liquid_lfmomni_engine_LfmOmniNative_stop(JNIEnv * /*env*/, jobject /*thiz*/) {
    g.abort_flag.store(true, std::memory_order_relaxed);
}

// "vision=1,audio=1,audio_rate=16000,video=1"
JNIEXPORT jstring JNICALL
Java_com_liquid_lfmomni_engine_LfmOmniNative_capabilities(JNIEnv * env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (g.mtmd == nullptr) {
        return env->NewStringUTF("vision=0,audio=0,audio_rate=-1,video=0");
    }
    const bool vision = mtmd_support_vision(g.mtmd);
    const bool audio = mtmd_support_audio(g.mtmd);
    const int rate = mtmd_get_audio_sample_rate(g.mtmd);
    const mtmd_helper_cap cap = mtmd_helper_get_capabilities(g.model, g.mtmd);
    char buf[160];
    snprintf(buf, sizeof(buf), "vision=%d,audio=%d,audio_rate=%d,video=%d,audio_gen=%d",
             vision ? 1 : 0, audio ? 1 : 0, rate, cap.video ? 1 : 0, cap.audio_gen ? 1 : 0);
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_com_liquid_lfmomni_engine_LfmOmniNative_contextUsed(JNIEnv * /*env*/, jobject /*thiz*/) {
    return g.n_past;
}

JNIEXPORT jint JNICALL
Java_com_liquid_lfmomni_engine_LfmOmniNative_contextSize(JNIEnv * /*env*/, jobject /*thiz*/) {
    return g.n_ctx;
}

// ---------------------------------------------------------------------------
// 生成本体
//
// prompt にはメディアの位置に mtmd_default_marker() ("<__media__>") を入れておく。
// rgb/audio/widths/heights/kinds はそのマーカーと同じ順序で並べる。
//   kind: 0 = 静止画, 1 = 動画フレーム (2fps, 連続フレームは1チャンクにまとめられる),
//         2 = 音声 (16kHz mono float)
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_liquid_lfmomni_engine_LfmOmniNative_generate(JNIEnv * env, jobject /*thiz*/,
                                                      jstring j_prompt,
                                                      jobjectArray j_rgb,
                                                      jobjectArray j_audio,
                                                      jintArray j_widths,
                                                      jintArray j_heights,
                                                      jintArray j_kinds,
                                                      jint max_tokens,
                                                      jfloat temperature,
                                                      jint top_k,
                                                      jobject j_callback) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (g.ctx == nullptr || g.model == nullptr) {
        return ERR_NOT_LOADED;
    }
    g.abort_flag.store(false, std::memory_order_relaxed);

    const char * prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    if (prompt_chars == nullptr) {
        return ERR_TOKENIZE;
    }
    std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    // --- メディアの準備 -----------------------------------------------------
    const jsize n_media = j_kinds != nullptr ? env->GetArrayLength(j_kinds) : 0;
    std::vector<jint> widths(n_media, 0), heights(n_media, 0), kinds(n_media, 0);
    if (n_media > 0) {
        env->GetIntArrayRegion(j_widths, 0, n_media, widths.data());
        env->GetIntArrayRegion(j_heights, 0, n_media, heights.data());
        env->GetIntArrayRegion(j_kinds, 0, n_media, kinds.data());
    }

    std::vector<mtmd_bitmap *> bitmaps;
    bitmaps.reserve(n_media);

    bool ok = true;
    for (jsize i = 0; i < n_media && ok; ++i) {
        if (kinds[i] == 2) {
            auto arr = static_cast<jfloatArray>(env->GetObjectArrayElement(j_audio, i));
            if (arr == nullptr) {
                ok = false;
                break;
            }
            const jsize n = env->GetArrayLength(arr);
            std::vector<float> samples(static_cast<size_t>(n), 0.0f);
            env->GetFloatArrayRegion(arr, 0, n, samples.data());
            env->DeleteLocalRef(arr);
            bitmaps.push_back(mtmd_bitmap_init_from_audio(samples.size(), samples.data()));
        } else {
            auto arr = static_cast<jbyteArray>(env->GetObjectArrayElement(j_rgb, i));
            if (arr == nullptr) {
                ok = false;
                break;
            }
            const jsize n = env->GetArrayLength(arr);
            std::vector<unsigned char> rgb(static_cast<size_t>(n), 0);
            env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(rgb.data()));
            env->DeleteLocalRef(arr);
            mtmd_bitmap * bmp = mtmd_bitmap_init(static_cast<uint32_t>(widths[i]),
                                                 static_cast<uint32_t>(heights[i]),
                                                 rgb.data());
            if (kinds[i] == 1) {
                mtmd_bitmap_set_mergeable(bmp, true);
                mtmd_bitmap_set_is_video(bmp, true);
            }
            bitmaps.push_back(bmp);
        }
    }

    auto free_bitmaps = [&]() {
        for (mtmd_bitmap * b : bitmaps) {
            if (b != nullptr) {
                mtmd_bitmap_free(b);
            }
        }
        bitmaps.clear();
    };

    if (!ok) {
        free_bitmaps();
        return ERR_ENCODE;
    }

    // --- プロンプトのトークン化と評価 ---------------------------------------
    const int32_t n_past_start = g.n_past;

    if (g.mtmd != nullptr) {
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        if (chunks == nullptr) {
            free_bitmaps();
            return ERR_TOKENIZE;
        }

        mtmd_input_text text{};
        text.text = prompt.c_str();
        text.text_len = prompt.size();
        text.add_special = (n_past_start == 0);
        text.parse_special = true;

        std::vector<const mtmd_bitmap *> cbitmaps(bitmaps.begin(), bitmaps.end());
        const int32_t rc = mtmd_tokenize(g.mtmd, chunks, &text,
                                         cbitmaps.empty() ? nullptr : cbitmaps.data(),
                                         cbitmaps.size());
        if (rc != 0) {
            LOGE("mtmd_tokenize failed: %d (bitmaps=%zu)", rc, cbitmaps.size());
            mtmd_input_chunks_free(chunks);
            free_bitmaps();
            return ERR_TOKENIZE;
        }

        int32_t new_n_past = n_past_start;
        const int32_t erc = mtmd_helper_eval_chunks(g.mtmd, g.ctx, chunks, n_past_start, 0,
                                                    g.n_batch, true, &new_n_past);
        mtmd_input_chunks_free(chunks);
        free_bitmaps();

        if (erc != 0) {
            LOGE("mtmd_helper_eval_chunks failed: %d", erc);
            return g.abort_flag.load() ? ERR_ABORTED : ERR_ENCODE;
        }
        g.n_past = new_n_past;
    } else {
        // mmproj なし (テキストのみ)
        free_bitmaps();
        std::vector<llama_token> tokens(prompt.size() + 16);
        int32_t n = llama_tokenize(g.vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                   tokens.data(), static_cast<int32_t>(tokens.size()),
                                   n_past_start == 0, true);
        if (n < 0) {
            tokens.resize(static_cast<size_t>(-n));
            n = llama_tokenize(g.vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                               tokens.data(), static_cast<int32_t>(tokens.size()),
                               n_past_start == 0, true);
        }
        if (n < 0) {
            return ERR_TOKENIZE;
        }
        tokens.resize(static_cast<size_t>(n));
        for (size_t off = 0; off < tokens.size(); ) {
            const int32_t take =
                    static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(g.n_batch), tokens.size() - off));
            llama_batch batch = llama_batch_get_one(tokens.data() + off, take);
            if (llama_decode(g.ctx, batch) != 0) {
                return g.abort_flag.load() ? ERR_ABORTED : ERR_DECODE;
            }
            off += static_cast<size_t>(take);
        }
        g.n_past += n;
    }

    // --- サンプリング -------------------------------------------------------
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jmethodID on_token = nullptr;
    if (j_callback != nullptr) {
        jclass cb_class = env->GetObjectClass(j_callback);
        on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cb_class);
    }

    const int32_t limit = max_tokens > 0 ? max_tokens : 512;
    std::string pending;
    jint result = OK;

    for (int32_t i = 0; i < limit; ++i) {
        if (g.abort_flag.load(std::memory_order_relaxed)) {
            result = ERR_ABORTED;
            break;
        }

        llama_token id = llama_sampler_sample(smpl, g.ctx, -1);
        if (llama_vocab_is_eog(g.vocab, id)) {
            break;
        }

        char piece[512];
        int32_t np = llama_token_to_piece(g.vocab, id, piece, sizeof(piece), 0, true);
        if (np < 0) {
            std::vector<char> big(static_cast<size_t>(-np) + 1);
            np = llama_token_to_piece(g.vocab, id, big.data(), static_cast<int32_t>(big.size()), 0, true);
            if (np > 0) {
                pending.append(big.data(), static_cast<size_t>(np));
            }
        } else if (np > 0) {
            pending.append(piece, static_cast<size_t>(np));
        }

        // 完全な UTF-8 文字までを送る
        const size_t emit = utf8_complete_len(pending);
        if (emit > 0 && on_token != nullptr) {
            std::string out = pending.substr(0, emit);
            pending.erase(0, emit);
            jstring js = env->NewStringUTF(out.c_str());
            if (js != nullptr) {
                env->CallVoidMethod(j_callback, on_token, js);
                env->DeleteLocalRef(js);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                g.abort_flag.store(true, std::memory_order_relaxed);
                result = ERR_ABORTED;
                break;
            }
        }

        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g.ctx, batch) != 0) {
            if (g.abort_flag.load()) {
                result = ERR_ABORTED;
            } else {
                LOGE("llama_decode failed at token %d", i);
                result = ERR_DECODE;
            }
            break;
        }
        llama_sampler_accept(smpl, id);
        g.n_past += 1;
    }

    // 端数 (改行など) を流す
    if (!pending.empty() && on_token != nullptr && !env->ExceptionCheck()) {
        jstring js = env->NewStringUTF(pending.c_str());
        if (js != nullptr) {
            env->CallVoidMethod(j_callback, on_token, js);
            env->DeleteLocalRef(js);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }

    llama_sampler_free(smpl);
    return result;
}

} // extern "C"
