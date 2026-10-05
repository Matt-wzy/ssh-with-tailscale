<p align="center">
  <img src="docs/icon-512.png" width="120" alt="SSHWithTailscale icon">
</p>

# SSHWithTailscale

这是一个内置
[Tailscale](https://tailscale.com/download) 的 Android SSH 客户端。

在 **不占用系统 VpnService** 的前提下，用 Android SSH 客户端访问 Tailscale 内网设备
（例如 `100.x.x.x` 上的机器）。

为什么做这么一个工具：让你的ssh连接无需每次都单独启动一次官方 Tailscale App。

本项目的做法是把 **Tailscale 直接嵌进 App 进程内**：以 userspace（netstack）模式拉起
Tailscale 节点，并在 `127.0.0.1:1055` 起一个**本地 SOCKS5 代理**。SSH 客户端把连接
指向这个 SOCKS 代理，流量就经进程内 WireGuard 隧道到达 tailnet，而不抢占系统 VPN 槽位。
无需另外启动 Tailscale 客户端。

```
 无需另外启动 Tailscale App —— 节点已内嵌于本 App 进程内
 SSHWithTailscale ──SOCKS 127.0.0.1:1055──> Tailscale netstack ──WireGuard──> 100.x.x.x
```

## 功能

- **SSH over tailnet** —— 经 SOCKS5 走 WireGuard 隧道连到 `100.x.x.x`，支持密码与
  PEM 私钥认证，内置自绘终端（TERM=xterm-256color，可长按选择复制）。
- **端口转发** —— 本地 `-L` 与远程 `-R`，规则持久化，每次 SSH 连接自动下发。
- **反向导出** —— 手机在 tailnet 上监听端口，既可作**中继**把连接转到手机局域网内的
  设备，也可作 **HTTP 代理**让其它 tailnet 设备经手机出网。
- **SFTP 文件管理** —— 浏览、上传、下载（多文件并行，碎文件自动打包）。
- **Tailnet 设备列表** —— 查看在线节点，点选即可填入 SSH 主机地址。
- **主机密钥校验** —— TOFU（首次连接信任）策略记录 SSH 主机指纹，密钥变更时拒绝连接
  并给出恢复步骤。
- **前台保活** —— 节点运行时以 `specialUse` 前台服务常驻，App 切到后台不会冻住
  SSH 会话或已导出的端口。

## 架构

- `go/tailscale/tailscale.go`：gomobile 绑定。用 `tailscale.com/tsnet` 在 userspace 模式
  启动节点，并起一个 SOCKS5 监听（`Start(stateDir, socksAddr, authKey)` / `Stop()` /
  `Status()` / `IsRunning()`）。
- `go/tailscale/publish.go`：反向导出。基于 `tsnet.Node.Listen` 在 tailnet 上开监听，
  连接再由手机自身网络发出。
- `app/`：Kotlin Android App。
  - `TailscaleManager`：加载绑定，start/stop，并暴露 SOCKS 地址。
  - `TailscaleService`：前台服务，保活进程内节点。
  - `SshConnector` / `SshSession`：基于 **JSch**（自带 `ProxySOCKS5`）建立经 SOCKS 的
    SSH 连接，提供交互式 Shell，并对外暴露底层 `Session` 供文件管理复用。
  - `KnownHosts`：TOFU 主机指纹校验。
  - `LocalForwarder`：自行实现的端口转发（不依赖 JSch 的转发能力）。
  - `SftpLink` / `RemoteArchive` / `TarExtractor`：SFTP 浏览、并行上传/下载，
    以及“碎文件在服务器端 `tar` 打包后下载、本地解包”的实现。
  - `FileManagerActivity`：文件管理 UI（见下）。
  - `terminal/TerminalView` / `TerminalBuffer`：自绘终端。

## 构建

### 0. 前置条件

- **Go 1.26+**（`gomobile@latest` 的 `go.mod` 要求 `go >= 1.26`；用更早的版本会在
  `go install` 阶段直接报版本过低）
- Android SDK（设置 `ANDROID_HOME`）
- NDK（设置 `ANDROID_NDK_HOME`，例如 `$ANDROID_HOME/ndk/25.1.8937393`）
- JDK 17+

系统 Go 过旧又不想改动全局环境时，可以把新版本装到用户目录再指过去：

```bash
GO_VER=1.26.8
curl -L -o /tmp/go.tar.gz "https://mirrors.aliyun.com/golang/go${GO_VER}.linux-amd64.tar.gz"
mkdir -p ~/tools && tar -C ~/tools -xzf /tmp/go.tar.gz   # -> ~/tools/go
```

### 1. 生成 Tailscale AAR

```bash
GO_BIN_DIR=~/tools/go/bin ./generate_aar.sh
```

产物：`app/libs/tailscale.aar`。

`generate_aar.sh` 默认构建全部 ABI（`android`）。只想快速迭代单一架构时：

```bash
GOMOBILE_TARGET=android/arm64 ./generate_aar.sh
```

### 2. 打包 APK

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

> 也可直接用 Android Studio 打开本仓库根目录构建。

CI（`.github/workflows/build.yml`）提供同样的一套流程：跑 Go 与 Kotlin 单元测试、构建
AAR、产出 Debug APK。目前**只在手动触发时运行**（Actions 页面的 Run workflow）；
配置里保留了 `push` / `pull_request` 触发器，取消注释即可恢复每次推送自动构建。

### 3. 发布签名（可选）

Debug 构建不需要签名。要产出可对外分发的 Release APK，先在本仓库根目录生成一份
签名材料：

```bash
./generate_keystore.sh
```

脚本会生成 `release.jks` 与 `keystore.properties`。也可以手动创建：复制模板
`keystore.properties.example` 为 `keystore.properties`，再用 `keytool` 生成密钥库
（文件内有完整命令）。随后：

```bash
./gradlew assembleRelease
```

> 未配置签名时 `assembleRelease` 仍会成功，只是产物未签名、无法安装。

### 已知问题与排查

- **模块拉取卡住**：默认的 `proxy.golang.org` 在部分网络下可能慢到像挂死。改用镜像：
  `GOPROXY=https://goproxy.cn,direct ./generate_aar.sh`。
- **`gomobile bind` 报缺 `golang.org/x/mobile`**：新版 gomobile 要求它在模块依赖图里，
  执行 `go get -tool golang.org/x/mobile/cmd/gobind`（脚本已包含）。
- **临时目录空间不足**：gomobile 交叉编译会写大量临时目标文件。`generate_aar.sh` 已把
  `TMPDIR`/`GOTMPDIR` 指向 `$HOME/tmp`；若 Gradle 侧仍报空间不足，可设
  `GRADLE_OPTS="-Djava.io.tmpdir=$HOME/tmp"`。
- **提示 `keystore.properties is present but incomplete`**：`keystore.properties`
  存在但 `.jks` 缺失或密码为空。检查 `storeFile` 指向的文件是否真的存在。

## 使用

1. 取得一个 **authkey**（见下节 [获取 authkey](#获取-authkey)）。
2. App 内粘贴 authkey → 点 **启动**，等待状态变为已连接。
   首次认证成功后，节点密钥会持久化在 App 私有目录，之后**可以留空 authkey** 直接启动。
3. Host 填目标设备的 `100.x.x.x`（也可点 **设备** 从 tailnet 在线节点中选选），
   填写 user / 密码 或 PEM 私钥 → 点 **连接**。
4. 在命令框输入命令执行，输出显示在下方。

工具栏菜单：

| 菜单项 | 作用 |
|---|---|
| 重连 | 断开并重新建立 SSH 连接 |
| 连接设置 | 展开/收起连接参数面板 |
| 文件管理 | 打开 SFTP 浏览器 |
| 设备 | tailnet 在线节点列表 |
| 端口转发 | 管理本地 `-L` / 远程 `-R` 规则 |
| 反向导出 | 在 tailnet 上导出手机端口 |
| 清屏 | 清空终端输出 |
| 关于 | 版本、功能与协议信息 |

## 端口转发

菜单「端口转发」可添加两类规则，保存后持久化，**每次 SSH 连接时自动下发**；已连接时
修改会立刻重下发。

- **本地 `-L`**：手机监听一个端口，连接被送到 SSH 服务端，再由服务端去连目标主机。
  绑定 `0.0.0.0` 会把该端口暴露给同一网络的其它设备，公共 Wi-Fi 下请勿使用。
- **远程 `-R`**：服务端监听一个端口，由手机去连目标（可填 tailnet 地址，经 Tailscale
  打通）。服务端需 `sshd` 配置 `GatewayPorts yes`，否则只会绑在服务器回环地址上。

## 反向导出

菜单「反向导出」让手机在 tailnet 上开监听端口，无 SSH、无 VpnService、无需管理员批准。
规则持久化，Tailscale 连上后自动生效。

- **中继**：所有连入该端口的连接被转发到「目标主机:端口」。目标由手机自身网络发出，
  因此可以填手机 Wi-Fi 网段内的设备（如 `192.168.1.50:80`）。
- **代理**：在 tailnet 上起一个 HTTP 代理。其它 tailnet 设备把代理设为
  `100.x.x.x:端口` 后，流量经手机出去。

## 文件管理

工具栏「文件管理」打开一个 SFTP 浏览器，复用在 SSH 页已登录的连接
（自动填好 host/user/密码，**不再二次输入**；若从其他入口进入且尚未连接，
会提示补填密码）。支持：

- **浏览 / 进入 / 返回 / 主目录**，目录在前，可按需排序。
- **下拉刷新**；也可用工具栏右上角菜单「刷新」。
- **排序**：工具栏菜单「排序」可选 名称 / 大小 / 修改时间，并切换正序 / 反序；
  目录始终排在文件前面，选择会被记住。
- 列表用**图标区分**目录（文件夹图标，主题色）与文件（文档图标）。
- **返回手势**（系统返回 / 侧滑）：逐级返回上一目录；到达进入时的初始目录后，
  再按一次会提示「再按一次返回退出文件管理」，3 秒内再次返回才退出。
- **上传**：从系统文件选择器多选（SAF），多文件**并行**上传到当前目录。
- **下载**：多选后支持**并行**下载；若检测到「碎文件」（≥8 个且平均 < 256 KB），
  会提示**让服务器端先 `tar -czf` 成一个 tar.gz 再下载、本地自动解包**，
  通常快很多；失败时自动回退到逐文件下载。
- **新建目录 / 删除（递归）/ 重命名**。
- **下载目录可配置**：点工具栏「下载目录」用系统文件夹选择器（SAF）挑一个共享目录
  （如 `Download/sshdownload`），选择会被持久化（下次自动复用），**无需申请存储权限**；
  长按该按钮可恢复默认（App 私有外部存储 `Download/sshdownload/<host>/<远端路径>/`）。
- **上传 / 下载可同时进行**：进度面板会聚合所有进行中的任务，显示每个任务的
  文件名进度与**实时速度（MB/s）**，取消按钮会中止全部活动任务。
- 下载文件落在所选目录（默认 App 私有外部存储）的 `<host>/<远端路径>/` 下，无需任何权限；
  长按条目可单独重命名 / 删除 / 下载。

> Shell 通道与 SFTP 通道复用**同一个** SSH 连接做浏览；进行批量上传/下载时，
> 每个 worker 各开一条独立 SSH 会话（默认并行度 4），所以是真正的并行传输。

## 终端视图

终端是自绘的 `TerminalView`（实现 `onCreateInputConnection`，不依赖离屏 EditText）。

软键盘弹出 / 收起、或从后台回到前台都会改变视图高度，从而改变 PTY 的 `rows`。
`TerminalBuffer.setSize` 在改变行数时会**保持光标所在的逻辑行不变**（按绝对行号
重新推导 `cursorY`），否则光标会停留在旧的屏幕行、看起来「跳到别处」。新的行列数
会通过 `ChannelShell.setPtySize` 告知服务器，避免换行宽度不一致导致的串行。

工具栏下方有修饰键行（`CTRL` / `ALT` / `ESC` / `TAB` / 方向键 / `^C` `^D` `^L` `^Z`），
方便在没有物理键盘时使用。

## 密钥与安全

- **SSH 认证**：密码，或在「密钥」中管理 PEM 私钥（支持生成 / 导入 / 删除）。
- **主机密钥（TOFU）**：首次连接某主机时记录其指纹，之后该主机必须出示同一密钥；
  密钥变更会**拒绝连接**并提示如何恢复。若管理员新增了另一种密钥类型（如在 RSA 之外
  加了 ed25519），按 OpenSSH 的惯例记录而非拒绝。
  服务器重装导致指纹变化时，可在「密钥」页清除全部记录后重连。
- **authkey** 等同凭据，**不要提交进仓库**，也不要截图外传。

## 获取 authkey

### 官方 Tailscale

1. 打开 <https://login.tailscale.com/admin/settings/keys>（Keys 页面）。
2. **Generate auth key** → 填描述 → 勾选选项（见下）→ **Generate key**。
3. **立刻复制** `tskey-auth-...`：关掉弹窗后就再也看不到了。

需要 Owner / Admin / IT admin / Network admin 权限才能生成。

推荐勾选：

| 选项 | 建议 | 原因 |
|---|---|---|
| Reusable（可复用） | ✅ 建议开 | 手机重装/清数据后不用重新生成；一次性 key 用完即废 |
| Pre-approved（预授权） | ✅ 建议开 | 若 tailnet 开了设备审批，否则新设备要你去后台手动点批准 |
| Ephemeral（临时） | ⚪ 可选 | 设备离线后自动从 tailnet 移除。手机上想要"用完即走"就开 |
| Tags | ⚪ 可选 | 配合 ACL 限制该设备能访问什么 |

> authkey 有效期 1–90 天；给 key 打 tag 的设备不会被强制重新认证。

### 自建 Headscale

```bash
headscale preauthkeys create --user <你的用户> --reusable --expiration 24h
```

Headscale 的 key **不是** `tskey-auth-` 前缀，而是一串随机字符 —— 本 App 对格式不做校验，
直接原样粘贴即可（控制服务器地址由 `tsnet.Server` 的登录流程决定）。

## 说明 / 取舍

- **SOCKS 模式只承载你显式指向它的流量**（本 App 的 SSH），其余 App 仍走系统默认网络。
- **MagicDNS**：SOCKS 下建议直接用 `100.x.x.x` 原始 IP，域名解析更稳。
- 鉴权走 authkey（无头、无需浏览器），适合手机端；如需交互登录可改用 `srv.Up` 的
  OAuth 流程（需额外 UI）。
- **前台服务**：App 切到后台时以 `specialUse` 类型前台服务保活，因为进程内节点一停，
  SSH 会话与已导出端口就都会断。Android 14+ 对此类型有额外审核要求。

## 开源协议

**GPL-3.0-or-later**（完整条款见 [LICENSE](LICENSE)）。

源码中的第三方依赖各自保留原协议（BSD-3-Clause、Apache-2.0 等），详见
`app/build.gradle.kts` 与 `go/go.mod`，以及 App 内「关于 → 开源组件」。

官网源码在 [`web/`](web)（纯静态，零构建）。其中内联了
[Bulma](https://bulma.io) 1.0（MIT 许可，`web/assets/vendor/bulma/LICENSE`），
升级用 `web/update-bulma.sh`。部署由 GitHub Actions 发布到 GitHub Pages。
