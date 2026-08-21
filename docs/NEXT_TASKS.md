# 残タスク一覧(次セッション用)

詳しい背景・検証内容は [HANDOFF.md](HANDOFF.md) を参照。ここは次にやることだけの
チェックリスト。

## 未コミットの変更(要判断)

以下はビルド確認・実機検証セッションで加えた修正で、まだコミットしていない。
- `app/src/main/AndroidManifest.xml` — `VIBRATE`権限追加(クラッシュ修正)
- `app/src/main/kotlin/com/tanutus/ime/view/KeyboardView.kt` — ジェスチャー除外矩形追加
- `core/src/main/kotlin/com/tanutus/ime/core/conversion/KanaConverter.kt` — 空候補チップのバグ修正
- `docs/HANDOFF.md` — 引き継ぎ内容更新
- `gradle.properties`(新規、`.gitignore`対象外)

- [ ] ユーザーに確認の上、コミットする

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

## 設計判断の再確認(仕様書に明記なし、暫定実装のまま)

- [ ] ローマ字入力モード中はShift状態を無視して常に小文字でかな変換器に渡す仕様
      (`TanutusImeService.onKeyChar`)。将来カタカナ変換等でShiftを使いたければ要見直し。
- [ ] ローマ字入力切替キーの「塗りつぶし」表示が、直接英数モードON **または** 全角モードONの
      単純な論理和になっている(`KeyboardStateMachine.isLockedVisual`)。2状態を1表示に集約している点、
      必要なら分離を検討。

## 将来タスク(今回は明示的にスコープ外)

- [ ] Mozcエンジン統合(mozkey/OyaMozcのfork、NDKビルド)。
      `KanaConverter`インターフェース経由で`RomajiHiraganaConverter`を差し替える設計にしてある。

## ビルド・テストコマンド

```bash
./gradlew :core:test          # ユニットテスト(53件)
./gradlew :app:assembleDebug  # APKビルド
```

`local.properties`(`sdk.dir`)は各自のSDKパスに合わせて作成(`.gitignore`対象)。
