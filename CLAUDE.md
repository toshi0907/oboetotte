# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

`oboetotte` はスマートフォンのみで開発するAndroid向けTodoアプリです。Claude Codeがコードを書き、GitHubにpushし、GitHub Actionsがビルドし(ローカルのAndroid Studio/SDKは使わない)、生成されたAPKを端末にインストールして動作確認する、という開発フローを取ります。Kotlin + Jetpack Compose、単一の`app`モジュール構成で、バックエンドは無く、データは端末内のみに保存します(Roomの依存関係は設定済みですが、実際の永続化処理はまだ実装していません)。

## コマンド

```
./gradlew assembleDebug   # デバッグAPKをビルド(CIが実行する内容と同じ)
./gradlew test            # JVMユニットテスト
./gradlew lint            # Androidのlint
```

このリポジトリの多くの開発サンドボックスにはローカルのAndroid SDKが無く、特にこの実行環境からは`dl.google.com`(Android用Mavenリポジトリ)に一切接続できません。そのためGradleプラグインの解決すら失敗し、`./gradlew`をローカルで最後まで実行することはできません。変更内容はコードを丁寧に読んで検証し、実際のビルドはGitHub Actionsに任せてください。ローカルでGradleが最後まで実行できる前提を置かないでください。

## CI/ビルドパイプライン

`.github/workflows/android-build.yml` は、全ブランチへのpushとPR、および手動の`workflow_dispatch`で実行されます。`ubuntu-latest`上で`./gradlew assembleDebug`を実行し、`app/build/outputs/apk/debug/app-debug.apk`を`app-debug`という名前のArtifactとしてアップロードします。このArtifactが、インストール可能なAPKをスマホに持っていく手段です。Actionsの実行結果からダウンロードし、端末に転送して「提供元不明のアプリ」を許可した上でインストールします。

## アーキテクチャに関する補足

- パッケージ名/applicationId: `com.toshi0907.oboetotte`、minSdk 26、target/compileSdk 35。
- Kotlin 2.0.21 + AGP 8.7.3。**Composeを使うには別途`org.jetbrains.kotlin.plugin.compose`というGradleプラグインが必須**です(ルートの`build.gradle.kts`と`app/build.gradle.kts`の両方に適用済み)。Kotlin 2.0以降、従来の`composeOptions.kotlinCompilerExtensionVersion`による指定方法は機能せず、指定しないと「Compose Compiler Gradle plugin is required」というエラーでビルドが失敗します。Kotlinのバージョンを上げる際は、この2箇所のプラグイン指定を同期させてください。
- 端末内での永続化を見据えて、Room + KSPの依存関係を`app/build.gradle.kts`に宣言済みです(`androidx.room:room-runtime`/`room-ktx`、`ksp`によるコンパイラ)。ただし`Entity`/`Dao`/`Database`クラスはまだ存在せず、`MainActivity.kt`は現状プレースホルダのCompose画面(`SetupCompleteScreen`)を表示しているだけです。
- テーマ関連は`app/src/main/java/com/toshi0907/oboetotte/ui/theme/`(`Color.kt`、`Theme.kt`、`Type.kt`)にあり、標準的なCompose Material3テンプレートの構成に従っています。
- アプリアイコンはアダプティブアイコンのXML(`res/mipmap-anydpi-v26`、`res/drawable/ic_launcher_*`)のみで定義しており、ラスター画像のフォールバックはありません。minSdk 26であればアダプティブアイコンに対応しているためです。
