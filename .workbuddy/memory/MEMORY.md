# WebHTV 项目长期记忆

## 项目定位与来源

- 本地工作区：`D:/project/webhtv`；git remote = `https://github.com/ifocus9/CatTV.git`（CatTV 定制分支）
- 产品名 WebHTV：Android 点播客户端，定位**极致轻量纯粹**——已彻底剔除直播全链路、TVBox JSON 解析树、Python（chaquo）运行时
- 与上游 CatVod/TVBox 系出同源，但已大幅瘦身
- `webhome-devkit/` 未克隆到本工作区（README 称其放在该目录 + 独立 CNB 仓库）

## 已知的目录改动决策

- **2026-09-29**：删除 `serverless/` 目录（原含 5 个自建服务端实现：Cloudflare Worker + DO / Deno Deploy / Vercel Edge / Go / Rust，共 17M）。
  - 原因：老大用不到该功能，且不参与 APK 构建（Gradle 零引用）
  - **同日后续：App 端的「远程托管」功能也已整套移除**（老大确认）。删除 `app/src/main/java/com/fongmi/android/tv/remote/` 整包 11 类 + `RemoteTrustDialog` + `RemoteTrustSetup` + 相关 layout/drawable/字符串/Manifest 声明，并清掉 `SyncOptions.remoteRelay` 与「一键同步」里的相关勾选分支。编译 + 打包验证通过（`assembleMobileArm64_v8aDebug` BUILD SUCCESSFUL）
  - **保留未动**：「一键同步」「局域网管理页 `/m`」「观影记录同步（PlaybackRemoteSync*）」「扫码 ScanActivity」等通用功能；`ic_remote_settings` 图标（关于页复用）、`dialog_remote_trust_text_command.xml`（推送对话框复用）、7 条 `remote_trust_config_*` / `remote_trust_push_*` 字符串（ConfigDialog / PushPlayUrlDialog 复用）
  - **两条项目级经验**：① 改 App 代码必须同时扫 `app/src/main`、`app/src/mobile`、`app/src/leanback` 三个源集，flavor 目录易漏；② `app/src/main/res/values*/strings.xml` 是唯一字符串全集，mobile/leanback 的 strings.xml 只是部分覆盖

## 构建约束

- 脚本一律用系统 Python 3.12：`C:\Users\admin\AppData\Local\Programs\Python\Python312\python.exe`（**不要**用托管 3.13.12）
- Bash 缺 coreutils、PowerShell stdout 不回传 → 可靠套路：Python 脚本执行 + 输出重定向到文件 + Read 读文件
- Android 构建可用：`sdk.dir=C:/Users/admin/AppData/Local/Android/Sdk`，flavor 维度 = `mode(leanback/mobile) × abi(arm64_v8a/armeabi_v7a)`，applicationId `com.catvod.custom.tv`
- 快速编译验证命令：`./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac`；完整打包 `:app:assembleMobileArm64_v8aDebug`
