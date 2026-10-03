#!/usr/bin/env bash
# 把 Bulma 最新版复制进仓库（内联，不依赖 CDN）。
#
# 用法：
#   ./update-bulma.sh          # 拉取当前最新版
#   ./update-bulma.sh 1.0.4    # 指定版本
#
# Bulma 采用 MIT 许可，允许复制与再分发，但必须随附版权与许可声明
# （LICENSE 文件会被一起下载到 assets/vendor/bulma/）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
DEST="$ROOT/assets/vendor/bulma"
CDN="https://cdn.jsdelivr.net/npm/bulma"

VER="${1:-}"
if [ -z "$VER" ]; then
  echo ">> 查询 npm registry 上的最新版本…"
  VER="$(curl -fsSL -m 20 https://registry.npmjs.org/bulma/latest \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["version"])')"
fi
[ -n "$VER" ] || { echo ">> 无法确定版本号" >&2; exit 1; }

OLD="$( [ -f "$DEST/VERSION" ] && tr -d '\n' < "$DEST/VERSION" || echo '(none)' )"
echo ">> 当前版本：$OLD  →  目标版本：$VER"

mkdir -p "$DEST"
echo ">> 下载 bulma.min.css"
curl -fsSL -m 60 -o "$DEST/bulma.min.css.tmp" "$CDN@$VER/css/bulma.min.css"
echo ">> 下载 LICENSE"
curl -fsSL -m 60 -o "$DEST/LICENSE.tmp"       "$CDN@$VER/LICENSE"

# 基本校验：文件非空且含关键选择器，避免把 404 页面写进去
if [ ! -s "$DEST/bulma.min.css.tmp" ] || ! grep -q "data-theme=dark" "$DEST/bulma.min.css.tmp"; then
  rm -f "$DEST/bulma.min.css.tmp" "$DEST/LICENSE.tmp"
  echo ">> 校验失败：下载到的 CSS 不完整或不是预期内容" >&2
  exit 1
fi
if [ ! -s "$DEST/LICENSE.tmp" ] || ! grep -qi "MIT License" "$DEST/LICENSE.tmp"; then
  rm -f "$DEST/bulma.min.css.tmp" "$DEST/LICENSE.tmp"
  echo ">> 校验失败：LICENSE 不是预期的 MIT 文本" >&2
  exit 1
fi

mv "$DEST/bulma.min.css.tmp" "$DEST/bulma.min.css"
mv "$DEST/LICENSE.tmp"       "$DEST/LICENSE"
printf '%s\n' "$VER" > "$DEST/VERSION"

echo ">> 完成：assets/vendor/bulma/ 已更新到 $VER"
ls -la "$DEST"
