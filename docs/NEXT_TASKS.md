# 残タスク一覧(次セッション用)

詳しい背景・検証内容は [HANDOFF.md](HANDOFF.md) を参照。ここは次にやることだけの
チェックリスト。

## 動作確認(要ローカル/実機)

- [ ] **Shiftキー単体のタップ(モーメンタリ)・長押し(ロック)**
      今回使用したAVDでは4段目左端キーへのタッチがジェスチャーナビゲーションに奪われ検証不可だった。
      実機か別のAVD設定(3ボタンナビゲーション等)で確認すること。
      改善しない場合はShiftキーの左マージンを設けるレイアウト変更を検討(詳細はHANDOFF.md参照)。
- [ ] 触覚フィードバックが実際に鳴ること(クラッシュしないことは確認済み、体感確認のみ残)

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
