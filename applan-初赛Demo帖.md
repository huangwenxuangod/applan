# 【学习工作赛道】applan——AI注意力防线，解锁手机后的第一道守门人

## 0. 先和大家打个招呼吧 👋

我是深圳大学金融科技专业的黄文轩，目前是在一家跨境电商公司做AI业务流程提效。

我是一个被手机成瘾折磨了很久的人。试过 Forest、Opal、AppBlock、各种番茄钟和专注模式，全被我绕过了——划后台、卸载、进设置关权限、重启手机，我的大脑在"逃避控制"这件事上的创造力远超你的想象。

最让我崩溃的场景是深夜：躺床上说"就看一眼时间"，结果刷到凌晨两点。醒来才知道，发誓今天一定专注，然后第二天晚上历史重演。我意识到问题的本质是：**所有专注App都在"行为发生之后"才介入**，当你已经打开抖音刷了20分钟，它才提醒你——太晚了。我需要一个在"行为发生之前"就拦住我的东西，而且它必须比我更聪明，能识破我的借口。

用 TRAE 做 applan 的过程，就是一个"把自己当成最狡猾的用户来防"的过程。最开始我只说了一句"做一个安卓App，解锁手机时弹出AI审问界面，如果我是为了找借口想刷手机就锁屏"，TRAE 直接帮我做了完整的技术方案选型——用 AccessibilityService 而不是 DevicePolicyManager（后者锁了必须输密码体验极差）、前台服务+全屏通知做保活、ADB设为默认Launcher最可靠。我提需求它实现，"要能防左滑返回""上拉Home也要拦住""杀了后台要能自己拉起来""紧急密钥搞个轮播输入框""改名叫applan"——每一句说完代码就改好了。在这个过程中，我总是能发现我自己开发的app的漏洞，然后跟trae去说，有什么漏洞和问题，然后立刻让它改，它真的能改好，当然我也用了很多的开发者skill去辅助我开发，如waza skills

最让我觉得"原来还能这么狠"的瞬间是保活机制：我跟 TRAE 说"能对我多狠就多狠"，它就真的上了双服务互保、AlarmManager 重启、onTaskRemoved 立即拉起 MainActivity、DeviceAdminReceiver 防卸载、30秒巡检——我试着划掉后台，它0.5秒内就弹回来了，那种"你逃不掉"的感觉，正是我想要的。我懂我自己有多会找借口，TRAE 懂怎么把这些路全堵死。

---

## 1. Demo 简介

**是什么：** 一个 Android App，在你每次解锁手机后自动弹出，通过 AI 对话审核你的使用意图，只有真正有目的、可验证的使用才被放行，否则直接锁屏。

**面向谁：** 有自我提升意愿但执行力不足的人——备考学生、远程工作者、自由职业者、ADHD 倾向者、深夜刷手机停不下来的人。

**主要功能：**

**① AI 意图守门（五层消解漏斗）**
解锁手机后不是桌面，而是 AI 对话界面。你必须告诉 AI 你要做什么。AI 依次检查：这是语言陷阱吗？基于错误假设吗？逻辑有漏洞吗？事实可验证吗？信息充分吗？"我就查个东西"——拦住。"我要查 React useEffect 的依赖数组用法"——放行。通过则调用 exit_app 回到桌面自由使用；没过则调用 lock_screen 直接锁屏，不给你挣扎机会。

**② AppBlock 级全屏拦截**
无障碍服务（AccessibilityService）实时监听窗口变化，任何非白名单 App 出现在前台，500ms 内 applan 全屏覆盖回来。左滑返回、上拉 Home/Recents、打开其他 App——全部无效。全屏沉浸模式（IMMERSIVE_STICKY）隐藏状态栏和导航栏，onWindowFocusChanged 持续重进沉浸模式，让手势区域也被覆盖。

**③ Cactus 式双服务保活**
KeepAliveService + DaemonService 双前台服务互保，5 秒心跳检查对方是否存活，onDestroy 立即用 AlarmManager.setExactAndAllowWhileIdle 重启，onTaskRemoved 立即拉起 MainActivity，START_STICKY + excludeFromRecents（从最近任务列表隐藏，划不掉），30秒巡检在守护模式下自动拉起前台界面。

**④ 紧急密钥系统（轮播输入框）**
紧急情况下需要输入 64 位随机密钥（含大小写+数字+特殊符号）才能退出。UI 采用轮播字符槽位设计：当前位置的字符格放大高亮(1.3x)，两侧渐小渐隐，使用系统默认键盘输入，输入错误会抖动并重新生成密钥——故意设计得很痛苦，让你在输密钥的过程中冷静下来。

**⑤ 设备管理员防卸载**
启用 DeviceAdminReceiver 后，无法直接卸载 App，必须先去设置关闭设备管理员权限——多一道门槛，多一次犹豫的机会。

---

## 2. Demo 创作思路

