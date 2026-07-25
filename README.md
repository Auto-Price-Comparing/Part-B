# 外卖比价助手 — M0 可行性验证工程

Android 无障碍读屏 + 悬浮窗的多平台外卖比价工具。当前为 **M0（go/no-go 验证）** 版本，目标是在真机上确认「能读到美团/淘宝闪购的店名和价格」。约定与分工见同目录的 `AGENTS.md`。

## 环境

- Android Studio（最新稳定版）+ Android SDK 34
- Android 真机（API 26+），开启开发者选项 + USB 调试
- 首次构建：用 Android Studio 打开本目录自动同步，或 `gradle wrapper && ./gradlew assembleDebug`

## M0 验证步骤（对应 AGENTS.md 的 go/no-go 清单）

1. **装机**：`./gradlew installDebug`，打开「外卖比价助手」。
2. **开权限**：App 内按钮 1（无障碍：找到本 App 并开启）、按钮 2（悬浮窗权限）。
3. **悬浮窗验证**：按钮 3，切到任意外卖 App，确认「比价助手运行中」卡片盖在页面上、可拖动。
4. **拉起验证（B）**：按钮 4，确认淘宝闪购被拉起且 Toast 显示「拉起成功」。
   - 另用 adb 实测 deep link 是否保留：
     ```bash
     adb shell am start -a android.intent.action.VIEW -d "eleme://" me.ele
     ```
5. **dump 验证（A/B 共用）**：手动打开淘宝闪购进一家店的菜单页，停留 3 秒以上，然后：
   ```bash
   adb pull /sdcard/Android/data/com.team.pricecompare/files/dumps/
   ```
   文件名形如 `me_ele_<时间戳>_p<价格节点数>.json`。**p 后面的数字 ≥5 即通过关键判定**——说明价格文本对无障碍可见。美团同理（包名 `com.meituan.takeout`）。
6. **fixtures 交接**：把有代表性的 dump 脱敏后按 `fixtures/README.md` 规范重命名，放入 `fixtures/` 交 C。

## go/no-go 判定

- 两个平台的 dump 都能读出店名 + ≥5 个「商品名+价格」→ **go**，进入 M1；
- 闪购（me.ele）读不出 → 验证淘宝 App 内闪购频道（`com.taobao.taobao`）；再不行 → 该平台降级手动录入；
- 拉起失败 → 检查按钮 1 的无障碍服务是否开启（落地检测依赖它）、目标 App 是否安装。

## 工程结构

```
app/src/main/java/com/team/pricecompare/
├── Models.kt                 # 三方数据契约（改动需三人同意）
├── MainActivity.kt           # M0 控制台（开权限/悬浮窗/拉起测试/状态）
├── accessibility/            # DumpAccessibilityService（dump+弹窗关闭）、NodeTree、TextExtractors
├── parsers/flash.kt          # 淘宝闪购解析器 spike（常量区在文件顶部）
├── launcher/AppLauncher.kt   # 拉起 + 落地检测（deep link 优先，包名兜底）
├── engine/data/              # Room 骨架（StoreSnapshot）
└── overlay/OverlayService.kt # 悬浮窗 Hello 卡片
fixtures/                     # 节点树 dump（脱敏后），跨模块对接物
```

## 单元测试

```bash
./gradlew testDebugUnitTest
```

覆盖 FlashParser 的商品名/价格提取与噪音过滤（纯 Kotlin，不需要真机）。

## 注意

- 解析器失败返回 null 是设计行为（优雅降级），不是 bug；
- dump 节流 3 秒是有意的（模拟真人节奏，合规红线）；
- 绝不点击下单/支付相关节点，采集到价格为止。
