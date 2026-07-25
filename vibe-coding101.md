# Vibe Coding 101

## Prompt Engineering: Tell LLM What You Want

LLM 每生成一个 token 都会在一个巨大的概率分布中采样，所以其输出具有不确定性。你说「帮我总结这篇文章」，它可以给你 100 字、500 字、段落、要点——这些都对，但只有一种是你想要的。因此在驱动 Agent coding 时，需要**在 Prompt 中约束 LLM 的行为**：最终程序的 API 接口是什么？内部如何实现这些逻辑？在不能一次成型的时候依照什么顺序完成？这类问题都要在 Prompt 中回答。更进一步，开始 Coding 之前首先要知道自己想要什么。

### BPT Example: API

用 B+ Tree 这个经典数据结构做例子。以下是 Prompt 中必须提前定好的接口与关键决策——你不说，LLM 就会猜，而猜的结果可能不是你要的：

```cpp
// ── 基础接口 ──
void insert(K key, V value);       // 插入键值对
V    lookup(K key);                // 查找 key 对应的 value，不存在则抛出异常
void delete(K key);                // 删除 key
```

**可能需要说明的 design decision**：

- 重复 key 的行为：覆盖旧值 / 报错 / 忽略 
- 空 key 查找：抛异常 / 返回 sentinel 值
- 范围扫描：是否提供 `scan(low, high)`

对比一下：
> ❌ `请实现一个 B+ Tree`
> 
> ✅ `实现 B+ Tree，insert(key, value) 覆盖旧值，lookup 不存在时抛异常，不提供 range scan，单线程，key 为 int，value 为 std::string`

Prompt 的差异，决定了 LLM 生成的 API 签名是否对得上后续的调用代码。

### BPT Example: Logic of Functions

有了正确接口，下一步是约束程序内部的行为路径。由于 BPT 过于经典模型一般不会在内部写一个块状链表，但是在自建项目中没法保证 LLM 不会自作主张。

**`lookup`**：从根开始 → 对内部节点的 `keys` 做二分查找，找到 key 应落入的区间，路由到对应子节点 → 递归到叶子 → 在叶子中二分找 key → 找到返回 value，未找到抛异常。

**`insert`**：根为空 → 创建叶子节点直接插入，返回。否则沿 key 路由到目标叶子 → 叶子未满（≤ 9 个 key）→ 二分定位位置插入，返回；叶子已满（已达 10 个 key）→ 插入后分裂：前 5 个 key 留在原叶子，后 5 个 key 移到新叶子，将中间 key 提升到父节点。如果父节点也满，向上递归分裂；如果根分裂，创建新根。

**`delete`**：路由到叶子 → 二分定位 key → 不存在则返回。存在则删除 → 如果叶子 key 数 ≥ ⌈10/2⌉ = 5，完成；如果 <5，尝试向兄弟节点借一个 key（兄弟 >5 则重分配）；兄弟也只有 5 个则与兄弟合并 → 父节点中对应分隔 key 被删除 → 向上递归。根合并后可能降低树高。

上述每个路径的分叉：如果已满 / 如果 key 数目小于一半 / 如果根为空就是**约束点**。Prompt 中写清楚每一个分支，LLM 就不会胡乱揣测。

### BPT Example: Type System

在 C++ 中，直接把数据结构定义写进 Prompt，LLM 就不会自己设计字段名、指针布局和节点判别方式：

```cpp
static constexpr int ORDER = 10;           // 最大 key 数量
static constexpr int MIN_KEYS = ORDER / 2; // 分裂/合并阈值 = 5

template <typename K, typename V>
struct InternalNode;

template <typename K, typename V>
struct LeafNode {
    std::vector<K> keys;                   // 有序，大小在 [MIN_KEYS, ORDER] 之间
    std::vector<V> values;                 // values[i] 对应 keys[i]
    LeafNode* next = nullptr;              // 兄弟叶子指针，支持顺序遍历
};

template <typename K, typename V>
struct InternalNode {
    std::vector<K> keys;                   // keys[i] = child[i+1] 子树的最小 key
    std::vector<Node<K,V>*> children;      // children 数量 = keys 数量 + 1
};

template <typename K, typename V>
struct BPlusTree {
    Node<K,V>* root = nullptr;             // 空树时 root 为 nullptr
};
```

在 Prompt 里给出节点结构和字段名，LLM 就不会把 `keys` 写成 `entries`、不会把 `next` 指针挂在 `InternalNode` 上。**类型定义是最基础的架构约束。**（你可能注意到这还缺一个 `Node` 类型，因为地方太小了写不下。）

**写好 prompt，就是缩窄模型的输出空间。**

## Context Engineering: Tell LLM What it Need

Prompt 中的约束不能解决“模型根本不知道”的问题，譬如试图使用 LLM 解决 “Norb 的探索时光”，但是只给出题面描述不给出下发文件中的地图生成算法。

**这就是上下文工程的领域：让模型有依据地说话，靠的不是更好的 prompt，而是把正确的资料在正确的时刻送到 LLM 眼前。**

### LLM Need Knowledge

没有上下文时，模型只能用训练数据中的统计规律填充。你问「X 购物网站上 iPhone 的退货政策」，它说「支持 30 天无理由退货，未开封，保留原包装」—— 听起来很靠谱，但可能实际政策是 14 天。模型给的只是「统计上最常见的退货政策」，不是你要的那一个。

解决办法的核心思路是把相关的资料作为上下文注入 prompt，譬如对应网站退货政策页面的 URL。

### LLM Need Necessary Knowledge

给资料有效，并不意味着可以将所有资料一股脑地全塞进 Context：

- **上下文窗口有上限**。就算是支持 1M 上下文的模型，用资料占据 50% 的窗口也令人难以接受。
- **成本随长度增长**。主流 API 按 token 计费，几千 token 和几十万 token 的成本可以差几十到几百倍。
- **信息越多，质量不一定越高**。当上下文堆满大量不直接相关的内容，真正有用的证据会被噪声稀释。

不要一次性给出所有的资料，而应只给出最相关的信息：三段高度相关的资料，比三十段模糊相关的内容好用得多。

### LLM Need Necessary Knowledge in Correct Place

选出合适的资料还不够，还要关心它被放在了什么位置。

Nelson Liu 等人在 2023 年的论文 *Lost in the Middle* 中发现了一个现象：给模型一段长上下文，把正确答案藏在不同位置——开头、中间、结尾。结果发现，答案放在开头或结尾时准确率明显更高；放在正中间时准确率显著下降。这就是**U 型注意力分布**——模型天然对头尾更敏感，中间的内容容易被忽略，所以我们可以得到几个 Tips：

1. **重排序**：把最相关的资料排到最前面或最后面，而不是埋在中间。
2. **关键事实前置**：在提示开头给出提醒，而不是让模型自行辨别“最重要的信息”。

以上只是从实践中归纳出的一些 Vibe Coding 注意事项，每个人都有自己与 LLM 互动的方式，各位可以大胆尝试，不断总结，找到最适合自己的 Vibe 方法。
