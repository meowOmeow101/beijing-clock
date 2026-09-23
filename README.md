# beijing-clock

一个很小的安卓应用：把北京时间（精确到秒）常驻在通知栏，打开应用首页也是同一个时间。

做这个东西的原因很直接。手机状态栏自带的时钟只有小时和分钟，看秒数得进设置里的时间页面；
市面上带秒的悬浮时钟要么需要悬浮窗权限，要么只有桌面小组件。所以干脆自己写一个，
要求只有两条：

- 首页一眼能看到秒级北京时间；
- 下拉通知栏随时能看到秒级北京时间，不用开着应用。

时间不依赖手机系统时区，固定按 `Asia/Shanghai`（UTC+8）显示，手机时区设成哪里都一样。
对时用 NTP，不引入任何第三方库，AndroidX 之外没有别的依赖。

## 安装

已签名的安装包在 [Releases](https://github.com/meowOmeow101/beijing-clock/releases) 页面，
下载 apk 直接安装即可（Android 6.0 及以上）。装完打开应用，把「通知栏显示北京时间」
开关打开，按提示允许通知权限。

## 功能

| 能力 | 说明 |
| --- | --- |
| 首页秒级时间 | 主界面大字显示 `HH:mm:ss`，等宽字体，日期和星期在下边 |
| 通知栏常驻时间 | 前台服务每秒刷新通知，下拉通知栏直接读秒 |
| 网络对时 | 启动或打开应用时向 NTP 服务器取一次标准时间，算出本机时钟偏移量 |
| 离线继续走时 | 偏移量保存到本地，之后靠本机时钟推算，不联网也准 |
| 打开即校准 | 每次打开应用都重新对时一次 |
| 开机自启 | 重启手机后常驻时钟自动恢复 |
| 状态栏图标开关 | 可以关掉状态栏图标，只保留下拉通知栏里的那一条 |
| 退出后仍然显示 | 是否在划掉后台后继续保留通知栏时间，由界面上的开关控制 |
| 对时信息 | 首页显示对时状态、上次用的服务器、本机时钟快或慢多少毫秒 |
| 手动校准 | 一个按钮随时强制重新对时 |

## 时间是怎么来的

对时那块是自己写的 SNTP 客户端（`SntpClient.java`），按 RFC 4330 的客户端流程走：
48 字节的 UDP 包打到服务器的 123 端口，取应答里第 40 字节开始的 Transmit Timestamp，
换算成 Unix 毫秒。NTP 时间戳的起点是 1900-01-01，和 Unix 纪元差 2208988800 秒，
小数部分满量程是 2 的 32 次方，所以毫秒数是 `fraction * 1000 / 2^32`。

服务器按顺序试，第一个通了就不再往下试：

```
ntp.aliyun.com
cn.pool.ntp.org
ntp.tencent.com
ntp1.aliyun.com
time.windows.com
```

单次请求超时 4 秒，一个服务器失败就换下一个。应答会做基本校验：长度对不对、
LI（闰秒告警位）是不是 3、mode 是不是 4 或 5、stratum 在不在 1 到 15 之间，
任何一条不过就当成失败。

拿到服务端时间后，用 `服务端时间 - 本机时间` 得到一个偏移量。这个偏移量存在
SharedPreferences 里，之后的每一秒都是 `System.currentTimeMillis() + 偏移量`，
所以断网、飞行模式都不影响走时，代价是误差会随着本机晶振慢慢累积。
想把它压回去也简单，打开一次应用就会重新对时。

如果当前本机时间比上次对时的时间还早，说明系统时钟被人往回拨过，这时旧偏移量
已经不可信，会直接丢掉等下一次联网校准，免得把时间推得更偏。

## 通知栏那条通知

常驻是靠前台服务（`NotificationClockService`）实现的。服务在 `onStartCommand` 里
先 `startForeground` 保住前台身份，然后挂一个 Handler，每次都把下一次刷新对齐到
整秒边界（当前时间距离下一秒还有多少毫秒，就延迟多少毫秒再刷），这样秒数跳变和
真实时间是对齐的，不会慢慢漂。

通知用 `IMPORTANCE_DEFAULT` 渠道，为的是状态栏能留下图标；嫌它占位的话，应用里可以
把「状态栏常驻图标」关掉，这时会切到 `IMPORTANCE_LOW` 的静默渠道。顺便说一句，
部分国产 ROM 对 `IMPORTANCE_LOW` 的前台服务通知会做隐藏处理，这是系统行为，
不是应用能控制的。

## 退出应用之后

界面上的「退出后仍然显示」开关决定这件事，默认开启。

在 Android 里，从最近任务划掉应用只会触发 `onTaskRemoved`，并不会销毁正在前台运行的服务，
所以开启这个开关时，通知栏的时间会一直走下去，不会因为退出应用而中断。
关掉开关，划掉后台就会把服务和通知一起收掉。

几种容易被误会的边界情况：

- 在系统设置里点「强行停止」，等同于系统层禁用该应用的后台，任何服务都起不来，
  通知栏时间会消失，重新打开一次应用才能恢复。这是系统行为，应用无法绕过。
- 进程被系统因内存紧张回收时，前台服务会由系统重建，重建时会重新对时并刷新通知。
- 国产 ROM 的省电策略可能直接冻结后台，把应用加进电池优化白名单可以显著降低概率。

服务内部为这件事做了几处防护：`onStartCommand` 兜住 `startForeground` 可能抛出的异常，
避免重建时陷入崩溃循环；WakeLock 带 12 小时超时申请、并在 10 小时处自动续期，
避免连续运行超过半天后息屏秒数停住；`onTaskRemoved` 只在开关打开时才把服务拉回来。

## 兼容性

- minSdk 23（Android 6.0），targetSdk 34。
- Android 13 及以上需要通知权限（`POST_NOTIFICATIONS`）。首次打开常驻显示开关时会申请，
  拒绝了就没有通知栏时间，界面会给出提示。
- Android 14 要求前台服务声明类型，这里声明的是 `specialUse`，并在清单里补了
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途。
- 国产 ROM 的后台限制比较激进，如果发现时间停住不动，把本应用加进电池优化白名单。
  应用内「后台 / 通知设置」里有直达系统页面的入口。
- 只申请了网络、通知、前台服务、开机自启这几项权限，没有读系统时间之类的权限，
  也不采集任何数据。

## 目录

```
app/src/main/java/com/beijing/clock/
    MainActivity.java              首页：秒级时间、对时状态、各种开关
    NotificationClockService.java  通知栏前台服务 + 开机广播
    TimeCenter.java                时间中心：偏移量、持久化、校准策略、状态文案
    ServicePolicy.java             保活规则（退出后是否保留、WakeLock 续期等）
    SntpClient.java                SNTP 客户端
    TimeFormatter.java             北京时间格式化（全部硬绑 Asia/Shanghai）

app/src/main/res/                  布局、配色、主题、图标
tools/gen_icons.py                 生成 launcher 图标（Pillow）
tools/jvmtest/                     JVM 端功能验证，见下文
tools/run_jvm_test.ps1             编译并运行上面的验证
tools/ntp_check.py                 纯 Python 的 NTP 服务器可达性自检
```

## 编译

需要 JDK 17 以上、Android SDK（含 `platforms;android-34`、`build-tools;34.0.0`、
`platform-tools`）、Gradle 8.11。

把 SDK 路径写进 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

如果本机装了多个 JDK，可以在 `gradle.properties` 里指定（不指定就用默认的）：

```properties
org.gradle.java.home=/path/to/jdk-17
```

然后：

```shell
gradle assembleRelease   # 正式签名包
gradle assembleDebug     # 调试包
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

两点提醒：

- 工程路径不要带中文，Android Gradle Plugin 会直接拦下来报错。中文路径下也能编过，
  但需要显式关掉这个校验（本仓库的 `gradle.properties` 里写了原因）。
- release 包的签名配置从根目录的 `keystore.properties` 读，文件长这样：

  ```properties
  storeFile=beijingclock.jks
  storePassword=你的密码
  keyAlias=beijingclock
  keyPassword=你的密码
  ```

  没有这个文件时 release 任务不会签名，编出来的包装不上。换自己的签名就重新生成一份：

  ```shell
  keytool -genkeypair -v -keystore mykey.jks -storetype JKS \
      -keyalg RSA -keysize 2048 -validity 10950 -alias mykey \
      -storepass 你的密码 -keypass 你的密码 \
      -dname "CN=Your Name, O=Your Org, C=CN"
  ```

## 怎么验证的

手边没有第二台安卓设备，所以时间相关的逻辑没有只停留在「编译通过」这一步。
`tools/jvmtest/` 下面写了几个桩类，把 `Context`、`SharedPreferences`、`Handler`、
`Looper`、`SystemClock`、`Log` 换成桌面 JVM 能跑的版本，然后把 `TimeCenter`、
`SntpClient`、`TimeFormatter` 这三个纯逻辑类和桩一起编译，在电脑上跑真实的网络对时。

```powershell
powershell -File tools/run_jvm_test.ps1
```

当前 44 项检查全部通过，覆盖：向真实 NTP 服务器取时并把结果和 UTC+8 格式化对照、
首选服务器失败时自动切换备用、偏移量计算与 `now()` 一致、每次打开都会重新校准、
校准进行中重复触发被去重、进程重启后偏移量仍然生效、系统时钟被回拨时丢弃旧偏移量，
以及保活规则的判定（退出后是否重启服务、WakeLock 何时该续期、界面状态文案）。

`tools/ntp_check.py` 是个更轻的自检脚本，不带桩，直接看几个授时服务器通不通、
拿到的时间和本机差多少。

Windows 上跑 `run_jvm_test.ps1` 时注意：这个脚本保持纯 ASCII，因为 Windows PowerShell 5.1
会把没有 BOM 的 UTF-8 文件按 ANSI 解析，脚本里写中文会解析出错。

## 已知问题

- 刷新依赖服务存活。被系统强制停止（比如在设置里点「强行停止」）之后不会自己起来，
  需要重新打开一次应用。
- 秒级刷新意味着每秒都会重建一条通知。系统对同一应用的通知频率有限制，
  这里每秒一次在实测中没有被丢弃，但理论上存在被限流的可能。
- 通知栏那条通知用默认样式，没有做 RemoteViews 自定义布局，
  在不同 ROM 上的观感差别不小。

## 许可证

MIT，见 [LICENSE](LICENSE)。
