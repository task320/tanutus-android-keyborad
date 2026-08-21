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
- [ ] **(新規発見)** 複数IMEが有効な状態だと、システムの言語切替(地球儀)アイコンが
      「A/あ」(ローマ字入力切替)キーの右上に重なって表示され、右寄りをタップするとシステムに
      タップを奪われることがある。実運用でのユーザー誤タップリスクとして要検討
      (`onCreateInputView`で`setInputMethodPickerVisibility`相当の抑制や、キー配置調整の余地がないか調査)。

## 数値のチューニング

`docs/keyboard-spec.md`に「実装しながら調整」と明記されていた項目。実機で見ながら調整する。

- [ ] `app/src/main/res/values/dimens.xml`
  - `space_drag_threshold`(現在24dp)
  - `space_tap_slop`(現在12dp)
  - `long_press_timeout_ms`(現在500ms)
  - `backspace_repeat_interval_ms`(現在60ms)
- [ ] `app/src/main/res/values/colors.xml` — 全色プレースホルダー

## 将来タスク(今回は明示的にスコープ外)

- [ ] Mozcエンジン統合(mozkey/OyaMozcのfork、NDKビルド)。
      `KanaConverter`インターフェース経由で`RomajiHiraganaConverter`を差し替える設計にしてある。

## ビルド・テストコマンド

```bash
./gradlew :core:test          # ユニットテスト(53件)
./gradlew :app:assembleDebug  # APKビルド
```

`local.properties`(`sdk.dir`)は各自のSDKパスに合わせて作成(`.gitignore`対象)。
