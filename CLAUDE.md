# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

`oboetotte` はスマートフォンのみで開発するAndroid向けTodoアプリです。Claude Codeがコードを書き、GitHubにpushし、GitHub Actionsがビルドし(ローカルのAndroid Studio/SDKは使わない)、生成されたAPKを端末にインストールして動作確認する、という開発フローを取ります。Kotlin + Jetpack Compose、単一の`app`モジュール構成で、バックエンドは無く、データは端末内のRoom(SQLite)にのみ保存します。

## 依頼内容の確認

ユーザーからの依頼内容や仕様について、解釈に迷う点がある場合、または実装方法・挙動に複数の選択肢が考えられる場合は、実装に着手する前に必ずユーザーに確認すること。曖昧な点を推測だけで補って実装を進めない。

## コマンド

```
./gradlew assembleDebug   # デバッグAPKをビルド(CIが実行する内容と同じ)
./gradlew test            # JVMユニットテスト
./gradlew lint            # Androidのlint
```

このリポジトリの多くの開発サンドボックスにはローカルのAndroid SDKが無く、特にこの実行環境からは`dl.google.com`(Android用Mavenリポジトリ)に一切接続できません。そのためGradleプラグインの解決すら失敗し、`./gradlew`をローカルで最後まで実行することはできません。変更内容はコードを丁寧に読んで検証し、実際のビルドはGitHub Actionsに任せてください。ローカルでGradleが最後まで実行できる前提を置かないでください。

## CI/ビルドパイプライン

`.github/workflows/android-build.yml` は、全ブランチへのpushとPR、および手動の`workflow_dispatch`で実行されます。`ubuntu-latest`上で`./gradlew assembleDebug`を実行し、`app/build/outputs/apk/debug/app-debug.apk`を`app-debug`という名前のArtifactとしてアップロードします。加えて、pushイベントの場合は`latest-debug`タグのGitHub Release(prerelease)を`softprops/action-gh-release`で自動更新し、同じAPKを添付しています。リポジトリはpublicなので、`https://github.com/toshi0907/oboetotte/releases/tag/latest-debug` は認証なしで常に最新のデバッグAPKを指す固定URLとして使えます。Claude Codeのセッションが`mcp__github__get_latest_release`等でこのReleaseのAsset URLを取得し、`SendUserFile`でチャットに直接APKを送ることもできます。Artifactは90日で失効しますが、Releaseは失効しないため、こちらが端末にAPKを持っていく主な手段です。

**デバッグ署名鍵は固定(`app/debug.keystore`)。** Android Gradle Pluginのデフォルトでは`~/.android/debug.keystore`を使いますが、GitHub Actionsのrunnerは毎回まっさらなVMのため、ビルドごとに異なる鍵で自動生成されてしまいます。署名が変わると、既に端末にインストール済みのAPKの上に新しいAPKを重ねてインストールできず「アプリがインストールされていません」というエラーになります。これを避けるため、リポジトリに`app/debug.keystore`(alias: `androiddebugkey`, storepass/keypass: `android`)をコミットし、`app/build.gradle.kts`の`signingConfigs.debug`で明示的に指定しています。中身は公開されても問題ないデバッグ専用鍵なので、コミットして構いません。

## ブランチ運用

作業用ブランチはPRがマージされたら削除してください。マージ済みブランチを残さず、リポジトリをブランチが整理された状態に保ちます。

## 機能追加・変更時の注意

機能の追加・変更を行った際は、`README.md`(実装済みの機能一覧、今後の実装予定)の更新を忘れないでください。あわせて、その変更がアーキテクチャや開発フローに関わる場合は、このCLAUDE.mdの該当箇所も更新してください。

## アーキテクチャに関する補足

