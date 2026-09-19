# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

`oboetotte` はスマートフォンのみで開発するAndroid向けTodoアプリです。Claude Codeがコードを書き、GitHubにpushし、GitHub Actionsがビルドし(ローカルのAndroid Studio/SDKは使わない)、生成されたAPKを端末にインストールして動作確認する、という開発フローを取ります。Kotlin + Jetpack Compose、単一の`app`モジュール構成で、バックエンドは無く、主データは端末内のRoom(SQLite)にのみ保存します(ローカルバックアップ・クラウド自動バックアップでZIP形式として別途書き出せます。詳細は`docs/architecture.md`)。

## 依頼内容の確認

ユーザーからの依頼内容や仕様について、解釈に迷う点がある場合、または実装方法・挙動に複数の選択肢が考えられる場合は、実装に着手する前に必ずユーザーに確認すること。曖昧な点を推測だけで補って実装を進めない。

## コマンド

```
./gradlew assembleDebug   # デバッグAPKをビルド(CIが実行する内容と同じ)
./gradlew test            # JVMユニットテスト
./gradlew lint            # Androidのlint
```

このリポジトリの多くの開発サンドボックスにはローカルのAndroid SDKが無く、`dl.google.com`に接続できないため`./gradlew`をローカルで最後まで実行することはできません。変更内容はコードを丁寧に読んで検証し、実際のビルドはGitHub Actionsに任せてください。

## CI/ビルドパイプライン

`.github/workflows/android-build.yml`は全ブランチのpush/PR・手動`workflow_dispatch`で`./gradlew assembleDebug`を実行し、APKを`app-debug`Artifactとしてアップロードします。`latest-debug`タグのGitHub Release更新は**mainへのpush時のみ**行われ(`softprops/action-gh-release`)、常にmainの最新デバッグAPKを指す固定URL(`https://github.com/toshi0907/oboetotte/releases/tag/latest-debug`)として、アプリ内アップデート機能から参照されます。**そのためClaude Codeが`SendUserFile`でAPKを配布する運用は行いません。**

**デバッグ署名鍵は固定(`app/debug.keystore`、alias/storepass/keypass: `android`)。** GitHub Actionsのrunnerは毎回まっさらなVMのためデフォルトの挙動だとビルドごとに鍵が変わり、既存インストール済みAPKの上書きインストールが失敗します。コミット済みの鍵は公開されても問題ないデバッグ専用鍵です。

## ブランチ運用

作業用ブランチはPRがマージされたら削除してください。

## 開発フロー(PR作成からマージまで)

1. 実装が完了したら作業ブランチにコミット・pushし、PRを作成する。
2. CIビルド完了を待つ。失敗したらログを確認して修正し、green になるまで push・待機を繰り返す。
3. CIが成功したら`code-review`スキルでセルフレビューする。指摘があれば対応する。
4. PRに`@coderabbitai review`とコメントしてレビューを依頼する(レビュー制限に引っかかったら時間を置いて再依頼。「レビュー不要」指示があればセルフレビューのみ行いこの依頼は省略可)。
5. CodeRabbitの指摘に対応する(修正してpush、または返信)。
6. CIが green であることを再確認してPRをマージする。
7. マージ後、mainのCIビルド成功を確認する。

**APKの配布(`SendUserFile`でのチャット送付)は行わない。** アプリ内アップデート機能により端末側で更新できるため、mainのビルド確認をもって完了とする。

完了後は、ブランチ運用・GitHub Issue対応の後片付けも忘れずに行ってください。

## GitHub Issue対応時の注意

GitHub Issueに対応する形で実装を行った場合は、対応内容のPRがマージされて完了したら、該当のIssueをCloseしてください。

## 機能追加・変更時の注意

機能の追加・変更を行った際は、`README.md`(実装済みの機能一覧、今後の実装予定)の更新を忘れないでください。あわせて、その変更がアーキテクチャや開発フローに関わる場合は、このCLAUDE.mdまたは`docs/architecture.md`の該当箇所も更新してください。

