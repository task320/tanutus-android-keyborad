# 残タスク一覧(次セッション用)

詳しい背景・検証内容は [HANDOFF.md](HANDOFF.md) を参照。ここは次にやることだけの
チェックリスト。

## 動作確認(実機で確認済み — Pixel 9a)

- [x] **Shiftキー単体のタップ(モーメンタリ)・長押し(ロック)**
      実機で確認。`systemGestureExclusionRects`追加後は4段目左端キーへのタッチも正しく拾えている。
      タップ→`As`(1文字だけ大文字化→自動解除)、長押し→`ASD`(ロック中は大文字維持)、
      再タップ→`ASDa`(解除)を確認。
- [x] 触覚フィードバックが実際に鳴ること
      コードは正常。ただし端末の**設定 → サウンドと振動 → タッチの触覚フィードバックがOFFだと
      `VibrationEffect.EFFECT_TICK`がシステム側で無効化される**仕様のため無振動に見えていた
      (この設定は`Settings.System.HAPTIC_FEEDBACK_ENABLED`)。ONにしたら振動を確認できた。
      アプリ側のバグではないため対応不要。ユーザーからバグ報告が来た場合はまずこの設定を疑うこと。
- [x] **(新規発見・解決済み)** 複数IMEが有効な状態だと、システムの言語切替(地球儀)アイコンと
      キーボード折りたたみ(▽)アイコンが行4に重なって表示され、タップがシステムに奪われることが
      あった。原因は行4がスワイプ/長押しでホーム・アシスタントを呼び出す`mandatorySystemGestures`
      (アプリ側で除外不可能な予約領域、`systemGestureExclusionRects`とは別物)と重なっていたこと。
      `TanutusImeService.applyGestureSafeAreaPadding`でルートビューに
      `WindowInsets.Type.mandatorySystemGestures()`/`navigationBars()`分の下パディングを追加し、
      実機(Pixel 9a)で重なり解消を確認済み。
- [x] **タブ入力の実値確認**: 長押しでタブが入力されているか見た目で分かりにくいとの指摘があったため、
      `uiautomator dump`でEditTextの実テキストを確認したところ`a\tb`(実際のタブ文字)であることを
      確認。表示上スペースと見分けづらいだけで、実装は正しい。

## 数値のチューニング(実機確認済み — Pixel 9a、2026-08-22)

`docs/keyboard-spec.md`に「実装しながら調整」と明記されていた項目。

- [x] `app/src/main/res/values/dimens.xml` — スペースキーのドラッグ(カーソル移動)・
      タップ判定の感触を実機で確認、現状の値(`space_drag_threshold`24dp、
      `space_tap_slop`12dp、`long_press_timeout_ms`500ms、
      `backspace_repeat_interval_ms`60ms)で問題なしとの回答。変更なし。
- [x] `app/src/main/res/values/colors.xml` — 配色(背景の濃紺、キーのグレー、
      ロック時の青)も実機で確認、このままでよいとの回答。変更なし。

## 次回検討する変更案(2026-08-22、ユーザーからのメモ・未実装)

以下はユーザーから「メモだけしておいて」と依頼された提案。実装はまだ行っていない。
次回セッションで実装方針を相談してから着手すること。

1. **Shiftキーの表示・挙動の見直し**
   - ローマ字入力モード中はShiftキー自体を表示しない
     (現状は常に行4左端に表示されている。`KeyboardLayouts.FUNCTION_ROW`は
     ローマ字/直接英数の両方で共通のため、モードに応じてキー自体を出し分ける
     仕組みが必要になる)
   - ローマ字入力から直接英数入力に切り替えた際は、Shiftキーを表示し、
     大文字/小文字を切り替えられるようにする。キーボードの文字表示(キーラベル)も
     連動して大文字/小文字が切り替わる(この「キーラベルが大文字になる」部分は
     今回のセッションで実装済み — `KeyboardView.render`の`uppercaseLetters`、
     `TanutusImeService.refreshKeyboardView`参照)
   - 直接英数入力からローマ字入力に戻した際は、キーボード表示を小文字に戻し、
     **Shiftのロック状態も解除する**(現状`onRomajiToggleTap`はコンポジションの確定と
     モード切替のみ行っており、Shift状態のリセットはしていない — ここが今回の
     提案を実装する上でのギャップになりそう)
2. **Shiftロック中の色をもう少し分かりやすく**
   - 現在Shift長押し(ロック)時に使っている青(`R.color.key_background_locked`
     = `#FF4C8BF5`、`KeyboardTheme.kt`経由)を、明るさ半分程度に落とした色に
     変更したい、とのリクエスト。ただし`key_background_locked`は他のロック系キー
     (レイヤー切替・ローマ字切替)とも共用の色なので、Shift専用に分けるか、
     全体のロック色自体を暗くするか要相談。
     色味の判断は前回同様、実機で見ながら確認するのが良さそう。

## 将来タスク(今回は明示的にスコープ外)

- [ ] Mozcエンジン統合(mozkey/OyaMozcのfork、NDKビルド)。
      `KanaConverter`インターフェース経由で`RomajiHiraganaConverter`を差し替える設計にしてある。

## ビルド・テストコマンド

```bash
./gradlew :core:test          # ユニットテスト(53件)
./gradlew :app:assembleDebug  # APKビルド
```

`local.properties`(`sdk.dir`)は各自のSDKパスに合わせて作成(`.gitignore`対象)。
