# 🔒 AppPlan (原LockAI)

> AI注意力防线：解锁手机后的第一道防线

AppPlan 是一个 Android 应用，在你每次解锁手机后弹出，通过 AI 对话审核你的使用意图。只有当你能清晰、具体、可验证地说出要做什么时才放行，否则直接锁屏——帮你对抗无意识刷手机的冲动。

## ✨ 核心特性

- **🤖 AI 意图审核**：基于 DeepSeek 的 LLM，通过五层消解漏斗（语言陷阱→错误假设→逻辑错误→事实验证→信息充分）判断你是否真的有正事要做
- **🛡️ AppBlock式全屏拦截**：AccessibilityService 实时监听，任何非白名单App出现500ms内被覆盖回来
- **🔐 紧急密钥系统**：紧急退出需输入8位随机密钥（含大小写+数字+特殊符号），故意设计得很痛苦
- **⚡ Cactus双服务保活**：KeepAliveService + DaemonService 互保，onDestroy/onTaskRemoved 立即重启
- **🚫 设备管理员防卸载**：启用后无法直接卸载，多一道犹豫门槛
- **🏠 默认Launcher模式**：设为默认桌面，Home键直接回到AppPlan
- **🔋 全权限引导**：电池白名单、自启动、悬浮窗、通知——一键引导配置
- **🎨 Material Design 3**：Compose 构建，深色主题，沉浸模式

## 🏗️ 技术架构

```
┌─────────────────────────────────────────────┐
│              MainActivity                    │
│  (全屏沉浸式 / FLAG_SHOW_WHEN_LOCKED)        │
│  ┌───────────────────────────────────────┐  │
│  │         Jetpack Compose UI            │  │
│  │  ┌─────────┐ ┌──────────┐ ┌────────┐ │  │
│  │  │ChatScreen│ │Permission│ │Emergency│ │  │
│  │  │ (AI对话) │ │ Guide   │ │Unlock  │ │  │
│  │  └─────────┘ └──────────┘ └────────┘ │  │
│  └───────────────────────────────────────┘  │
├─────────────────────────────────────────────┤
│         LockAccessibilityService            │
│  (监听窗口变化 → 非白名单App → 拉回AppPlan)  │
├─────────────────────────────────────────────┤
│  KeepAliveService  ←→  DaemonService        │
│  (双前台服务互保 / AlarmManager重启)         │
├─────────────────────────────────────────────┤
│  BootReceiver / AppDeviceAdminReceiver      │
│  (开机自启 / 防卸载)                         │
├─────────────────────────────────────────────┤
│           HermesClient (SSE)                │
│  ←→ Hermes Agent (DeepSeek v4 Flash)       │
│  Function Calling: lock_screen / exit_app   │
└─────────────────────────────────────────────┘
```

## 🔑 AI工具定义

| 工具 | 作用 | 调用时机 |
|------|------|---------|
| `lock_screen` | 立即锁屏 | 用户意图不纯/找借口/逃避/连续3次试图绕过 |
| `exit_app` | 放行用户 | 通过五层消解漏斗检查，意图具体可验证 |

## 📁 项目结构

```
android/app/src/main/java/com/lockai/
├── MainActivity.kt              # 全屏沉浸Activity
├── LockAiApp.kt                 # Application (DeviceProtectedStorage)
├── network/
│   ├── HermesClient.kt          # SSE流式AI对话
│   ├── ChatMessage.kt           # 消息模型
│   └── StreamEvent.kt           # 流式事件
├── service/
│   ├── KeepAliveService.kt      # 前台保活服务
│   ├── DaemonService.kt         # 守护服务（双服务互保）
│   ├── LockAccessibilityService.kt  # 无障碍拦截服务
│   ├── BootReceiver.kt          # 开机自启
│   └── AppDeviceAdminReceiver.kt    # 设备管理员
├── ui/
│   ├── App.kt                   # 主Compose App
│   ├── chat/                    # AI对话界面
│   ├── emergency/               # 紧急密钥解锁（轮播输入框）
│   ├── onboarding/              # 权限引导
│   └── settings/                # 设置页面
└── util/
    ├── AppState.kt              # 全局状态机
    ├── EmergencyKeyGenerator.kt # 紧急密钥生成
    ├── PermissionHelper.kt      # 权限工具
    ├── CrashHandler.kt          # 崩溃捕获
    └── AppUpdateManager.kt      # 自更新
```

## 🔧 编译构建

```bash
cd android
./gradlew assembleRelease
# APK输出: android/app/build/outputs/apk/release/app-release.apk
```

## ⚙️ Hermes Agent 配置

AppPlan 通过 SSE 连接到 Hermes Agent（默认 `http://<服务器IP>:8787`）。需要在 Hermes 端：

1. 启动 Hermes Agent，确保监听 `0.0.0.0:8787`
2. 在 AppPlan 设置页面配置服务器地址和 API Key
3. 确保手机和服务器在同一网络（或服务器有公网IP）

**测试服务器是否正常：**
```bash
# 在服务器上测试
curl http://localhost:8787/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"你好"}],"stream":false}'

# 从手机测试（替换为服务器IP）
curl http://<服务器IP>:8787/v1/models
```

## 🛡️ 保活机制

| 层级 | 机制 | 说明 |
|------|------|------|
| 1 | 双前台服务 | KeepAliveService + DaemonService，各有独立Notification |
| 2 | 互保心跳 | 每15秒互相检查，对方死了立即拉起 |
| 3 | onTaskRemoved | 从最近任务划掉后立即重启MainActivity+自己 |
| 4 | onDestroy | AlarmManager.setExactAndAllowWhileIdle 300ms后重启 |
| 5 | START_STICKY | 系统内存回收后尝试重启服务 |
| 6 | BootReceiver | 开机+解锁后自动启动双服务 |
| 7 | 30秒巡检 | 守护模式下如果App不在前台，自动拉起MainActivity |
| 8 | excludeFromRecents | 从最近任务列表隐藏，无法划掉 |
| 9 | 设备管理员 | 启用后无法直接卸载App |
| 10 | 电池白名单 | 引导用户加入，防止Doze模式杀死 |

## 🔒 拦截机制

- 全屏沉浸模式（IMMERSIVE_STICKY）：隐藏状态栏+导航栏
- onWindowFocusChanged：重新进入沉浸模式，防止手势调出导航栏
- AccessibilityService：监听TYPE_WINDOW_STATE_CHANGED，任何非白名单包名→立即拉回
- 白名单：LockAI自身、系统UI、设置宽限期（2分钟）
- 状态机：默认守护模式 → AI放行/密钥放行 → 下次锁屏重置

## 📄 License

MIT
