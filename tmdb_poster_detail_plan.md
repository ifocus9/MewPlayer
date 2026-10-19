# 📋 MewPlayer TMDB 大海报详情页实现计划

> **设计核心原则**：低耦合、渐进增强、高视觉质感。
> **核心交互策略**：配置了 TMDB 时前置沉浸式大画报详情页，未配置 TMDB 或来自历史记录时直接进入原有播放页。完全不改动播放器核心逻辑。

---

## 架构流程设计

```mermaid
flowchart TD
    A[用户在列表中点击影片] --> B{是否配置了 TMDB\n且开启了详情页开关?}
    B -- 否 --> C[直接启动现有 VideoActivity\n(保持半屏即时播放原逻辑)]
    B -- 是 --> D{是否来自历史记录续播?}
    D -- 是 / 极速追剧 --> C
    D -- 否 / 首次点选 --> E[打开全新 VodDetailActivity]
    E --> F[异步请求 TMDB 高清海报/演职员/评分]
    E --> G[展示沉浸式海报大画报 + 剧情/演员]
    G --> H[点击播放 / 选择集数]
    H --> C
```

---

## 阶段一：TMDB 基础数据层与设置面板（Data & Config）

> **目标**：在 Java/Android 原生环境下，构建 TMDB 请求能力与本地缓存管理，提供可视化的设置面板。

1. **配置存储扩展（`Setting.java`）**
   - 增加 TMDB 配置持久化键值：
     - `tmdb_enable` (boolean，总开关，默认 `false`)
     - `tmdb_api_key` (String，用户 API Key，本地加密/隐藏存储)
     - `tmdb_api_base` (String，API 代理地址，默认 `https://api.tmdb.org`)
     - `tmdb_image_base` (String，图片代理地址，默认 `https://images.tmdb.org/t/p`)
     - `tmdb_detail_page_enable` (boolean，前置大海报页开关，默认 `true`)
2. **设置界面搭建（`SettingFragment.java`）**
   - 在手机端设置增加「**TMDB 刮削与海报**」入口；
   - 提供输入框（API Key 掩码显示、自定义 Base URL 可选输入）；
   - 提供「测试 API 连通性」和「测试图片 CDN」两个即时检测按钮；
   - 提供开关：「点选影片先进入大画报页」。
3. **TMDB 客户端封装（`com.fongmi.android.tv.api.tmdb.TmdbApi`）**
   - 基于现有 OkHttp 和 Gson，移植实现：
     - 影视通用搜索：`3/search/multi?query=...&language=zh-CN`
     - 详情与演职员：`3/{media_type}/{id}?append_to_response=credits&language=zh-CN`
     - 多图海报库：`3/{media_type}/{id}/images`
   - **关键算法移植**：片名智能清洗算法（自动剔除“更新至xx集”、“4K/HDR”、“国语版/粤语版”、“完结”等干扰后缀，提高匹配率）。
4. **两级缓存机制（`TmdbCache.java`）**
   - **内存缓存**：LruCache 缓存最近浏览的命中数据；
   - **磁盘负缓存**：若 TMDB 确无收录该影片，记录 7 天负缓存，避免同一影片反复向 TMDB 发出无效请求；
   - 在系统清除缓存时同步清除 TMDB 缓存。

---

## 阶段二：大画报详情页 UI 开发（Presentation Layer）

> **目标**：打造一个高颜值、类似 Infuse / Apple TV+ 的全沉浸式视觉详情页，完全不干扰现有的播放器代码。

