# Taildeck

[English](#english) · [中文](#中文)

## English

An Android app for the machines in your tailnet. It runs its own Tailscale node inside the app (via [SagerNet/tailscale](https://github.com/SagerNet/tailscale) `tsnet`), so it doesn't use the VPN slot and doesn't affect other apps.

- Sign in with Tailscale or a Headscale control server (browser login or auth key)
- Node list with online state, OS, addresses, tags, routes, direct/relay path and ping
- Per-node services: add manually or scan common ports, open them in your default browser (cookies and saved passwords are shared with it), or in the app
- Built-in SSH terminal (xterm.js): Tailscale SSH, password, the built-in ed25519 key, or imported keys (passphrase-protected keys and OpenSSH certificates supported)

Browsers reach the tailnet through a loopback gateway: `http://<node>-<port>.localhost:18680/` (append `s` to the port for HTTPS services, e.g. `nas-5001s`). Each service gets its own origin. The gateway skips TLS verification for upstream services and is reachable by any app on the device.

### Build

Requirements: Go 1.26+, `gomobile`, JDK 17+, Android SDK 36, NDK r28.

```sh
go install golang.org/x/mobile/cmd/gomobile@latest
./bridge/build.sh                      # -> android/app/libs/tsbridge.aar
cd android && ./gradlew assembleRelease
```

The release build is signed with the debug key. Change `signingConfig` before distributing it.

## 中文

管理 Tailnet 内机器的 Android App。App 内通过 [SagerNet/tailscale](https://github.com/SagerNet/tailscale) 的 `tsnet` 运行独立的 Tailscale 节点，不占用系统 VPN，也不影响其他 App。

- 支持 Tailscale 官方或 Headscale 控制服务器，可浏览器登录或使用 Auth Key
- 节点列表：在线状态、系统、地址、标签、子网路由、直连或中继、Ping
- 节点服务：手动添加或扫描常用端口，用系统默认浏览器打开（Cookie 和保存的密码与浏览器共用），也可在 App 内打开
- 内置 SSH 终端（xterm.js）：支持 Tailscale SSH、密码、内置 ed25519 密钥，以及导入的私钥（支持密码短语和 OpenSSH 证书）

浏览器通过本机网关访问 Tailnet：`http://<节点>-<端口>.localhost:18680/`（HTTPS 服务在端口后加 `s`，如 `nas-5001s`）。每个服务是独立的源。网关不校验上游服务的 TLS 证书，且本机任何 App 都能访问它。

### 构建

需要 Go 1.26+、`gomobile`、JDK 17+、Android SDK 36、NDK r28。

```sh
go install golang.org/x/mobile/cmd/gomobile@latest
./bridge/build.sh                      # 生成 android/app/libs/tsbridge.aar
cd android && ./gradlew assembleRelease
```

Release 包用 debug 密钥签名，正式分发前请修改 `signingConfig`。
