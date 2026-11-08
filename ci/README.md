# GitHub Actions ビルド定義

このファイルは `.github/workflows/build-apk.yml` に置くべきものです。

Pickle (GitHub App) には **Workflows 権限** が無いため、アプリから `.github/workflows/` 配下へ
直接プッシュすることができません（`without 'workflows' permission` で拒否されます）。
そのため同じ内容をここに置いています。

## 有効化の方法（どちらか）

**A. Pickle の GitHub App に権限を付与する**
https://github.com/apps/pickle → Configure → Repository permissions → **Workflows: Read and write** → Save
その後、このファイルを `.github/workflows/build-apk.yml` に移動してコミットすれば Actions が動きます。

**B. GitHub の Web UI から追加する**
リポジトリ → Add file → Create new file → ファイル名に `.github/workflows/build-apk.yml` を入力 →
このファイルの内容を貼り付けて Commit changes。

どちらでも、`main` への push を契機に APK のビルドが走り、
Artifact `app-debug-apk` と Release `v0.1.0` に `app-debug.apk` が出力されます。
