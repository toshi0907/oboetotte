# oboetotte

スマートフォンのみで開発するAndroid向けTodoアプリです。

- 実装: Kotlin + Jetpack Compose
- データ保存: 端末内(Room / SQLite)を予定
- ビルド: GitHub Actions
- 開発フロー: GitHubでコード管理 → Claude Codeで実装 → GitHub ActionsでAPKビルド → 端末にインストールして動作確認

## ビルド方法(GitHub Actions)

`main` ブランチや各ブランチへのpush、Pull Request作成時に `.github/workflows/android-build.yml` が自動実行され、デバッグ用APKがビルドされます。

1. GitHubリポジトリの「Actions」タブを開く
2. 該当のワークフロー実行(Android Build)を開く
3. 「Artifacts」欄の `app-debug` をダウンロード(zip形式)
4. 展開して出てくる `app-debug.apk` をスマホに転送
5. スマホの設定で「提供元不明のアプリ」のインストールを許可し、APKをタップしてインストール

手動でビルドを実行したい場合は、Actionsタブから「Android Build」ワークフローを選び「Run workflow」で実行できます。

## プロジェクト構成

```
app/
  src/main/java/com/toshi0907/oboetotte/   アプリのKotlinソースコード
  src/main/res/                             リソース(文字列・アイコン等)
.github/workflows/android-build.yml         GitHub ActionsによるAPK自動ビルド
```

## ローカルでのビルド(参考)

Android Studioやローカル環境がある場合は以下でもビルド可能です。

```
./gradlew assembleDebug
```

## 今後の実装予定

- Todoの追加・編集・削除・完了チェックのUI実装
- Roomを用いたローカルDBへの保存
