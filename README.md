# Mobile Agent App

纯 Android App，直接调用多模态大模型 API，无需服务端。
装个 APK 就能用。

## 架构

```
手机 App（一切在手机上）
│
├── LlmClient           ← 直接调 GPT-4o / DeepSeek / Qwen API
├── AgentEngine          ← 核心循环：截图→LLM→执行→验证
├── AccessibilityService ← 点击、滑动、输入（无障碍服务）
├── ScreenCaptureService ← MediaProjection 截图
├── MemoryEngine         ← 本地记忆（AppAgentX 链式记忆）
└── UI（Jetpack Compose） ← 指令输入 + 状态展示 + 安全确认
```

## 快速开始

### 1. 用 Android Studio 打开

```
File → Open → 选择 android/ 目录
```

### 2. 配置 API Key

打开 App → 设置 → 填入你的 API Key

支持：
- **OpenAI**: `gpt-4o` / `gpt-4o-mini`
- **DeepSeek**: `deepseek-chat`（便宜，中文好）
- **阿里云百炼**: `qwen-vl-max`（国产合规）

### 3. 开启无障碍服务

首次打开 App 会引导你去设置里开启。
需要开启「手机助手」的无障碍权限。

### 4. 开始使用

输入「打开微信」→ 点执行 → 看手机自己动。

## 项目结构

```
android/
├── app/src/main/java/com/agent/app/
│   ├── AgentApp.kt              # Application 入口
│   ├── api/
│   │   └── LlmClient.kt         # LLM API 客户端（核心）
│   ├── service/
│   │   ├── AgentAccessibilityService.kt  # 无障碍服务（执行操作）
│   │   ├── ScreenCaptureService.kt       # 截图服务
│   │   └── AgentEngine.kt                # Agent 执行引擎
│   ├── data/
│   │   └── MemoryEngine.kt               # 本地记忆引擎
│   └── ui/
│       ├── MainActivity.kt      # 主界面
│       ├── SettingsActivity.kt   # 设置界面
│       └── ConfirmActivity.kt    # 安全确认界面
├── build.gradle.kts
└── settings.gradle.kts
```

## 功能

- [x] 直接调多模态模型 API（不需要服务端）
- [x] 截图 + UI 树分析
- [x] 点击、滑动、输入等操作
- [x] 安全确认弹窗（高危操作拦截）
- [x] AppAgentX 链式记忆（越用越快）
- [x] 已安装应用列表（打开任意应用 / 让 Agent 在应用内执行任务）
- [ ] 悬浮球快捷操作
- [ ] 技能录制（用户演示一次就学会）

## 打开应用并执行任务（领克/微信场景）

主界面点「📱 打开应用…」→ 搜索并选择应用（如领克、微信）→ 输入任务描述 → 自动完成。

示例任务链：
1. 选择「领克」→ 输入「找到一篇文章分享到微信，然后在微信里确认发送」
2. App 自动打开领克 → Agent 循环执行（截图→LLM→点击）：浏览文章 → 点分享 → 系统分享面板选微信 → 微信对话框点发送
3. 涉及支付/转账/发送等关键词时会弹安全确认，需手动批准

底层是 AgentEngine 新增的 `open_app` 动作（通过包名启动应用），LLM 在应用切换场景下也能自行调用。

## 费用参考

| 模型 | 每次任务 | 每天 50 次 | 每月 |
|------|---------|-----------|------|
| GPT-4o | ~$0.05 | ~$2.5 | ~$75 |
| DeepSeek | ~$0.005 | ~$0.25 | ~$7.5 |
| GPT-4o-mini | ~$0.01 | ~$0.5 | ~$15 |

记忆系统会让你越用越省（重复任务不走 LLM，省 60-80%）。

## 隐私

- 截图仅发送到 LLM API，不做其他用途
- 敏感信息（密码、验证码）自动脱敏后上传
- API Key 只存储在本地 SharedPreferences
- 所有数据不上传任何第三方服务器（除了你指定的 LLM API）
