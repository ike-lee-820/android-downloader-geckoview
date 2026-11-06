# Android 下载器（GeckoView 迁移版）

基于 Kotlin + Jetpack Compose + Material3 的下载器应用。内置浏览器已从系统 WebView 迁移到 GeckoView，下载引擎、前台服务、Room 持久化与 DataStore 设置模块保持不变。

## 一、功能概览

- 下载核心：自研多线程分片引擎，HTTP Range 并发、断点续传、全局并发队列、暂停/恢复/取消/重试、优先级、单任务与全局限速。
- 流媒体：m3u8（多码率选择、AES-128 解密、TS 分片并发合并）、MPD 基础解析、m4s 分片。
- 后台常驻：dataSync 前台服务、开机自启恢复队列、WorkManager 兜底、网络切换自动暂停恢复。
- 系统入口：VIEW / SEND 链接、自定义 ACTION、默认下载器引导。
- 内置浏览器（本版替换为 GeckoView）：多标签、隐身、书签历史、地址栏搜索合一、页内查找、桌面版 UA、资源嗅探。
- 资源嗅探：内置 WebExtension 的 webRequest 为主通道，ContentDelegate.onExternalResponse 与辅助 JS 探测为补充。
- 设置：下载、网络、浏览器、外观、隐私、关于七组，全部由 Preferences DataStore 持久化，Flow 驱动即时生效。

## 二、GeckoView 迁移要点

### 1. 不是 drop-in replacement

壳层完全重写，没有继承或适配 WebViewClient / WebChromeClient：

| 迁移前 | 迁移后 |
| --- | --- |
| `WebView` | `org.mozilla.geckoview.GeckoView` |
| `WebViewClient` | `GeckoSession.NavigationDelegate` + `ProgressDelegate` |
| `WebChromeClient` | `GeckoSession.ContentDelegate` + `ProgressDelegate` |
| `CookieManager` | `GeckoRuntime.getStorageController().clearData(...)` |
| `shouldInterceptRequest` | 内置 WebExtension `webRequest` + native messaging |
| `WebSettings` | `GeckoSessionSettings`（`userAgentOverride` / `userAgentMode` / `allowJavascript`） |
| 每标签一个 WebView | 单个 `GeckoView` + 多个 `GeckoSession`，切换调用 `setSession()` |

### 2. 依赖与构建

- `app/build.gradle.kts` 中声明 `geckoview.channel = release`（Stable）与 `geckoview.version`；默认 `latest.release`，构建时读取 `maven.mozilla.org` 的 `maven-metadata.xml` 解析当前 Stable 版本号。可复现构建时用 `-Pgeckoview.version=<具体版本>` 钉住。
- Mozilla Maven 是 GeckoView 唯一来源：`https://maven.mozilla.org/maven2`，配合 `content { includeGroup("org.mozilla.geckoview") }` 限定，不使用 JCenter 或任何第三方镜像。
- Java 17：`compileOptions` 与 `kotlinOptions.jvmTarget` 均为 17。
- `compileSdk = 37` 用于满足当前 Stable GeckoView（159.x）AAR metadata 的要求；`minSdk = 26`、`targetSdk = 34` 保持不变，AGP 对更高编译版本的提示由 `android.suppressUnsupportedCompileSdk=37` 显式确认。
- 全工程已移除 `android.webkit.*` 引用，包括 `MimeTypeMap`、`CookieManager`、`WebStorage`。MIME 判定改为 `StorageHelper` 内自维护映射表。

### 3. 浏览器壳层结构

