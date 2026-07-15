## 这个目录是Android项目

### 如何在Android Studio中打开

1. 打开Android Studio（Koala或更高版本）
2. File → Open → 选择 `applan/android` 目录
3. 等待Gradle同步完成（首次可能需要下载依赖，几分钟）
4. 如果提示SDK路径不对，编辑 `local.properties` 设置正确的SDK路径：
   ```
   sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
   ```
5. 连接手机（开启USB调试），点击Run按钮（绿色三角形）即可安装运行

### 项目结构

```
android/
├── app/
│   ├── build.gradle.kts          # 应用依赖配置
│   └── src/main/
│       ├── AndroidManifest.xml   # 权限和组件声明
│       ├── java/com/applan/
│       │   ├── MainActivity.kt           # 入口Activity
│       │   ├── ApplanApp.kt              # Application
│       │   ├── network/
│       │   │   ├── ApplanClient.kt       # HTTP SSE客户端
│       │   │   └── ChatMessage.kt        # 数据模型
│       │   ├── service/
│       │   │   ├── KeepAliveService.kt   # 前台保活服务
│       │   │   ├── BootReceiver.kt       # 开机广播
│       │   │   └── LockAccessibilityService.kt # 无障碍锁屏
│       │   ├── ui/
│       │   │   ├── App.kt                # 主题+导航
│       │   │   ├── theme/Theme.kt        # Material3主题
│       │   │   ├── chat/ChatScreen.kt    # 聊天页面
│       │   │   ├── chat/ChatViewModel.kt # 聊天逻辑
│       │   │   └── settings/SettingsScreen.kt # 设置页面
│       │   └── util/
│       │       ├── PermissionHelper.kt   # 权限检查
│       │       └── AutoStartHelper.kt    # 厂商自启动跳转
│       └── res/                          # 资源文件
├── build.gradle.kts              # 项目级构建配置
├── settings.gradle.kts           # 模块设置
└── gradle.properties             # Gradle属性
```

### 注意事项

- minSdk = 24 (Android 7.0)，覆盖98%以上设备
- compileSdk = 35 (Android 15)
- 使用Jetpack Compose + Material3
- 不需要手动下载Gradle，Android Studio会自动处理