1. **页面骨架（新建 `VodDetailActivity.java` + `activity_vod_detail.xml`）**
   - **全屏暗色毛玻璃背板**：
     - 取当前海报图通过 Glide 加载并进行实时高斯模糊（RenderEffect / FastBlur），随海报色调自适应氛围底色。
   - **Hero 核心展示区**：
     - **主海报卡片**：圆角 + 弥散投影，加载 TMDB `w780` 高清大图，若有多图支持轻微视差呼吸效果；
     - **影片信息块**：片名、原名、年份、时长、TMDB 官方评分（金色星标/评分圆环）、分类流派标签（Chips 风格）。
   - **核心交互区**：
     - **“立即播放” 主胶囊按钮**：大面积强调色，醒目的播放图标，文案支持“从第 1 集播放”或“继续播放上次进度”；
     - **辅助操作**：收藏按钮（同步现有收藏夹）、快搜/换源按钮。
   - **演职人员板块（Cast）**：
     - 横向滑动列表：演员真实圆形头像、中文名、角色名；
   - **剧情大纲板块**：
     - 优雅的排版字体，支持点击“展开全部简介”。
   - **选集快速预览区**：
     - 展示从源站解析到的剧集横排列表，点击任一集数直接启动该集播放。

---

## 阶段三：路由门禁与分流跳转（Navigation & Dispatch）

> **目标**：把控“什么时候进详情页，什么时候直达播放”，保证日常操作的顺滑。

1. **集中式路由入口封装（`VodNavigator.java`）**
   - 提取全局导航方法：
     ```java
     public static void open(Context context, String siteKey, String vodId, String vodName, String vodPic, boolean fromHistory);
     ```
2. **三级分流策略**：
   - **分支 A（历史记录/继续观看）**：
     - `fromHistory == true` 时，**跳过海报详情页，直达 `VideoActivity` 秒开播放**（保护追剧体验，不增加多余点击）。
   - **分支 B（配置了 TMDB 且开启详情页）**：
     - `TmdbApi.isConfigured() && Setting.isTmdbDetailPageEnable()`：
     - 启动 `VodDetailActivity`，进入画报页。
   - **分支 C（未配置 TMDB 或已关闭开关）**：
     - 保持现有逻辑，直接进入 `VideoActivity` 原生半屏播放。
3. **调用点统一接管**：
   - 替换 `TypeFragment`（分类/首页推荐）、`CollectFragment`（聚合搜索列表）、`SearchActivity` 等的点击事件。

---

## 阶段四：起播衔接与容错兜底（Handoff & Edge Cases）

> **目标**：确保从详情页跳入播放页的无缝体验与异常情况的安全兜底。

1. **从详情页起播跳转**：
   - 点击“立即播放”或点击具体第 N 集时，调用现有的 `VideoActivity.start(...)` 启动；
   - 配置 Activity 共享元素过渡动画或平滑淡入动画，保证视觉连贯。
2. **返回栈与生命周期管理**：
   - 在 `VideoActivity` 中退出播放（按返回键）时，退回 `VodDetailActivity`，再按返回退回主列表，层级清晰自然。
3. **TMDB 搜索失败/冷门片兜底**：
   - 如果用户配置了 TMDB，但这部冷门片在 TMDB 搜不到：
     - 自动回退显示视频源自带的原始封面海报（`vod_pic`）；
     - TMDB 演职员与评分板块自动隐去，保留剧情简介和选集；
     - 确保页面任何时候都不会出现空白或加载死锁。

---

## 阶段五：实施步骤与时间排期建议

| 步骤 | 内容 | 预期周期 | 交付物 |
| :--- | :--- | :--- | :--- |
| **Step 1** | TMDB API 客户端、网络请求封装、智能片名清洗过滤 | 1~2 天 | `TmdbApi.java`、连通性单测 |
| **Step 2** | 设置页 UI（API Key 输入、Base 代理、测试按钮） | 1 天 | 设置页面板与持久化配置 |
| **Step 3** | 大海报详情页布局与渲染（毛玻璃大图、评分、演职员） | 2~3 天 | `VodDetailActivity` 及对应 XML |
| **Step 4** | 路由分发器（`VodNavigator`）及各入口（首页/搜索/历史）接入 | 1 天 | 全局点击分流与跳转 |
| **Step 5** | 兜底机制、弱网超时、内存（Glide 大图防 OOM）走查与联调 | 1~2 天 | 完整功能闭环与发版验证 |
