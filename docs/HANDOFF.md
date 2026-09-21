# ローカル引き継ぎメモ

このリポジトリは Claude Code セッションで、`docs/keyboard-spec.md`の仕様確定、Androidプロジェクトの
骨格実装、そして **Android SDK / エミュレータを使ったビルド確認・実機動作確認** まで進めた状態です。
以降は「数値のチューニング」「Mozc統合」がローカル(Android Studio + 実機)への引き継ぎ事項です。

## 現状のブランチ
`claude/spec-refinement-y3vbn7`

## このセッションでやったこと(2回目: ビルド・エミュレータ検証)

前回のクラウドセッションでは Android SDK が無く `:app` のビルドが一度も通せていませんでしたが、
今回は Android SDK (`D:\Android\sdk`, platform 36, AGP 8.7.3) と Pixel_9a エミュレータ
(Android 17 "CinnamonBun" プレビュー, API相当37, Google APIs Playstore) が利用できる環境だったため、
以下を実施しました。

1. **ビルド確認 — 完了**
   - `local.properties` (`sdk.dir`) と `gradle.properties` (`android.useAndroidX=true` 等)が
     存在しなかったため作成。後者は元々コミットされていなかった設定ファイルで、`.gitignore`対象外
     なのでコミットに含めています。
   - `./gradlew :app:assembleDebug` — **成功**
   - `./gradlew :core:test` — **53件全て成功**(前回と変わらず)

