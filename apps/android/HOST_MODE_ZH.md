# CrossDesk 安卓被控模式（Host Mode）

在 CrossDesk 安卓主控端的基础上，增加**安卓被控**能力：安卓设备（工业平板、商用平板、手机）
可以被其他 CrossDesk 客户端（桌面版 / 安卓版）远程观看和控制。

- 连接方式：与桌面被控完全一致——经**信令/中继服务器**用「设备 ID + 连接密码」连接，
  **不使用局域网直连**。
- 抓屏：MediaProjection（前台服务）。
- 输入注入：**无障碍服务（免 Root、免 ADB）**——点击、滑动、拖拽、滚动、返回/主页、
  方向键移焦，以及向焦点输入框填入文字。
- 文件传输：与桌面被控共用官方协议与引擎（文件浏览 + 分块传输 + ACK 流控）。
- 声音：系统声音经 AudioPlaybackCapture 送主控；可开麦克风与主控语音通话，
  主控麦克风声音在本机播放。
- 保活：前台服务 + WakeLock + START_STICKY + 闹钟看门狗 + 无障碍锚点复活 + 开机自启。

## 架构

```
主控端 ──ID+密码──▶ 信令/中继服务器 ──▶ HostService（信令会话 + host peer 登录）
                                              │
                    MediaProjection 抓屏 ──▶ HostCaptureService ──RGBA 帧──▶ native host peer
                                              │                              (NV12 转换后 MiniRtcSendVideoFrame)
                    无障碍注入 ◀── HostInputService ◀── RemoteAction JSON ◀── native host peer
                    文件浏览/传输 ◀── FileBrowser + file_transfer::Session（native，与桌面共用）
                    系统声音 ◀── HostCaptureService(AudioPlaybackCapture) ──PCM──▶ native host peer
                    语音通话 ◀── HostAudio(麦克风) / AudioPlayer(播放主控麦克风)
```

native 层（`apps/android/native/rtc_bridge.cpp`）在原有 identity/controller 两个 peer 之外
新增第三个 **host peer**：以 `deviceId@hostPassword` 登录，注册与桌面被控相同的流
（Display1 视频、control_audio 音频、mouse/keyboard/control_data/clipboard/file 数据流），
连接建立后主动发送 `MakeHostInformation(platform=Android, supports_file_browser=true)`，
并把收到的鼠标/键盘/文本 RemoteAction 转发到 Java 层执行。实现方式与官方
`apps/harmony/tests/mobile_synthetic_host.cpp` 测试件一致。

## 保活机制（借鉴 RustDesk 等无人值守远控）

多条独立复活路径，任何单一路径被杀都有其他路径拉回：

1. **前台服务 + WakeLock + START_STICKY**，通知常驻。
2. **onTaskRemoved / onDestroy 闹钟重启**：划掉最近任务或服务被异常杀死后，
   4 秒后经 AlarmManager 拉回（用户主动「停止被控」会先清 hostMode，不会复活）。
3. **看门狗闹钟**：每 15 分钟检查一次（setAndAllowWhileIdle，每次触发自续期），
   服务不在就拉起。
4. **无障碍锚点**：无障碍服务（系统绑定、极少被杀）每次连接时检查 HostService，
   不在就拉起——与 RustDesk 用 InputService 锚定 MainService 的做法相同。
5. **开机/更新自启**：BOOT_COMPLETED、MY_PACKAGE_REPLACED 后恢复待命。
6. **面板引导**：忽略电池优化（安卓 12+ 后台拉起前台服务的豁免前提）、
   厂商自启动设置直达（小米/OPPO/vivo/华为/三星）——国产平板上这一步最关键。

例外：投屏授权无法保活恢复，见下方限制。

## 文件传输

- 协议与桌面被控完全相同：`FileBrowser`（control_data 流上的 JSON，
  `type:"file_browser"`）负责浏览/发起，`file_transfer::Session`
  （`libs/controller` 的共享引擎）负责分块（64KB/块）、ACK 流控（1MiB 窗口）、
  超时与取消；native 构建新增链接 `libs/controller/src/file_browser.cpp`。
- 主控可浏览本机目录、下载文件到主控、上传文件到本机；接收文件落在
  「下载/CrossDesk」目录（同名自动加时间戳后缀，不覆盖）。
