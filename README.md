# LFM3 Omni Local for Android

Liquid AI のマルチモーダルモデル **LFM3-Omni-2B** を、Android 端末の上だけで動かすアプリです。
テキスト・画像・音声・動画を 1 つのモデルに入力し、テキストで答えます。クラウド API は使いません。

- 対象端末: **SHARP AQUOS R6 (A101SH)** / Snapdragon 888 / RAM 12GB / Android 13 / arm64-v8a
- モデル: `LiquidAI/LFM3-Omni-2B-GGUF` の `Q4_K_M` (本体 + mmproj)
- 推論: **llama.cpp b11960 (v0.8.0)** + **libmtmd** を Android NDK でビルドし、JNI から直接呼び出し
- UI: Kotlin + Jetpack Compose + Material 3 (日本語 / ダークモード対応)

---

## 1. できること

| 機能 | 実装 |
| --- | --- |
| テキストチャット | ストリーミング表示、停止、再生成、システムプロンプト変更、履歴の端末内保存 |
| 画像認識 | ギャラリー選択 / カメラ撮影 → RGB 変換してモデルへ入力、内容について質問 |
| 音声入力 | マイク録音 (16kHz mono) / 音声ファイル読み込み → 文字起こし・内容への質問 |
| 動画認識 | `MediaMetadataRetriever` で **2fps** のフレーム切り出し + `MediaExtractor`/`MediaCodec` で音声トラックを 16kHz に変換し、**映像と音声を同時に**入力 |
| 音声出力 | Android 標準 TTS による読み上げ (LFM3-Omni 自身の音声生成とは区別。下記「既知の制限」参照) |
| モデル管理 | Hugging Face からのワンタップ取得 (進捗表示 / 再開)、GGUF の手動インポート、削除 |
| ハイブリッド推論 | アプリ内 JNI 推論 (既定) と、Termux 上の `llama-server` (localhost:8080) 接続モード |

## 2. ビルド

### GitHub Actions (推奨・このリポジトリの設定済み)

`main` に push すると `.github/workflows/build-apk.yml` が走り、

1. JDK 17 / Android SDK / NDK 27.2 / CMake 3.31.6 を用意
2. `gradle assembleDebug` で APK をビルド (arm64-v8a のみ)
3. `app-debug.apk` を Artifact と Release **v0.1.0** にアップロード

を行います。Actions の実行ページ、または Release から APK を取得できます。

### ローカルの Android Studio

- Android Studio Ladybug 以降 / JDK 17
- SDK Platform 35、**NDK 27.2.12479018**、**CMake 3.31.6** (SDK Manager から導入)
- 初回ビルド時に llama.cpp のソース (約 40MB) を CMake の `FetchContent` が GitHub から取得します
  (要ネットワーク。2 回目以降はキャッシュ)

