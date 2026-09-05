# 進捗ログ

新しいセッションはこのファイルの一番下から読む。追記は日付降順ではなく**追記順(古い順)**。

---

## 2026-09-02 — 現状把握(コード変更なし)

### やったこと
リポジトリ全体を読み、ビルド・テストを実行して現状を棚卸しした。実装は一切変更していない。

### モジュール構成

| モジュール | 中身 |
| --- | --- |
| `:core` | 純Kotlin(Android非依存)。`KanaConverter`インターフェース、`KeyboardLayoutData`、`KeyboardStateMachine`、`SpaceGestureDetector`/`SpaceKeyGestureHandler`、`RomajiHiraganaConverter`(現在は未使用の暫定実装)、`ZenkakuHankakuConverter` |
| `:app` | `TanutusImeService`(InputMethodService)、`KeyboardView`(Canvas描画)、`CandidateBarView`、`SpaceKeyTouchAdapter`、`EditorInfoActionMapper`、`HapticsHelper`、`KeyboardTheme`、`MainActivity`(IME有効化導線のみ) |
| `:mozc` | `libmozc.so`(4 ABI)/ `mozc.data`(18MB、いずれもGit LFS)、`protocol/*.proto`のprotobuf-liteビルド、`MozcJNI`、`MozcEngine`、`MozcKanaConverter` |

### 動作確認の結果

- `./gradlew :app:assembleDebug` — **成功**(BUILD SUCCESSFUL)
- `./gradlew :core:test --rerun-tasks` — **56件すべて成功**(failure 0 / ignored 0)
- 生成APK: `app/build/outputs/apk/debug/app-debug.apk` = **76MB**(4 ABI同梱)

### 動いているもの(実機Pixel 9aで検証済みと記録あり)

- ローマ字 → かな → 漢字変換。実エンジンMozc(`MozcKanaConverter`)がデフォルト
- スペースキー: タップ(合成中は次候補 / 通常は半角スペース)、長押しTab、上下左右スワイプでカーソル移動
- 候補バーのタップ確定(`SessionCommand.SUBMIT_CANDIDATE`で、フォーカス位置と違う候補でも正しく確定)
- Enterの二段階(合成の確定 → エディタアクション)、複数セグメントの1セグメントずつ確定
- Backspace(合成中はMozcへBACKSPACE、通常は削除+長押しリピート)
- レイヤー1/2トグル、Shift(タップ=モーメンタリ / 素早い2回タップ=ロック)、直接英数トグル、全角トグル
- 句読点キー(ローマ字モードのみ、合成に合流)、長音符(`-`)のローマ字合成への合流
- ジェスチャーナビゲーション回避(`systemGestureExclusionRects` + `mandatorySystemGestures`分の下パディング)

### 作りかけ / 見つかった穴(優先順位1「変換の安定動作」に関わるもの)

1. **`onUpdateSelection` 未実装**。変換中にユーザーがテキストを直接タップしてカーソルを
   動かすと、Android側は composing を終了させるが`MozcKanaConverter`はセッションの合成を
   保持したまま。次の打鍵で入力が二重・入れ替わる恐れがある。
2. **`EditorInfo.inputType` を見ていない**。パスワード欄・数値欄・URL欄でもローマ字変換
   モードで起動し、候補バーに入力内容が出る。実害あり(プライバシー、ストア審査観点も)。
3. **Enterキーのラベルが動的でない**。`EditorInfoActionMapper`は「検索」「送信」などの
   ラベルを計算しているが、`EnterKeyBehavior.label`はどこからも参照されておらず、表示は
   常に固定文字列 `"Enter"`(`KeyboardLayouts`)。仕様書は「動作・**ラベル**を動的に
   切り替える」と書いている。
4. **カタカナ/半角カナへの変換手段がない**(F7相当)。日本語IMEとしての欠けが目立つ。
5. `MozcKanaConverter.rawInputBuffer` がセグメント部分確定後もリセットされず、
   `Composition.rawInput` が実態とずれる(現状の分岐は`isEmpty`しか見ないので実害は未確認)。
6. Mozcのユーザー辞書/学習の永続化を明示的に呼んでいない(`mozc_profile`は作成済み)。

