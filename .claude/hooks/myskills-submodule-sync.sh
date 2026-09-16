#!/bin/bash
# Claude Code on the web のセッション開始時に、myskills submodule
# (.myskills) を最新のリモート内容へ更新するフック。
#
# 前提となるリポジトリ構成(対象リポジトリ側で事前に一度だけ設定):
#   .myskills/          ... myskills を submodule として追加した場所
#   .claude/skills       ... .myskills/claude-skills への symlink
#
# このフックはsubmoduleの中身を更新するだけで、symlink自体は
# 通常のgit管理ファイルとして対象リポジトリに含まれているため、
# ここで作成し直す必要はない。
#
# 設置方法:
#   1. このファイルを対象リポジトリの
#      .claude/hooks/myskills-submodule-sync.sh にコピー
#   2. chmod +x .claude/hooks/myskills-submodule-sync.sh
#   3. .claude/settings.json に settings.snippet.json の内容をマージ
set -euo pipefail

# ローカル(非remote)環境では何もしない。ローカルは別途、通常の
# submodule運用(明示的な git submodule update)を想定している。
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

if [ ! -f .gitmodules ] || ! grep -q '\.myskills' .gitmodules 2>/dev/null; then
  echo "myskills-submodule-sync: .myskills submodule が見つかりません。スキップします。" >&2
  exit 0
fi

# --remote: myskills側の最新コミットを取得する
# --init:   初回clone直後でsubmoduleが未初期化でも動くようにする
# コンテナは毎セッション使い捨てのため、ここで更新される内容は
# コミットしない(次回セッションでは改めて最新を取得する)。
git submodule update --init --remote -- .myskills

echo "myskills-submodule-sync: synced to $(git -C .myskills rev-parse --short HEAD)" >&2
