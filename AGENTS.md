# AGENTS.md — 外卖比价助手

> 本文件是 AI 编码助手在本仓库工作时的必读上下文。修改架构、接口或协作约定后，请同步更新本文件。

## 项目概述

Android 手机端外卖多平台比价工具：通过**无障碍服务（Accessibility Service）**读取前台外卖 App（**美团 / 淘宝闪购**）的界面节点树，提取店铺与商品价格，用**悬浮窗**实时展示跨平台实付价对比和最优下单策略。全部数据保存在手机本地（Room/SQLite），无服务器。

- 目标平台：Android 真机，侧载 APK 自用（不上架应用商店）
- iOS 不支持（系统限制，无解）
- 使用场景：仅限团队成员个人学习自用

### 平台说明（重要，2025-12 更新）

- **饿了么 App 已全面更名为「淘宝闪购」**（2025-12-05 起，包名 `me.ele` 沿用）——项目中不再存在独立的饿了么平台。
- 更名伴随 12.0 大版本 UI 重构，灰度期内界面可能持续变动——解析器代码必须写「宽容」，节点路径集中在常量区，改版后只修常量。
- 淘宝闪购有两个入口：① 淘宝闪购 App（包名 `me.ele`），主要采集对象；② 淘宝 App 内「闪购」频道（包名 `com.taobao.taobao`），备选验证对象。
- 目标包名：美团 `com.meituan.takeout`、淘宝闪购 `me.ele`（均以 M0 真机实测为准）。

## 架构（三层，全部在 App 内）

```
悬浮窗 UI（overlay/）          ← 比价卡片、最优策略、商家分析
逻辑层（engine/）              ← 同店同品匹配、实付价计算、Room 数据层
采集层（accessibility/ + parsers/ + launcher/） ← 无障碍服务 + 每平台一个解析器 + App 拉起
```

核心设计：**适配器模式**。每个平台一个解析器，统一输出 `StoreInfo`；外卖 App 改版只改对应解析器文件。

## 技术栈

- 语言：Kotlin + 原生 Android（不使用跨平台框架）
- 采集：`AccessibilityService` 遍历 `AccessibilityNodeInfo` 节点树；V2 用 `dispatchGesture` 模拟手势、Intent/deep link 拉起各 App
- 解析策略：**按文本模式匹配（如 `¥\d+` 正则），不按 resource-id 定位**——抗混淆，改版后只坏局部
- 存储：Room（SQLite）
- UI：悬浮窗用 `WindowManager`；主界面原生 View
- 构建：Gradle（Kotlin DSL）

## 目录约定

```
app/src/main/java/<package>/
├── Models.kt            # 三方共享数据契约：StoreInfo / ItemPrice / Deal
├── accessibility/       # 无障碍服务骨架：事件监听、节点树遍历工具、页面路由、手势工具
│                        #   AutoCaptureController.kt：M4 一键全采编排（拉起→滑屏→入库）
├── parsers/             # 平台解析器：meituan.kt / flash.kt
│                        #   节点路径与关键词常量集中在每个文件顶部常量区
├── launcher/            # AppLauncher.kt：包名/deep link 拉起、前台落地检测、超时重试
├── engine/              # match（匹配）、pricing（实付价）、data（Room）、analysis（商家分析）
├── overlay/             # 悬浮窗 UI
└── MainActivity.kt      # 主界面：历史价格、商家分析、红包录入
fixtures/                # 真实节点树 dump 的 JSON（脱敏后），跨模块测试数据
```

## 铁律：模块边界

- **采集层不碰 UI，UI 不碰节点树**——模块间只通过 `Models.kt` 和 `fixtures/` 对接。
- 改 `Models.kt` 中任何字段必须三人协商一致，并同步更新 fixtures。
- 解析器失败时**优雅降级**（悬浮窗提示「该页面暂不支持」），绝不崩溃。
- 采集到价格为止，**绝不触碰下单/支付流程**的节点操作。

## 数据契约（Models.kt）

```kotlin
data class ItemPrice(val name: String, val price: Double, val packageFee: Double)
data class StoreInfo(
    val platform: String,        // "meituan" | "flash"
    val storeName: String,
    val rating: Double, val monthlySales: Int,
    val deliveryFee: Double, val minOrder: Double,
    val discounts: List<String>,
    val items: List<ItemPrice>,
    val capturedAt: Long,
)
data class Deal(val platform: String, val finalPrice: Double, val breakdown: List<String>)
```

## B 模块细化（淘宝闪购解析器 + App 拉起）

### launcher/AppLauncher.kt（可行性：高，预计 2-3 天）

- 职责：拉起目标 App → 确认落地 → 超时重试。
- **Android 11+ 包可见性**：manifest 必须声明，否则检测不到/拉不起目标 App：
  ```xml
  <queries>
      <package android:name="me.ele" />
      <package android:name="com.meituan.takeout" />
      <package android:name="com.taobao.taobao" />
  </queries>
  ```
- **Android 10+ 后台启动限制**：本 App 持有悬浮窗权限（SYSTEM_ALERT_WINDOW），在后台启动 Activity 的系统豁免名单内；兜底方案为在悬浮窗点击事件（用户手势上下文）中发起拉起。
- **落地检测**：复用无障碍服务的 `TYPE_WINDOW_STATE_CHANGED` 事件确认前台包名/Activity，不引入额外权限。
- **deep link**：优先 `eleme://` scheme（更名后大概率保留，M0 实测）；失败降级为包名拉起首页，站内导航交给无障碍手势。
- 对外接口约定：`fun launch(target: LaunchTarget, timeoutMs: Long = 8000): LaunchResult`，结果枚举 `Success / Timeout / NotInstalled`。

