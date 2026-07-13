# LockAI 部署指南

## 一、服务器部署（腾讯云）

### 1.1 准备工作
- 腾讯云轻量服务器（2C2G Ubuntu 22.04 足够）
- 一个域名（解析到服务器IP）

### 1.2 安装 Hermes Agent

```bash
# SSH到服务器后执行
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- --skip-browser
```

### 1.3 配置 DeepSeek API

编辑 `~/.hermes/.env`：
```bash
OPENAI_API_KEY=sk-your-deepseek-key
OPENAI_BASE_URL=https://api.deepseek.com
MODEL=deepseek-chat
API_SERVER_ENABLED=true
API_SERVER_HOST=127.0.0.1
API_SERVER_PORT=8787
API_SERVER_KEY=your-random-secret-key
```

### 1.4 上传人格和Skill配置

将本项目 `server/` 目录下的文件上传到服务器：
- `SOUL.md` → `~/.hermes/SOUL.md`
- `dbs-skill/SKILL.md` → `~/.hermes/skills/dbs/SKILL.md`

### 1.5 安装 Caddy（HTTPS反代）

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

上传 `server/Caddyfile` 到 `/etc/caddy/Caddyfile`，将域名替换为你的域名：
```
your-domain.com {
    reverse_proxy 127.0.0.1:8787
}
```

```bash
sudo systemctl restart caddy
```

### 1.6 启动Hermes服务

```bash
sudo hermes gateway install --system
sudo systemctl enable hermes
sudo systemctl start hermes
```

验证：`curl https://your-domain.com/v1/models -H "Authorization: Bearer your-secret-key"`

---

## 二、Android端构建

### 2.1 配置服务器地址

**不需要在APP内设置！** 直接在构建配置中写死：

编辑 `android/app/build.gradle.kts`，修改 defaultConfig 中的：
```kotlin
buildConfigField("String", "SERVER_URL", "\"https://your-domain.com\"")
buildConfigField("String", "API_KEY", "\"your-random-secret-key\"")
```

### 2.2 用Android Studio打开

1. 打开 Android Studio（Koala或更高版本）
2. File → Open → 选择 `lockai/android` 目录
3. 等待Gradle Sync完成
4. 如果提示SDK路径，创建 `local.properties`：
   ```properties
   sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
   ```
5. 手机开启USB调试连接，点Run ▶️

### 2.3 首次使用

1. 打开APP后会请求通知权限 → 允许
2. 点击右上角 ⚙️ 进入设置
3. 逐个打开开关，跳转到对应设置页开启权限：
   - **自启动**：跳转到厂商自启动管理页，手动开启
   - **后台运行**：跳转到电池优化，将LockAI加入白名单
   - **锁屏服务**：跳转到无障碍设置，开启LockAI服务
   - **通知**：跳转到通知设置，确保通知开启
4. （推荐）点击"设为默认桌面"将LockAI设为默认Launcher
5. 返回聊天页，测试AI对话

---

## 三、权限说明

| 权限 | 用途 | 是否必须 |
|------|------|---------|
| INTERNET | 连接Hermes Agent API | 必须 |
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
