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

## ブランチ運用

作業用ブランチはPRがマージされたら削除してください。マージ済みブランチを残さず、リポジトリをブランチが整理された状態に保ちます。

## アーキテクチャに関する補足

- パッケージ名/applicationId: `com.toshi0907.oboetotte`、minSdk 26、target/compileSdk 35。
- Kotlin 2.0.21 + AGP 8.7.3。**Composeを使うには別途`org.jetbrains.kotlin.plugin.compose`というGradleプラグインが必須**です(ルートの`build.gradle.kts`と`app/build.gradle.kts`の両方に適用済み)。Kotlin 2.0以降、従来の`composeOptions.kotlinCompilerExtensionVersion`による指定方法は機能せず、指定しないと「Compose Compiler Gradle plugin is required」というエラーでビルドが失敗します。Kotlinのバージョンを上げる際は、この2箇所のプラグイン指定を同期させてください。
- 永続化層は`app/src/main/java/com/toshi0907/oboetotte/data/`に置いています(`Task`エンティティ、`TaskDao`、`AppDatabase`)。`AppDatabase.getInstance(context)`でシングルトンのRoomデータベース(`oboetotte.db`)を取得する構成です。`TaskViewModel`(`AndroidViewModel`)が`TaskDao`をラップし、`tasks: StateFlow<List<Task>>`とタスク追加用の`addTask(title)`を公開しています。現時点ではタスクの追加と一覧表示のみで、編集・削除・完了チェックは未実装です。
- `MainActivity.kt`の`TaskScreen`が、タスク入力欄+追加ボタン+一覧(`LazyColumn`)からなる最低限のUIです。DBのマイグレーション定義はまだ無いので、`Task`のスキーマを変更する際は`fallbackToDestructiveMigration`の追加か、実際のマイグレーションの実装が必要になります。
- テーマ関連は`app/src/main/java/com/toshi0907/oboetotte/ui/theme/`(`Color.kt`、`Theme.kt`、`Type.kt`)にあり、標準的なCompose Material3テンプレートの構成に従っています。
- アプリアイコンはアダプティブアイコンのXML(`res/mipmap-anydpi-v26`、`res/drawable/ic_launcher_*`)のみで定義しており、ラスター画像のフォールバックはありません。minSdk 26であればアダプティブアイコンに対応しているためです。