- 安全边界：被控面板有「允许文件传输」开关，关闭时浏览器请求一律回
  `browser_not_open`（与桌面端一致）；浏览任意目录需要「文件访问授权」
  （全部文件访问），未授权时仅下载目录可达。
- 进度通过通知栏显示（发送/接收百分比与完成/失败）。

## 声音与语音通话

- **系统声音**（默认开）：HostCaptureService 用 AudioPlaybackCapture
  （安卓 10+，借投屏授权）抓取媒体/游戏声音，48kHz 单声道 PCM 经音频流送主控。
- **麦克风**（默认关，面板开启）：VOICE_COMMUNICATION 源（含回声消除），
  主控可听到本机周围声音；开启时系统声音自动让行。
- **主控语音**：主控麦克风音频由 native 转发 Java，用 AudioPlayer 在本机播放，
  构成双向语音通话。
- 三者都需要录音权限，在面板打开对应开关时会申请。

## 使用步骤（被控平板）

1. 安装 APK，打开桌面上的「CrossDesk 被控」图标（HostActivity）。
2. 设置连接密码（或随机生成）和设备名称，点「开启被控」：
   - 允许前台服务通知；
   - 在系统弹窗中授权投屏（整个屏幕）。
3. 点「打开无障碍设置」，开启「CrossDesk 被控」无障碍服务。
4. 无人值守部署强烈建议依次完成：**忽略电池优化** → **厂商自启动设置**里允许自启动 →
   **文件访问授权**（需要文件传输时）。
5. 界面会显示本机 ID。主控端输入该 ID + 连接密码即可连接控制；
   连接后主控端打开文件管理即可浏览/传输文件。

重启后：被控服务自动恢复待命（信令 + host peer），但 **Android 14+ 要求每次进程重启后
重新授权一次投屏**（平台规则，无法绕过）；在授权前主控可连接但画面为黑屏，
点通知进入被控页面点「开启被控/重新授权投屏」即可。无障碍服务重启不失效。

## 已知限制

- 键盘输入走无障碍文本注入：可向焦点输入框追加文字、退格、回车（IME 动作）；
  组合键、Delete、游戏/自绘界面内的按键无法模拟。主控端的输入法候选词文本
  （text_input）可正常上屏。
- 鼠标按下-移动-松开会被重放为一条无障碍手势轨迹，悬停（hover）无对应能力。
- 安卓 14+ 投屏授权在进程重启后需人工点一次（见上）；系统声音在安卓 9 以下不可用。
- 设备信任（DeviceTrust）挑战在 v1 未做校验，接入控制依赖信令服务器的 ID + 密码校验。
- host peer 与 presence 会话是两个独立登录；若服务器对同一设备 ID 的并发登录有限制，
  开启被控时主界面（MainActivity）的 presence 会话会被让行（已在 ensureSignaling 中处理）。

## 改动文件

- `apps/android/native/rtc_bridge.cpp`：host peer、JNI `nHostStart/nHostStop/nHostFrame/
  nHostClipboard/nHostAudio/nHostFileDirectory/nHostFileEnabled/nHostPumpFiles`、事件 20–25、
  文件流路由与 file_browser 分发、主控音频转发（onHostAudio）。
- `apps/android/native/xmake.lua`：新增链接 `libs/controller/src/file_browser.cpp`，
  语言标准 c++17 → c++20（file_browser 使用 C++20 特性，与桌面端一致）。
- `NativeSession.java`：host 事件分发、文件/音频封装、Listener.hostAudio。
- 新增 `HostService.java`（信令 + host 生命周期 + 文件泵 + 音频播放/麦克风调度）、
  `HostCaptureService.java`（MediaProjection 抓屏 + 系统声音）、
  `HostInputService.java`（无障碍注入 + 保活锚点）、`HostAudio.java`（48k PCM 采集）、
  `HostActivity.java`（被控面板：开关/授权/厂商自启动）、`BootReceiver.java`（开机自启 + 看门狗）。
- `AndroidManifest.xml`（RECORD_AUDIO、MANAGE_EXTERNAL_STORAGE、stopWithTask、
  MY_PACKAGE_REPLACED）、`res/xml/host_accessibility_config.xml`、
  `res/values/host_strings.xml`、`MainActivity.java`（host 模式下让行 presence 会话）。
