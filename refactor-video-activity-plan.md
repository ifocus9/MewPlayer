# VideoActivity.java 拆分计划

**文件**：`app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java`
**初始行数**：6333（超过 3000 阈值）
**目标**：通过提取纯视图工厂 / 有状态控制器，将 Activity 本体降至 ~2500 行。

---

## 现状结构

| 行数段 | 功能块 | 方法数 |
|---|---|---|
| 355–530 | 静态入口 + getter | ~10 |
| 530–664 | 生命周期 / init | ~5 |
| 664–1076 | initEvent、焦点、按钮设置 | ~20 |
| 1076–1550 | 详情加载、剧集、播放流程 | ~25 |
| 1550–1780 | 歌词搜索 Sheet | ~25 |
| 1780–2448 | 音频队列（含 AudioQueueAdapter） | ~80 |
| 2448–3030 | 卡拉OK面板、进度对话框 | ~50 |
| 3030–3497 | 播放控制（倍速、片头片尾、内核切换） | ~40 |
| 3497–3776 | 背景墙、封面、历史 | ~20 |
| 3776–4233 | 播放回调 + 音频舞台 | ~30 |
| 4233–4507 | 歌词搜索逻辑 | ~25 |
| **4507–5360** | **音频/歌词面板工厂** | **~50** |
| 5360–5584 | EventBus 回调 + 搜索 | ~15 |
| 5584–5856 | 搜索、播放开始 | ~20 |
| 5856–6333 | 按键分发、手势、生命周期收尾 | ~30 |

**合计**：约 624 个方法、7 个内部类/接口、近 100 个实例字段。

---

## 第一步：音频/歌词面板工厂（已在进行中）

### 目标
把 **4507–5360** 段的纯视图构建器、样式、背景抽取为两个静态工厂类：
- `ui.custom.AudioSheetFactory` — 纯构建器 + 背景 Drawable + 工具方法
- `ui.custom.LyricsSheetFactory` — 卡拉OK/歌词面板控件

### 已搬移方法（~60 个）

**AudioSheetFactory：**
- `createAudioSheet()`、`createAudioQueueDialog()`
- 文本视图：`createAudioSheetTitle`、`createAudioSheetText`、`createAudioSheetItem`、`createAudioSheetButton`、`createAudioSheetMiniButton`、`createAudioSheetIconButton`、`createAudioSheetInlineIconButton`
- 控件：`createSegmentedControl`
- 焦点：`setAudioSheetFocusable`
- 布局参数：`audioSheetButtonParams`、`audioSheetMiniButtonParams`、`audioSheetTopParams`、`audioSheetWrapTopParams`
- 背景 Drawable：`audioSheetItemBackground`、`audioSheetControlBackground`、`audioSheetSegmentBackground`、`audioSheetSelectableBackground`、`roundRect`、`audioDrawerBackground`、`audioSheetGlassBackground`、`audioGlassColors`
- 窗口样式：`styleAudioSheetInput`、`applyAudioSheetWindowGlass`
- 常量：`SHEET_*`（10 个）、接口：`SegmentClickHandler`

**LyricsSheetFactory：**
- 卡拉OK：`createKaraokeSheetHeader`、`createKaraokeHeaderButton`、`createKaraokeModeToggle`、`updateKaraokeModeToggle`
- 歌词步长控制：`createLyricsStepControl`、`createLyricsStepButton`、`applyLyricsLongSetting`
- 布局参数：`lyricsStepButtonParams`、`lyricsSettingRowParams`、`karaokeActionGridParams`、`karaokeActionButtonParams`、`karaokeActionButtonFullParams`、`lyricsResultItemParams`
- 条目构建：`createLyricsChoiceItem`、`createKaraokeActionGrid`、`createKaraokeActionButton`、`createLyricsResultItem`、`createKaraokeTrackResultItem`
- 背景：`lyricsResultItemBackground`
- 工具：`formatLyricsOffset`
- 接口：`LyricsLongSetter`、`LyricsLongGetter`

