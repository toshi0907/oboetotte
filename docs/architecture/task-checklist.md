# タスクのチェックリスト

**データ**: `Task.checklist`(TEXT、DBバージョン27で追加。`MIGRATION_26_27`)に、1行1項目・行頭の`[x] `(チェック済み)/`[ ] `(未チェック)で状態を表す文字列として保存し、項目が無ければNULL。別テーブルにはせず、繰り返しタスクの次回分生成(`Task.copy`)やバックアップ(`backup.json`の`checklist`キー、旧形式に無ければNULL)にそのまま乗る形にしている。文字列と`List<ChecklistItem>`の相互変換は`Checklist`(`Checklist.kt`、`parse`/`format`/`itemsFromInput`/`resetChecks`)に集約し、項目テキストは改行を空白にまとめ前後の空白を除いた1行に正規化する(空の項目は保存しない)。形式外の行は未チェック項目として読む。ユニットテストは`ChecklistTest`。

**編集画面(`EditTaskDialog`)**: メモ欄の直後に「チェックリスト」セクション。既存項目は1行ずつ`☑`/`☐`(状態表示のみ、編集画面ではトグル不可)+単一行の`OutlinedTextField`(文言編集)+`↑`/`↓`(並べ替え)+`✕`(削除)。下部の複数行入力欄に入力して「追加」を押すと`Checklist.itemsFromInput`で各行が未チェック項目として末尾に追加される(空行は無視)。他の項目と同じくダイアログ内の状態を変えるだけで、「保存」時に`TaskEdits.checklist`として`TaskViewModel.updateTask`へ渡す。「追加」を押さずに保存した場合も入力欄の内容を項目として取り込む。操作ログの「タスク編集」には項目テキストの変化(チェック状態は比較しない)を「チェックリスト」として記録する。編集中の各行は`EditingChecklistItem`の一意な`key`で`key()`に包み、並べ替え・削除で入力欄の状態(IME入力中の文字等)が別の項目へ移らないようにしている。

**詳細画面(`TaskDetailDialog`)**: メモの直後に「チェックリスト(完了数/全体数)」と各項目を`Checkbox`付きで表示し、行(またはチェックボックス)のタップで`TaskViewModel.toggleChecklistItem(task, index, expectedText)`を呼ぶ。この処理は`Mutex`で直列化した上でDBの最新値を読み直し、`index`の項目のテキストが`expectedText`と異なれば(表示後に編集で位置がずれた場合)同じテキストの項目が1つだけあればそれを反転し(複数あれば判別できないため何もしない)、`TaskDao.setChecklist`で`checklist`カラムだけを更新する(古いスナップショットで完了状態等を巻き戻さないため)。操作ログに「チェックリスト項目をチェック」/「チェックリスト項目のチェックを外す」を記録する。

**一覧**: `TaskTreeRow`(メイン画面)・`SubtaskTreeRow`(詳細・編集画面のサブタスク)に、共通の`ChecklistProgressText`でチェックリストがあれば「☑ 完了数/全体数」を表示する。

**繰り返しタスク**: `TaskCompletion.complete`で次回分を生成する際、DBから読み直した最新の`checklist`を`Checklist.resetChecks`で項目を引き継ぎつつチェックをすべて外す。