## アーキテクチャ

パッケージ名/applicationId: `com.toshi0907.oboetotte`、minSdk 26、target/compileSdk 35。Kotlin 2.0.21 + AGP 8.7.3で、**Composeを使うには`org.jetbrains.kotlin.plugin.compose`プラグインが必須**(ルート・`app`両方の`build.gradle.kts`に適用済み。Kotlin 2.0以降は従来の`composeOptions.kotlinCompilerExtensionVersion`は機能しない)。**Room等のエンティティのスキーマを変更する際は、`@Database`の`version`を上げるのに加えて必ず新しい`Migration`を追加し`addMigrations()`に登録すること**(怠るとアプリ更新時にユーザーのデータが失われる)。

機能ごとの実装詳細は`docs/architecture/`配下にファイルを分割している。該当する機能に触れる作業の前に、CLAUDE.md全体ではなく対象のファイルだけを参照すること(新しい機能をここに追記する際も、既存ファイルへの追記か新規ファイル追加かを検討し、CLAUDE.md本体は肥大化させない)。

- 永続化層(Room): `docs/architecture/persistence-room.md`
- TaskViewModel: `docs/architecture/task-viewmodel.md`
- 繰り返しタスク: `docs/architecture/repeat-tasks.md`
- 画面構成(MainActivity): `docs/architecture/screen-structure.md`
- タスク一覧画面(TaskScreen): `docs/architecture/task-list-screen.md`
- 設定画面(SettingsScreen): `docs/architecture/settings-screen.md`
- サブタスクのツリー表示: `docs/architecture/subtask-tree.md`
- 期限ピッカー・DBマイグレーション: `docs/architecture/due-picker-db-migration.md`
- リマインダー通知: `docs/architecture/reminder-notifications.md`
- AI連携(Gemini): `docs/architecture/ai-gemini.md`
- 位置情報リマインダー: `docs/architecture/location-reminders.md`
- 位置情報リマインダーの確認方式(連続追跡): `docs/architecture/location-tracking-mode.md`
- 位置情報の更新履歴(デバッグ): `docs/architecture/location-update-log.md`
- 通知履歴(デバッグ): `docs/architecture/notification-log.md`
- 保存済みの場所: `docs/architecture/saved-locations.md`
- タスクのURL・メモ: `docs/architecture/task-url-memo.md`
- タスクへのファイル添付: `docs/architecture/task-attachments.md`
- ローカルバックアップ(エクスポート/インポート): `docs/architecture/local-backup.md`
- クラウド自動バックアップ: `docs/architecture/cloud-backup.md`
- ホーム画面ウィジェット: `docs/architecture/home-widget.md`
- 共有によるタスク追加: `docs/architecture/share-task.md`
- アプリ内アップデート: `docs/architecture/app-update.md`
- テーマ: `docs/architecture/theme.md`
- アプリアイコン: `docs/architecture/app-icon.md`
- 基本情報(パッケージ名/SDKバージョン): `docs/architecture/basic-info.md`
- Kotlin/AGP/Composeコンパイラプラグイン: `docs/architecture/kotlin-agp-compose.md`

## 利用可能なスキル(myskills 由来)

このリポジトリの `.claude/skills/` は実ディレクトリで、[myskills](https://github.com/toshi0907/myskills)
を submodule として取り込んだ `.myskills/claude-skills/` 配下の各スキルへの symlink が
スキルごとに登録されています(`.claude/skills` 自体はsymlinkではありません)。
リポジトリ固有のローカルスキルがある場合は、同じ `.claude/skills/` 内に通常のディレクトリとして
共存できます。
Claude Code on the web のセッション開始時に、未登録のスキルsymlinkが自動で追加されます
(`.claude/hooks/myskills-skills-sync.sh`。既存のsymlinkやローカルスキルは上書きしません)。

実装作業を始める前に、`.claude/skills/` にあるスキルの一覧と各 `SKILL.md` の
description を確認し、該当するものがあれば優先的に使ってください。