2. **実機(エミュレータ)動作確認 — Shift単体を除きほぼ全項目を確認完了**
   - APKをインストールし、`adb shell ime set` でキーボードを有効化・選択
   - 確認できたもの:
     - ローマ字→ひらがな変換(`ka`→`か`)、候補バーへの反映、Backspaceでのセグメント単位取消
     - Enterキー: 未確定文字列があれば自動確定してから、`EditorInfo`に応じた動的アクション実行
       (検索欄で「検索」実行を確認)
     - レイヤー切替(#12キー): 見た目(背景色・記号配置)が正しく切り替わり、Backspace位置がズレない
     - レイヤー2のShiftPairキー(`-`/`_`)の非Shift側入力
     - ローマ字入力切替キーのタップ→直接英数モード、直接英数モードでの literal 入力
       (小文字のまま、かな変換を経由しない)
     - ローマ字入力切替キーの長押し→全角(zenkaku)トグル(直接英数モードで`k`→`ｋ`の全角化を確認)
     - スペースキー: タップ(未確定文字列なし→半角スペース挿入 / 未確定文字列あり→候補送り、
       候補1件時は据え置き)、長押し(Tab挿入)、左右スワイプ(カーソル移動)、上下スワイプ
       (クラッシュしないこと。1行のテキストフィールドだったため見た目の変化は確認できず)
     - Backspace長押しでのリピート削除(1秒の長押しで複数文字が連続削除されることを確認)
   - **未確認**(テスト環境固有の制約、下記「既知の問題」参照):
     - Shift単体のタップ/長押し(モーメンタリ/ロック) — 4段目の左端に位置するため
       このAVDのジェスチャーナビゲーションに干渉されタップが届かない
     - 触覚フィードバックの体感確認(振動権限追加後は例外は出なくなり、状態変化を伴う
       操作を多数実行してもクラッシュしないことは確認したが、実際に鳴るかどうかは
       スクリーンショットでは確認不可)

3. **見つけて修正した実バグ2件**
   - **[core/conversion/KanaConverter.kt](../core/src/main/kotlin/tokyo/tanutus/ime/core/conversion/KanaConverter.kt)**:
     `Composition.candidates`のデフォルトが`listOf(text)`だったため、`text`が空文字のとき
     (Backspaceで未確定文字列を完全に消したときなど)「空文字列が1件だけ入った候補リスト」を返して
     しまい、候補バーに空の青いチップが残留するバグがあった。`text.isEmpty()`なら`emptyList()`を
     返すよう修正。実機で再現・修正確認済み。
   - **[AndroidManifest.xml](../app/src/main/AndroidManifest.xml)**:
     `android.permission.VIBRATE`が宣言されておらず、状態変化(レイヤー切替・Shift・ローマ字切替など)
     で`HapticsHelper.performStateChangeTick()`が呼ばれるたびに`SecurityException`でIMEプロセスが
     クラッシュしていた(「アプリが繰り返し停止しています」)。権限を追加して解決。実機で再現・修正確認済み。

## 既知の問題・要ローカル確認事項

### エミュレータでのジェスチャーナビゲーション干渉(要現地確認)
4段目(機能キー段)のうち画面左端に近いキー(Shift、`x`座標がおおよそ0〜150px≈0〜60dp相当)を
タップすると、システムのジェスチャーナビゲーション(または今回使用したAndroidプレビュー版の
「ホームハンドル長押しでContextual Search」機能)にタッチが奪われ、IME側の`onTouchEvent`に
届かずキーボードが非表示になる、という挙動を今回のAVDで繰り返し確認しました。

- アプリ側のコードにBACKキー処理は一切ない(`grep`で確認済み)ため、アプリのバグではなく
  システムUI/ジェスチャーナビゲーション側がタッチを先取りしていると考えられます。
- 対策として`KeyboardView`に`View.setSystemGestureExclusionRects()`
  (キーボード全体を除外矩形として登録)を追加しましたが、**今回使用したAVD(Android 17
  プレビュー版)では改善が確認できませんでした**。標準的なAndroidバージョンの
  エッジスワイプ・バックジェスチャーに対しては効果があるはずのAPIですが、この環境固有の
  「ホームハンドル長押し」機能はこの除外指定の対象外である可能性があります。
- **ローカルでの確認をお願いします**: 安定版のAVD(古いAPIレベルや3ボタンナビゲーション設定)や
  実機であれば再現しないか、あるいは`setSystemGestureExclusionRects`の効果が出るかを確認して
  ください。もし実機でも同様の干渉が起きる場合は、Shiftキーを画面左端から少し内側に配置する
  (左マージンを設ける)などのレイアウト変更を検討してください。
- 今回のセッションで`adb shell settings put secure navigation_mode 0`(3ボタンナビゲーションへの
  切替)を試しましたが、このAVDでは設定してもすぐ`2`(ジェスチャーナビゲーション)に戻ってしまい、
  回避できませんでした。ローカルでは3ボタンナビゲーションのAVD/実機設定で試すか、素直に実機で
  確認するのが早いと思われます。
- Shift以外の4段目キー(レイヤー切替・スペース・ローマ字切替・Enter・Backspace)はこの干渉を
  受けず問題なく操作できたため、今回はそれらとBackspace長押し・スペースジェスチャー・
  ローマ字長押し(全角)まで一通り確認できました。

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
- `:app`(**ビルド確認済み・実機動作の主要フロー確認済み**)
  - `TanutusImeService`(`InputMethodService`実装、状態管理・IME全体の配線)
  - `KeyboardView`(Canvas描画、固定Rectグリッド、タップ/長押し/Backspaceリピート、
    ジェスチャーナビゲーション除外矩形指定)
  - `SpaceKeyTouchAdapter`(`MotionEvent`→`TouchSample`変換)
  - `CandidateBarView`(候補バー、`HorizontalScrollView`ベース)
  - `EditorInfoActionMapper`(Enterキーの動的ラベル/アクション)
  - `HapticsHelper`(状態変化時のみ振動)
  - `KeyboardTheme`(レイヤーごとの配色解決)
  - `MainActivity`(IME有効化導線のみの最小画面)
  - マニフェスト・`res/xml/method.xml`・リソース一式

### 未実施(ローカルでやること)

1. **未確認の動作確認**(上記「既知の問題」参照。他の項目は今回のセッションで確認済み)
   - Shift: タップ(モーメンタリ)、長押し(ロック) — このAVDではジェスチャーナビゲーション干渉で
     未確認。実機か別のAVD設定で確認してください
   - 触覚フィードバックが状態変化時に実際に鳴ること(クラッシュしないことは確認済み、
     体感確認のみ残)

2. **数値のチューニング**(`docs/keyboard-spec.md`に「実装しながら調整」と明記されていた項目)
   - `app/src/main/res/values/dimens.xml`
     - `space_drag_threshold`(現在24dp)
     - `space_tap_slop`(現在12dp)
     - `long_press_timeout_ms`(現在500ms)
     - `backspace_repeat_interval_ms`(現在60ms)
   - `app/src/main/res/values/colors.xml` — 全色プレースホルダーなので実機で見ながら調整

3. **設計判断の再確認**(仕様書に明記がなく、実装時にこちらで暫定的に決めた点)
   - ローマ字入力モード中はShift状態に関わらず常に小文字でかな変換器に渡す仕様にしています
     (`TanutusImeService.onKeyChar`)。Shiftは直接英数モードとレイヤー2のShiftPairキー
     (`-/_`, `'/"`)にのみ影響します。将来カタカナ変換などにShiftを使いたい場合はここを見直してください。
   - ローマ字入力切替キーの「塗りつぶし」表示は、直接英数モードON **または** 全角モードONの
     いずれかで塗りつぶす単純な論理和にしています(`KeyboardStateMachine.isLockedVisual`)。
     2つの独立した状態を1つの視覚表現に集約している点、必要なら別々の視覚表現に分けることを検討してください。

4. **将来の別タスク**
   - Mozcエンジン統合(mozkey/OyaMozcのfork、NDKビルド)。今回は明示的にスコープ外。
     `KanaConverter`インターフェース(`core/conversion/KanaConverter.kt`)経由で
     `RomajiHiraganaConverter`を差し替える形を想定した設計にしてあります。

## ビルド・テストコマンド

```bash
# core のユニットテスト
./gradlew :core:test

# app のビルド
./gradlew :app:assembleDebug
```

`local.properties`(`sdk.dir`)はローカルのSDKパスに合わせて各自作成してください
(`.gitignore`対象、リポジトリには含まれません)。

## 参考
- 仕様書: `docs/keyboard-spec.md`
- コミット履歴(`git log`)に、仕様確定の議論の流れと実装の分割単位がそのまま残っています。
