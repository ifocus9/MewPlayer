# WebHTV 定制化：专用于 Node.js 猫源的极简 TVBox 实施方案

## 1. 项目背景与定制目标

用户拥有已开发完成的 Node.js 猫源库（路径为 `D:\project\CatPawOpen`，基于 Fastify 框架打包输出单文件 `dist/index.js` 及对应 MD5 校验文件）。

本定制项目的核心目标：
**基于当前 WebHTV 代码库，定制开发一个专用于运行和播放 Node.js 猫源的极简 TVBox 应用，彻底去除传统 TVBox 冗余功能与包袱，保留核心底层体验。**

### 核心设计原则
1. **保留能力**：
   - **WebHome 网页自定义首页**：保留全屏沉浸式 WebView 容器及 `window.webhome` 原生 JSBridge；
   - **Native SDK 播放内核**：保留 Media3 (ExoPlayer) / MPV 双引擎、硬解切片代理、画质选择、多音轨与字幕能力；
   - **原生搜索与详情**：保留全局并发搜索、影片详情解析、历史记录与网盘换源播放；
2. **剔除与精简能力**：
   - **彻底剔除直播功能（Live）**：移除所有直播相关的 UI 导航、页面、配置、解析（TVBus）与后台调度；
   - **彻底告别传统 JSON 配置**：不再下载和解析复杂的 TVBox JSON 语法树（`sites`、`parses`、`lives`、`rules`、`wallpaper` 等），零 JSON 依赖；
   - **剔除无用运行时**：移除 Python (`chaquo`) 运行时，大幅减少 APK 体积与构建负担；
3. **新增与重构能力**：
   - **【点播配置】纯粹化**：直接支持输入 Node.js bundle 地址（格式如 `https://domain.com/.../index.js;md5;[hash]`），自动比对 MD5、增量下载、原子替换；
   - **【首页配置】独立化**：在设置中开辟独立的 WebHome 首页入口（默认 `http://127.0.0.1:9988/web.html`，也可配置远程 H5）；
   - **集成 `FongMi/nodejs-mobile`**：在 Android 内部拉起嵌入式 Node 实例，监听本地 `127.0.0.1:9988`，将 CatPawOpen 的 `/t4/config` 作为内部唯一数据源。

---

## 2. 总体系统架构设计

