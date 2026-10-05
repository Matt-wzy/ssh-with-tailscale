<p align="center">
  <img src="docs/icon-512.png" width="120" alt="SSHWithTailscale icon">
</p>

# SSHWithTailscale

> An **Android SSH client with Tailscale built in** — connect to your `100.x.x.x`
> devices over SSH **without installing the Tailscale app and without using a VPN**.

[中文文档](README.zh.md) · [Website](https://ssh-with-tailscale.mattsgateway.dpdns.org/)
· License: [GPL-3.0-or-later](LICENSE)

---

## Why this exists

On Android, reaching a device on your Tailscale network normally means: install the
official Tailscale app, keep it running, and let it **occupy the system VPN
(VpnService) slot**. That conflicts with any other VPN you may be using, and you have
to juggle two apps just to open one SSH session.

**SSHWithTailscale embeds Tailscale directly inside the app process.** It starts a
Tailscale node in **userspace (netstack) mode** and opens a **local SOCKS5 proxy** at
`127.0.0.1:1055`. The built-in SSH client points its connection at that proxy, so
traffic travels through an in-process WireGuard tunnel to your tailnet — **no separate
Tailscale app, no root, and no VpnService permission.**

```
  No separate Tailscale app needed — the node lives inside this app's process
  SSHWithTailscale ──SOCKS 127.0.0.1:1055──> Tailscale netstack ──WireGuard──> 100.x.x.x
```

In one line: **a Tailscale SSH client for Android that ships Tailscale itself**, so
you can `ssh` into `100.x.x.x` from your phone without ever opening the Tailscale app.

---

## Features

- **SSH over tailnet** — connect to `100.x.x.x` through the SOCKS5 → WireGuard tunnel.
  Password and PEM private-key auth. Built-in terminal emulator
  (`TERM=xterm-256color`, long-press to select/copy).
- **Port forwarding** — local `-L` and remote `-R`, persisted and re-applied on every
  SSH connection.
- **Reverse export** — the phone listens on a tailnet port and acts as a **relay** to
  devices on its own LAN, or as an **HTTP proxy** so other tailnet devices exit through
  the phone. No SSH, no VpnService, no admin approval.
- **SFTP file manager** — browse, upload, download (parallel), with small-file
  server-side `tar` packing for speed. Uses SAF, **no storage permission required**.
- **Tailnet device list** — see online nodes and tap one to fill the SSH host field.
- **Host key verification** — TOFU (trust-on-first-use) records the SSH fingerprint;
  a key change is rejected with recovery steps.
- **Foreground keepalive** — while the node runs, a `specialUse` foreground service
  keeps the SSH session and exported ports alive after backgrounding.

---

## How it works (architecture)

- `go/tailscale/tailscale.go` — gomobile binding. Uses `tailscale.com/tsnet` to start a
  node in userspace mode and listen on SOCKS5
  (`Start(stateDir, socksAddr, authKey)` / `Stop()` / `Status()` / `IsRunning()`).
- `go/tailscale/publish.go` — reverse export. Based on `tsnet.Node.Listen` to accept
  connections on the tailnet; the phone's own network carries them onward.
- `app/` — the Kotlin Android app.
  - `TailscaleManager` — loads the binding, start/stop, exposes the SOCKS address.
  - `TailscaleService` — foreground service that keeps the in-process node alive.
  - `SshConnector` / `SshSession` — SSH over the SOCKS proxy via **JSch**
    (`ProxySOCKS5`), interactive shell, and a raw `Session` reused by the file manager.
  - `KnownHosts` — TOFU host-key checking.
  - `LocalForwarder` — port forwarding implemented in-house (not via JSch's forwarding).
  - `SftpLink` / `RemoteArchive` / `TarExtractor` — SFTP browse, parallel
    upload/download, and "tar small files on the server, extract locally" transfers.
  - `terminal/TerminalView` / `TerminalBuffer` — the self-drawn terminal.

---

## Build

### 0. Prerequisites

- **Go 1.26+** (`gomobile@latest` requires `go >= 1.26`)
- Android SDK (`ANDROID_HOME`)
- NDK (`ANDROID_NDK_HOME`, e.g. `$ANDROID_HOME/ndk/25.1.8937393`)
- JDK 17+

If your system Go is older and you don't want to touch the global install:

```bash
GO_VER=1.26.8
curl -L -o /tmp/go.tar.gz "https://mirrors.aliyun.com/golang/go${GO_VER}.linux-amd64.tar.gz"
mkdir -p ~/tools && tar -C ~/tools -xzf /tmp/go.tar.gz   # -> ~/tools/go
```

### 1. Generate the Tailscale AAR

```bash
GO_BIN_DIR=~/tools/go/bin ./generate_aar.sh
```

Output: `app/libs/tailscale.aar`. To iterate on a single ABI:

```bash
GOMOBILE_TARGET=android/arm64 ./generate_aar.sh
```

### 2. Build the APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. (Or just open the repo root in
Android Studio.)

CI (`.github/workflows/build.yml`) runs the same flow — Go + Kotlin unit tests, AAR
build, Debug APK. Currently **manual trigger only** (Run workflow in Actions); the
`push` / `pull_request` triggers are present but commented out.

### 3. Release signing (optional)

Debug builds need no signing. For a shippable Release APK, generate signing material at
the repo root:

```bash
./generate_keystore.sh
```

That creates `release.jks` and `keystore.properties`. Or copy
`keystore.properties.example` to `keystore.properties` and use `keytool` (commands
inside). Then:

```bash
./gradlew assembleRelease
```

> Without signing configured, `assembleRelease` still succeeds but the artifact is
> unsigned and won't install.

### Troubleshooting

- **Module fetch stalls** — the default `proxy.golang.org` can hang on some networks.
  Use a mirror: `GOPROXY=https://goproxy.cn,direct ./generate_aar.sh`.
- **`gomobile bind` complains about missing `golang.org/x/mobile`** — newer gomobile
  wants it in the dependency graph: `go get -tool golang.org/x/mobile/cmd/gobind`
  (the script already does this).
- **Out of temp space** — gomobile cross-compiles many temp objects. `generate_aar.sh`
  already points `TMPDIR`/`GOTMPDIR` at `$HOME/tmp`; if Gradle still runs short, set
  `GRADLE_OPTS="-Djava.io.tmpdir=$HOME/tmp"`.
- **`keystore.properties is present but incomplete`** — the file exists but `.jks` is
  missing or the password is empty. Check the `storeFile` path.

---

## Usage

1. Get an **authkey** (see [Get an authkey](#get-an-authkey)).
2. Paste the authkey in the app → tap **Start**, wait until status shows connected.
   After the first successful auth, the node key persists in the app's private dir, so
   you can leave the authkey empty on later starts.
3. Host = the target device's `100.x.x.x` (or tap **Devices** to pick from online
   tailnet nodes). Fill user / password or PEM key → tap **Connect**.
4. Type commands in the input box; output shows below.

Toolbar menu:

| Item | Action |
|---|---|
| Reconnect | Drop and rebuild the SSH connection |
| Connection settings | Expand/collapse the connection panel |
| File manager | Open the SFTP browser |
| Devices | Online tailnet node list |
| Port forwarding | Manage local `-L` / remote `-R` rules |
| Reverse export | Expose a phone port on the tailnet |
| Clear | Clear terminal output |
| About | Version, features, license |

---

## Port forwarding

The **Port forwarding** menu adds two rule kinds, persisted and **re-applied on every
SSH connection** (changes while connected re-apply immediately).

- **Local `-L`** — the phone listens on a port; the connection is sent to the SSH server,
  which then reaches the target. Binding `0.0.0.0` exposes the port to other devices on
  the same network — **don't do this on public Wi-Fi**.
- **Remote `-R`** — the server listens on a port; the phone reaches the target (a tailnet
  address works, via Tailscale). The server needs `sshd` `GatewayPorts yes`, otherwise it
  binds loopback only.

---

## Reverse export

The **Reverse export** menu lets the phone listen on a tailnet port — no SSH, no
VpnService, no admin approval. Rules persist and auto-apply once Tailscale connects.

- **Relay** — connections to that port are forwarded to `target-host:port`. The target is
  reached from the phone's own network, so you can use a LAN device (e.g.
  `192.168.1.50:80`).
- **Proxy** — an HTTP proxy on the tailnet. Other tailnet devices set their proxy to
  `100.x.x.x:port` and exit through the phone.

---

## File management

The **File manager** toolbar item opens an SFTP browser that reuses the SSH connection
from the terminal (host/user/password auto-filled, no second entry; if opened elsewhere
and not yet connected, it prompts for the password). Supports:

- Browse / enter / back / home, directories first, sortable.
- Pull-to-refresh (or the toolbar refresh menu).
- **Sort** by name / size / mtime, asc/desc; directories always first, choice remembered.
- Icons distinguish directories (folder) from files (document).
- Back gesture: steps up one directory; at the entry dir, a second press within 3s exits.
- **Upload**: multi-select from the system picker (SAF), **parallel** upload to the
  current dir.
- **Download**: multi-select, **parallel**; if "small files" are detected (≥8 files
  averaging <256 KB) it offers to `tar -czf` on the server then extract locally — usually
  much faster — and falls back to per-file on failure.
- New dir / delete (recursive) / rename.
- **Configurable download dir**: pick a shared dir via SAF (e.g. `Download/sshdownload`);
  remembered, **no storage permission needed**; long-press resets to the default App
  private external storage `Download/sshdownload/<host>/<remote-path>/`.
- Upload and download run concurrently; the progress panel aggregates tasks with per-file
  progress and live speed (MB/s); cancel aborts all active tasks.

> The shell and SFTP channels reuse the **same** SSH connection for browsing; during
> batch transfer each worker opens its own SSH session (default parallelism 4), so it's
> genuinely parallel.

---

## Terminal

The terminal is a self-drawn `TerminalView` (implements `onCreateInputConnection`,
no offscreen `EditText`). Keyboard show/hide and foreground returns change the view
height, hence the PTY `rows`. `TerminalBuffer.setSize` keeps the cursor's logical line
stable when row count changes (recomputes `cursorY` by absolute line) so the cursor
doesn't appear to jump; new size is sent to the server via `ChannelShell.setPtySize` to
avoid wrap misalignment.

A modifier-key row (`CTRL` / `ALT` / `ESC` / `TAB` / arrows / `^C ^D ^L ^Z`) sits above
the toolbar for use without a physical keyboard.

---

## Keys & security

- **SSH auth**: password, or manage PEM private keys in **Keys** (generate / import /
  delete).
- **Host key (TOFU)**: the first connection to a host records its fingerprint; that host
  must present the same key afterwards. A key change **rejects the connection** with
  recovery steps. If an admin adds another key type (e.g. ed25519 alongside RSA), it's
  recorded per OpenSSH convention rather than rejected. After a server reinstall, clear
  all records in **Keys** and reconnect.
- **authkey is a credential** — don't commit it, don't screenshot and share it.

---

## Get an authkey

### Official Tailscale

1. Open <https://login.tailscale.com/admin/settings/keys> (Keys page).
2. **Generate auth key** → add a description → pick options (below) → **Generate key**.
3. **Copy `tskey-auth-...` immediately** — it's gone after you close the popup.

Requires Owner / Admin / IT admin / Network admin.

Recommended options:

| Option | Suggest | Why |
|---|---|---|
| Reusable | ✅ on | Survives app reinstall / data wipe; one-shot keys die after use |
| Pre-approved | ✅ on | Skips device approval if your tailnet requires it |
| Ephemeral | ⚪ optional | Node auto-removed when offline — "use and forget" on phones |
| Tags | ⚪ optional | Scope what the device can reach via ACL |

> authkey lasts 1–90 days; tagged devices aren't forced to re-auth.

### Self-hosted Headscale

```bash
headscale preauthkeys create --user <your-user> --reusable --expiration 24h
```

A Headscale key is **not** `tskey-auth-` prefixed — just a random string. The app does
no format check; paste it as-is (the control-server address is decided by `tsnet.Server`'s
login flow).

---

## Notes / tradeoffs

- **SOCKS mode only carries traffic you explicitly point at it** (this app's SSH); other
  apps still use the system default network.
- **MagicDNS**: under SOCKS, prefer the raw `100.x.x.x` IP — resolution is more stable.
- Auth uses authkey (headless, no browser), which suits mobile; for interactive login you
  could switch to `srv.Up`'s OAuth flow (needs extra UI).
- **Foreground service**: while backgrounded, a `specialUse` foreground service keeps the
  in-process node alive, because stopping it would drop SSH sessions and exported ports.
  Android 14+ has extra review requirements for this type.

---

## License

**GPL-3.0-or-later** (full text in [LICENSE](LICENSE)).

Third-party dependencies keep their own licenses (BSD-3-Clause, Apache-2.0, …) — see
`app/build.gradle.kts` and `go/go.mod`, and **About → Open source components** in the app.

The website source is in [`web/`](web) (static, zero-build). It inlines
[Bulma](https://bulma.io) 1.0 (MIT, `web/assets/vendor/bulma/LICENSE`); upgrade via
`web/update-bulma.sh`. Deployed to GitHub Pages by GitHub Actions.
