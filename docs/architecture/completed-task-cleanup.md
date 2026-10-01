# 完了済みタスクの自動削除

**完了済みタスクの自動削除**: 完了してから設定日数(既定30日、1〜3650日)が経過した完了済みタスクを自動で削除します。実体は`app/src/main/java/com/toshi0907/oboetotte/cleanup/`配下です。

- **完了日時の記録**: `Task.completedAt`(epoch millis、任意)に完了日時を持たせています。`TaskDao.setDone(taskId, isDone, completedAt)`が`isDone`と同時に更新し、`TaskCompletion.complete`は現在時刻を、`TaskViewModel.toggleDone`で未完了に戻す場合は`null`を渡します。繰り返しタスクの次回分は`completedAt = null`で生成します。
- **既存データの扱い**: `MIGRATION_22_23`で`completedAt`列を追加する際、既存の完了済みタスクにはマイグレーション実行時刻(アプリ更新時点)を設定します(更新直後に過去の完了済みタスクがまとめて消えないようにするため)。ローカルバックアップのインポートでも、`completedAt`キーを持たない旧形式の完了済みタスクにはインポート時刻を設定します。
- **削除処理(`CompletedTaskCleanup.run`)**: `TaskDao.getCompletedBefore(cutoff)`でトップレベル・サブタスクを問わず個別に削除対象を求め、それぞれの配下のサブタスク(孫以下も含む、未完了のものも含む)も一緒に削除します。1件ごとの削除は手動削除と共通の`TaskDeletion.delete`(DB削除・アラーム/ジオフェンス解除・繰り返しシリーズで共有中でなければ添付ファイル削除)で行い、表示中の通知も消去したうえで、最後にウィジェットを1回だけ再描画します。同一プロセス内の同時実行は`Mutex`で直列化しています。
- **実行タイミング**: アプリ起動時(`MainActivity`の`LaunchedEffect`)、設定変更時(`TaskViewModel.setCompletedTaskCleanupEnabled`/`setCompletedTaskCleanupRetentionDays`)、および`CompletedTaskCleanupScheduler`が登録する1日1回の定期実行(`CompletedTaskCleanupWorker`、`ExistingPeriodicWorkPolicy.KEEP`)。有効/無効は実行時に毎回`CompletedTaskCleanupSettings`を見て判定するため、無効化しても定期実行自体は止めません。
- **設定**: `CompletedTaskCleanupSettings`(SharedPreferences)に有効/無効(既定有効)・日数(既定30日)を保存し、設定画面の「完了タスク」セクション(「クラウド自動バックアップ」と「デバッグ」の間)から変更します。