```
                    【用户远程托管】(OSS / CDN / GitHub)
                              │
               ┌──────────────┴──────────────┐
               │                             │
    [点播配置: JS + MD5]              [首页配置: H5 URL (可选)]
    https://.../index.js;md5;...       http://.../webhome.html
               │                             │
               ▼                             ▼
┌─────────────────────────────────────────────────────────────┐
│                     WebHTV 客户端应用                        │
│                                                             │
│  ┌─────────────────────────┐  ┌──────────────────────────┐  │
│  │ 首页配置 (WebHome 容器) │  │ 原生播放引擎 (OSD 层)    │  │
│  │  - 加载设置的网页 URL   │  │  - Media3 (ExoPlayer)    │  │
│  │  - window.webhome 桥接  │  │  - MPV 播放内核          │  │
│  │  - 遥控器平滑焦点导航   │  │  - M3U8 代理 / 切片直通  │  │
│  └───────────▲─────────────┘  └────────────▲─────────────┘  │
│              │ 原生调用                    │ 播放直通        │
│  ┌───────────┴─────────────────────────────┴─────────────┐  │
│  │               WebHTV 核心控制与数据调度               │  │
│  │  - NodeBundleManager: 监听点播配置，下载/校验 index.js│  │
│  │  - NodeService: 管理 FongMi/nodejs-mobile 本地常驻服务│  │
│  │  - T4 适配器: 自动从 127.0.0.1:9988/t4 挂载数据源    │  │
│  │  - 搜索 / 详情 / 历史进度 / 收藏持久化                │  │
│  └───────────────────────────▲───────────────────────────┘  │
│                              │ 127.0.0.1:9988 (IPC)         │
│  ┌───────────────────────────┴───────────────────────────┐  │
│  │         嵌入式 Node.js 运行时 (FongMi/nodejs-mobile)  │  │
│  │  - libnode.so + JNI 桥接                              │  │
│  │  - 运行: context.getFilesDir()/nodejs/index.js        │  │
│  │  - 数据持久化: filesDir/nodejs/data/db.json (网盘Token)│  │
│  │  - 对外暴露: /t4/* (API), /web.html (后台), /proxy    │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 分阶段详细实施方案

---

### 阶段一：彻底剔除“直播（Live）”全功能与相关依赖

#### 目标
清除所有与直播相关的代码、页面、配置及后台调度，使工程成为纯粹的点播 + WebHome 架构。

#### 涉及文件与改动
1. **页面与导航移除**：
   - `app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`：
     - 移除 Leanback 顶栏中【直播】的 Tab 项及对应点击事件（`R.string.home_live`、`LiveActivity.start()`）；
     - 移除遥控器菜单事件中跳转直播的快捷逻辑；
   - `app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`：
     - 移除底部导航栏中的【直播】Tab 及桌面快捷方式（`R.string.nav_live`）；
   - **删除直播专属页面**：
     - 删除 `app/src/leanback/java/.../ui/activity/LiveActivity.java`
     - 删除 `app/src/mobile/java/.../ui/activity/LiveActivity.java`
     - 删除所有直播相关的 Fragment/Adapter/Dialog（如 `LiveControlDialog`, `LiveEpgDialog`, `EpgDataAdapter`, `ChannelAdapter`, `GroupAdapter` 等）；
2. **配置层清理**：
   - 删除 `app/src/main/java/com/fongmi/android/tv/api/config/LiveConfig.java`；
   - 修改 `app/src/main/java/com/fongmi/android/tv/api/config/VodConfig.java`：
     - 移除 `initLive(config, object)`，不再解析远程配置中的 `lives`；
     - 移除对 `LiveConfig` 的同步操作；
   - 修改 `Setting` 界面：移除【直播配置】输入入口；
3. **播放与解析清理**：
   - 移除 `app/src/main/java/com/fongmi/android/tv/player/extractor/TVBus.java`（第三方直播提取库）；
   - 移除 `BaseLoader` 中对 `Live` 对象的蜘蛛查找逻辑；
   - 清理 `Nano.java` / `Manage.java` 中有关 `tvbus` 和直播配置推送的管理接口。

---

### 阶段二：精简构建体系，集成 `FongMi/nodejs-mobile`

#### 目标
移除无用运行库（Chaquopy），引入 FongMi 官方适配好的 `nodejs-mobile` 原生依赖，跑通最小启动验证。

#### 涉及文件与改动
1. **移除 Python 运行时**：
   - 从 `settings.gradle` 中移除 `:chaquo` 模块；
   - 从 `build.gradle` 中移除 Chaquopy 插件声明；
   - 移除 `BaseLoader` 中对 `.py` 爬虫的派发逻辑（`PyLoader`）；
2. **引入 `FongMi/nodejs-mobile`**：
   - 将 `FongMi/nodejs-mobile` 编译好的 Android 产物（或作为 submodule / AAR）引入工程：
     - 引入 `libnode.so`（支持 `arm64-v8a` 和 `armeabi-v7a`，可选 `x86_64`）；
     - 引入 NodeRunner JNI Java 桥接类；
3. **构建验证**：
   - 确保 `gradlew assembleRelease` 能够顺利编译，且由于剔除了 Python 与直播，APK 基础体积与编译速度得到显著优化。

---

### 阶段三：改造【点播配置】为 Node.js Bundle 远程管理器

#### 目标
不再支持和解析传统复杂的 JSON 格式，点播配置直接接收 Node.js 脚本地址，负责动态下载、MD5 校验与生命周期启动。

#### 核心模块设计
1. **新建 `NodeBundleManager`（沙箱文件与下载管理器）**：
   - **工作目录**：`context.getFilesDir() + "/nodejs/"`
     - 脚本路径：`.../nodejs/index.js`
     - 数据目录：`.../nodejs/data/`（用于存放 `db.json`，存储网盘 Token、Cookie 等，更新脚本时不被删除）
   - **解析配置规则**：
     - 支持格式：`https://domain.com/catpaw/index.js;md5;32位哈希`，或者普通 `https://domain.com/catpaw/index.js`；
     - 若包含 `;md5;`，先比对本地 `index.js` 的 MD5，一致则无需重复下载；
     - 若不一致或无 MD5，下载到 `index.js.download`，下载完整后安全替换 `index.js`；