**灵感来源：** 我自己就是手机成瘾的重度患者。手机就在手边，每次解锁都是一场意志力和多巴胺的战争，而意志力几乎每次都输。我发现所有现存方案的根本缺陷是：它们需要你"主动开启专注模式"，但在你最需要专注的时刻——深夜、疲惫、焦虑——你根本不会去开。我希望的是打开手机之前，就能知道我自己要的是什么，完成之后，自愿的离开手机，手机只是我的效率工具，而不是成为我的娱乐场所。

**想解决的问题：**

- **薄弱时刻无法启动防御**：人在意志力薄弱时不会主动打开专注App，需要一个"默认就在"的第一道防线
- **AI 要能识破借口**："我就看一眼""查个东西""回个消息"，普通黑名单无法区分真需求和假借口
- **绕过太容易**：划后台、卸载、关权限，用户有100种方式杀死专注App
- **一刀切逆反心理强**：完全禁止用手机不现实，有时候真的需要查资料，需要智能放行

**为什么做这个方向：** 把"锁屏"从一个主动行为变成被动默认——你不需要"开启专注模式"，因为 applan 就在那里，每次解锁都要面对它。AI 对话不是在"禁止"你，而是在帮你建立一个习惯：**在行动之前，先想清楚为什么。** 与其做又一个黑名单工具，不如做一个"意图觉察训练器"。

---

## 3. Demo 体验地址

**APK 下载地址：** （发布时填写你的APK下载链接，建议上传到GitHub Release或网盘）

> 注：applan 是 Android 原生应用，需要安装到手机上体验。首次安装需要按引导开启无障碍服务、电池白名单、自启动等权限（App内有一键引导）。服务器地址需配置为你的 applan 后端地址，或使用默认配置。

---

## 4. TRAE 实践过程

### 用 TRAE 完成 applan 开发的完整流程

#### 第一阶段：从零搭建 + 技术选型（1 轮对话）

我对 TRAE 说："做一个安卓端 kotlin+android 原生开发的App，专门面向安卓开发的软件，当打开手机的那一刻就用app必须获得自启动权限，然后弹出页面，显示出AI提问说你今天想要干嘛？如果我的回答是为了找借口、想刷手机，那就绝对不行，是我打开手机开始玩的第一道方向。ai设置的简单一点，后端部分需要接口这需求。"

TRAE 在一轮对话中完成了：

- 判断技术路线：用 Kotlin + Jetpack Compose 做Android原生开发，不用跨平台框架
- 确定核心机制：用 AccessibilityService 而非 DevicePolicyManager（不影响指纹/人脸解锁）
- 保活方案：前台服务+全屏通知做兜底，ADB设为默认Launcher最可靠（一行命令）
- 后端方案：Python FastAPI + SQLite，后续接 applan 后端（SSE流式对话+Function Calling）
- 搭建了完整的项目骨架：MainActivity、LockAccessibilityService、KeepAliveService、ChatViewModel、UI 组件
- 实现了 SSE 流式 AI 对话 + Function Calling（lock_screen / exit_app 两个工具）

这一步的产出：一个能跑起来的 MVP，解锁手机弹出 AI 对话，能识别意图并执行锁屏/放行。

![trae 1](此处上传trae 1.jpg)

#### 第二阶段：全屏拦截 + 手势屏蔽（1 轮对话）

MVP 的问题是：用户可以左滑返回、上拉 Home、打开其他 App，轻松绕过。

我对 TRAE 说："全面屏手势问题必须解决——从右向左滑就退出了不行，对话页绝对不允许。从底部拉出来能杀后台或退出来也不行。能对我多狠就多狠，像 AppBlock 那样弹出页面完全不能任何操作，左划右滑都出不去。"

TRAE 的处理：

- 实现了全屏沉浸模式（SYSTEM_UI_FLAG_IMMERSIVE_STICKY），隐藏状态栏+导航栏
- onWindowFocusChanged 持续重进沉浸模式，防止手势调出导航栏后永久可见
- AccessibilityService 监听 TYPE_WINDOW_STATE_CHANGED + TYPE_WINDOWS_CHANGED
- 500ms 防抖的 pullBackToApp()，检测到任何非白名单包名立即拉起
- 白名单机制：applan自身、系统 UI（状态栏/对话框）、设置宽限期（2分钟）
- 系统白名单：com.android.systemui、com.android.permissioncontroller、com.google.android.permissioncontroller

这一步的产出：左滑/上拉/打开任何 App 都会被 500ms 内拉回，AppBlock 级别的拦截强度。

#### 第三阶段：编译错误修复 + Debug（1 轮对话）

开发过程中遇到了 Kotlin 编译错误。我把错误截图发给 TRAE，让它分析修复。

TRAE 的处理：

- 精准定位根因：Kotlin 运算符优先级陷阱——Elvis 操作符(?:)优先级低于infix操作符to
- 用表格列出每个错误的文件、行号、根因、修复方案
- 修复了 App.kt:43 的解构声明类型不匹配问题
- 修复了多个类型推导问题
- 同步修复了跳转设置后 App 消失的核心问题（设置页返回自动拉回）
- 修复了 Toast 位置问题（改到屏幕顶部居中）
- 修复了网络连接错误的详细诊断提示

