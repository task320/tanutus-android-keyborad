# ローカル引き継ぎメモ

このリポジトリはクラウド上のClaude Codeセッションで、`docs/keyboard-spec.md`の仕様確定と
Androidプロジェクトの骨格実装(Mozcなしのレイヤー/ジェスチャー/UI)まで進めた状態です。
以降の「実機・エミュレータでのビルド確認」「動作調整」「Mozc統合」はAndroid SDKが必要なため、
ローカル環境(Android Studio)へ引き継ぎます。

## 現状のブランチ
`claude/spec-refinement-y3vbn7`(このコミット時点でクリーン)

## なぜクラウドセッションで打ち切ったか
このセッションの実行環境には
- Android SDK / エミュレータが存在しない
- Android Gradle Plugin(AGP)の配布元 `dl.google.com` がネットワークポリシーでブロックされている
  (`google()` リポジトリ自体には到達できるが、AGPアーティファクトのダウンロードで403)

という制約があり、`:app`モジュール(Androidアプリ本体)は一度もビルドできていません。
`:core`(純Kotlin/JVMモジュール)は制約の影響を受けないため、
`./gradlew --configure-on-demand :core:test` でビルド・テスト済み(53件全て成功)です。

ローカルのAndroid Studio環境であればこの制約はなく、通常通り
`./gradlew :app:assembleDebug` が使えるはずです。

## 現在の実装状況

### 完了
- `docs/keyboard-spec.md`: 仕様確定(レイヤー構成、ジェスチャー、視覚/触覚フィードバック、
  かな漢字変換操作、全角半角スコープなど)
- Gradleプロジェクト骨格(`:core` / `:app` の2モジュール構成、バージョンカタログ、wrapper)
- `:core`(純Kotlin、ユニットテスト53件パス済み)
  - `KeyboardLayoutData` — レイヤー1/2のキー配列、4段目共有インスタンス
  - `RomajiHiraganaConverter` / `RomajiKanaTable` — ローマ字→ひらがな暫定変換(漢字変換なし)
  - `ZenkakuHankakuConverter` — 全角/半角変換
  - `KeyboardStateMachine` — レイヤー/Shift/入力モード/全角の状態遷移、塗りつぶし表示ルール、
    触覚フィードバック発火条件
  - `SpaceGestureDetector` / `SpaceKeyGestureHandler` — スペースキーのジェスチャー判定と
    変換中の分岐ロジック
  - `KeyboardActionListener` — スペース以外のキーイベント用インターフェース
- `:app`(**ビルド未確認**)
  - `TanutusImeService`(`InputMethodService`実装、状態管理・IME全体の配線)
  - `KeyboardView`(Canvas描画、固定Rectグリッド、タップ/長押し/Backspaceリピート)
  - `SpaceKeyTouchAdapter`(`MotionEvent`→`TouchSample`変換)
  - `CandidateBarView`(候補バー、`HorizontalScrollView`ベース)
  - `EditorInfoActionMapper`(Enterキーの動的ラベル/アクション)
  - `HapticsHelper`(状態変化時のみ振動)
  - `KeyboardTheme`(レイヤーごとの配色解決)
  - `MainActivity`(IME有効化導線のみの最小画面)
  - マニフェスト・`res/xml/method.xml`・リソース一式

### 未実施(ローカルでやること)

1. **ビルド確認**
   - Android Studioで開いてGradle Sync
   - `./gradlew :app:assembleDebug` が通ることを確認
   - コンパイルエラーが出た場合は修正(コードレビューは入念に行いましたが、実際のAGP/SDKでの
     コンパイルは一度も通していないため、想定外のエラーが出る可能性があります)

2. **実機/エミュレータでの動作確認**
   - APKをインストールし、設定でキーボードを有効化・切り替え
   - 以下を一通り確認:
     - ローマ字→ひらがな入力・Backspaceでのセグメント単位取り消し
     - スペースキー: タップ(半角スペース/変換中は次候補)、長押し(Tab、変換中は確定してから)、
       左右スワイプ(カーソル移動)、上下スワイプ(行移動)
     - Shift: タップ(モーメンタリ)、長押し(ロック)
     - ローマ字入力切替キー: タップ(直接英数⇄ローマ字)、長押し(全角⇄半角)
     - レイヤー切替: 見た目(背景色)が変わり、4段目とBackspaceの位置がズレないこと
     - レイヤー2: 数字・カッコ・記号の配置、Shiftでの `-/_`・`'/"` 切替
     - Enterキー: 検索欄と通常のテキスト欄で挙動が変わること
     - Backspace長押しでのリピート削除
     - 触覚フィードバックが状態変化時のみ鳴ること

3. **数値のチューニング**(`docs/keyboard-spec.md`に「実装しながら調整」と明記されていた項目)
   - `app/src/main/res/values/dimens.xml`
     - `space_drag_threshold`(現在24dp)
     - `space_tap_slop`(現在12dp)
     - `long_press_timeout_ms`(現在500ms)
     - `backspace_repeat_interval_ms`(現在60ms)
   - `app/src/main/res/values/colors.xml` — 全色プレースホルダーなので実機で見ながら調整

4. **設計判断の再確認**(仕様書に明記がなく、実装時にこちらで暫定的に決めた点)
   - ローマ字入力モード中はShift状態に関わらず常に小文字でかな変換器に渡す仕様にしています
     (`TanutusImeService.onKeyChar`)。Shiftは直接英数モードとレイヤー2のShiftPairキー
     (`-/_`, `'/"`)にのみ影響します。将来カタカナ変換などにShiftを使いたい場合はここを見直してください。
   - ローマ字入力切替キーの「塗りつぶし」表示は、直接英数モードON **または** 全角モードONの
     いずれかで塗りつぶす単純な論理和にしています(`KeyboardStateMachine.isLockedVisual`)。
     2つの独立した状態を1つの視覚表現に集約している点、必要なら別々の視覚表現に分けることを検討してください。

5. **将来の別タスク**
   - Mozcエンジン統合(mozkey/OyaMozcのfork、NDKビルド)。今回は明示的にスコープ外。
     `KanaConverter`インターフェース(`core/conversion/KanaConverter.kt`)経由で
     `RomajiHiraganaConverter`を差し替える形を想定した設計にしてあります。

## ビルド・テストコマンド

```bash
# core のユニットテスト(ローカルでは configure-on-demand は不要)
./gradlew :core:test

# app のビルド(ローカルでのみ可能)
./gradlew :app:assembleDebug
```

## 参考
- 仕様書: `docs/keyboard-spec.md`
- このセッションでのコミット履歴(`git log`)に、仕様確定の議論の流れと実装の分割単位が
  そのまま残っています。
