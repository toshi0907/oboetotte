# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

`oboetotte` はスマートフォンのみで開発するAndroid向けTodoアプリです。Claude Codeがコードを書き、GitHubにpushし、GitHub Actionsがビルドし(ローカルのAndroid Studio/SDKは使わない)、生成されたAPKを端末にインストールして動作確認する、という開発フローを取ります。Kotlin + Jetpack Compose、単一の`app`モジュール構成で、バックエンドは無く、データは端末内のRoom(SQLite)にのみ保存します。

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
- `TaskViewModel`(`AndroidViewModel`)が両DAOをラップし、`allTasks: StateFlow<List<Task>>`(全件、サブタスク含む)、`tasks: StateFlow<List<Task>>`(`allTasks`を`parentTaskId == null`と選択中の`selectedListId`でフィルタした一覧表示用)、`lists: StateFlow<List<TaskList>>`を公開しています。操作系は、タスクの追加(`addTask`、選択中のリストに紐づく)・完了切り替え(`toggleDone`、サブタスクにも共用)・タイトル/期限/繰り返しルールの更新(`updateTask`)・削除(`deleteTask`)・サブタスク追加(`addSubtask(parent, title)`)、リストの追加/改名/削除(`addList`/`renameList`/`deleteList`。削除時は`TaskDao.clearListId`でそのリストに属するタスクの`listId`をNULLに戻してから削除)。`TaskDao.update`は`@Update`によるRoom標準の全カラム更新です。
- **繰り返しタスク**: `repeatRule`は`RepeatRule`(`TaskViewModel.kt`内の`object`)で定義した`"DAILY"`/`"WEEKLY"`/`"MONTHLY"`のいずれかです。カスタム間隔は未対応です。`toggleDone`でタスクを完了にする際、`repeatRule`と`dueAt`が両方設定されていれば、`nextDueAt()`(`TaskViewModel.kt`内のプライベート関数、`java.time`でローカルタイムゾーンの日時を加算)で次回の期限を計算し、同じタイトル/リスト/親/繰り返しルールを持つ未完了の新規タスクを`taskDao.insert`で生成します。`dueAt`が未設定の繰り返しタスクは、完了しても次回分は生成されません。
- `MainActivity.kt`の`TaskScreen`が一覧UIです(表示は`tasks`、サブタスク集計や編集ダイアログへの受け渡しには`allTasks`を使います)。上部の`FilterChip`行でリストを切り替え、「リストを編集」の`AssistChip`から`ManageListsDialog`(リストの追加・改名・削除)を開きます。タスク行のタップで編集ダイアログ、長押しで削除確認ダイアログを開きます(`combinedClickable`はFoundationのExperimental API)。編集ダイアログ(`EditTaskDialog`)では、タイトル・期限・繰り返しルール(`FilterChip`で「なし/毎日/毎週/毎月」を選択)に加えてサブタスクの一覧(チェック可能)と追加フォームを表示します。
- **サブタスクのツリー表示**: `EditTaskDialog`内のサブタスク一覧は`SubtaskTreeRow`(再帰的なComposable)で描画しており、直接の子だけでなく孫・ひ孫…まで`depth * 20.dp`のインデント付きで一度に見渡せます。各行のタイトルをタップすると、そのタスクを対象にした`EditTaskDialog`が`nestedTask`状態を介して重ねて開き(`EditTaskDialog`自身も再帰的に自分を呼び出す)、そこでタイトル・期限・繰り返しルールの編集やさらに子の追加ができます。`allTasks`をそのままツリー描画・再帰呼び出しの両方に渡し、各ノードは`allTasks.filter { it.parentTaskId == node.id }`で自分の子だけを算出します。サブタスクの追加階層数に上限はありません。
- 期限は`DatePickerDialog`+`TimePicker`(いずれも`@ExperimentalMaterial3Api`)を連続で開いて設定・クリアでき、`DatePicker`が返す`selectedDateMillis`はUTC 0時のepoch millisなので、選択した時刻と合成する際は`combineDateAndTime()`(`MainActivity.kt`内のプライベート関数)でローカルタイムゾーンに変換しています。DBのマイグレーション定義はまだ無く、`AppDatabase`は`fallbackToDestructiveMigration()`で対応しているので、エンティティのスキーマを変更する際は`@Database`の`version`を上げてください(開発中のためデータは破棄されます)。
- テーマ関連は`app/src/main/java/com/toshi0907/oboetotte/ui/theme/`(`Color.kt`、`Theme.kt`、`Type.kt`)にあり、標準的なCompose Material3テンプレートの構成に従っています。
- アプリアイコンはアダプティブアイコンのXML(`res/mipmap-anydpi-v26`、`res/drawable/ic_launcher_*`)のみで定義しており、ラスター画像のフォールバックはありません。minSdk 26であればアダプティブアイコンに対応しているためです。
