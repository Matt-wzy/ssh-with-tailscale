#!/usr/bin/env bash
# 把本地编译好的 release APK 上传到已有的 v1.0.0 GitHub Release。
# 适用于沙箱上传 408 超时的情况：在本地或你常用的开发机上跑。
#
# 用法：
#   ./upload_apk.sh                     # 默认上传 app/build/outputs/apk/release/app-release.apk
#   ./upload_apk.sh path/to/other.apk   # 上传其他 APK
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"

if [ ! -f "$APK" ]; then
  echo "error: $APK not found" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "error: gh CLI not installed; install from https://cli.github.com" >&2
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "error: not logged in to gh. Run: gh auth login" >&2
  exit 1
fi

# --clobber 把已有同名 asset 覆盖；只这一份 APK 的话无所谓
gh release upload v1.0.0 "$APK" --clobber
echo ">> uploaded $APK to https://github.com/Matt-wzy/ssh-with-tailscale/releases/tag/v1.0.0"
