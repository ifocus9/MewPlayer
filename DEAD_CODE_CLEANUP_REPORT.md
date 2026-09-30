# WebHTV 无入口功能清理报告

> 执行日期：2026-09-30
> 基线快照：`76419a4`（清理前，含你正在改的 UI 与新播放器设置页）
> 结果：**186 个文件删除 / 27,163 行删除**，编译 + R8 全部通过

---

## 一、总览

| 批次 | commit | 内容 | 规模 |
|---|---|---|---|
| 回滚点 | `76419a4` | 清理前基线快照 | — |
| 批次 1 | `6bad41d` | 无入口死代码 | 74 类 + 1 测试 + 41 layout + 3 menu |
| 批次 2 | `5bdca96` | 孤儿资源（清理后重扫） | 63 drawable + 624 string + 9 color + 5 style + 4 string-array |
| 批次 3 | `8d5f515` | 级联孤儿资源 | 4 drawable + 1 style |

累计 diff（对比基线）：`186 D / 16 M`，**-27,163 行**。

---

## 二、批次 1：无入口功能代码

### 2.1 整块功能（完全无入口）

| 功能块 | 删除类 | 规模 |
|---|---|---|
| **Git 云备份 / 云仓库同步** | `GitCloudDialog`(2888) + `gitcloud` 包 29 类 + `OneKeySyncDialog` + `SyncPathDialog` + `SyncDialog` | ~5,900 行 |
| **自定义 CSP 源编辑** | `CustomCspDialog`(1974) + `CspWarmupDialog` | 2,216 行 |
| **WebHome 扩展** | `WebHomeExtensionDialog`(1261) + `WebHomeExtensionDebugDialog`(919) | 2,180 行 |
| **Shell 代理** | `ShellProxyDialog` | 925 行 |
| **观影记录同步 / Webhook** | `ViewingRecordSyncDialog` + `PlaybackWebhookDialog` + `PlaybackRemoteSyncDialog` | 1,331 行 |
| **登录状态学习** | `LoginStateLearnDialog` + `LoginStatePathDialog` | 1,093 行 |
| **MPV 配置管理（部分）** | `MpvCustomButtonDialog` + `MpvConfigHistoryDialog` + `MpvConfigHistoryAdapter` | 631 行 |
| **APK 推送 / 推送播放** | `ApkPushDialog` + `ApkPushUrlDialog` + `ApkPushMethodDialog` + `PushPlayDialog` + `PushPlayUrlDialog` | 829 行 |
| **关于页 / 更新设置** | `AboutDialog` + `UpdateSettingsDialog` | 343 行 |
| **页面管理（App 内入口）** | `ManagePageDialog` | 110 行 |
| **站点健康提示** | `SiteHealthDialog` | 97 行 |

### 2.2 零散死类

| 类别 | 类 |
|---|---|
| 自定义 View / 控件 | `SettingClipboardOverlay`、`CustomNestedScrollView`、`CustomLeftRightLayout` |
| Adapter | `EpgDataAdapter`、`SyncDeviceAdapter` |
| 播放器内核 / 策略 | `MpvGpuLoadTracker`(+单元测试)、`LutPipelineWarmupEffect`、`PlaybackCompatibilityRetentionPolicy`、`AssPrototypeActivity` |
| Bean / 数据模型 | `ClearKey`、`DanmakuData` |
| 工具类 | `PartUtil`、`Biometric` |
| 未接入组件 | `PassDialog`(leanback+mobile) + `PassListener`、`SettingDanmakuActivity`、`ShortcutReceiver` |

### 2.3 配置层

| 文件 | 改动 |
|---|---|
| `mobile/AndroidManifest.xml` | 删除 `ShortcutReceiver` 声明（无 intent-filter，无入口） |
| `leanback/AndroidManifest.xml` | 删除 `SettingDanmakuActivity` 声明（无 intent-filter，无入口） |
| `proguard-rules.pro` | 删除失效的 `remote.**` 规则；`gitcloud.**` 收窄为 `gitcloud.secure.**`；删除整段 JGit 规则 |
| `app/build.gradle` | 移除 `implementation libs.jgit` |
| `gradle/libs.versions.toml` | 移除 jgit 版本与库定义 |

---

## 三、批次 2 / 3：孤儿资源

删除的资源 = 清理后**重新全量扫描**得出（不是清理前的旧清单），并做了**级联迭代**直到收敛为 0：

