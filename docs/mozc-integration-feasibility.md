# Mozcエンジン統合 — 実現可能性検証(2026-08-22)

`docs/NEXT_TASKS.md`の「将来タスク」に挙げていたMozcエンジン統合について、本格着手前に
実現可能性だけを検証したセッションの記録。**実装はしていない**(検証のみ)。

## 結論

一番リスクが高かった「本家`google/mozc`が本当にAndroid向けにビルドできるか」は**実証済み**。
WSL2(Ubuntu 22.04)+ Bazel + Android NDKで`libmozc.so`(4アーキテクチャ全て)のビルドに成功した。
辞書データセットのビルドはツールチェーン起因のエラーで未達成だが、致命的な壁ではない(下記参照)。

## 検証環境

- Windows側のAndroid SDK/NDKではなく、**WSL2(Ubuntu 22.04.1 LTS)**を使用。
  Mozc公式ドキュメント([build_mozc_for_android.md](https://github.com/google/mozc/blob/master/docs/build_mozc_for_android.md))
  でも「Currently macOS and Linux are tested」とあり、Windows単体は非対応。
- 追加インストールしたパッケージ(`sudo`が必要、ユーザー側で実行):
  ```bash
  sudo add-apt-repository -y ppa:deadsnakes/ppa
  sudo apt-get update
  sudo apt-get install -y python3.12 python3.12-venv clang lld unzip
  ```
  (Ubuntu 22.04標準はPython 3.10だが、Mozcのビルドには3.12以上が必要)
- Bazelは`bazelisk`(バージョン固定なしで最新のBazelを自動取得)を`~/bin`に手動配置。
  実行時にBazel 9.0.2/9.2.0が自動ダウンロードされた。
- Android NDKは手動セットアップ不要。`python3.12 build_tools/update_deps.py`が
  **NDK r29を自動取得**する(`third_party_cache/android-ndk-r29-linux.zip`)。

## 実行したコマンド

```bash
git clone --depth 1 https://github.com/google/mozc.git
cd mozc/src
python3.12 build_tools/update_deps.py          # NDK r29等の依存関係取得

export CC=clang   # 重要: 未設定だと「Cannot find gcc or CC」でビルド即失敗
bazelisk build package --config oss_android --config release_build
```

**ビルド成功**(所要時間 約13分、2907アクション):

```
Target //:package up-to-date:
  bazel-bin/android/jni/native_libs.zip
INFO: Elapsed time: 788.111s
INFO: Build completed successfully, 2907 total actions
```

`native_libs.zip`の中身(4アーキテクチャ全て生成されていることを確認):

```
libs/arm64-v8a/libmozc.so    16,174,992 bytes
libs/armeabi-v7a/libmozc.so  12,932,540 bytes
libs/x86/libmozc.so          14,099,512 bytes
libs/x86_64/libmozc.so       15,155,848 bytes
```

## JNIインターフェース(`android/jni/mozcjni.cc`)

Android向けクライアント(Java/UI)コードは本家では2021年頃に削除済み。取得できるのは
ネイティブ変換エンジン(`libmozc.so`)のみで、Kotlin側のUI/IME配線(このプロジェクトの
`TanutusImeService`等)は自前で用意する必要がある。ただしJNI境界そのものは非常に薄く、
公開されているネイティブ関数は3つだけ:

| 関数 | シグネチャ | 役割 |
|---|---|---|
| `onPostLoad` | `(String userProfileDir, String dataFilePath): Boolean` | セッションハンドラ初期化。`dataFilePath`は下記の辞書データセットファイルへのパス |
| `evalCommand` | `(byte[]): byte[]` | `mozc::commands::Command`(protobuf)をシリアライズして渡し、変換結果もprotobufで受け取る |
| `getDataVersion` | `(): String` | 読み込んだ辞書データのバージョン文字列 |

つまりKotlin側の統合イメージは、`mozc.commands.Command`protoをKotlin/Java側でも
(protoファイルは`protocol/commands.proto`にある)ビルドし、`evalCommand`にリクエストを
シリアライズして渡し、レスポンスをパースして`KanaConverter`インターフェース
([core/conversion/KanaConverter.kt](../core/src/main/kotlin/com/tanutus/ime/core/conversion/KanaConverter.kt))
の実装にマッピングする形になる。`data_file_path`が渡せない/読み込みに失敗した場合は
自動的に「minimal engine」にフォールバックする実装になっている(`CreateMobileEngine`)。

## 未解決の問題: 辞書データセットのビルド

変換エンジンが実際に「かな漢字変換」をするには、上記`dataFilePath`に渡す辞書データファイル
(`//data_manager/oss:mozc_dataset_for_oss`)が別途必要。このビルドを試したところ、
Android向け本体ビルドとは別の、**ホスト(Linux)側で動かすデータ生成ツールのコンパイル**で
エラーになった:

```
dictionary/file/codec.cc:197:15: error: no matching function for call to 'construct_at'
Target //data_manager/oss:mozc_dataset_for_oss failed to build
```

原因はclang-14(Ubuntu 22.04標準)とGCC 11のlibstdc++ヘッダの組み合わせによる非互換と
見られる(C++20の`construct_at`まわり)。Android向け本体ビルド(NDK付属のclangを使う
クロスコンパイル)は影響を受けず成功しているため、**アーキテクチャ上の壁ではなく
ホスト側ビルド環境のツールチェーンのバージョン不一致**という位置付け。新しいclang
(clang-16以降など)かGCCをインストールして`CC`を切り替えれば解決する可能性が高いが、
今回は未検証(時間の都合で未着手)。

## 次回セッションへの引き継ぎ(未着手・優先度つけず列挙のみ)

- 辞書データセット(`mozc_dataset_for_oss`)ビルドのツールチェーン問題の解消
- 生成した辞書データ(サイズ未計測、数十MB程度が見込まれる)を`:app`のアセットとして同梱する方法の検討
- `protocol/commands.proto`をKotlin/Java側でビルドし、`evalCommand`とやり取りするラッパー実装
- 既存の`KanaConverter`インターフェースへ、Mozc版実装(`MozcKanaConverter`のようなクラス)を接続
- ライセンス確認: Mozc本体はBSD-3-Clauseで組み込みに問題なし(要事前確認だが今回は未実施)
- `libmozc.so`(4 ABI合計 約58MB)+辞書データをAPKに含めた場合のアプリサイズへの影響確認

## ビルド生成物の後始末

検証に使ったクローン・ビルド成果物はWSL側`~/work/mozc`に残置している(このリポジトリには
何もコミットしていない)。次回そのまま`bazelisk build`を再開できるが、不要であれば
`rm -rf ~/work/mozc ~/work/mozc_build.log`で削除して問題ない。