### parsers/flash.kt（可行性：中高，预计 1-2 周）

- 解析流程固定五步，顺序不可乱：
  1. **检测并关闭弹窗**（红包/会员/更新弹窗会遮挡菜单节点）；
  2. **识别页面类型**（店铺菜单页 / 搜索结果页 / 其他 → 放弃返回 null）；
  3. **文本模式匹配提取**：`¥\d+` 正则定位价格节点，向上回溯兄弟节点取商品名；
  4. **滑一屏抓一屏**，按商品名去重合并（菜单懒加载）；
  5. 组装 `StoreInfo` 返回。
- 节点关键词、弹窗关闭按钮文案等常量集中在文件顶部 `FLASH_SELECTORS` 区；12.0 改版期只改这里。
- 任何一步匹配失败 → 返回 null，由框架提示「该页面暂不支持」，**不允许抛出未捕获异常**。
- 第 1、4 步依赖 A 框架提供的手势工具（`scrollAndCollect`、`findByText`、`dumpTree`）——M0-M1 阶段 B 协助 A 完成这些工具，是给自己铺路。
- **时机风险**：更名改版期 UI 可能持续变动，每次 App 大版本更新后必须重新 dump fixtures 并跑解析器回归。

### fixtures（B 负责维护）

- `fixtures/flash_store_01.json`（店铺菜单页 dump）、`fixtures/flash_popup_01.json`（带弹窗页 dump）。
- dump 前脱敏：不得包含定位地址、手机号、账号昵称等个人信息。
- fixtures 同时提供给 C 开发引擎和 UI，是 B 对全组的并行开发承诺。

### B 的 M0 验证清单（go/no-go）

1. dump `me.ele` 店铺页节点树：能找到店名 + ≥5 个「商品名+价格」→ 解析器 go；
2. `adb shell am start -a android.intent.action.VIEW -d "eleme://..."` 实测 deep link 是否保留、能否直达店铺页 → 决定拉起策略；
3. 任一项失败 → 验证淘宝 App 内闪购频道（`com.taobao.taobao`）作备选；再失败 → 该平台降级为用户手动录入；
4. 产出：两份脱敏 dump fixtures 交 C，拉起可行性结论同步全组。

## 协作流程

- 三人分工：A = 采集框架 + 美团解析器；B = 淘宝闪购解析器 + App 拉起；C = 引擎 + 悬浮窗 UI + 工程化。
- Git：`main` 分支保护，`feature/xxx` 分支 + PR 互审（至少一人 approve）。
- 给 AI 助手布置任务时，把 `Models.kt` 和相关 fixture 一并提供作为上下文。
- 每周一次进度同步；解析器节点路径变更、接口字段调整须即时通知全组。

## 里程碑（当前进度：M5，全部完成）

- **M0 可行性验证（go/no-go）**：真机上用最小无障碍服务 dump 美团、淘宝闪购两个 App 的节点树，确认能读到店名和价格；两平台都通过才继续（闪购不过则验证淘宝内频道，再不过则降级手动录入）。
- **M1 V1 实时跟随模式（浏览时悬浮窗即时比价）**：已集成——dump → 页面路由 → 解析 → Room 持久化 → 跨平台匹配 → 实付价估算 → 悬浮窗实时展示。
- **M2 匹配与最优策略**：已完成——商品名归一化完全相等配对 + 字符二元组 Jaccard 相似度兜底（阈值 0.5）；实付价在满减后叠加一张该平台可用的最大金额红包。
- **M3 商家分析**：已完成——`engine/analysis/PriceAnalyzer`（价格轨迹/评分销量趋势/变价检测），主界面红包录入 + 商家分析展示。
- **M4 V2 一键全采（模拟手势自动驾驶各 App）**：已完成——`accessibility/AutoCaptureController.kt` 编排「拉起 → 落地 → 验证码检查 → 限速滑屏采集 → 多屏合并 → 流水线入库」；全采期间暂停常规节流 dump；命中验证码即停并提示人工。真机行为待实测回归。
- **M5 保活适配 + 打磨**：已完成——① 修复悬浮窗数据链路（OverlayService 直接订阅 CaptureHub.state，原 companion + ACTION_REFRESH 协议无人接线已删除）；② 主界面显示无障碍服务存活状态，新增「关闭电池优化」「自启动/后台设置」引导按钮（国产 ROM 逐个尝试，兜底应用详情页）；③ 快照入库按内容去重（不含采集时间），停留同页不再刷库；④ 跨平台比价限定 2 小时新鲜度窗口；⑤ 悬浮窗标题展示数据更新时间。保活做到「可感知 + 一键引导」，不引入进程守护黑魔法。

## 合规红线（AI 助手必须遵守）

- 无障碍权限敏感：代码仅供团队自用，不生成对外分发/上架相关内容。
- 不实现自动破解验证码的功能；遇到验证码暂停并提示人工处理。
- 模拟操作必须限速、模拟真人节奏；不采集用户个人信息。
- 下单支付永远由人完成，不写任何自动下单代码。
- 每次进行代码修改时必须征求我的同意
- 所有需要下载的东西全部下载到D盘
- 所有工作都在D盘项目目录中进行