| 类型 | 数量 | 备注 |
|---|---|---|
| drawable | 67 | 含 `ic_live_*`、`shape_live_*`、`ic_git_cloud_*`、`shape_audio_*` 等 |
| string | 624 个名称（跨 6 个文件共 **1,887 条目**） | 含 `live_*`、`danmaku_*`、`git_cloud_*`、`login_state_*`、`mpv_config_*` 等 |
| color | 9 | |
| style | 6 | 含 `Theme.Splash`、`Player.Live`、`Nav*` 等 |
| string-array | 4 | |

**多语言同步**：`values` / `values-zh-rCN` / `values-zh-rTW` 三套同步删除，避免出现"翻译文件有、默认文件无"的 AAPT2 报错。

---

## 四、有意保留（均给出保留理由）

| 保留项 | 理由 |
|---|---|
| `OkGlideModule` | `@GlideModule` 注解类，**Glide 靠反射加载**，删除会导致图片加载的 OkHttp 集成与日志配置失效（扫描误报，必须留） |
| `AndroidTrackInfo`、`FFmpegApi` | 第三方 IJK 包（`tv.danmaku.ijk`），JNI native / 接口实现，删了收益低（168 行）、崩了排查麻烦 |
| `GitCloudTokenStore`（`gitcloud.secure`） | 被活跃的 `LoginStateSync` 引用（一键同步仍在打包登录态） |
| `LoginStateSync` + `SyncOptions.isLoginState` | 一键同步链路仍在使用，未动 |
| CSP 加载链路 `api/loader/*` | 核心功能，只有编辑 Dialog 能删 |
| `SiteHealthStore`、`SiteAdapter` | 排序逻辑在跑，只删了 `SiteHealthDialog` |
| `ManageService` + `assets/manage.html` | 局域网 `/m` 页面可用，只删了 App 内入口 Dialog |
| `app/libs` 4 个 aar（forcetech/jianpian/thunder/tvbus） | 属播放解析链路，**是活的**，未动 |

---

## 五、验证产物

| 验证项 | 结果 |
|---|---|
| `compileMobileArm64_v8aDebugJavaWithJavac` + `compileLeanback...` | **BUILD SUCCESSFUL** |
| `assembleMobileArm64_v8aDebug`（完整打包） | **BUILD SUCCESSFUL**，产物 `app-mobile-arm64_v8a-debug.apk` 215M |
| `minifyMobileArm64_v8aReleaseWithR8`（R8 + 改动后的 ProGuard） | **BUILD SUCCESSFUL** |
| `seeds.txt` 中 gitcloud 保留类 | 仅 `gitcloud.secure.GitCloudTokenStore` ✅（keep 收窄生效） |
| `seeds.txt` 中 `org.eclipse.jgit` 类 | **0** ✅（JGit 彻底移除） |
| `seeds.txt` 中 `com.fongmi.android.tv.remote` | **0** ✅（失效残留已清） |
| 迭代扫描收敛 | 代码层 0（除保留项）、资源层 0 孤儿 |

> **注**：`assembleRelease` 完整打包因**环境网络问题**中断——lint 工具链依赖 `protobuf-java:3.25.5` 无法从 maven central / jitpack 下载（TLS handshake 被拒），与本次清理无关（不涉及 jgit，也未改动 lint 依赖）。R8 任务单独执行已通过。

---

## 六、回滚方式

```bash
# 回滚单一批次
git revert 8d5f515   # 批次3
git revert 5bdca96   # 批次2
git revert 6bad41d   # 批次1

# 全部回滚到清理前
git reset --hard 76419a4
```

`76419a4` 已完整体含你清理前的所有工作（UI 调整 + 新播放器设置页），可安全回退。

---

## 七、后续建议

1. **JGit 依赖已从 classpath 移除**，release 包体积会减小（JGit + slf4j 约 3-6MB）。
2. `SyncOptions.isLoginState` 目前是**半截状态**——登录态学习 UI 已删，但一键同步仍在打包登录态。若确定不要该能力，可一并清理。
3. `GitCloudTokenStore` 是 gitcloud 包唯一幸存者。若后续连一键同步的登录态也去掉，可连同 `LoginStateSync` 一起清理，`gitcloud` 包即可整包消失。
4. 清理后残留的分析清单在 `.workbuddy/analysis/`，改动代码后可重跑 `scan3.py` / `res2.py` 复验。
