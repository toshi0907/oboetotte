# CI/ビルドパイプライン(GitHub Actions)

`.github/workflows/android-build.yml`の構成。概要はCLAUDE.mdの「CI/ビルドパイプライン」を参照。

- **権限の分離**: トップレベルは`permissions: contents: read`。全ブランチのpush/PRで動く`build`jobは読み取り専用トークンでGradleの依存解決・ビルドスクリプトを実行し、APKを`app-debug`artifactとしてアップロードする。`latest-debug`リリースの更新は`main`へのpush時のみ動く`release`job(`needs: build`)に分離し、`contents: write`はこのjobにだけ付与する。`release`jobはチェックアウトもビルドもせず、`actions/download-artifact`でAPKを受け取って`softprops/action-gh-release`に渡すだけ。連続マージでビルドの完了順が前後した場合や古い実行の再実行で古いAPKが新しいものを上書きしないよう、更新前に`gh release view`で公開済み本文の`ビルド番号`を読み、`github.run_number`以下なら更新をスキップする(`concurrency`グループは待機中のjobを後着のjobで置き換え、新しい方が取り消され得るため使っていない。ほぼ同時に2つの`release`jobが走った場合の競合は残るが、次のmainへのpushで解消される)。リリース本文の`ビルド番号: ${{ github.run_number }}`は実行単位の値のため、`build`jobで`BuildConfig.BUILD_NUMBER`に埋め込まれた値と一致する(`docs/architecture/app-update.md`)。
- **ActionのSHA固定**: 公式`actions/*`も含め全Actionをコミットの完全SHAで固定し、`# vX.Y.Z`コメントでバージョンを併記する。更新時は対象タグが指す**コミット**のSHAを確認して差し替える(注釈付きタグは`git ls-remote`の`refs/tags/<tag>^{}`側、またはfetchして`git rev-parse <tag>^{commit}`で得たSHA。タグオブジェクト自体のSHAを書かない)。
- **Gradle Wrapperの検証**: `build`jobの先頭で`gradle/actions/wrapper-validation`により`gradle/wrapper/gradle-wrapper.jar`が公式のチェックサムと一致するか検証する。
- `actions/checkout`は`persist-credentials: false`(後続ステップでgitの認証情報を使わないため。コミットSHAは`resolveGitCommitSha()`が`GITHUB_SHA`から取得する)。
