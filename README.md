# 极目桌面 · Android WebView 壳

把 GMSSH 桌面客户端（Electron）的网页前端打包成 Android 应用：
原生部分只做 WebView 宿主 + `electronAPI` 兼容垫片，业务逻辑仍是原来的 Vue 前端。

## 结构

```
app/src/main/assets/dist/            桌面端前端（index.html / terminal.html / assets）
app/src/main/assets/gm_shim.js       Electron preload 契约垫片（document-start 注入）
app/src/main/java/com/gmssh/android/
  MainActivity.kt                    WebView 宿主 + JS 桥 + 深链
  AuthActivity.kt                    client-auth 内置授权页
```

## 垫片提供的接口（对齐 dist-electron/preload.js）

- `window.electronAPI.invoke("render-to-main", payload)` → Promise
- `window.electronAPI.on/removeListener("main-to-render", cb)`
- `window.networkStatus.check/subscribe/unsubscribe`
- `window.electronParam.node/chrome/electron/platform/arch/preloadPath/distPath`
- `window.api.ping()`
- 原生 → 页面：`window.__gmDispatchMainToRender(json)`

`client-auth` 流程：页面 `invoke("open-browser" | "client-auth-start")` → 内置 `AuthActivity`
打开授权页 → 命中 `gmssh://auth/callback?...` → 回传页面
`{type:"client-auth-callback", data:"<完整回调URL>"}`。

## 构建

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

CI：`.github/workflows/build-apk.yml`（ubuntu-latest + JDK17，产物上传 artifact 并挂 Release）。