这一步的产出：BUILD SUCCESSFUL，产出可安装的 APK。

![trae 2](此处上传trae 2.jpg)

#### 第四阶段：保活增强 + 防卸载（多轮迭代）

我对 TRAE 说："杀了后台之后还能自动重新拉起来，不允许卸载。开机启动、忽略电池优化、在最近任务列表中掩藏、启用 Cactus 增强保活措施、智能省电场景优化。"

TRAE 的处理：

- 创建了 DaemonService 作为第二个前台服务，与 KeepAliveService 互相守护
- 15 秒心跳检查对方是否存活，不活就拉起来
- onTaskRemoved 中立即拉起 MainActivity + 重启自己 + AlarmManager 兜底（100ms）
- onDestroy 中用 AlarmManager.setExactAndAllowWhileIdle 安排 500ms 后重启
- 创建 AppDeviceAdminReceiver，启用设备管理员后无法直接卸载
- PermissionHelper 添加设备管理员权限引导
- BootReceiver 开机时启动双服务
- excludeFromRecents=true 从最近任务列表隐藏
- 30 秒巡检：守护模式下 App 不在前台则自动拉起

这一步的产出：双服务 Cactus 保活，划后台0.5秒内弹回，防卸载机制就位。

#### 第五阶段：紧急密钥 UI 重设计（1 轮对话）

我给 TRAE 看了一张背单词 App 的轮播输入设计图，说："密钥解锁用系统默认键盘，中间一块输入框，左右渐出，一个格子只输入一个字符。"

TRAE 的处理：

- 重写 EmergencyUnlockScreen：隐藏的 BasicTextField 接收系统键盘输入
- 8 个字符槽位横向排列，当前位置放大高亮(1.3x/52dp)，两侧按距离渐小渐隐
- 距离 0：scale 1.3 / alpha 1.0 / 主色边框
- 距离 1：scale 0.9 / alpha 0.7
- 距离 2：scale 0.75 / alpha 0.45
- 距离 3+：scale 0.6 / alpha 0.25
- 输入错误时整行抖动动画，清空输入并重新生成密钥
- 密钥长度从 64 位改为 8 位方便测试（正式版可改回）
- 目标密钥卡片支持显示/隐藏/重生成

这一步的产出：优雅的轮播输入式紧急密钥界面，系统键盘自动弹出，输入完8位自动验证。

![trae 3](此处上传trae 3.jpg)

#### 第六阶段：崩溃修复 + 改名 applan（1 轮对话）

我给 TRAE 看了三个崩溃日志，让它全部修复，并改名 applan。

TRAE 的处理：

- **崩溃1（SP credential storage）**：attachBaseContext 中切换到 DeviceProtectedStorage，锁屏状态下也能访问 SharedPreferences
- **崩溃2（registerReceiver SecurityException）**：当前版本已移除所有动态 registerReceiver，规避 Android 13+ 的 RECEIVER_NOT_EXPORTED 强制要求
- **崩溃3（MainActivity NPE）**：setupFullscreenImmersive 从 onCreate 移到 onAttachedToWindow，全链路 try-catch + `window?:return` 空判断
- **网络优化**：connectTimeout 15s→30s，readTimeout 120s→300s（SSE长连接5分钟），pingInterval 30s 检测死连接，retryOnConnectionFailure(true)，网络预检查，详细错误诊断
- **改名**：所有用户可见文字从 LockAI 统一改为 applan（app_name、通知标题、聊天标题、引导页、设置页）

这一步的产出：v1.2.0 稳定版，三个崩溃全部修复，改名完成，BUILD SUCCESSFUL。

---

### 附开发关键步骤截图

（发帖时把 trae 1.jpg、trae 2.jpg、trae 3.jpg 上传到此处）

### 附关键任务对话的 Session ID

1. `68588352844156:da506ce646dd23fc251d59fac9796238_6a5392de371e6179e6c9763e.6a565690f2210804c8bc917a.6a565690f2210804c8bc9179` (2026/7/14 23:33:53) — 编译修复 + 多bug修复阶段
2. `68588352844156:da506ce646dd23fc251d59fac9796238_6a5392de371e6179e6c9763e.6a5655b3f2210804c8bc9141.6a5655b3f2210804c8bc9140` (2026/7/14 23:29:20) — 保活增强+防卸载阶段
3. `68588352844156:da506ce646dd23fc251d59fac9796238_6a5392de371e6179e6c9763e.6a56538ef2210804c8bc90ae.6a56538ef2210804c8bc90ac` (2026/7/14 23:19:42) — 全屏拦截+手势屏蔽阶段

### 5. 对应的报名审核通过的帖子链接

（报名审核通过后，把报名帖的链接粘贴到这里）
