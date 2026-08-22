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

## Shift表示・挙動の見直し(実装済み・実機確認済み — Pixel 9a、2026-08-22)

ユーザーからの提案を実装・確認済み。

- [x] ローマ字入力モード中はShiftキー自体を表示しない
      (`KeyboardLayouts.FUNCTION_ROW_ROMAJI`を新設、`layoutFor(layer, inputMode)`が
      モードに応じて`FUNCTION_ROW_ALNUM`/`FUNCTION_ROW_ROMAJI`を出し分け)
- [x] ローマ字→直接英数切替でShiftが表示され、大文字/小文字切替・キーラベルの
      大文字表示(前回実装済みの`uppercaseLetters`)が有効になる
- [x] 直接英数→ローマ字入力に戻すとキー表示が小文字に戻り、Shiftのロックも解除される
      (`KeyboardStateMachine`の`RomajiToggleTap`処理で`shift`を`OFF`にリセット)
- [x] Shiftロック中の色を通常のロック色の半分の明るさ(`key_background_shift_locked`
      = `#FF26457A`)に変更、`#12`・`A/あ`のロック色とは視覚的に区別できるようにした

## 句読点キーの追加(実装済み・実機確認済み — Pixel 9a、2026-08-22)

- [x] ローマ字入力モードのみ、句点(。)をスペースの左隣、読点(、)をスペースの右隣に追加
      (`KeyAction.Punctuation`、`KeyboardLayouts.FUNCTION_ROW_ROMAJI`)。
      タップすると未確定のローマ字合成を確定してから句読点自体を確定する
      (`TanutusImeService.onPunctuationKey`)。実機で`あ。、`の入力を確認済み。
      直接英数モードのレイアウト(`FUNCTION_ROW_ALNUM`)には影響なし。

## 将来タスク

- [ ] **Mozcエンジン統合**。実現可能性検証は完了済み(2026-08-22、詳細は
      [mozc-integration-feasibility.md](mozc-integration-feasibility.md)):
      WSL2 + Bazel + Android NDKで本家`google/mozc`から`libmozc.so`のビルドに成功、
      JNIインターフェース(`evalCommand`等)も把握済み。未着手なのは辞書データセットの
      ビルド(ツールチェーンエラー未解消)、Kotlin側JNIブリッジ実装、`KanaConverter`
      インターフェース経由での接続。次回セッションへの引き継ぎ事項は上記ドキュメント参照。

## ビルド・テストコマンド

```bash
./gradlew :core:test          # ユニットテスト(56件)
./gradlew :app:assembleDebug  # APKビルド
```

`local.properties`(`sdk.dir`)は各自のSDKパスに合わせて作成(`.gitignore`対象)。
