# CrossDesk 安卓被控模式（Host Mode）

在 CrossDesk 安卓主控端的基础上，增加**安卓被控**能力：安卓设备（工业平板、商用平板、手机）
可以被其他 CrossDesk 客户端（桌面版 / 安卓版）远程观看和控制。

- 连接方式：与桌面被控完全一致——经**信令/中继服务器**用「设备 ID + 连接密码」连接，
  **不使用局域网直连**。
- 抓屏：MediaProjection（前台服务）。
- 输入注入：**无障碍服务（免 Root、免 ADB）**——点击、滑动、拖拽、滚动、返回/主页、
  方向键移焦，以及向焦点输入框填入文字。
- 保活：前台服务 + WakeLock；开机自启恢复信令与被控待命状态。

## 架构

```
主控端 ──ID+密码──▶ 信令/中继服务器 ──▶ HostService（信令会话 + host peer 登录）
                                              │
                    MediaProjection 抓屏 ──▶ HostCaptureService ──RGBA 帧──▶ native host peer
                                              │                              (NV12 转换后 MiniRtcSendVideoFrame)
                    无障碍注入 ◀── HostInputService ◀── RemoteAction JSON ◀── native host peer
```

native 层（`apps/android/native/rtc_bridge.cpp`）在原有 identity/controller 两个 peer 之外
新增第三个 **host peer**：以 `deviceId@hostPassword` 登录，注册与桌面被控相同的流
（Display1 视频、control_audio 音频、mouse/keyboard/control_data/clipboard/file 数据流），
连接建立后主动发送 `MakeHostInformation(platform=Android)`，并把收到的鼠标/键盘/文本
RemoteAction 转发到 Java 层执行。实现方式与官方 `apps/harmony/tests/mobile_synthetic_host.cpp`
测试件一致。

## 使用步骤（被控平板）

1. 安装 APK，打开桌面上的「CrossDesk 被控」图标（HostActivity）。
2. 设置连接密码（或随机生成）和设备名称，点「开启被控」：
   - 允许前台服务通知；
   - 在系统弹窗中授权投屏（整个屏幕）。
3. 点「打开无障碍设置」，开启「CrossDesk 被控」无障碍服务。
4. （无人值守建议）点「忽略电池优化」，并在系统设置中允许应用自启动。
5. 界面会显示本机 ID。主控端输入该 ID + 连接密码即可连接控制。

重启后：被控服务自动恢复待命（信令 + host peer），但 **Android 14+ 要求每次进程重启后
重新授权一次投屏**（平台规则，无法绕过）；在授权前主控可连接但画面为黑屏，
点通知进入被控页面点「开启被控/重新授权投屏」即可。无障碍服务重启不失效。

## 已知限制（v1）

- 音频流已注册但暂不发送声音（静音）。
- 文件传输到被控端暂未接通（流已注册，数据被忽略）。
- 键盘输入走无障碍文本注入：可向焦点输入框追加文字、退格、回车（IME 动作）；
  组合键、Delete、游戏/自绘界面内的按键无法模拟。主控端的输入法候选词文本
  （text_input）可正常上屏。
- 鼠标按下-移动-松开会被重放为一条无障碍手势轨迹，悬停（hover）无对应能力。
- 设备信任（DeviceTrust）挑战在 v1 未做校验，接入控制依赖信令服务器的 ID + 密码校验。
- host peer 与 presence 会话是两个独立登录；若服务器对同一设备 ID 的并发登录有限制，
  开启被控时主界面（MainActivity）的 presence 会话会被让行（已在 ensureSignaling 中处理）。

## 改动文件

- `apps/android/native/rtc_bridge.cpp`：host peer、JNI `nHostStart/nHostStop/nHostFrame/nHostClipboard`、事件 20–25。
- `apps/android/app/src/main/java/cn/crossdesk/mobile/NativeSession.java`：host 事件分发与封装方法。
- 新增 `HostService.java`（信令 + host 生命周期）、`HostCaptureService.java`（MediaProjection 抓屏）、
  `HostInputService.java`（无障碍注入）、`HostActivity.java`（被控面板）、`BootReceiver.java`（开机自启）。
- `AndroidManifest.xml`、`res/xml/host_accessibility_config.xml`、`res/values/strings.xml`、
  `MainActivity.java`（host 模式下让行 presence 会话）。
