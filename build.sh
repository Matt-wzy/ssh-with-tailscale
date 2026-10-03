#!/usr/bin/env bash
# 一键构建：先重编 Go 侧的 Tailscale AAR，再编 Android APK。
#
# 用法：
#   ./build.sh            # 默认打 debug 包
#   ./build.sh release    # 打签名 release 包（需要 keystore.properties + release.jks）
#   ./build.sh all        # 两个都打（改图标/改资源后请用这个，避免两边产物不一致）
#
# 优先用环境变量（$ANDROID_HOME / $JAVA_HOME / $GO_BIN_DIR），缺省值适配常见 Linux/macOS
# 路径。在普通开发机上只需要有 Android SDK 和 JDK 17+ 即可。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

# ---- Android / Gradle 环境 ----
# ANDROID_HOME：优先用环境变量，否则常见路径
if [ -z "${ANDROID_HOME:-}" ]; then
  for cand in "$HOME/Android/Sdk" "/opt/android-sdk" "/usr/local/android-sdk"; do
    if [ -d "$cand" ]; then ANDROID_HOME="$cand"; break; fi
  done
fi
: "${ANDROID_HOME:?ANDROID_HOME not set and no Android SDK found under ~/Android/Sdk, /opt/android-sdk, /usr/local/android-sdk}"

# 把 Gradle 的 user.home/tmp 指到 $HOME（普通机器上不影响；沙箱里 $HOME 才能写）
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
if [ -z "${JAVA_HOME:-}" ]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  else
    JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
  fi
fi
export JAVA_HOME
mkdir -p "$HOME/tmp"
export TMPDIR="$HOME/tmp"
export GRADLE_OPTS="${GRADLE_OPTS:--Duser.home=$HOME -Djava.io.tmpdir=$HOME/tmp}"

# ---- Go / gomobile 工具链 ----
export GO_BIN_DIR="${GO_BIN_DIR:-$HOME/tools/go/bin}"
if [ -d "$GO_BIN_DIR" ]; then
  export PATH="$GO_BIN_DIR:$HOME/go/bin:$PATH"
fi
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/25.1.8937393}"
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
export GOFLAGS="${GOFLAGS:--mod=mod}"
export GOTMPDIR="$HOME/tmp"

FLAVOR="${1:-debug}"

echo ">> [1/2] 重编 Tailscale AAR (gomobile bind)"
if ! command -v gomobile >/dev/null 2>&1; then
  echo ">> 安装 gomobile / gobind"
  go install golang.org/x/mobile/cmd/gomobile@latest
  go get -tool golang.org/x/mobile/cmd/gobind
  export PATH="$(go env GOPATH)/bin:$PATH"
fi

cd "$ROOT/go"
gomobile init || true
mkdir -p ../app/libs
gomobile bind -target=android -androidapi 24 -o ../app/libs/tailscale.aar ./...
echo ">> AAR 已生成: app/libs/tailscale.aar"

# ---- Android ----
# 重编 AAR 后第一次 assemble 偶发在 packageDebug 阶段失败（增量打包器状态错乱，
# 原生库刚合并完还没索引好）。重跑一次通常就过，所以这里最多重试 3 次。
echo ">> [2/2] 构建 Android ($FLAVOR)"
cd "$ROOT"
case "$FLAVOR" in
  release) FLAVORS=("release") ;;
  all)     FLAVORS=("debug" "release") ;;
  *)       FLAVORS=("debug") ;;
esac

for flavor in "${FLAVORS[@]}"; do
  case "$flavor" in
    release) TASK=":app:assembleRelease" ;;
    *)       TASK=":app:assembleDebug" ;;
  esac

  ok=0
  attempt=0
  for attempt in 1 2 3; do
    echo ">> gradle 尝试 $attempt/3: $TASK"
    if ./gradlew "$TASK" "${@:2}"; then
      ok=1
      break
    fi
    echo ">> gradle 失败，稍后重试（见上方说明）"
  done

  if [ "$ok" -ne 1 ]; then
    echo ">> 构建失败：gradle 连续 $attempt 次未通过，请查看上方错误。" >&2
    exit 1
  fi
done

echo ">> 完成。产物："
for flavor in "${FLAVORS[@]}"; do
  ls -la "app/build/outputs/apk/$flavor/"*.apk 2>/dev/null || true
done
