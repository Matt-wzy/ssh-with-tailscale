#!/usr/bin/env bash
# Build the gomobile AAR that embeds Tailscale (userspace / netstack mode).
# The result (app/libs/tailscale.aar) is what the Android app links against.
#
# Requires Go >= 1.26 (gomobile@latest needs it) and the Android NDK.
set -euo pipefail

cd "$(dirname "$0")/go"

# Optional: use a specific Go installation (e.g. one installed under $HOME
# when the system Go is too old). Defaults to whatever `go` is on PATH.
if [ -n "${GO_BIN_DIR:-}" ]; then
  export GOROOT="${GOROOT:-$(dirname "$GO_BIN_DIR")}"
  export PATH="$GO_BIN_DIR:$PATH"
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/25.1.8937393}"

# gomobile cross-compiles the whole Tailscale dependency tree, which writes a
# lot of temp object files. On some boxes /tmp is a tiny tmpfs (e.g. 10 MB),
# which fails with "no space left on device" even when the real disk is fine.
if [ -w "$HOME" ]; then
  mkdir -p "$HOME/tmp"
  export TMPDIR="$HOME/tmp"
  export GOTMPDIR="$HOME/tmp"
fi

# Modules are fetched from proxy.golang.org by default. In mainland China that
# is frequently slow enough to look like a hang; override with a local mirror:
#   GOPROXY=https://goproxy.cn,direct ./generate_aar.sh
export GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}"
export GOFLAGS="${GOFLAGS:--mod=mod}"

echo ">> go: $(go version)"

# gomobile@latest requires the binding module to list golang.org/x/mobile in its
# module graph; `go get -tool` records it (and keeps it across `go mod tidy`).
echo ">> adding golang.org/x/mobile to module graph"
go get -tool golang.org/x/mobile/cmd/gobind

echo ">> installing gomobile"
go install golang.org/x/mobile/cmd/gomobile@latest
export PATH="$(go env GOPATH)/bin:$PATH"

echo ">> gomobile init"
gomobile init || true

# `android` builds every ABI (correct for a release). Narrow it for fast local
# iteration, e.g. GOMOBILE_TARGET=android/arm64 ./generate_aar.sh
GOMOBILE_TARGET="${GOMOBILE_TARGET:-android}"

OUT="../app/libs/tailscale.aar"
mkdir -p ../app/libs
echo ">> gomobile bind -target=$GOMOBILE_TARGET -> $OUT"
gomobile bind -target="$GOMOBILE_TARGET" -androidapi 24 -o "$OUT" ./...

echo ">> wrote $OUT"
