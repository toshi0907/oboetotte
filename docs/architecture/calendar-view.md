# カレンダー表示

**画面**: `TaskScreen`(`MainActivity.kt`)の3段目のチップ行にある「📅 カレンダー」`FilterChip`で、`showCalendar`(`rememberSaveable`)を切り替えます。カレンダー表示中は一覧部分(タスク入力行・`LazyColumn`)の代わりに`TaskCalendar`(`TaskCalendar.kt`)を`Modifier.weight(1f)`で残りの高さいっぱいに表示します。カレンダー本体の高さを確保するため、「リスト」行は「▸ リスト: 〈選択中のリスト名〉」という1行の要約に折りたたみ(タップで`filtersExpanded`を切り替えて展開)、カレンダーには適用しない「フィルタ」行と「完了済みを表示」チップは非表示にします。

**表示対象**: `allTasks`のうち、親タスク(`parentTaskId == null`)・未完了・`dueAt`あり・リストの絞り込み(`Task.matchesListFilter`、`TaskViewModel.kt`。一覧と共通)に合致するものだけです。サブタスク・完了済み(`showCompleted`に関わらず)・表示フィルタ(`TaskDisplayFilter`)は対象外です。

**繰り返しの将来の予定**: `buildCalendarEntries`(`TaskCalendar.kt`)が、繰り返しルールと期限を持つタスクについて、完了時の次回分生成と同じ`RepeatRule.nextDueAt`を現在の期限から順に適用し、今日〜1年後(当日を含む)に入る分を`CalendarEntry(isProjected = true)`として追加します。DBには保存しない表示専用のデータです。期限切れのまま残っている繰り返しタスクの次回以降の分のうち、今日より前のものは表示しません。計算は表示中の月のグリッド範囲(前後月のはみ出し分を含む)に限定し、1タスクあたり`MAX_PROJECTION_STEPS`回で打ち切ります。毎月の繰り返しは前回の期限に1ヶ月足す連鎖計算のため(1/31→2/28→3/28…)、カレンダーも実際の生成と同じくずれた日付で表示されます。

**マスの表示**: 日曜始まりで5〜6週分を`Row`/`Column`の`weight`で均等に割り付けます。各マス(`CalendarDayCell`)は`BoxWithConstraints`で実際の高さを取得し、1件あたりの高さ(`9.sp`をdp換算+余白。端末の文字サイズ設定も反映)から表示できる件数を計算し、入りきらない場合は最後の1行を「+N」にします。マス内の文字は`CellEntryTextStyle`/`CellDateTextStyle`で`lineHeight`も`fontSize`と同じ値にしています(`fontSize`だけだとテーマ既定の大きな`lineHeight`が残り、文字が上下で切れて潰れて見えるため)。色分けは一覧と同じく`Task.isOverdue`(赤系)・`Task.isDueToday`(緑系)で、将来の予定は点線枠(`dashedBorder`)と「↻」付きで表示します。

**日付タップ**: `ModalBottomSheet`でその日のタスクを時刻順に表示します。行をタップすると`TaskScreen`の`viewingTask`に渡され、通常のタスクと同じ`TaskDetailDialog`が開きます(将来の予定の場合は元になっている現在未完了のタスク)。
