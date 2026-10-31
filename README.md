# Taildeck

[English](#english) · [中文](#中文)

## English

An Android app for the machines in your tailnet. It runs its own Tailscale node inside the app (via [SagerNet/tailscale](https://github.com/SagerNet/tailscale) `tsnet`), so it doesn't use the VPN slot and doesn't affect other apps.

- Sign in with Tailscale or a Headscale control server (browser login or auth key)
- Node list with online state, OS, addresses, tags, routes, direct/relay path and ping
- Per-node services: add manually or scan common ports, open them in the built-in WebView
- Built-in SSH terminal (xterm.js): Tailscale SSH, password or an in-app ed25519 key

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
- 节点服务：手动添加或扫描常用端口，在内置 WebView 中打开
- 内置 SSH 终端（xterm.js）：支持 Tailscale SSH、密码、App 内生成的 ed25519 密钥

### 构建

需要 Go 1.26+、`gomobile`、JDK 17+、Android SDK 36、NDK r28。

```sh
go install golang.org/x/mobile/cmd/gomobile@latest
./bridge/build.sh                      # 生成 android/app/libs/tsbridge.aar
cd android && ./gradlew assembleRelease
```

Release 包用 debug 密钥签名，正式分发前请修改 `signingConfig`。