2. **新建 `NodeServerService`（嵌入式 Node 守护服务）**：
   - 启动本地 Node 实例：`node index.js`；
   - 注入环境变量：`STORAGE_DIR = filesDir/nodejs/data`，`DEV_HTTP_PORT = 9988`；
   - **就绪探针**：异步轮询 `http://127.0.0.1:9988/t4/config` 直到返回 HTTP 200；
   - 成功后发出 `OnNodeServerReadyEvent` 事件；
3. **改造 `VodConfig`（挂载 T4 数据源）**：
   - 收到 Node 服务就绪事件后，自动将 `http://127.0.0.1:9988/t4/config` 返回的站点数组注入到 App 站点列表中；
   - 用户无需手写任何 `sites` 配置，CatPawOpen 内置的全部爬虫源即刻成为点播主源。

---

### 阶段四：新增独立【首页配置】并打通 WebHome 交互

#### 目标
在设置中增加独立的 WebHome URL 配置，实现自由切换首页，并与底层原生播放无缝打通。

#### 涉及文件与改动
1. **设置界面新增【首页配置】**：
   - 在设置中新增 `WebHomeSettingDialog`；
   - 支持用户输入任意 Web 地址，默认值建议预设为：`http://127.0.0.1:9988/web.html`；
   - 保存至本地持久化配置；
2. **WebHome 启动与热重载**：
   - `WebHomeActivity` / WebView 容器启动时，直接加载【首页配置】的 URL；
   - 若 Node 服务尚未完全启动，展示优雅的骨架屏或 Loading 动画，一旦端口探针就绪立即刷新；
3. **JSBridge 原生联动**：
   - WebHome 点击视频：通过 `window.webhome.play(vodId)` 调起 Media3 / MPV 原生硬解播放器；
   - WebHome 点击搜索：通过 `window.webhome.search(wd)` 唤起原生聚合搜索面板；
   - 支持透传播放直链与 CatPawOpen 的 `/m3u8-proxy` 切片解密代理。

---

## 4. 关键风险与应对策略

| 风险项 | 潜在问题 | 针对性应对策略 |
| :--- | :--- | :--- |
| **网盘登录态丢失** | 更新远程 `index.js` 时导致阿里云盘/夸克 Cookie 被清空 | 将 `db.json` 隔离在子目录 `data/` 中，更新代码时只替换 `index.js`，严格保护 `data/` 目录 |
| **端口冲突** | 9988 端口被其他应用或残留进程占用 | 继承 CatPawOpen 自带的端口冲突递增机制（`EADDRINUSE` 尝试 `9989`），并将实际端口通知 Android 原生层 |
| **低端 TV 盒子 OOM** | Node V8 引擎与 Android 原生内存争抢 | 启动 Node 时注入 `--max-old-space-size=128` 参数，压制 Node 最大内存；生产模式关闭详细文件日志 |
| **首次冷启动等待** | Node 服务启动需要 1~2 秒，避免用户感知白屏 | WebView 首帧加载本地缓存骨架屏，后台并行异步拉起 Node，探针通过后局部刷新数据 |

---

## 5. 阶段执行与验证计划

- [ ] **Phase 1**：全量清理直播代码，确保当前分支编译通过，UI 无直播痕迹；
- [ ] **Phase 2**：引入 `FongMi/nodejs-mobile` 原生库，测试在本地能否正常启动 JNI Node 实例；
- [ ] **Phase 3**：实现 `NodeBundleManager`，测试输入远程 `https://.../index.js;md5;...` 能够自动下载、校验并由 Node 启动；
- [ ] **Phase 4**：打通 `127.0.0.1:9988/t4` 协议与 WebHTV 原生播放，验证视频解析与硬解播放流畅度；
- [ ] **Phase 5**：增加独立【首页配置】，测试加载 `http://127.0.0.1:9988/web.html` 及全屏遥控器交互体验。
