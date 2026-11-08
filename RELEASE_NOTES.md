# LFM3 Omni Local for Android v0.1.0

Liquid AI の **LFM3-Omni-2B** を Android 端末内だけで動かすアプリの初回ビルドです。
テキスト・画像・音声・動画を 1 つのモデルで扱い、結果をテキストで返します。
クラウド API は使いません。

## 収録内容

- `app-debug.apk` … AQUOS R6 (Android 13 / arm64-v8a) 向けのデバッグ APK
- 推論エンジン: llama.cpp **b11960 (v0.8.0)** + libmtmd を NDK で静的リンク
- モデルは APK に含まれません。初回起動後にアプリ内 [モデル] 画面から
  `LiquidAI/LFM3-Omni-2B-GGUF` の `Q4_K_M` を取得してください (約 2.3GB)。

## インストール

1. APK を端末へ転送
2. 「提供元不明のアプリ」を許可してインストール
3. [モデル] → 「Hugging Face からワンタップ取得」 (Wi-Fi 推奨)
4. [チャット] で会話

## 既知の制限 (v0.1.0)

- LFM3-Omni 自身の 24kHz 音声生成は、現行 llama.cpp の公開 C API では
  端末内 JNI から安定して呼べる形になっていないため未接続です。
  読み上げは Android 標準 TTS を使用します。
- 動画は 2fps・既定 24 フレーム (約 12 秒) までを解析します。
- サーバーモード (`localhost:8080` の llama-server) はテキストのみ対応です。
