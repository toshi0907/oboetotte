# CI/ビルドパイプライン(GitHub Actions)

`.github/workflows/android-build.yml`の構成。概要はCLAUDE.mdの「CI/ビルドパイプライン」を参照。

- **権限の分離**: トップレベルは`permissions: contents: read`。全ブランチのpush/PRで動く`build`jobは読み取り専用トークンでGradleの依存解決・ビルドスクリプトを実行し、APKを`app-debug`artifactとしてアップロードする。`latest-debug`リリースの更新は`main`へのpush時のみ動く`release`job(`needs: build`)に分離し、`contents: write`はこのjobにだけ付与する。`release`jobはチェックアウトもビルドもせず、`actions/download-artifact`でAPKを受け取って`softprops/action-gh-release`に渡すだけ。`concurrency: latest-debug-release`で連続マージ時の更新を直列化している。リリース本文の`ビルド番号: ${{ github.run_number }}`は実行単位の値のため、`build`jobで`BuildConfig.BUILD_NUMBER`に埋め込まれた値と一致する(`docs/architecture/app-update.md`)。
- **ActionのSHA固定**: 公式`actions/*`も含め全Actionをコミットの完全SHAで固定し、`# vX.Y.Z`コメントでバージョンを併記する。更新時は対象タグが指す**コミット**のSHAを確認して差し替える(注釈付きタグは`git ls-remote`の`refs/tags/<tag>^{}`側、またはfetchして`git rev-parse <tag>^{commit}`で得たSHA。タグオブジェクト自体のSHAを書かない)。
- **Gradle Wrapperの検証**: `build`jobの先頭で`gradle/actions/wrapper-validation`により`gradle/wrapper/gradle-wrapper.jar`が公式のチェックサムと一致するか検証する。
- `actions/checkout`は`persist-credentials: false`(後続ステップでgitの認証情報を使わないため。コミットSHAは`resolveGitCommitSha()`が`GITHUB_SHA`から取得する)。
