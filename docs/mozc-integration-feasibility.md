# Mozcエンジン統合 — 実現可能性検証(2026-08-22)

`docs/NEXT_TASKS.md`の「将来タスク」に挙げていたMozcエンジン統合について、本格着手前に
実現可能性だけを検証したセッションの記録。**実装はしていない**(検証のみ)。

## 結論

一番リスクが高かった「本家`google/mozc`が本当にAndroid向けにビルドできるか」は**実証済み**。
WSL2(Ubuntu 22.04)+ Bazel + Android NDKで`libmozc.so`(4アーキテクチャ全て)、および
変換に必要な辞書データセット(`mozc.data`、約18MB)の両方のビルドに成功した(追記:
2026-08-22セッション後半、`CC=gcc-12`への切り替えでツールチェーン問題を解消)。
ビルド面での大きな障害は現時点でなく、残るのはKotlin側の統合実装。

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
([core/conversion/KanaConverter.kt](../core/src/main/kotlin/tokyo/tanutus/ime/core/conversion/KanaConverter.kt))
の実装にマッピングする形になる。`data_file_path`が渡せない/読み込みに失敗した場合は
自動的に「minimal engine」にフォールバックする実装になっている(`CreateMobileEngine`)。

## 解決済みの問題: 辞書データセットのビルド(2026-08-22追記)

変換エンジンが実際に「かな漢字変換」をするには、上記`dataFilePath`に渡す辞書データファイル
(`//data_manager/oss:mozc_dataset_for_oss`)が別途必要。最初の試行(`CC=clang`、
Ubuntu標準のclang-14)では、Android向け本体ビルドとは別の、**ホスト(Linux)側で動かす
データ生成ツールのコンパイル**でエラーになった:

```
dictionary/file/codec.cc:197:15: error: no matching function for call to 'construct_at'
Target //data_manager/oss:mozc_dataset_for_oss failed to build
```

原因はclang-14(Ubuntu 22.04標準)とGCC 11のlibstdc++ヘッダの組み合わせによる非互換と
見られる(C++20の`construct_at`まわり)。Android向け本体ビルド(NDK付属のclangを使う
クロスコンパイル)は影響を受けず成功していたため、アーキテクチャ上の壁ではなくホスト側
ビルド環境のツールチェーンのバージョン不一致と判断。

**対処**: `sudo apt-get install -y g++-12`でGCC 12を導入し、`export CC=gcc-12`に
切り替えて再ビルドしたところ成功した:

```bash
sudo apt-get install -y g++-12   # ユーザー側で実行(sudo必要)

export CC=gcc-12
cd ~/work/mozc/src
bazelisk build //data_manager/oss:mozc_dataset_for_oss --config oss_linux
```

```
Target //data_manager/oss:mozc_dataset_for_oss up-to-date:
  bazel-bin/data_manager/oss/mozc.data
INFO: Elapsed time: 145.042s
INFO: Build completed successfully, 344 total actions
```

生成物: `bazel-bin/data_manager/oss/mozc.data`(18,855,160 bytes ≈ 18MB)。
ビルド中に出る`E0000 ... Failed to create directory: /home/*/.mozc: PERMISSION_DENIED`
は、サンドボックス内でユーザープロファイルディレクトリを作ろうとして失敗している無害な
警告で、ビルド結果には影響しない(Target up-to-dateで成功している)。

なお`//:package`(libmozc.so本体、`android/jni:native_libs`)のビルドは`CC=clang`
(clang-14)のままで最初から成功していた。`CC=gcc-12`に統一しても影響はない見込みだが、
未検証。

## 次回セッションへの引き継ぎ(未着手・優先度つけず列挙のみ)

- 生成した辞書データ(`mozc.data`、約18MB)+ `libmozc.so`(4 ABI合計 約58MB)を
  `:app`のアセットとして同梱する方法の検討、APKサイズへの影響確認
- `protocol/commands.proto`をKotlin/Java側でビルドし、`evalCommand`とやり取りするラッパー実装
- 既存の`KanaConverter`インターフェースへ、Mozc版実装(`MozcKanaConverter`のようなクラス)を接続
- ライセンス確認: Mozc本体はBSD-3-Clauseで組み込みに問題なし(要事前確認だが今回は未実施)
- 実機での動作確認(WSLでのビルド確認のみで、Android実機/エミュレータでの`.so`ロード・
  変換動作はまだ試していない)

## ビルド生成物の後始末

検証に使ったクローン・ビルド成果物はWSL側`~/work/mozc`に残置している(このリポジトリには
何もコミットしていない)。次回そのまま`bazelisk build`を再開できるが、不要であれば
`rm -rf ~/work/mozc ~/work/mozc_build.log`で削除して問題ない。