```
./gradlew :app:assembleDebug
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

NDK のビルドを含むため、初回は 10〜20 分ほどかかります。

## 3. インストールと初期設定 (AQUOS R6)

1. `app-debug.apk` を端末へ転送 (USB / Google Drive など)
2. ファイルマネージャから開き、「提供元不明のアプリ」を許可してインストール
3. アプリを起動 → 下部タブ **[モデル]**
4. 「**Hugging Face からワンタップ取得**」を押す
   - `LFM3-Omni-2B-Q4_K_M.gguf` (約 1.4GB)
   - `mmproj-LFM3-Omni-2B-Q4_K_M.gguf` (視覚・音声エンコーダ)
   - 合計 約 2.3GB。**Wi-Fi 推奨**。進捗と残量が表示されます
5. 取得後 [チャット] に戻り、メッセージを送ると初回だけモデルの読み込み (数十秒) が入ります

モデルはアプリ内部 (`/data/data/com.liquid.lfmomni/files/models/`) にのみ保存され、
外部へ送信されません。

## 4. 使い方

- **テキスト**: そのまま入力して送信。生成中は送信ボタンが停止ボタンに変わります
- **画像**: 左下の「＋」→「写真・動画・音声を選ぶ」または「カメラで撮影」→ 質問を入力して送信
- **音声**: 「＋」→「マイクで録音」→ 話して「録音を停止」→ 質問 (例:「文字起こしして」)
- **動画**: 「＋」→ 動画を選択 → 例:
  - 「この動画で何が起きている？」
  - 「動画の中で話している内容をまとめて」
  - 「人物がどのような動作をしているか説明して」
  - 「10秒付近では何が起きている？」
- アシスタントの吹き出しの「読み上げ」で Android TTS が読み上げます
- 右上の ⟳ は **再生成** (会話全体を組み直して最後の質問に答え直す)、🗑 は **新しい会話**

### 動画の扱い (実装の詳細)

LFM3-Omni のプロンプト形式に合わせ、動画は次の形に組み立てます (2fps なので 2 フレーム = 1 秒)。

```
<0.0 seconds> [フレーム][フレーム][その 1 秒の音声]
<1.0 seconds> [フレーム][フレーム][その 1 秒の音声]
...
```

- フレームは `MediaMetadataRetriever` で 0.5 秒間隔に切り出し、長辺を設定値 (既定 768px) に縮小
- 音声は `MediaExtractor` + `MediaCodec` でデコードし、16kHz モノラル float に変換して 1 秒ずつに分割
- フレームはすべて「動画フレーム」として libmtmd に渡し、連続 2 枚が 1 チャンクに統合されます
- メモリ保護のため、既定では先頭 **24 フレーム (約 12 秒)** まで。設定画面で 8〜96 フレームに変更可能

## 5. サーバーモード (Termux / llama-server)

端末内 JNI を使わず、Termux 上の `llama-server` に接続して動作を比較できます (仕様書フェーズ 1)。

```bash
# Termux 上 (llama.cpp b11960 以降をビルド済みの前提)
llama serve -hf LiquidAI/LFM3-Omni-2B-GGUF:Q4_K_M --reasoning off --temp 0 --host 127.0.0.1 --port 8080
```

アプリの [設定] → 推論モード「**ローカルサーバー**」→ 接続。
サーバーモードは現状 **テキストのみ** 対応です (画像・音声・動画は端末内モードで)。

## 6. 構成

```
app/src/main/java/com/liquid/lfmomni/
├── MainActivity.kt            画面の切り替え (チャット / モデル / 設定)
├── LfmOmniApplication.kt
├── ui/                        Compose UI (Chat / Model / Settings / Theme) と ViewModel
├── engine/
│   ├── LfmOmniNative.kt       JNI 宣言
│   ├── Engine.kt              NativeEngine / ServerEngine とプロンプト組み立て
├── media/
│   ├── AudioRecorder.kt       16kHz マイク録音
│   ├── AudioDecoder.kt        MediaCodec で 16kHz mono float へ
│   ├── VideoProcessor.kt      2fps フレーム切り出し
│   ├── ImageUtils.kt          EXIF 回転・縮小・RGB 変換
│   ├── MediaPreparer.kt       添付 → プロンプト + メディア列
│   └── SpeechOutput.kt        TTS / WAV 再生
├── model/                     モデル取得 (HF)・インポート・削除
├── data/                      設定 (SharedPreferences)・会話履歴 (JSON)
└── ../../cpp/
    ├── CMakeLists.txt         llama.cpp を FetchContent → liblfmomni.so
    └── lfm_omni_jni.cpp       JNI ブリッジ
```

### 推論の流れ

```
Kotlin: 添付を RGB / 16kHz float に変換
   ↓  本文に "<__media__>" マーカーを埋め込む
JNI: mtmd_tokenize()  →  mtmd_helper_eval_chunks()  →  llama_decode ループでサンプリング
   ↓  1 トークンずつ Kotlin へコールバック (UTF-8 境界を保証)
表示: ストリーミング更新 → 完了時に履歴保存
```

- プロンプトは LFM3-Omni の ChatML 風テンプレート
  (`<|im_start|>system … <|im_end|>` / assistant は `<think></think>` で開始)
- メディアの `<|vision_start|> … <|vision_end|>` は **libmtmd が自動付与** するため書きません
- 会話は KV キャッシュに積み上げ (2 ターン目以降は差分のみ評価)。再生成時は履歴から全体を再構築
- 推論はメインスレッドでは行いません (`Dispatchers.Default` のワーカースレッド)
- 中断は `std::atomic` フラグ + `llama_context_params.abort_callback`

## 7. 既知の制限 (v0.1.0)

- **LFM3-Omni 自身の音声生成 (24kHz)** は未接続です。現行 llama.cpp の公開 C API
  (`llama.h` / `mtmd.h`) では音声出力が実験段階の API 群に分かれており、
  Omni モデルの音声を端末内 JNI から安定して取り出す入り口が用意されていません。
  このアプリの「読み上げ」は **Android 標準 TTS** で、モデルの音声生成とは別物です。
- GPU (Adreno 660) は使いません。CPU (NEON + OpenMP) 推論のみです。
- リアルタイムのカメラ・マイク解析は未実装 (仕様書 4.6 の将来項目)。
- サーバーモードはテキストのみ。
- 会話履歴は 1 本のみ (複数スレッド管理は今後)。

## 8. プライバシー

- 推論はすべて端末内。画像・音声・動画・会話を外部へ送信しません
- ネットワークを使うのは **モデル取得 (Hugging Face)** と、設定した場合のローカルサーバー接続だけ
- 権限は マイク / インターネット / ネットワーク状態 のみ。カメラは標準カメラアプリに撮影を委譲するため権限不要

## 9. ライセンス

- アプリ本体: このリポジトリのコード
- llama.cpp: MIT
- LFM3-Omni-2B: **LFM Open License v1.0** (モデルの利用条件に従ってください)
