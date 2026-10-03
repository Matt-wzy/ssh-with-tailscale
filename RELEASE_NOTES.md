首次开源发布。

## 内容
- SSH over Tailscale（Android 端进程内嵌入 Tailscale userspace/netstack，不占用系统 VpnService）
- JSch（mwiede 维护分支）经本地 SOCKS5 `127.0.0.1:1055` 走 WireGuard 隧道
- 终端：自绘 VT100/ANSI，长按选中复制
- SFTP 文件管理：多选、并发上传/下载、可配置下载目录、碎文件服务器端打包
- 反向导出（tailnet 中继 / HTTP 代理）
- Tailnet 设备列表
- 端口转发（本地 -L 已实装；远程 -R 暂时关闭，代码保留）

## 安装
下载 `app-release.apk`。可用
```
adb install -r app-release.apk
```
或直接在手机上点开安装（需开启「未知来源」）。

校验：
```
sha256sum -c SHA256SUMS.txt
```

## 签名
Release APK 用项目自带的 `release.jks` 签名。升级时请确认签名一致，否则需要先卸载再安装。
（`release.jks` 与 `keystore.properties` 不入库；用 `generate_keystore.sh` 生成自己的。）

## 注意
- minSdk 24 / Android 7.0+。
- applicationId：`org.dpdns.mattsgateway.sshwithtailscale`。
- 本仓库历史已排扁为 1 个提交；完整开发历史保留在原仓库。
