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

## アーキテクチャに関する補足

- パッケージ名/applicationId: `com.toshi0907.oboetotte`、minSdk 26、target/compileSdk 35。
- Kotlin 2.0.21 + AGP 8.7.3。**Composeを使うには別途`org.jetbrains.kotlin.plugin.compose`というGradleプラグインが必須**です(ルートの`build.gradle.kts`と`app/build.gradle.kts`の両方に適用済み)。Kotlin 2.0以降、従来の`composeOptions.kotlinCompilerExtensionVersion`による指定方法は機能せず、指定しないと「Compose Compiler Gradle plugin is required」というエラーでビルドが失敗します。Kotlinのバージョンを上げる際は、この2箇所のプラグイン指定を同期させてください。
- 永続化層は`app/src/main/java/com/toshi0907/oboetotte/data/`に置いています(`Task`/`TaskList`エンティティ、`TaskDao`/`TaskListDao`、`AppDatabase`)。`Task`は`id`/`title`/`isDone`/`dueAt`(epoch millis、任意)/`listId`(`TaskList`への外部キー、任意)/`parentTaskId`(サブタスク用の自己参照、任意)を持ちます。いずれもRoom上の`@ForeignKey`制約は付けていません。サブタスクも`Task`と同じテーブル・同じエンティティで表現しており、`parentTaskId`の有無でトップレベルタスクかサブタスクかを区別します。`TaskDao.getAll`は`ORDER BY isDone ASC, dueAt IS NULL ASC, dueAt ASC, id DESC`で全件(トップレベル+サブタスク)を取得し、未完了→期限が近い順→期限なしの順に並びます。`AppDatabase.getInstance(context)`でシングルトンのRoomデータベース(`oboetotte.db`)を取得する構成です。
- `TaskViewModel`(`AndroidViewModel`)が両DAOをラップし、`allTasks: StateFlow<List<Task>>`(全件、サブタスク含む)、`tasks: StateFlow<List<Task>>`(`allTasks`を`parentTaskId == null`と選択中の`selectedListId`でフィルタした一覧表示用)、`lists: StateFlow<List<TaskList>>`を公開しています。操作系は、タスクの追加(`addTask`、選択中のリストに紐づく)・完了切り替え(`toggleDone`、サブタスクにも共用)・タイトル/期限の更新(`updateTask`)・削除(`deleteTask`)・サブタスク追加(`addSubtask(parent, title)`)、リストの追加/改名/削除(`addList`/`renameList`/`deleteList`。削除時は`TaskDao.clearListId`でそのリストに属するタスクの`listId`をNULLに戻してから削除)。`TaskDao.update`は`@Update`によるRoom標準の全カラム更新です。
- `MainActivity.kt`の`TaskScreen`が一覧UIです(表示は`tasks`、サブタスク集計や編集ダイアログへの受け渡しには`allTasks`を使います)。上部の`FilterChip`行でリストを切り替え、「リストを編集」の`AssistChip`から`ManageListsDialog`(リストの追加・改名・削除)を開きます。タスク行のタップで編集ダイアログ、長押しで削除確認ダイアログを開きます(`combinedClickable`はFoundationのExperimental API)。編集ダイアログ(`EditTaskDialog`)では、タイトル・期限に加えてサブタスクの一覧(チェック可能)と追加フォームを表示します。**`EditTaskDialog`は自分自身を再帰的に呼び出すことで無制限の階層のサブタスクに対応しています**(サブタスク行のタイトル部分をタップすると、そのサブタスクを対象にした`EditTaskDialog`が`nestedTask`状態を介して重ねて開きます)。`allTasks`をそのまま再帰呼び出しに渡し、各階層は`allTasks.filter { it.parentTaskId == task.id }`で自分の直接の子だけを算出します。期限は`DatePickerDialog`+`TimePicker`(いずれも`@ExperimentalMaterial3Api`)を連続で開いて設定・クリアでき、`DatePicker`が返す`selectedDateMillis`はUTC 0時のepoch millisなので、選択した時刻と合成する際は`combineDateAndTime()`(`MainActivity.kt`内のプライベート関数)でローカルタイムゾーンに変換しています。DBのマイグレーション定義はまだ無く、`AppDatabase`は`fallbackToDestructiveMigration()`で対応しているので、エンティティのスキーマを変更する際は`@Database`の`version`を上げてください(開発中のためデータは破棄されます)。
- テーマ関連は`app/src/main/java/com/toshi0907/oboetotte/ui/theme/`(`Color.kt`、`Theme.kt`、`Type.kt`)にあり、標準的なCompose Material3テンプレートの構成に従っています。
- アプリアイコンはアダプティブアイコンのXML(`res/mipmap-anydpi-v26`、`res/drawable/ic_launcher_*`)のみで定義しており、ラスター画像のフォールバックはありません。minSdk 26であればアダプティブアイコンに対応しているためです。
