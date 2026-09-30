# WebHTV 无入口功能清理 · 总结报告

> 执行日期：2026-09-30
> 回滚点：`76419a4`（清理前基线快照，含你当时未提交的 UI 调整与新播放器设置页）
> 净变更：**206 文件变更 / +244 −27,232 行**（186 删除 + 18 修改 + 2 新增）

---

## 一、提交链

| order | commit | 内容 | 规模 |
|---|---|---|---|
| 回滚点 | `76419a4` | 清理前基线快照 | — |
| 批次 1 | `6bad41d` | 无入口死代码 + 配置 | 74 类 + 1 测试 + 41 layout + 3 menu + Manifest/ProGuard/JGit |
| 批次 2 | `5bdca96` | 孤儿资源（清理后重扫） | 63 drawable + 624 string + 9 color + 5 style + 4 string-array |
| 批次 3 | `8d5f515` | 级联孤儿资源 | 4 drawable + 1 style |
| 批次 4 | `a1690d5` | `Setting.java` 无入口配置方法 | 34 方法（684→523 行） |
| 修复 | `a3a0f39` | 测试残留修复（恢复夹具 + 删残留测试） | +1 恢复 / −2 测试 / 1 资源 |

---

## 二、清理的整块功能（完全无入口）

| 功能块 | 关键类 | 规模 |
|---|---|---|
| **Git 云备份 / 云仓库同步** | `GitCloudDialog`(2888) + `gitcloud` 包 **30 类**（含 `drive/`、`provider/`）+ `OneKeySyncDialog` + `SyncPathDialog` + `SyncDialog` + `SyncDeviceAdapter` | ~5,900 行 |
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
| **弹幕设置独立页** | `SettingDanmakuActivity`(leanback) + 相关 layout/drawable | — |
| **APK 推送组件 / 快捷方式** | `ShortcutReceiver`(mobile，无 filter) | — |

> 合计删除 Java **75 个**（main 59 / mobile 9 / leanback 5 / test 2）。

---

## 三、零散死类

| 类别 | 类 |
|---|---|
| 自定义 View / 控件 | `SettingClipboardOverlay`、`CustomNestedScrollView`、`CustomLeftRightLayout` |
| Adapter | `EpgDataAdapter` |
| 播放器辅助 / 度量（**非核心链路**） | `MpvGpuLoadTracker`(+单元测试)、`LutPipelineWarmupEffect`、`PlaybackCompatibilityRetentionPolicy`(+残留测试) |
| Bean / 数据模型 | `ClearKey`、`DanmakuData`（活的弹幕是 `bean/Danmaku.java`） |
| 工具类 | `PartUtil`、`Biometric` |
| 未接入组件 | `PassDialog`(leanback+mobile) + `PassListener` |
| **测试夹具（误删，已恢复）** | `AssPrototypeActivity` —— debug 源集字幕播放测试宿主 |

---

## 四、清理的资源

删除依据 = **清理代码后重新全量扫描** + **级联迭代到 0 孤儿**（不是清理前的旧清单）。

| 类型 | 数量 | 代表 |
|---|---|---|
| layout | 41 | `dialog_*`（one_key_sync / custom_csp / shell_proxy / about / manage_page / site_health …） |
| drawable | 67 | `ic_live_*`、`shape_live_*`、`ic_git_cloud_*`、`shape_audio_*`、`selector_live_*` … |
| string | 624 名称（跨 6 文件 1887 条目） | `live_*`、`danmaku_*`、`git_cloud_*`、`login_state_*`、`mpv_config_*` … |
| color | 9 | `bg_site`、`black_70`、`nav_capsule_bg` … |
| style | 6 | `Theme.Splash`、`Player.Live`、`Nav*` … |
| string-array | 4 | `select_language`、`select_size`、`select_ui_scale` … |
| menu | 3 | `menu_keep`、`menu_nav`、`menu_setting_enhance` |
| declare-styleable | 1 | `CustomNestedScrollView`（类已删） |

> **多语言同步**：`values` / `values-zh-rCN` / `values-zh-rTW` 三套一起删，避免 AAPT2「翻译有、默认无」报错。

---

## 五、配置改动

| 文件 | 改动 |
|---|---|
| `mobile/AndroidManifest.xml` | 删 `ShortcutReceiver` 声明（无 intent-filter） |
| `leanback/AndroidManifest.xml` | 删 `SettingDanmakuActivity` 声明（无 intent-filter） |
| `proguard-rules.pro` | 删**失效**的 `remote.**` 规则；`gitcloud.**` 收窄为 `gitcloud.secure.**`；删整段 JGit 规则 |
| `app/build.gradle` | 移除 `implementation libs.jgit` |
| `gradle/libs.versions.toml` | 移除 jgit 版本与库定义 |
| `Setting.java` | 删 34 个零引用配置方法（A 类整对死 + B 类 setter 死/getter 活） |

