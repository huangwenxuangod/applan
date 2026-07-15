# applan 部署指南

## 一、后端部署（applan Proxy，Docker）

后端是一个 OpenAI 兼容代理（`server/proxy_backend.py`，FastAPI + httpx），做三件事：
- 注入 `SOUL.md` 人格（五层漏斗守门人）
- 把 `applan.md`（历史漏洞记忆）注入 system，让模型能说"你第N次说这个了"
- 逐字节透传 DeepSeek 的 SSE 流与 `tools`（lock_screen / exit_app），100% 兼容 App 的解析器

### 1.1 前置
- 一台能装 Docker 的 Linux 服务器（2C2G 起步，Ubuntu 22.04 推荐）
- 一个 DeepSeek API Key（https://platform.deepseek.com）

### 1.2 上传 server/ 目录
把本项目 `server/` 整个目录传到服务器，例如 `~/appplan-server/`。

### 1.3 配置环境变量
```bash
cd ~/appplan-server
cp .env.example .env
# 编辑 .env，填入 DEEPSEEK_API_KEY=sk-xxx，其余用默认值即可
```

### 1.4 启动（Docker Compose）
```bash
docker compose up -d --build
```
容器内监听 8787，宿主映射到 8799。

### 1.5 反代（Caddy，可选但推荐）
`/etc/caddy/Caddyfile` 追加：
```
:8787 {
    reverse_proxy 127.0.0.1:8799
}
```
```bash
sudo systemctl reload caddy
```

### 1.6 验证
```bash
# 模型列表
curl http://localhost:8787/v1/models

# 带工具测一次锁屏（应返回 tool_calls: lock_screen）
curl http://<服务器IP>:8787/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"我就看一眼"}],"tools":[{"type":"function","function":{"name":"lock_screen","description":"立即锁屏","parameters":{"type":"object","properties":{}}}}],"tool_choice":"auto","stream":false}'
```

---

## 二、Android 端构建

### 2.1 配置服务器地址
编辑 `android/app/build.gradle.kts`：
```kotlin
buildConfigField("String", "SERVER_URL", "\"https://你的域名或IP:8787\"")
buildConfigField("String", "API_KEY", "\"\"")   // 代理默认免鉴权
```

### 2.2 用 Android Studio 打开
1. Android Studio（Koala 或更高）→ File → Open → 选择 `android/` 目录
2. 等待 Gradle Sync 完成
3. 手机开启 USB 调试，点 Run ▶️
（如需 Release 包：签名已配置为 `applan-release.jks`，`build.gradle.kts` 中 alias=`applan`）

### 2.3 首次使用
1. 打开 App 请求通知权限 → 允许
2. 右上角 ⚙️ 进入设置，逐项开启：自启动 / 后台运行（电池白名单）/ 锁屏服务（无障碍）/ 通知
3. （推荐）点击"设为默认桌面"将 applan 设为默认 Launcher
4. 返回聊天页，测试 AI 对话与锁屏

---

## 三、权限说明

| 权限 | 用途 | 是否必须 |
|------|------|---------|
| INTERNET | 连接 applan 后端 API | 必须 |
| FOREGROUND_SERVICE | 保持后台服务运行 | 必须 |
| RECEIVE_BOOT_COMPLETED | 开机自启动 | 必须 |
| POST_NOTIFICATIONS | 显示前台服务通知(Android 13+) | 必须 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 防止系统杀后台 | 必须 |
| BIND_ACCESSIBILITY_SERVICE | 执行锁屏操作 | 必须（锁屏功能） |
| SYSTEM_ALERT_WINDOW | 后台弹出界面 | 推荐 |
| USE_FULL_SCREEN_INTENT | 锁屏上显示通知 | 推荐 |
| CATEGORY_HOME | 作为默认桌面 | 可选（最稳定） |

---

## 四、应用上架注意事项

### Google Play
- 无障碍服务(AccessibilityService)审核极严，必须声明是辅助残障用户
- 纯锁屏/工具类App使用无障碍权限大概率被拒
- 需要提供隐私政策URL、数据安全表单
- targetSdk 34+

### 国内应用商店
- 需要：工信部App备案、软件著作权、隐私政策
- 无障碍权限需在申请时同步弹窗说明用途
- 华为审核最严，权限申请必须有明确用途说明
- 小米/OPPO/vivo/应用宝相对宽松但需要合规材料