- `GeckoSessionManager`：进程级单例持有唯一 `GeckoRuntime`（GeckoView 单进程只允许一个活跃 runtime），`Application.onCreate` 预热，`Activity` 销毁时不 `shutdown()`。通过 `WebExtensionController.ensureBuiltIn("resource://android/assets/sniffer/")` 安装内置扩展并绑定 `MessageDelegate`。
- `TabSessionManager`：`LinkedHashMap<String, GeckoTabSession>` 持有全部打开的 session；`activate()` 调用 `GeckoView.setSession()`；`onNewSession` 接住 `window.open` 并创建新 session；`onCrash` 自动重建 session；`closeTab` 时才 `session.close()`。
- `GeckoBrowserView`：Compose `AndroidView` 封装，全局复用同一个 `GeckoView` 实例，factory 中先摘除旧父容器避免重复 attach。
- `GeckoNavigationDelegate`：`onLoadRequest`（`GeckoResult.deny()` 阻断 / `allow()` 放行）、`onLocationChange`、`onPageStop`、`onLoadError`（返回 data URI 错误页）、`onNewSession`、`onCanGoBack/Forward`。
- `GeckoContentDelegate`：`onTitleChange`、`onFullScreen`、`onContextMenu`、`onExternalResponse`（读取 `WebResponse.uri` 与响应头，主动关闭 `body` 流）、`onCloseRequest`、`onCrash`。
- 隐身标签：`GeckoSessionSettings.Builder().usePrivateMode(true)` + 独立 storage 目录；`GeckoSessionManager.allowSeparateIncognitoRuntime` 默认关闭，因为单进程创建第二个 `GeckoRuntime` 会抛异常。退出全部隐身标签时清理目录与 runtime 存储。

### 4. 嗅探架构

主通道为内置 WebExtension，位于 `app/src/main/assets/sniffer/`：

- `manifest.json`：声明 `webRequest`、`webRequestBlocking`、`nativeMessaging`、`<all_urls>`，background 脚本 `background.js`，content script `probe.js`。
- `background.js`：`onBeforeRequest`（URL、方法、tabId、请求类型、POST 请求体摘要）、`onBeforeSendHeaders`（请求头）、`onHeadersReceived`（Content-Type 二次识别）；把命中媒体资源的事件通过 `browser.runtime.sendNativeMessage` 回传原生层。POST 请求即使 URL 无媒体后缀也会上报到「请求观测」面板。
- `SnifferExtensionBridge`：实现 `WebExtension.MessageDelegate.onMessage` 与 `onConnect`，解析 JSON、判定标签归属，并通过 `Port.postMessage` 下发嗅探开关、图片加载与混合内容策略。
- `SniffAggregator`：接收 `NetworkEvent`，按 URL 后缀、Content-Type 与 `requestType` 识别 m3u8 / ts / mp4 / m4s / mpd / flv / webm / mp3 / m4a；去重聚合并生成下载任务请求。
- HLS 去重：同一主 playlist 分组下的变体 m3u8 与 TS 分片聚合成一条 HLS 任务，TS 先到时按目录暂存计数，m3u8 到达后吸收；画质子目录（如 `720p/`、`video1/`）向上归并一层。结果列表不逐条展示 TS 分片。
- HLS 感知：Gecko 内部经 Necko 加载 HLS，`onBeforeRequest` 能拿到 Necko 请求；`onExternalResponse` 补充接收无法在页面内呈现的媒体响应（含 `WebResponse.headers` 的 Content-Type）。
- 辅助层：`probe.js` 仅在 WebExtension 无法覆盖的场景（Blob 拼接、`MediaSource.appendBuffer`、运行时赋值 `HTMLMediaElement.src`）工作；原生 `requestProbe()` 通过扩展端口下发补充探测指令，不作为主嗅探通道。
- 下载任务：聚合后的 URL 由 `DownloadViewModel.createFromSniff` 交给既有 `DownloadEngine.enqueue`，引擎自动按 URL 识别为 HLS 任务，下载链路未改动。

## 三、编译步骤

```bash
# JDK 17、Android SDK Platform 37 + Build Tools 35.0.0
# 方式一：使用已配置的 Gradle 8.9
gradle :app:assembleDebug --no-daemon
gradle :app:assembleRelease --no-daemon

# 方式二：补齐 gradle-wrapper.jar 后使用包装器
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
```

调试版本需连接 arm64-v8a 或 armeabi-v7a 真机 / 模拟器（GeckoView 不含 x86_64 ABI 产物时以本机 ABI 为准）。`gradle :app:printGeckoViewCoordinate` 可输出本次构建实际解析的 Maven 坐标。

GitHub Actions 工作流位于 `.github/workflows/android-build.yml`：安装 API 37 平台、校验 Mozilla Maven 可达性、输出坐标、分别构建 Debug 与 Release，并上传两类 APK 产物。

## 四、权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 下载与网络策略判定 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 下载前台服务 |
| `POST_NOTIFICATIONS` | Android 13 及以上通知权限 |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复未完成任务队列 |
| `WAKE_LOCK` | 下载期间保持 CPU |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导加入电池优化白名单 |
| `READ/WRITE_EXTERNAL_STORAGE`（maxSdk 28） | 旧版本分区存储兼容 |

