# 外卖比价助手

Android 无障碍读屏 + 悬浮窗的多平台外卖比价工具（美团 / 淘宝闪购）。全部里程碑（M0–M5）已完成，并已整合 C 侧的确认配对机制、悬浮窗交互与工程化配置。约定与分工见同目录的 `AGENTS.md`。

## 环境

- Android Studio（最新稳定版）+ Android SDK 34，JDK 17
- Android 真机（API 26+），开启开发者选项 + USB 调试
- 构建：`./gradlew assembleDebug`；项目路径含非 ASCII 字符时需加 `-Pandroid.overridePathCheck=true`

## 使用步骤

1. **装机**：`./gradlew installDebug`，打开「外卖比价助手」。
2. **开权限**：App 内按钮 1（无障碍）、按钮 2（悬浮窗）。开启后悬浮窗由 OverlayController 按系统开关自动启停。
3. **保活（建议）**：按钮 6 关闭电池优化、按钮 7 跳自启动/后台设置（按厂商逐个尝试，兜底应用详情页）。
4. **实时比价（M1）**：手动浏览美团/闪购的店铺菜单页，悬浮窗实时显示实付价对比，最低价高亮；疑似同品在悬浮窗逐条「确认同品」，确认后永久按同品计价。
5. **一键全采（M4）**：按钮 5，自动拉起两平台、限速滑屏采集、入库比价；命中验证码即停并提示人工。
6. **商家分析（M3）**：主界面录入红包（带门槛）、生成商家分析（趋势 + 变价 + 价格走势图）。

## 工程结构

```
app/src/main/java/com/team/pricecompare/
├── Models.kt                 # 三方数据契约（改动需三人同意）
├── Morandi.kt                # 莫兰迪色板（移植自 C 侧）
├── App.kt                    # 进程入口：注册无障碍开关监听
├── MainActivity.kt           # 控制台：权限/拉起/一键全采/红包录入/商家分析
├── accessibility/            # DumpAccessibilityService（读屏+弹窗关闭）、NodeTree、
│                             #   TextExtractors、PageRouter、GestureTools、NodeMerger、
│                             #   AutoCaptureController（M4 一键全采编排）
├── parsers/                  # meituan.kt / flash.kt（常量区在文件顶部）+ SafeParse
├── launcher/AppLauncher.kt   # 拉起 + 落地检测（deep link 优先，包名兜底）
├── engine/                   # match（三级判定配对）、pricing（实付价）、
│                             #   data（Room：快照/红包/确认配对）、analysis（商家分析）、
│                             #   CapturePipeline（流水线）、CaptureHub（状态总线）
├── overlay/                  # OverlayService（前台服务悬浮窗：折叠/确认交互）、
│                             #   OverlayController（无障碍开关监听 + 自动启停）
└── ui/ChartView.kt           # 价格走势折线图（纯 Canvas）
fixtures/                     # 节点树 dump（脱敏后），跨模块对接物
```

## 单元测试

```bash
./gradlew testDebugUnitTest
```

覆盖解析器、页面路由、匹配三级判定、计价（满减/红包）、流水线口径、商家分析、无障碍串解析等纯 Kotlin 逻辑（不需要真机）。

## ROM 保活清单（整合自 C 侧文档）

无障碍服务被杀后只能由用户/系统恢复，本工程做到「可感知 + 一键引导」：

- **通用**：关闭电池优化（按钮 6）；主界面状态区实时显示服务存活状态
- **小米**：安全中心 → 应用管理 → 权限 → 自启动管理，允许本 App
- **华为/荣耀**：手机管家 → 应用启动管理 → 手动管理，允许自启动/关联启动/后台活动
- **OPPO/一加**：手机管家 → 权限隐私 → 自启动管理；电池 → 高级设置 → 允许后台运行
- **vivo/iQOO**：i 管家 → 应用管理 → 权限管理 → 自启动；电池 → 后台耗电管理 → 允许

## 注意

- 解析器失败返回 null 是设计行为（优雅降级），不是 bug；
- dump 节流 3 秒、滑屏间隔 ≥1 秒是有意的（模拟真人节奏，合规红线）；
- 绝不点击下单/支付相关节点，采集到价格为止；命中验证码即停并提示人工。
