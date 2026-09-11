# oboetotte

スマートフォンのみで開発するAndroid向けTodoアプリです。

- 実装: Kotlin + Jetpack Compose
- データ保存: 端末内(Room / SQLite)のみ。クラウド同期・共同編集は非対応
- ビルド: GitHub Actions
- 開発フロー: GitHubでコード管理 → Claude Codeで実装 → GitHub ActionsでAPKビルド → 端末にインストールして動作確認

## 実装済みの機能

- タスクの登録・完了チェック・編集・削除
- 期限日時の設定(期限切れは一覧で強調表示)
- 一覧の並び替え(未完了優先・期限が近い順)、完了済みタスクの表示/非表示切り替え
- リスト(プロジェクト)によるタスクの分類
- サブタスク(チェックリスト、多階層ネスト対応。メイン画面・編集ダイアログの両方でインデント付きツリー表示)
- 繰り返しタスク(毎日・毎週・毎週(曜日を複数選択)・毎月。完了すると次回分を自動生成)
- リマインダー通知(期限日時に通知。端末再起動後も再スケジュール。「テスト通知」ボタンで動作確認可能。通知から完了・スヌーズ〈15分後/30分後/1時間後/3時間後/1日後〉が可能)
- ローカルバックアップ(JSON形式でのエクスポート/インポート。設定画面から実行)

## アプリのインストール方法

### 方法1: GitHub Releaseから(推奨)

`latest-debug` というタグに、pushのたびに最新のデバッグAPKが自動で添付されます。

1. [Releaseページ](https://github.com/toshi0907/oboetotte/releases/tag/latest-debug)を開く
2. `app-debug.apk` をダウンロード
3. スマホの設定で「提供元不明のアプリ」のインストールを許可し、APKをタップしてインストール

デバッグ用の署名鍵は固定しているため、2回目以降は上書きインストールできます。

### 方法2: GitHub Actionsのartifactsから

`main` ブランチや各ブランチへのpush、Pull Request作成時に `.github/workflows/android-build.yml` が自動実行され、デバッグ用APKがビルドされます。

1. GitHubリポジトリの「Actions」タブを開く
2. 該当のワークフロー実行(Android Build)を開く
3. 「Artifacts」欄の `app-debug` をダウンロード(zip形式)
4. 展開して出てくる `app-debug.apk` をスマホに転送してインストール

Artifactは90日で失効するため、通常は方法1のReleaseを使うことを推奨します。

手動でビルドを実行したい場合は、Actionsタブから「Android Build」ワークフローを選び「Run workflow」で実行できます。

## プロジェクト構成

```
app/
  src/main/java/com/toshi0907/oboetotte/       アプリのKotlinソースコード
    backup/                                      ローカルバックアップ(JSON export/import)
    data/                                       Room(Task, TaskList, DAO, Database)
    notification/                                リマインダー通知(AlarmManager, BroadcastReceiver)
    ui/theme/                                    Compose Material3テーマ
  src/main/res/                                 リソース(文字列・アイコン等)
.github/workflows/android-build.yml             GitHub ActionsによるAPKビルド・Release更新
```

## ローカルでのビルド(参考)

Android Studioやローカル環境がある場合は以下でもビルド可能です。

```
./gradlew assembleDebug
```

## 今後の実装予定

- カレンダー表示、ホーム画面ウィジェットなど

詳細な機能設計の方針は [Issues](https://github.com/toshi0907/oboetotte/issues) を参照してください。
