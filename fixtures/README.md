# fixtures 目录规范

真实节点树 dump 的 JSON，是采集层（A/B）与逻辑/展示层（C）并行开发的对接物。

## 命名

`{platform}_{pageType}_{seq}.json`，例如：

- `flash_store_01.json` — 淘宝闪购店铺菜单页
- `flash_popup_01.json` — 带弹窗的页面
- `meituan_store_01.json` — 美团店铺菜单页

`flash_store_sample.json` 是手工构造的合成样例（非真实 dump），仅用于说明格式。

## 来源

真机开启无障碍服务后，App 会自动把节点树 dump 到手机：

```
adb pull /sdcard/Android/data/com.team.pricecompare/files/dumps/ ./fixtures_raw/
```

挑选有代表性的 dump，**脱敏后**重命名移入本目录。

## 脱敏要求（红线）

移入本目录前必须删除/替换：定位地址、手机号、账号昵称、头像 URL、订单号等个人信息。
只保留页面结构、店名、商品名、价格、满减文案。

## 格式

```json
{
  "text": "节点文本",
  "class": "android.widget.TextView",
  "id": "resource-id（可能为空）",
  "bounds": "left,top,right,bottom",
  "children": [ ... ]
}
```

由 `SimpleNode.toJson()` 生成，`SimpleNode.fromJson()` 还原，解析器测试直接消费。
