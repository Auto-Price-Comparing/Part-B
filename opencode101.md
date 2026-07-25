# OpenCode 101

## 1. 什么是 OpenCode？如何安装？

**OpenCode** 是一个开源的 AI 编码代理（Coding Agent），提供终端界面（TUI）、桌面应用和 IDE 扩展等多种使用方式。它能理解你的项目结构，直接读写文件、执行命令，帮助你完成软件开发任务。

### 使用前提

- 一个 LLM 提供商的 API 密钥
- WSL 2 / Linux / MacOS 环境
 
### 安装

**推荐方式 — 安装脚本：**

```bash
curl -fsSL https://opencode.ai/install | bash
```

安装完成后，二进制位于 `~/.opencode/bin/opencode`，脚本会自动将其加入 PATH。

**其他方式：** npm - `npm install -g opencode-ai` 等.

## 2. Hello World：第一次对话

### 2.1 连接提供商

进入你的项目目录，启动 OpenCode：

```bash
cd /path/to/your/project
opencode
```

在 TUI 中运行 `/connect` 命令，选择一个提供商（以 Deepseek 为例）：

```
/connect
```

然后输入 API key, API 密钥存储在 `~/.local/share/opencode/auth.json` 中。

### 2.2 选择模型

```
/models
```

从列表中选择供应商对应模型，例如 `DeepSeek V4 Pro`。

### 2.3 第一个提示词

```
Please explain the project structure to me.
```

之后就可以通过 Prompt 进行对话了。

## 3. More than Hello World: 深入提示词

### 3.1 用 `@` 追加文件上下文

输入 `@` 可以模糊搜索项目中的文件，将其作为上下文附加到提示词中：

```
How is authentication handled in @packages/functions/src/api/index.cpp
```

### 3.2 切换提供商与模型

可以通过 `/models` 在已经 `/connect` 的供应商的模型中切换。

```
/connect → DeepSeek  → /models 可选: V4 Pro, Flash...
         → Anthropic → /models 可选: Sonnet, Opus, Haiku...
         → OpenAI    → /models 可选: GPT-5.6, GPT-5.5...
```

### 3.3 两种工作模式

按 **Tab** 键在两种代理模式之间切换：

| 模式 | 特点 |
|------|------|
| **Build** | 拥有全部工具（读/写/编辑/bash），直接执行修改 |
| **Plan** | 只读 + 只分析，不修改任何文件，用于制定方案 |

**典型工作流：**

1. Tab 切换到 **Plan** 模式 → 描述需求 → OpenCode 给出方案。之后审查、反馈、迭代方案。
1. Tab 切换回 **Build** 模式 → 进行具体修改。

### 3.4 AGENTS.md

`AGENTS.md` 是 OpenCode 理解项目的核心文件。你可以手动编辑它，也可以使用 `/init` 让代理自动生成：

```markdown
# Project: My App

- Target: XXXXX
- Rules: XXXXX
- Testing: XXXXX
- Workflow: XXXXX
```

每次对话 OpenCode 都会读取此文件作为系统级指令。

### 3.5 退出与恢复

- `Ctrl+C` 或 `/exit` — 退出当前会话
- `opencode -c` — 继续上一次会话
- `opencode -s <session-id>` — 继续指定会话（ID 从 `session list` 获取）
- `opencode -s <id> --fork` — 派生指定会话，在此基础上新建分支
- `opencode session list` — 列出所有历史会话及 ID
- `opencode session list -n 5` — 仅列出最近 5 个会话
- `/undo` — 撤销最后一次修改（可多次执行）
- `/redo` — 重做被撤销的修改

## 4. 并发

OpenCode 通过 **子代理 (Subagent)** 实现并行任务执行。

### 4.1 机制

主代理使用 `Task` 工具同时启动多个子代理，每个子代理在独立子会话中运行，处理一个子任务。子代理会阻塞主代理的运行。

如果想实现 fire-and-forget 式的并行，需要用 `opencode` 开启另一个独立的 session.

### 4.2 内置子代理

| 子代理 | 用途 | 工具 |
|--------|------|------|
| **General** | 通用研究 & 复杂任务 | 完整（可读写） |
| **Explore** | 快速探索代码库 | 只读 |
| **Scout** | 外部文档 & 依赖研究 | 只读 |

### 4.3 使用方式

**提示词驱动：**

```
Please do the following in parallel:
1. Search all auth-related logic in src/
2. Find every try/catch block and list errors handled
3. Check API version strings across all route files
```

主代理会自行决定是否使用 Task 工具并行调用多个子代理。

**手动 @ 调用：**

```
@explore Find all places where we use magic numbers.
```

## 5. 自定义配置：`opencode.json`

在项目根目录创建 `opencode.json`，用于定制 OpenCode 在该项目中的行为。以下是一个带注释的完整示例，展示了部分可自定义的维度：

```jsonc
{
  "$schema": "https://opencode.ai/config.json",

  // ── 1. 模型：用哪个 LLM ──
  "model": "deepseek/deepseek-v4-pro",            // 主模型，处理日常编码
  "small_model": "deepseek/deepseek-v4-flash",    // 轻量模型，用于标题生成等琐碎任务

  // ── 2. 提供商：连接到 LLM 服务 ──
  "provider": {
    "deepseek": {
      "options": {
        "apiKey": "{env:DEEPSEEK_API_KEY}",       // 从环境变量读取密钥/明文
        "timeout": 300000                         // 请求超时（毫秒）
      }
    }
  },

  // ── 3. 工具开关：OpenCode 能观测到哪些工具 ──
  "tools": {
    "write": true,    // 创建新文件
    "edit": true,     // 修改文件
    "bash": true,     // 执行终端命令
    "webfetch": true  // 访问网络
  },

  // ── 4. 权限：哪些操作需要你确认 ──
  //     "allow"=自动放行 / "ask"=需要确认 / "deny"=禁止
  "permission": {
    "edit": "ask",
    "bash": {
      "*": "ask",              // 所有命令默认需确认
      "git status": "allow",   // git status 自动放行
      "git diff": "allow"      // git diff 自动放行
    }
  },

  // ── 5. 自定义代理：不同场景用不同模型+提示词 ──
  "agent": {
    "code-reviewer": {
      "description": "Code review to detect potential problems",
      "mode": "subagent",
      "model": "anthropic/claude-sonnet-4-5",
      "prompt": "You are a code reviewer. Focus on security, performance, maintainability.",
      "tools": { "write": false, "edit": false }
    }
  },

  // ── 6. 指令文件：追加项目规范 ──
  "instructions": ["docs/coding-style.md"],
}
```

**多层配置**：OpenCode 同时也支持全局配置（`~/.config/opencode/opencode.json`）和环境变量覆盖（`OPENCODE_CONFIG`）等多层配置。具体可见[文档](https://opencode.ai/docs)。
