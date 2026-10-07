# MewPlayer（CatTV）

MewPlayer 是基于 [FongMi/TV](https://github.com/FongMi/TV) 与 WebHTV 二次定制的影视播放器，主打 **Node.js 猫源（CatPawOpen 等标准猫源 bundle）**，同时保留 jar / QuickJS / Python 爬虫源。播放层集成 **Exo（Media3）/ MPV / IJK 三个内核**，手机端提供可注入原生 JSBridge 的 **「网页」Tab**。

- 应用名：`MewPlayer`；applicationId：`com.mewplayer.tv`；代码 namespace 仍为 `com.fongmi.android.tv`。
- 版本：`5.6.0`（versionCode 560），对应上游 FongMi/TV 560 基线。
- 构建变体：`leanback`（电视）/ `mobile`（手机）× `arm64_v8a` / `armeabi_v7a`。

## 核心特性

### Node.js 猫源

- 内置 nodejs-mobile 运行时：`app/src/main/jniLibs/<abi>/libnode.so` + JNI 桥 `app/src/main/cpp/node_bridge.cpp`，Java 侧在 `app/src/main/java/com/fongmi/android/tv/node/`（`NodeService`、`NodeBundleManager`、`NodeRunner`）。
- Node 运行在独立进程 `:node`；脚本放在 `files/nodejs/index.js`，数据目录 `files/nodejs/data`（通过 `NODE_PATH` 传给 bundle，网盘 Token / Cookie 等由 bundle 自行持久化在这里，更新脚本不丢登录态）。
- 端口：首选 `9988`，被占用时依次尝试 `9989`、`9990`……；实际端口会持久化，App 一律通过 `http://127.0.0.1:<端口>` 访问。网页里写死的 `127.0.0.1:9988` / `localhost:9988` 会被自动替换成实际端口。
- 监听地址：`HOST=0.0.0.0`，同一局域网的手机 / 电脑可以直接打开 `http://<设备IP>:<端口>/website` 管理网盘配置。**该后台无鉴权**，局域网内任何设备都能查看和修改网盘 Cookie / 密码，请只在可信网络中使用。
- 点播配置识别（`NodeBundleManager.isNodeConfig`），只有以下地址按 Node bundle 处理，其余一律按普通 JSON 配置：
  - `https://.../index.js.md5`：标准入口，同目录必须提供 `index.js`、`index.config.js`、`index.config.js.md5`；
  - `https://.../index.js;md5;<hash 或 md5 地址>`：兼容写法，`index.config.js` 可选；
  - 直接以 `.js` 结尾的 bundle 地址：不校验，`index.config.js` 可选。
- `index.js` 与 `index.config.js` 按 MD5 增量下载（没有 MD5 时按"地址变化或本地缺失"判断），下载到临时文件校验后原子替换；文件有更新且 Node 正在运行时自动重启 Node。Node 进程存活但 60 秒内没有提供服务也会被重启。
- 内置 launcher（`-r` 预加载）提供 `catServerFactory` / `catDartServerPort`；bundle 导出 `start(config)` 时读取同目录 `index.config.js` 调用。
- 站点加载（`VodConfig.loadNodeBundle`）：读取 `/config` 的 `video.sites`（或 `data.video.sites`），每个站点映射为 `node:<路由>`，按 `<路由>/init`、`/home`、`/category`、`/detail`、`/search`、`/play` 以 POST JSON 调用；没有标准 `/config` 时退回旧版 `/t4/config` + `/t4/api`。服务连不上时会自动重启 Node 并等待最多 10 秒。

### 网页 Tab（手机端）

- 手机端底部「网页」Tab 可管理多个网页（本地 `http://127.0.0.1:9988/...` 或任意远程 H5），支持文件上传、HTML5 全屏视频和下载。
- 只有标记为「可信」的网页才挂载 `fongmiBridge` 并在 document-start 注入 SDK，且调用方 origin 必须与网页 origin 一致（首次加载的服务端 3xx 跳转会更新可信 origin，JS 跳转和点击链接不会扩大范围）。
- SDK 对象为 `window.fongmi`（简写 `window.fm`），能力包括 `player.*`（播放 URL / 点播 / 控制 / 状态）、`net.request`、`cache.*`、`pan.check` / `pan.play`、`app.search` / `openVod` / `openSetting` / `history`、`ui.*`、`device` / `site` / `config.info` 等。
- 电视端没有网页 Tab；站点配置里的 `homePage` 不会作为首页渲染。旧版"自定义首页"设置只做一次性迁移，迁移为一个可信的网页 Tab 页面。

### 播放

- 三个内核可在播放设置中切换：Exo（定制 Media3 `1.11.0-alpha01-fongmi`）、MPV（`libmpv.so` + 自建 JNI `libplayer.so`）、IJK（`libijk*.so`）。各内核有独立的性能设置。
- Exo 增强：libass ASS 字幕渲染（`third_party/exo-ass-native`）、双字幕、杜比视界（DV5 GPU 渲染 `libexo_dovi_renderer.so`、DV7 → P8.1 / HDR10 回退）、nextlib FFmpeg 软解（含 AV3A、AVS3、APE）、实时弹幕。
- MPV 增强：OpenGL / Vulkan、AImageReader 硬解、HDR / Dolby Vision、TrueHD / 压缩音频直通、curl + HTTP/2、配置编辑、LUT 调色。
- 光盘：DVD / 蓝光 ISO（含远程 HTTP Range ISO）与光盘菜单导航。
- 其他：播放器按钮 / OSD 自定义、编解码能力查看、播放性能面板、歌词（含桌面歌词）与 K 歌评分。

### 其他功能

- 爬虫源：Node bundle、jar（DexClassLoader）、QuickJS、Python（Chaquopy，首次加载 `.py` 站点时才启动解释器）。
- 网盘检测：本地 HTTP `/pan/check` 与 JSBridge `pan.check`，另有网盘网络诊断。
- 站点健康排序：自动记录站点搜索成败，搜索与换源优先使用更可用的站点；站点弹窗保留用户配置顺序。
- 当前播放记录 API：只读接口 `/api/playback/current`（兼容 `/playback/current`）。
- 调试日志：设置页「调试日志」同时给出本机地址和局域网地址（`/debug/logs`）。
- 推送：二维码 / 剪贴板（电视）、链接对话框（手机）、系统分享、`/action?do=push&url=` 等入口统一交给 `push_agent` 站点；没有该站点时使用内置兜底解析（磁力 / 迅雷 / 种子 / YouTube 列表等）。
- DLNA：电视端可作为投屏接收端，两端都可投屏到其他设备；支持 MediaSession（Media3 `MediaLibraryService`）。
- 备份：仅在设置页手动触发，写入 `/sdcard/TV/bak-yyyyMMdd-HHmm.zip`，保留最新 7 份；退出时不再自动备份。

### 已移除

- 直播：直播界面与入口已移除（无 LiveActivity / 直播配置）；数据库中的 `Live` 表、`/tvbus` 地址拼接和 `app/libs/tvbus-release.aar` 仍有残留。
- 设置页的「增强功能」入口、局域网管理页 `/m`、电视端网页首页、WebHome 扩展脚本、站点注入、登录态学习、APP 代理、观影记录同步 / Webhook 上报。

## 系统架构

```
              用户托管的点播配置（OSS / CDN / GitHub）
              https://.../index.js.md5  或  普通 JSON 配置
                                │
                                ▼
┌───────────────────────────────────────────────────────────────┐
│                       MewPlayer 客户端                         │
│                                                               │
│  ┌──────────────────────────┐   ┌───────────────────────────┐ │
│  │ 网页 Tab（仅手机端）     │   │ 播放内核                  │ │
│  │  - 用户添加的网页        │   │  - Exo（Media3 定制版）   │ │
│  │  - 可信网页 window.fongmi│   │  - MPV（libmpv + JNI）    │ │
│  └────────────▲─────────────┘   │  - IJK                    │ │
│               │ 原生调用        └─────────────▲─────────────┘ │
│  ┌────────────┴───────────────────────────────┴─────────────┐ │
│  │ 核心调度                                                 │ │
│  │  - VodConfig / SiteApi：/config → video.sites → node:路由│ │
│  │  - jar / QuickJS / Python 爬虫加载                        │ │
│  │  - 本地 HTTP 服务（NanoHTTPD，端口 9978–9998）           │ │
│  │  - 搜索 / 详情 / 历史 / 收藏 / 站点健康                  │ │
│  └────────────────────────────▲─────────────────────────────┘ │
│                               │ http://127.0.0.1:9988（可递增）│
│  ┌────────────────────────────┴─────────────────────────────┐ │
│  │ :node 进程（nodejs-mobile）                              │ │
│  │  - files/nodejs/index.js + index.config.js + launcher.js │ │
│  │  - 数据目录 files/nodejs/data                            │ │
│  │  - 对外：/config、/spider/*（或旧版 /t4/*）、/website    │ │
│  │  - 回调宿主：本地 HTTP 服务 /msg                         │ │
│  └──────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────┘
```

本地 HTTP 服务（`app/src/main/java/com/fongmi/android/tv/server/`）从 `9978` 起找空闲端口，主要端点：`/device`、`/action`、`/cache`、`/debug/*`、`/pan/check`、`/file`、`/upload`、`/newFolder`、`/delFolder`、`/delFile`、`/m3u8`、`/media`、`/parse`、`/api/playback/current`、`/proxy`、`/webResource`、`/msg`，其余路径回退到 assets。这些端点没有鉴权，局域网可访问。

## 构建

### 环境要求

- JDK 21（`sourceCompatibility` / `targetCompatibility` 均为 Java 21）。
- Android SDK：Platform 37、Build Tools 37.0.0、Platform Tools。当前 `compileSdk=37`、`minSdk=24`、`targetSdk=28`。
- **NDK 29.0.14206865 与 CMake 3.22.1**：App 自带 CMake 构建（`app/src/main/cpp/CMakeLists.txt`），会编译 `libexo_dovi_renderer.so` 和 `libnode_bridge.so`，普通打包也需要。SDK 许可已接受时 AGP 可自动安装。
- **Python 3.10**（`py -3.10` 或 `python3.10` 可用）：`chaquo` 模块（Chaquopy 17.0.0）构建时用 pip 安装 `chaquo/requirements.txt`（lxml、ujson、pyquery、requests、cachetools、pycryptodome、beautifulsoup4），其中原生包只能从 `https://chaquo.com/pypi-13.1` 获取。
- Gradle Wrapper 9.5.1，Android Gradle Plugin 9.2.1。
- 能访问 Maven Central、Google Maven、Gradle Plugin Portal、JitPack 和 chaquo.com。定制 Media3、nextlib 已作为本地 Maven 产物放在 `third_party/maven`，MPV / IJK / Node / ASS 原生库均已预编译提交，普通打包不会现场编译它们。

在仓库根目录创建 `local.properties` 指定 SDK 位置：

```properties
sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```

需要代理时在同一个终端设置（PowerShell 示例）：

```powershell
$env:HTTPS_PROXY = "http://127.0.0.1:7897"
$env:HTTP_PROXY  = "http://127.0.0.1:7897"
```

### 打包命令

Windows 使用 `.\gradlew.bat`，macOS / Linux 使用 `bash gradlew`，任务名相同。

```powershell
# debug
.\gradlew.bat :app:assembleMobileArm64_v8aDebug
.\gradlew.bat :app:assembleLeanbackArm64_v8aDebug
.\gradlew.bat :app:assembleLeanbackArmeabi_v7aDebug

# release
.\gradlew.bat :app:assembleMobileArm64_v8aRelease
.\gradlew.bat :app:assembleMobileArmeabi_v7aRelease
.\gradlew.bat :app:assembleLeanbackArm64_v8aRelease
.\gradlew.bat :app:assembleLeanbackArmeabi_v7aRelease

# 快速 release：关闭 R8 / 资源压缩 / release lint，仅用于临时测试
.\gradlew.bat :app:assembleMobileArm64_v8aRelease -PfastRelease=true
```

- 版本标识：正常构建为 `<versionName>-yyyyMMddHHmm`，快速构建为 `<versionName>-fast-yyyyMMddHHmm`（上海时区）。
- 依赖下载完成后尽量不要带 `clean`：`clean` 会清掉 `chaquo/build` 里的 Python 环境和 wheel，下次需要重新联网下载。
- 可选环境变量：`WEBHTV_RELEASE_TAG` 写入 `BuildConfig.BUILD_TAG` 作为显示版本；`WEBHTV_APK_SUFFIX` 追加到 release APK 文件名（如 `-beta`）。

### APK 输出

debug：

```text
app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk
app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk
...
```

release：Gradle 原始输出在 `app/build/outputs/apk/<flavor>/release/<mode>-<abi>.apk`，并会在任意 `assemble*Release` 完成后复制到 `Release/apk/`：

```text
Release/apk/mobile-arm64_v8a.apk
Release/apk/mobile-armeabi_v7a.apk
Release/apk/leanback-arm64_v8a.apk
Release/apk/leanback-armeabi_v7a.apk
```

复制时会把 `app/build/outputs/apk/**/release/` 下的所有 APK 一起拷过去，`Release/apk/` 里可能留有之前构建的其他变体，请以修改时间为准。快速包与正式包文件名相同，只能通过应用内版本号区分。

安装 / 推送到设备：

```powershell
adb install -r app\build\outputs\apk\mobileArm64_v8a\debug\app-mobile-arm64_v8a-debug.apk
adb push Release\apk\mobile-arm64_v8a.apk /sdcard/Download/
adb pull /sdcard/DCIM/Screenshots .\Screenshots
```

### 签名

未配置签名时 release 包使用 debug key 兜底。正式签名在 `local.properties` 中增加（`keyPassword` 省略时复用 `storePassword`）：

```properties
storeFile=/path/to/keystore.jks
keyAlias=your_alias
storePassword=your_password
keyPassword=your_key_password
```

### 常见构建失败

- `Unsupported class file major version`、`invalid source release: 21`：当前终端没有使用 JDK 21。
- `SDK location not found`：缺少 `local.properties` 或 `sdk.dir` 错误。
- `failed to find target with hash string 'android-37'`：未安装 Android SDK Platform 37。
- NDK / CMake 相关错误：确认已安装 NDK `29.0.14206865` 与 CMake `3.22.1`。
- `:chaquo:installReleasePythonRequirements` 报 `Could not find a version that satisfies the requirement lxml`、`ProxyError` 或 `SSLEOFError`：pip 走了失效的代理或无法访问 chaquo.com。先 `curl -I https://chaquo.com/pypi-13.1/lxml/` 确认网络，再在同一终端设置可用代理，或用 `py -3.10 -m pip config list` 检查并删除失效的 `proxy=`。
- 找不到 Python 3.10：安装 Python 3.10，确保 `py -3.10` 或 `python3.10` 可用。
- `Could not resolve ...`：依赖下载失败，检查网络或代理。
- 运行时 `dlopen failed`（`libmpv.so` / `libplayer.so`）：确认对应 ABI 的整套 MPV `.so` 已打包，`app/src/<abi>/assets/mpv-libs/<abi>/` 目录完整。

## 原生库与第三方依赖

普通打包直接使用仓库内已提交的预编译产物：

| 位置 | 内容 |
| --- | --- |
| `app/src/main/jniLibs/<abi>/` | `libnode.so`（nodejs-mobile）、`libijkffmpeg.so`、`libijksdl.so`、`libijkplayer.so` |
| `app/src/<abi>/assets/mpv-libs/<abi>/` | `libmpv.so`、`libplayer.so`、`libc++_shared.so`、改名后的 FFmpeg（`libmv*` / `libmw*`） |
| `third_party/exo-ass-native/prebuilt/<abi>/` | `libexo_ass.so`（Exo ASS 字幕） |
| `third_party/exo-dv5-native/prebuilt/<abi>/` | libplacebo / shaderc / libdovi 静态库，供 App CMake 链接 `libexo_dovi_renderer.so` |
| `third_party/maven/` | `androidx.media3:*:1.11.0-alpha01-fongmi`（17 个模块）与 `nextlib-media3ext` |
| `app/libs/*.aar` | Hook、Thunder、ForceTech、JianPian、TVBus |

- `nextlib-media3ext` 当前使用 `1.10.0-0.12.1-fongmi-softload-av3a-avs3-ffmpeg901-r5`（FFmpeg 9.0.1-fongmi `177f090e`，含 AV3A / AVS3 / APE 与软解负载控制）；`third_party/maven` 中还保留了 r1–r4 等旧版本。
- `ExoplayerHdrUtils 0.4.0` 提供基于 libdovi 的 HEVC RPU 转换：按"原生 DV7 → P8.1 转换 → HDR10 基底层"选择播放路径。
- `settings.gradle` 仓库顺序：`third_party/maven`、Maven Central、Google Maven、`app/libs`、JitPack；`app/build.gradle` 强制所有 `androidx.media3` 依赖使用 `1.11.0-alpha01-fongmi`。
- MPV FFmpeg 的文件名、`SONAME` 和 `DT_NEEDED` 已从 `libav*` / `libsw*` 改为 `libmv*` / `libmw*`，避免与 nextlib 内置 FFmpeg 冲突；替换 MPV native 时必须按同一 ABI、同一 lock 成套替换，不能跨 ABI 复制。

### 版本锁定文件

| 文件 | 内容 |
| --- | --- |
| `third_party/mpv-native-lock.json` | MPV `cca559b4`（0.41.0-940）、FFmpeg `177f090e`、libplacebo `b694a21b`（7.375.0）、mpv-android `99a60ad2`、curl 8.21.0、nghttp2 1.69.0、MbedTLS 3.6.7 等；NDK r29 / API 24。构建时会读取它生成 `BuildConfig.MPV_NATIVE_SOURCES`，不能删除 |
| `third_party/media-lock.json` | Media3 fork（fish2018/webhtv `media/release-1.11.0-alpha01-fongmi` @ `e3e922d5`）及补丁；nextlib @ `6ff6cf9d`，NDK 28.2.13676358 |
| `third_party/ijk-native-lock.json` | ijkplayer @ `be89479c`、FFmpeg `ff4.0--ijk0.8.8--20210426--001`、OpenSSL 3.2；NDK 28.2.13676358 |
| `third_party/dvd-native-lock.json` | libdvdread 7.0.1、libdvdnav 7.0.0、libbluray；NDK 28.2.13676358 |
| `third_party/exo-ass-lock.json` | libass、freetype 2.14.3、harfbuzz 14.2.1、fribidi、fontconfig、libxml2；NDK r29 |
| `third_party/fongmi-repositories-lock.json` | 上游仓库审计基线（见下表） |

`third_party/patches/` 保存 FFmpeg、IJK、libplacebo、Media3、MPV、nextlib 的补丁；`third_party/mpv-player-jni/` 是 `libplayer.so` 的 JNI 源码；`third_party/mpv-native-overrides/`、`avs3-hpm/`、`nextlib-media3ext-compat/` 是原生重建用的配方与适配源码。

本仓库**不包含**原生库重建脚本（上游 WebHTV 的 `scripts/build_mpv_native.sh`、`build_mpv_player_jni.sh`、`build_ijk_native.sh`、`build_media_deps.sh`、`verify_mpv_native_assets.sh`）。`third_party/mpv-native-build.md` 等文档中对这些脚本和 `docs/` 的引用仅供参考；需要重建 MPV / IJK / Media3 / nextlib 时，请使用上游 WebHTV 仓库的脚本配合本仓库的 lock 和补丁。

## 目录结构

```text
app/          Android 主应用（leanback/mobile × arm64_v8a/armeabi_v7a，内置 nodejs-mobile 与 CMake 原生桥）
catvod/       CatVod 抽象层：Spider 接口、OkHttp 网络、代理、WebDAV/SMB、工具类
quickjs/      QuickJS JavaScript Spider 运行时
chaquo/       Python Spider 运行时（Chaquopy，Python 3.10）
third_party/  本地 Maven（Media3/nextlib）、原生预编译库、补丁、JNI 源码和版本锁定文件
gradle/       Gradle Wrapper 与版本目录 libs.versions.toml
Release/apk/  release 构建自动复制的 APK（已在 .gitignore 中忽略）
```

## 上游基线

| 仓库 | 分支 | Commit |
| --- | --- | --- |
| [TV](https://github.com/FongMi/TV) | `fongmi` | `1a19fee278fa2234da725d61a53bf59b69fe9127`（`560 / 5.6.0`） |
| [FFmpeg](https://github.com/FongMi/FFmpeg) | `release-9.0-fongmi` | `177f090e0503b7e013922ca903bde14b1c375f18` |
| [mpv-android](https://github.com/FongMi/mpv-android) | `fongmi` | `99a60ad2141d5ace94453590903c2c6b9a0a2443` |
| [media](https://github.com/FongMi/media) | `release` | `2bc207851df311340767e913931ca7b28cab1794` |
| [mpv](https://github.com/FongMi/mpv) | `fongmi` | `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` |
| [libplacebo](https://github.com/FongMi/libplacebo) | `fongmi` | `b694a21bf2dc176c1e98b8a13c6421a0de5f3da5` |
| [CatVodSpider](https://github.com/FongMi/CatVodSpider) | `main` | `a511a606a287089dffdd8374db75d95ec5f372b6`（仅审计，不打包） |

实际打包的 Media3 来自 fork `fish2018/webhtv` 分支 `media/release-1.11.0-alpha01-fongmi` @ `e3e922d5c01bc0b564849940fe589daf37360d15`，在 FongMi/media 基础上选择性移植上游修复。机器可读记录见 [`third_party/fongmi-repositories-lock.json`](third_party/fongmi-repositories-lock.json) 与 [`third_party/media-lock.json`](third_party/media-lock.json)。

## 免费声明

本项目是基于开源生态二次开发的技术学习与研究项目，软件本体完全免费，不提供任何付费服务、影视内容、直播源、接口源、资源存储或内容分发能力。

本软件仅供技术学习、研究和个人测试使用，请在下载、安装或试用后 24 小时内自行卸载。继续使用本软件所产生的一切行为及后果，由使用者自行承担。

本软件不内置、不售卖、不传播任何影视资源，不对用户自行添加的接口、站源、插件、脚本、链接、网盘资源或第三方服务内容负责。使用者应遵守所在地法律法规，尊重版权方和内容提供方的合法权益，不得将本软件用于任何侵权、盗版、传播非法内容或其他违法违规用途。

严禁任何个人或组织以本软件名义进行售卖、引流、收费维护、会员服务、广告变现、盒子预装、电视盒子捆绑销售或其他任何形式的获益行为。

## 致谢

- [FongMi/TV](https://github.com/FongMi/TV) 及其 FFmpeg / mpv / mpv-android / media / libplacebo 分支
- [WebHTV](https://github.com/fish2018/webhtv)
- nodejs-mobile、[nextlib](https://github.com/anilbeesetti/nextlib)、[Chaquopy](https://chaquo.com/chaquopy/)

### 友情链接
[![Linux.do](https://img.shields.io/badge/-Linux.do-1c1c1e?style=flat-square&logo=data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiPz4KPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZlcnNpb249IjEuMiIgYmFzZVByb2ZpbGU9InRpbnktcHMiIHdpZHRoPSIxMjgiIGhlaWdodD0iMTI4IiB2aWV3Qm94PSIwIDAgMTIwIDEyMCI+CiAgPGNsaXBQYXRoIGlkPSJhIj4KICAgIDxjaXJjbGUgY3g9IjYwIiBjeT0iNjAiIHI9IjQ3Ii8+CiAgPC9jbGlwUGF0aD4KICA8Y2lyY2xlIGZpbGw9IiNmMGYwZjAiIGN4PSI2MCIgY3k9IjYwIiByPSI1MCIvPgogIDxyZWN0IGZpbGw9IiMxYzFjMWUiIGNsaXAtcGF0aD0idXJsKCNhKSIgeD0iMTAiIHk9IjEwIiB3aWR0aD0iMTAwIiBoZWlnaHQ9IjMwIi8+CiAgPHJlY3QgZmlsbD0iI2YwZjBmMCIgY2xpcC1wYXRoPSJ1cmwoI2EpIiB4PSIxMCIgeT0iNDAiIHdpZHRoPSIxMDAiIGhlaWdodD0iNDAiLz4KICA8cmVjdCBmaWxsPSIjZmZiMDAzIiBjbGlwLXBhdGg9InVybCgjYSkiIHg9IjEwIiB5PSI4MCIgd2lkdGg9IjEwMCIgaGVpZ2h0PSIzMCIvPgo8L3N2Zz4K)](https://linux.do/)