---

## 六、有意保留（附理由，别误删）

| 保留项 | 理由 |
|---|---|
| `OkGlideModule` | `@GlideModule` 注解类，**Glide 靠反射加载**，删除会让图片加载 OkHttp 集成失效（扫描误报） |
| `AndroidTrackInfo` / `FFmpegApi` | 第三方 IJK 包，JNI native / 接口实现，收益低风险高 |
| `GitCloudTokenStore`(`gitcloud.secure`) | 被**活跃的** `LoginStateSync` 引用 |
| `LoginStateSync` + `SyncOptions.isLoginState` | **活代码！** `/m` 局域网管理页 + 一键同步 + 本地备份三条链路在用 → `gitcloud` 包**不能整包删** |
| CSP 加载链路 `api/loader/*` | 核心功能，只删了编辑 Dialog |
| `SiteHealthStore` / `SiteAdapter` | 排序逻辑在跑，只删了 `SiteHealthDialog` |
| `ManageService` + `assets/manage.html` | 局域网 `/m` 页面可用，只删了 App 内入口 Dialog |
| `app/libs` 4 个 aar（forcetech/jianpian/thunder/tvbus） | 属**播放解析链路**，是活的 |
| 三大播放内核 | `player/exo`(179) `ijk`(22) `mpv`(46) 及 `PlayerManager`/三引擎全部未动 |

---

## 七、验证产物

| 验证项 | 结果 |
|---|---|
| 主源集编译 `compileMobileArm64_v8aDebugJavaWithJavac` + leanback | **SUCCESSFUL** |
| 完整打包 `assembleMobileArm64_v8aDebug` | **SUCCESSFUL**（APK 215M） |
| `minifyMobileArm64_v8aReleaseWithR8`（验改后的 ProGuard） | **SUCCESSFUL** |
| **unit test 源集编译** | ✅ 清理引入错误清零（仅剩 1 个原有错误，见下） |
| **androidTest 源集编译** | ✅ 通过 |
| `seeds.txt`：gitcloud 保留类 | 仅 `gitcloud.secure.GitCloudTokenStore` ✅ |
| `seeds.txt`：`org.eclipse.jgit` / `remote` 包 | **0** / **0** ✅ |
| 迭代扫描收敛 | 代码层 0（除保留项）、资源层 0 孤儿 |

> **遗留（原有问题，与清理无关）**：`ExoCompressedAudioDirectPolicyTest.java:240` 访问 media3 的 `ExoPlaybackException.isRecoverable` 报「非 public」——项目用**定制版 `media3 1.11.0-alpha01-fongmi`**；该文件自 `1f188f0 first commit` 未改动。

---

## 八、修复记录（`a3a0f39`）

| 问题 | 处理 |
|---|---|
| `AssPrototypeActivity` 是 debug 源集的**字幕播放测试夹具**（被 `AssPlaybackTest`/`DualSubtitlePlaybackTest` + `debug/AndroidManifest.xml` 依赖），按「无入口」误删 → androidTest 编译失败 | **从 git 恢复**（debug 源集，不进 release，零体积影响） |
| `PlaybackCompatibilityRetentionPolicy` 删类漏了测试 | **删** `PlaybackCompatibilityRetentionPolicyTest` |
| `attrs.xml` 里 `CustomNestedScrollView` 的 declare-styleable 成孤儿 | **删** |

**教训**：清理验证**必须覆盖 test / androidTest 源集**——只跑主源集编译，主源集照样通过，测试源集对被删类的引用却不暴露。

---

## 九、回滚方式

```bash
git revert a3a0f39   # 撤销测试修复
git revert a1690d5   # 撤销 Setting 方法清理
git revert 8d5f515   # 批次3
git revert 5bdca96   # 批次2
git revert 6bad41d   # 批次1

# 全部回滚到清理前
git reset --hard 76419a4
```

`76419a4` 完整含你清理前的所有工作，可安全回退。

---

## 十、备注

1. **JGit 依赖已移除**（release 包约减 3–6MB）。
2. 分析脚本与原始清单在 `.workbuddy/analysis/`（`scan3.py` 可达性 / `res2.py` 资源 / `method_scan3.py` 方法级），改代码后可重跑复验。
3. `WEB_TAB_PLAN.md`（你的「底部网页 Tab」规划）随 `a1690d5` 一并纳入版本控制，内容未改动。
4. **方法级散点死代码不再清理**（你已拍板）：全项目 121 个零引用方法信噪比差（JNI/Gson/预留混杂），且 R8 在 release 已移除，删了不减包。
5. `assembleRelease` 完整打包受**环境网络**限制（lint 工具链依赖 `protobuf-java` 下载 TLS 被拒），与清理无关。