### 未着手(CLAUDE.mdの優先順位に対応)

- **優先2: Markdown補助動作 — コード上に一切存在しない**。`**`/`` ` ``のペア入力、
  リスト行のEnter継続、`- [ ]`、```` ``` ````、見出しトグル、いずれも未実装。
- 優先3: レイアウト編集/JSON入出力 — レイアウトは`KeyboardLayoutData.kt`にハードコード。
- 優先4: Ctrl / Alt / Esc — なし。
- 優先5: クリップボード履歴・スニペット — なし。
- 設定画面・設定の永続化(SharedPreferences等)の基盤そのものがない。
- Play Billing(買い切りPro)未着手。リリースビルド設定も未整備
  (`isMinifyEnabled=false`、署名設定なし、`versionCode=1`)。
- OSSライセンス表記画面(Mozc = BSD-3-Clause)未着手。

### パーミッション / 依存

- `AndroidManifest.xml`のパーミッションは **`VIBRATE` のみ**。INTERNETなし(方針通り)。
- AGP 8.7.3 / Kotlin 2.1.0 / compileSdk 36 / minSdk 26 / targetSdk 36。
- 依存: androidx core-ktx 1.15.0、appcompat 1.7.0、protobuf-javalite 3.25.5、junit 4.13.2。
  変換エンジンは外部ライブラリではなく自前ビルドの`libmozc.so`同梱。

### 次にやること(提案)

1. **変換の安定化パック**(優先1の締め。1セッション): 上記の穴 1・2・3 を潰す。
   → **同日中に承認を得て実装済み。下記を参照。**
2. その次に **優先2のMarkdown補助動作 第一弾**(1〜2セッション)。

---

## 2026-09-02 — 変換の安定化パック(実装)

上の現状把握で挙げた優先1の穴 1・2・3 を実装した。

### やったこと

1. **未確定文字列とカーソル移動の同期**(穴1)
   - `TanutusImeService.onUpdateSelection`を新規実装。変換中にユーザーがテキストを直接
     タップしてカーソルを動かすと、`finishComposingText()`で今の位置に確定してMozcセッションを
     破棄する(`abandonComposition`)。
   - 誤爆防止を2つ入れた:
     - エディタが合成範囲を報告していない(`candidatesStart < 0`)場合は何もしない。
       WebViewなど常に-1を返す実装があり、そこで「移動した」と誤判定すると1打鍵ごとに
       変換が壊れるため。
     - `applySegmentCommit`(セグメント確定→残りを再合成)を`beginBatchEdit`/`endBatchEdit`で
       くくった。確定と再合成の間は合成範囲が一瞬消えるので、そこに`onUpdateSelection`が
       割り込むと「ユーザーが離れた」に見えてしまうため。
2. **入力欄の種類によるモード自動選択**(穴2)
   - 新規 `app/.../editor/EditorInfoInputModeMapper.kt`。`EditorInfo.inputType`から
     `ConversionPolicy`を3段階で決める。
     - `NORMAL`: 従来どおり(ローマ字+候補バー)
     - `DEFAULT_ALNUM`(メール/URI/数値/電話/日時): 直接英数で開始、ユーザーは切替可能
     - `SUPPRESSED`(パスワード各種): 直接英数で開始、**候補バーをGONE**、
       **ローマ字入力切替キーを無効**。パスワードが候補バーに平文で出るのを防ぐ。
   - `onStartInputView`が`KeyboardStateMachine(KeyboardUiState(inputMode = ...))`で
     初期モードを与えるようになった。
3. **Enterキーのラベルを動的に**(穴3)
   - `KeyboardView.render`に`enterLabel`引数を追加し、`EnterKeyBehavior.label`
     (「検索」「送信」「完了」…)を実際に描画するようにした。これまで計算だけして捨てていた。
   - あわせて`fitLabelToKey`を追加。アプリが`EditorInfo.actionLabel`に任意の文字列を
     渡してくるので、キー幅に収まらないラベルは文字サイズを縮めて収める。
   - `KeyboardLayouts`のEnterキーは共有定数`ENTER_KEY`に整理し、ラベルは
     「フォーカス前のフォールバック」であることをコメントで明示(`"Enter"` → `"⏎"`)。