### 保留在 Activity 中的状态依赖项
以下方法因读取实例字段，继续留在 `VideoActivity`：
- `createAudioSheetRoot()`（读 `mEpisodeBottomInset`）
- `isLandscapeAudioSheet()`（读 `mAudioStageVisible`）
- `styleAudioDrawerRoot()` / `styleAudioQueueDrawerRoot()`
- `audioDrawerWidth()` / `audioDrawerHeight()` / `audioQueueDrawerHeight()` / `audioDrawerBottomMargin()` / `audioQueueDrawerListMaxHeight()` / `audioQueueContentHeight()` / `lyricsResultSheetHeight()` / `karaokeTrackResultSheetHeight()`
- `showAudioSheet()` / `showCompactPlaybackSheet()` / `showLyricsSearchSheetDialog()` / `showAudioDrawerSheet()` / `showAudioQueueDrawerDialog()`
- `showAudioBackgroundSheet()` / `showLyricsSettingsSheet()`
- `focusAudioSheetContent()` / `applyAudioSheetWindowGlass()`
- `hideSystemBarsForAudioSheet()` / `hideSystemBarsForAudioDialog()`
- `updateLyricsResultList()` / `updateLyricsResultSheetHeight()`
- `createKaraokeModeHeader()`、`createLyricsChoiceGrid()`、`createLyricsSettingsTabs()`
- `preserveLyricsSettingsSheetHeight()`

---

## 第二步：有状态功能控制器（规划中）

| 新类 | 吸收内容 | 预计行数 |
|---|---|---|
| `AudioQueueController` | 音频队列 Sheet、搜索、加/删曲、`AudioQueueAdapter` | ~668 |
| `LyricsSearchController` | 歌词搜索 Sheet + 搜索逻辑 | ~500 |
| `KaraokePanelController` | 卡拉OK面板与导入流程 | ~580 |
| `AudioStageController` | 音频舞台可见性、背景调色/光晕/Palette 提色、封面旋转、音频 seek 预览 | ~350 |

模式：构造函数注入所需 View + `Source` 回调接口，由 Activity 实现并持有引用。

---

## 第三步：按键/焦点分发器（规划中）

- `AudioStageKeyDispatcher`：`dispatchAudioStageKey`、`moveAudioStageFocus`、`collectAudioStageFocusables`、`findAudioStageFocusTarget`、seek 预览步长
- `LutQuickKeyDispatcher`：`dispatchLutQuick*`、`scoreLutQuickFocusCandidate`、`isSameFocusRow`

Activity 内只保留优先级编排：
```java
if (dispatchLutQuickKey(event)) return true;
if (mAudioStageVisible && ...) return audioStageKeyDispatcher.dispatch(event);
```

---

## 第四步（可选，价值最高）：两端去重（规划中）

mobile 版（2912 行）与 leanback 版有 **197 个同名方法**。抽取 `VodPlaybackFlow` 协作类或公共基类让两端共用。建议放到最后单独做，每挪一块编译 + 真机验证一次。

---

## 当前进度

| 阶段 | 状态 | 备注 |
|---|---|---|
| 工厂类创建 | ✅ 完成 | `AudioSheetFactory.java` (243 行)、`LyricsSheetFactory.java` (237 行) |
| 删除已搬成员 | ✅ 完成 | VideoActivity 6333 → 5905 行 |
| 调用点改写 | ✅ 完成 | 129 处调用（102 AudioSheetFactory + 27 LyricsSheetFactory） |
| 清理冗余 import | ⏳ 进行中 | `Typeface`、`GradientDrawable` 可删除；`ColorStateList`/`TextInputLayout`/`Locale` 保留 |
| 编译验证 | ❌ 未执行 | 下一步 |
| 第二步～第四步 | ⏳ 规划中 | 见上表 |
