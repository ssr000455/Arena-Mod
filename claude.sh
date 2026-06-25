#!/bin/bash
ORIG_DIR="$(pwd)"
PKG="$(npm root -g)/@anthropic-ai/claude-code"
BACKUP_DIR="$HOME/clijsbak"

# 取最新备份
LATEST=$(ls -1 "$BACKUP_DIR"/cli.js* 2>/dev/null | tail -n1)
if [ -z "$LATEST" ]; then
    echo "❌ 备份目录没有 cli.js* 文件"
    exit 1
fi

# 替换 cli.js
cp "$LATEST" "$PKG/cli.js"

# 回到原目录
cd "$ORIG_DIR"

# 启动
exec node "$PKG/cli.js" "$@"