4. `docs/keyboard-spec.md` に上記2件の仕様(「入力欄の種類による自動切り替え」
   「未確定文字列とカーソル移動の同期」)を追記。

### 動作確認の結果

- `./gradlew :core:test` — **56件成功**(failure 0)
- `./gradlew :app:assembleDebug` — **成功**
- **実機での確認は未実施**。この環境に端末がないため。次のセッションで最低限これを確認したい:
  - 変換中にテキストを直接タップ → 文字が入れ替わらないこと(Gboardと同じ挙動になるか)
  - パスワード欄 → 候補バーが消え、A/あキーが効かないこと
  - 検索欄 → Enterキーのラベルが「検索」になること
  - WebView(Chromeのフォーム等)で1打鍵ごとに変換が切れないこと ← 誤爆の主な懸念点

### 次にやること

- 上記の実機確認。
- その後 **優先2のMarkdown補助動作 第一弾**(1〜2セッション)。
- 積み残しメモ: 数値/電話番号の入力欄では初期レイヤーもレイヤー2(数字段)にしたほうが
  自然だが、今回のスコープ外として入れていない。

---

## 2026-09-05 — Shiftを3段目へ移動、スペースの両隣に句読点

### 経緯
実機で使っていて「半角入力時にカンマとドットがまったく打てない」ことが判明した。
4段目の左端をShiftが占めており、句読点を置く枠がなかった。

### やったこと

- **Shiftを4段目から3段目の左端へ移動**(物理QWERTYと同じ位置)。
  レイヤー1の3段目は `Shift z x c v b n m`(8キー)、レイヤー2の3段目は
  `Shift # * + < > \` ~ '`(9キー)。
- **空いた枠に句読点を配置**。4段目は両モードとも
  `レイヤー切替 / 句読点 / スペース / 句読点 / ローマ字入力切替 / エンター` の6キー構成になり、
  **入力モードで変わるのはスペース両隣の字形だけ**になった。
  - 直接英数モード: `,` と `.`
  - ローマ字モード: `。` と `、`(従来どおり)
- `,` `.` は `KeyAction.Punctuation` ではなく `KeyAction.Char` にした。直接英数モードには
  合流すべき合成がなく、通常の文字経路を通すことで**全角トグルON時に `，` `．` になる**ため。

### 副次的に直った既存の穴

ローマ字入力モードの4段目にはShiftがなかったため、**ローマ字モード+レイヤー2では
`_` と `"`(Shift併用キー)が打てなかった**。Shiftが3段目に来て両モードで常時表示に
なったことで解消した。

### 引き換えに発生した変化(実機で違和感がないか要確認)

- レイヤー1の3段目が7キー→8キーになったため、`z`〜`m` の位置が**半キー分右にずれた**。
- レイヤー1の3段目(8キー・中央寄せ)とレイヤー2の3段目(9キー・全幅)でキー数が違うため、
  **レイヤーを切り替えるとShiftの左右位置が動く**。Backspaceや4段目のような
  「レイヤーを跨いでも位置が変わらない」保証はShiftにはない。
  気になるようならレイヤー2の3段目から記号を1つ減らして8キーに揃える手がある。
- ローマ字入力中もShiftが画面に出るようになった(2026-08-22に「ローマ字入力中はShiftを
  表示しない」と決めた方針の変更)。レイヤー2のShift併用キーに必要なので意図的。
  レイヤー1のローマ字入力中は依然として効果がない(かなに大文字小文字がないため)。

### 動作確認の結果

- `./gradlew :core:test` — **57件成功**(failure 0。レイアウト検証を1件追加)
- `./gradlew :app:assembleDebug` — **成功**
- **実機確認は未実施**。前回の変換安定化パックとあわせて次回まとめて確認する。

### 次にやること

- 実機確認(前回分 + 今回分)。今回分で見るのは、カンマ/ドットが打てること、全角ONで
  `，``．` になること、Shiftの新しい位置の押しやすさ、ローマ字+レイヤー2で `_` `"` が
  打てること。
- その後 **優先2のMarkdown補助動作 第一弾**。