自定义下载目录通过 SAF（`ACTION_OPEN_DOCUMENT_TREE`）授权并持久化 URI 权限。

## 五、包体积对比与 ABI 裁剪配置解释

### 迁移前（系统 WebView）

系统 WebView 由系统提供，APK 内不含渲染引擎与 native 库，基线 debug APK 约 6 MB - 10 MB（仅应用代码、Compose、Room、OkHttp、Hilt 等）。

### 迁移后（GeckoView）

GeckoView 自带完整 Gecko 引擎的 `.so` 与资源，完整多架构 AAR 约 150 MB - 200 MB。若不裁剪，单 APK 增量会远超 100 MB 目标。

### 裁剪配置

`app/build.gradle.kts` 中两处共同生效：

1. `defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")`：在打包阶段把 AAR 内 `.so` 限定为两个移动设备主流 ABI。
2. `splits.abi { isEnable = 仅 Release 构建; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = false }`：Release 按 ABI 拆分 APK，不生成 universal 包；Debug 保持单一 APK 便于调试。
3. `release { isMinifyEnabled = true; isShrinkResources = true }`：R8 代码缩减与资源压缩；`proguard-rules.pro` 全量保留 `org.mozilla.geckoview.**`，避免 JNI / 反射调用被裁掉。

### 预期体积

| 构建 | 预期 APK | 相对迁移前增量 |
| --- | --- | --- |
| Debug（未裁剪，含全部保留 ABI） | 约 120 MB - 180 MB | 仅用于开发 |
| Release arm64-v8a | 约 55 MB - 70 MB | 约 50 MB - 65 MB |
| Release armeabi-v7a | 约 50 MB - 65 MB | 约 45 MB - 60 MB |

目标为 arm64-v8a 增量约 50 MB - 70 MB、不超过 100 MB；按 ABI 拆分后单包可满足。实际数值随当前 Stable GeckoView 版本波动，CI 日志中的「输出 APK 体积」步骤会打印真实大小。

> 注意：按 ABI 拆分后需向不同设备分发对应 APK；若上架 Play 商店，建议使用 App Bundle 并由 Play 按 ABI 下发。

## 六、验收项对照

1. API 26 与 API 34 设备上 `GeckoView` 正常渲染；`onPageStart` / `onLocationChange` / `onPageStop` / `onLoadError` 回调覆盖加载开始、完成与错误。
2. 含 m3u8 + TS 的测试页面：`onBeforeRequest` 捕获两类请求，经 `SnifferExtensionBridge` 回传，`SniffAggregator` 聚合为一条 HLS 任务。
3. POST 请求：`onBeforeRequest` 采集方法与 `requestBody` 摘要，`onBeforeSendHeaders` 采集请求头，「请求观测」面板可查看。
4. 多标签切换：session 常驻于 `TabSessionManager`，切换只调用 `setSession()`，页面状态保留。
5. Release 包体积：ABI 裁剪 + R8 后单 ABI 增量控制在目标区间。
6. 下载链路：`DownloadEngine`、Room、前台服务与通知代码零改动，浏览器层仅通过 `engine.enqueue` 创建任务。

## 七、已知限制

- 单进程只允许一个活跃 `GeckoRuntime`，因此「独立隐身 runtime」默认关闭；隐身隔离依赖 `usePrivateMode` 与独立 storage 目录，而非第二个 runtime 实例。
- `ensureBuiltIn` 以 `sniffer@yuanbao.downloader` 为扩展 id；升级扩展 `version` 后 ensureBuiltIn 会重新安装，否则跳过。
- `webRequestBlocking` 在内置扩展中用于图片拦截与混合内容阻断，媒体资源嗅探只观察不阻断，不影响页面加载。
- MPD 为基础支持：不实现动态 ABR 切换与多 Period 时间轴；fMP4 流按 init + m4s 顺序合并为 `.mp4`，TS 流合并为 `.ts`，未内置 FFmpeg 转码。
- `latest.release` 会随 Mozilla 发布更新而解析到新版本；生产环境建议在 CI 中钉住具体版本号以保证可复现，并回归验证 API 变化。
- API 26 设备上 GeckoView 对部分现代 Web 标准的支持与桌面 Firefox 不完全一致；国产 ROM 的自启动与电池策略仍需用户手动授权。