- パッケージ名/applicationId: `com.toshi0907.oboetotte`、minSdk 26、target/compileSdk 35。
- Kotlin 2.0.21 + AGP 8.7.3。**Composeを使うには別途`org.jetbrains.kotlin.plugin.compose`というGradleプラグインが必須**です(ルートの`build.gradle.kts`と`app/build.gradle.kts`の両方に適用済み)。Kotlin 2.0以降、従来の`composeOptions.kotlinCompilerExtensionVersion`による指定方法は機能せず、指定しないと「Compose Compiler Gradle plugin is required」というエラーでビルドが失敗します。Kotlinのバージョンを上げる際は、この2箇所のプラグイン指定を同期させてください。
- 永続化層は`app/src/main/java/com/toshi0907/oboetotte/data/`に置いています(`Task`/`TaskList`エンティティ、`TaskDao`/`TaskListDao`、`AppDatabase`)。`Task`は`id`/`title`/`isDone`/`dueAt`(epoch millis、任意)/`listId`(`TaskList`への外部キー、任意)/`parentTaskId`(サブタスク用の自己参照、任意)/`repeatRule`(繰り返しルール、任意)を持ちます。いずれもRoom上の`@ForeignKey`制約は付けていません。サブタスクも`Task`と同じテーブル・同じエンティティで表現しており、`parentTaskId`の有無でトップレベルタスクかサブタスクかを区別します。`TaskDao.getAll`は`ORDER BY isDone ASC, dueAt IS NULL ASC, dueAt ASC, id DESC`で全件(トップレベル+サブタスク)を取得し、未完了→期限が近い順→期限なしの順に並びます。`AppDatabase.getInstance(context)`でシングルトンのRoomデータベース(`oboetotte.db`)を取得する構成です。
- `TaskViewModel`(`AndroidViewModel`)が両DAOをラップし、`allTasks: StateFlow<List<Task>>`(全件、サブタスク含む)、`tasks: StateFlow<List<Task>>`(`allTasks`を`parentTaskId == null`・選択中の`selectedListId`・`showCompleted`〈デフォルト`true`。`false`なら完了済みのトップレベルタスクを除外〉でフィルタした一覧表示用。この完了済みフィルタはトップレベルタスクにのみ適用され、表示中の親の配下のサブタスクは`TaskTreeRow`/`SubtaskTreeRow`側でこれまでどおり全件表示されます)、`lists: StateFlow<List<TaskList>>`を公開しています。操作系は、タスクの追加(`addTask`、選択中のリストに紐づく)・完了切り替え(`toggleDone`、サブタスクにも共用。完了にする側の実処理は`TaskCompletion.complete`に委譲し、未完了に戻す側のみ`taskDao.setDone`+`ReminderScheduler.schedule`をここで直接行う)・タイトル/期限/繰り返しルールの更新(`updateTask`)・削除(`deleteTask`)・サブタスク追加(`addSubtask(parent, title)`)、リストの追加/改名/削除(`addList`/`renameList`/`deleteList`。削除時は`TaskDao.clearListId`でそのリストに属するタスクの`listId`をNULLに戻してから削除)、完了済みタスクの表示切り替え(`setShowCompleted`)。`TaskDao.update`は`@Update`によるRoom標準の全カラム更新です。
- **繰り返しタスク**: `repeatRule`は`RepeatRule`(`TaskViewModel.kt`内の`object`)で定義した`"DAILY"`/`"WEEKLY"`/`"MONTHLY"`のいずれかです。カスタム間隔は未対応です。タスクを完了にする処理は`TaskCompletion.complete(context, task)`(`TaskCompletion.kt`、アプリ内・通知の両方から共通利用)に集約しており、`repeatRule`と`dueAt`が両方設定されていれば`nextDueAt()`(同ファイル内のプライベート関数、`java.time`でローカルタイムゾーンの日時を加算)で次回の期限を計算し、同じタイトル/リスト/親/繰り返しルールを持つ未完了の新規タスクを`taskDao.insert`で生成します。`dueAt`が未設定の繰り返しタスクは、完了しても次回分は生成されません。
- `MainActivity.kt`の`TaskScreen`が一覧UIです(表示は`tasks`、ツリー描画や編集ダイアログへの受け渡しには`allTasks`を使います)。上部は「リスト」ラベル付きの`FilterChip`行(すべて+各リスト)でリストを切り替える行と、その下のもう1つの行(「完了済みを表示」`FilterChip`〈選択状態=`showCompleted`〉、「リストを編集」「テスト通知」「設定」の`AssistChip`)の2段に分かれており、リスト選択とそれ以外の操作が視覚的に区別されています。「リストを編集」の`AssistChip`から`ManageListsDialog`(リストの追加・改名・削除)を開きます。編集ダイアログ(`EditTaskDialog`)では、タイトル・期限・繰り返しルール(`FilterChip`で「なし/毎日/毎週/毎月」を選択)に加えてサブタスクの一覧(チェック可能)と追加フォームを表示します。
- **メイン画面・編集ダイアログ双方でのサブタスクのツリー表示**: メイン画面のタスク一覧(`TaskScreen`内の`LazyColumn`)は、トップレベルタスク(`tasks`)それぞれを起点に`TaskTreeRow`(再帰的なComposable)で描画しており、直接の子だけでなく孫・ひ孫…まで`depth * 20.dp`のインデント付きで一覧に表示されます。各行のタップで`EditTaskDialog`を開き、長押しで削除確認ダイアログを開きます(`combinedClickable`はFoundationのExperimental API)。編集ダイアログ内のサブタスク一覧も同様の考え方の`SubtaskTreeRow`(チェックボックスとタイトルのみの簡易版、行タップで`nestedTask`状態を介して`EditTaskDialog`が重ねて開く)で描画しています。両者とも`allTasks`をそのままツリー描画・再帰呼び出しに渡し、各ノードは`allTasks.filter { it.parentTaskId == node.id }`で自分の子だけを算出するため、サブタスクの階層数に上限はありません。
- 期限は`DatePickerDialog`+`TimePicker`(いずれも`@ExperimentalMaterial3Api`)を連続で開いて設定・クリアでき、`DatePicker`が返す`selectedDateMillis`はUTC 0時のepoch millisなので、選択した時刻と合成する際は`combineDateAndTime()`(`MainActivity.kt`内のプライベート関数)でローカルタイムゾーンに変換しています。`EditTaskDialog`の「設定」ボタンをタップして期限ピッカーを開いた時点でまだ期限(`dueAt`)が未設定の場合、`DatePicker`の初期選択日は`todayAsDatePickerMillis()`(端末のローカルタイムゾーンでの「今日」をUTC 0時基準のepoch millisに変換するプライベート関数)、`TimePicker`の初期時刻は`LocalTime.now()`となり、いずれも現在日時がデフォルト表示されます(既に`dueAt`が設定済みの場合は、その日時をそのまま初期値として表示します)。この挙動はタスク追加時のデフォルト値とは無関係です。DBのマイグレーション定義はまだ無く、`AppDatabase`は`fallbackToDestructiveMigration()`で対応しているので、エンティティのスキーマを変更する際は`@Database`の`version`を上げてください(開発中のためデータは破棄されます)。
- **リマインダー通知**: `app/src/main/java/com/toshi0907/oboetotte/notification/`に`ReminderScheduler`(スケジュール/キャンセル)・`ReminderReceiver`(`BroadcastReceiver`、通知表示)・`BootReceiver`(端末再起動後の再スケジュール)を置いています。`ReminderScheduler.schedule(context, task)`は、`dueAt`が未来かつ`isDone == false`の場合に`AlarmManager.setExactAndAllowWhileIdle`でタスクIDを`PendingIntent`のリクエストコードとしたアラームを登録し、条件を満たさなければ`cancel`を呼びます。API 31(S)以降は`AlarmManager.canScheduleExactAlarms()`で正確なアラームの権限有無を確認し、無ければスケジュールをスキップします(`MainActivity`のバナーから`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`で設定画面へ誘導)。`TaskViewModel`の`updateTask`・`toggleDone`(繰り返しタスクの次回生成分を含む)・`deleteTask`からこの`schedule`/`cancel`を呼び出し、常にDBの状態とアラームの登録状態を同期させています。`ReminderReceiver`は発火時にRoomから最新のタスク情報を`taskDao.getById`で取得し直し(タイトル変更や削除に追従)、未完了であれば通知チャンネル`task_reminders`で通知を表示します。`BootReceiver`は`RECEIVE_BOOT_COMPLETED`を受けて`taskDao.getPendingWithDueDate()`の全件を再スケジュールします。API 33(TIRAMISU)以降は`POST_NOTIFICATIONS`権限が必要なため、`MainActivity.onCreate`で未許可なら都度リクエストしています。メイン画面上部の「テスト通知」`AssistChip`からは`ReminderScheduler.scheduleTestNotification(context)`を呼び出し、タスクに紐づかない実際のアラーム経路(`TEST_DELAY_SECONDS`=5秒後、`ReminderReceiver`が`EXTRA_IS_TEST`フラグを見てDB参照無しで固定文言の通知を表示)を使って通知が届くかどうかを、本番と同じ経路のまま検証できます。**スヌーズ**: タスクの通知(テスト通知は対象外)には`ReminderScheduler.SNOOZE_OPTIONS`(15分後/30分後/1時間後/3時間後/1日後)ぶんの`NotificationCompat.Action`を付与しており、タップすると`notification/SnoozeReceiver`が起動して現在の通知を消し、`ReminderScheduler.scheduleSnooze(context, taskId, minutes)`で「タップ時刻+選択した分数」後に再度アラームを発火させます。**タスクの`dueAt`自体は変更しません**(スヌーズは通知タイミングだけをずらすもので、期限の再設定ではないため)。`scheduleSnooze`はアラーム本体(`schedule`/`cancel`)と同じ`pendingIntentFor`(リクエストコード=`taskId.toInt()`、`ReminderReceiver`宛)を再利用して発火時刻だけを書き換えているので、後から`updateTask`等で`schedule`/`cancel`が呼ばれれば通常どおり上書き・キャンセルされます。各スヌーズボタン自身の`PendingIntent`(`SnoozeReceiver`宛)は`taskId.toInt() * 10 + optionIndex`をリクエストコードとして使い、同一タスクの5つのボタンを区別しています。**完了ボタン**: タスクの通知には「完了」ボタンも付与しており、タップすると`notification/CompleteReceiver`が起動して現在の通知を消し、対象タスクが未完了のまま残っていることを確認した上で`TaskCompletion.complete(context, task)`(アプリ内のチェックボックスから完了にする場合と同じ共通処理。繰り返しタスクなら次回分の生成・スケジュールも行う)を呼びます。`completePendingIntent`は`CompleteReceiver`宛・リクエストコード`taskId.toInt()`で、`SnoozeReceiver`宛のスヌーズボタン群やアラーム本体(`ReminderReceiver`宛)とはコンポーネントが異なるため衝突しません。
- **ローカルバックアップ(エクスポート/インポート)**: `app/src/main/java/com/toshi0907/oboetotte/backup/BackupManager.kt`が実体です。エクスポート形式はJSON(`{"version":1,"exportedAt":<epoch millis>,"lists":[...],"tasks":[...]}`)で、`lists`/`tasks`はそれぞれ`TaskList`/`Task`の全フィールドをそのまま含みます(標準ライブラリの`org.json`のみを使用し、追加のシリアライズ用依存は入れていません)。`BackupManager.export(context, uri)`はRoomの`taskListDao().getAll()`/`taskDao().getAll()`を`Flow.first()`で1回だけ取得しJSONとして`ContentResolver`経由で書き出し、`import(context, uri)`は逆にJSONをパースして`TaskDao`/`TaskListDao`の`deleteAll()`で全件削除した後、元の`id`を保持したまま`insertAll()`で復元します(`id`/`listId`/`parentTaskId`の対応関係を維持するため、インポートは追記ではなく全置き換えです)。UI側はメイン画面の「設定」`AssistChip`から開く`SettingsDialog`(`MainActivity.kt`)が導線で、実際のファイル選択は`MainActivity`に登録した`ActivityResultContracts.CreateDocument("application/json")`(エクスポート)と`ActivityResultContracts.OpenDocument()`(インポート)というStorage Access Frameworkの標準コントラクトで行います。DAOの`getAll()`はRoomの`Flow`なので、インポート後の`deleteAll`/`insertAll`による変更も既存の`StateFlow`購読(`TaskViewModel`)へ自動的に反映され、画面の再起動は不要です。
- テーマ関連は`app/src/main/java/com/toshi0907/oboetotte/ui/theme/`(`Color.kt`、`Theme.kt`、`Type.kt`)にあり、標準的なCompose Material3テンプレートの構成に従っています。
- アプリアイコンはアダプティブアイコンのXML(`res/mipmap-anydpi-v26`、`res/drawable/ic_launcher_*`)のみで定義しており、ラスター画像のフォールバックはありません。minSdk 26であればアダプティブアイコンに対応しているためです。
