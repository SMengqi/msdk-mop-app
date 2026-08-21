# MOP Client

> ⏸️ **本项目已于 2026-08-20 搁置**（构建通过，未与 PSDK 联调）。
> 重启前请先读 **[STATUS.md](STATUS.md)** —— 那里记录了验证边界、遗留疑问和接手步骤。

基于 DJI Mobile SDK V5 (5.18.0) 的独立 MOP 通道客户端，用于与自研 PSDK 负载做**流式数据**收发。

从官方 sample 工程（`Mobile-SDK-Android-V5/SampleCode-V5`）抽取而来，只保留 MOP 所需部分，不依赖 uxsdk。

---

## 1. 与官方 sample 的关系

| | 官方 sample | 本工程 |
|---|---|---|
| 代码量 | sample 2.6 万行 + uxsdk 11.6 万行 | ~700 行 |
| uxsdk 依赖 | 深度使用 | 无（sample 的 MOP 代码对 uxsdk 只有一处 `ToastUtils` 引用） |
| MOP 实现 | `models/MopVM.kt` | `mop/MopChannel.kt`（重写） |
| 文件传输协议 | `data/MOPCmdHelper.java`（543 行） | 未移植（只用流式传输） |
| 注册/初始化 | `models/MSDKManagerVM.kt` | `sdk/MsdkManager.kt`（逐行照搬） |

> **重要前提**：已经和 PSDK 联调通过的是 **sample 那份代码**，本工程尚未实证。`MopChannel` 相对 sample 有行为改动，因此提供了[兼容模式](#4-兼容模式)用于对比排查。

---

## 2. 快速开始

### 环境

| 项 | 版本 |
|---|---|
| Gradle | 8.12（wrapper 内置） |
| AGP | 8.7.0 |
| Kotlin | 2.1.0 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |
| MSDK | 5.18.0 |

### 步骤

1. 在 `gradle.properties` 填入 App Key：
   ```properties
   AIRCRAFT_API_KEY=<你已激活验证过的 Key>
   ```
2. Android Studio 打开**本目录**，sync
3. 真机运行（见下方限制）

### 硬件限制

- **只编 `arm64-v8a`**（`app/build.gradle` 的 `abiFilters`）→ 模拟器跑不了，必须真机
- 需要 USB Host/Accessory → USB 连遥控器，或直接装在 RC Pro 这类自带 Android 的遥控器上
- App 强制横屏

### 关于 applicationId

`app/build.gradle` 里 `applicationId` 暂时沿用 **`com.dji.sampleV5.aircraft`**，目的是直接复用你已经激活验证过的 App Key —— App Key 与包名绑死，改包名就必须回 developer.dji.com 重新申请。

namespace 已经是 `com.bxt.mop`，两者独立。等功能跑通后再一起改包名 + 换新 Key。

---

## 3. 已固化的 MOP 参数

定义在 `mop/MopConfig.kt`。取值依据是 PSDK 侧设计文档
`bxt-psdk-3.16.0/docs/superpowers/plans/2026-08-19-bxt-msdk-link.md` 的 Global Constraints
（标注 2026-08-19 实测），**不是** DJI sample 界面上的默认值 —— 两者不同。

| 参数 | 本工程取值 | sample 默认值 | 依据 |
|---|---|---|---|
| `pipelineId` | **`49200`** | `49152` | `msdk_link_entry.h` → `BXT_MSDK_LINK_CHANNEL_ID` |
| `componentIndex` | **`ComponentIndexType.PORT_3`** | `LEFT_OR_MAIN` | Global Constraints 第 18 行 |
| `deviceType` | `PipelineDeviceType.PAYLOAD` | 同 | 一致，已实测确认 |
| `transmission` | `TransmissionControlType.STABLE` | 同 | 一致（PSDK 侧 `TRANS_RELIABLE`） |
| `readBufferSize` | `19004` | 同 | 沿用 sample，与 PSDK 侧收包缓冲无关 |

> `49152` / `49153` 是官方 sample 硬编码占用的通道，PSDK 侧特意避开，改用 `49200`。
> 两端的 pipelineId 必须一致。

### 链路拓扑

- PSDK 作 MOP **server**（Bind 49200 + Accept），App 连入即启用、断开即停用，无控制报文
- 数据方向以 **PSDK → App** 为主，App 基本不发数据
- 上报频率 **0.5Hz**（周期 2000ms），跟随 `bxt_osd`

### 帧格式

```
[1B TYPE][2B LEN 小端][LEN 字节 PAYLOAD]      单帧 payload 上限 65535
```

| TYPE | 含义 | App 侧处理 |
|---|---|---|
| `0x00` HELLO | 飞机序列号（ASCII） | 缓存，不转发 |
| `0x01` OSD | 完整 OSD JSON | 转发服务器 |
| `0x02` EVENT | HMS 告警 JSON | 转发服务器 |

> **尚未实现**：App 侧的帧解码与路由。当前 `MainActivity` 只做字节统计，收到的是带帧头的
> 原始字节流，直接看内容会是帧头混着 JSON。需要补一个对应 PSDK `FrameDecoder` 的
> Kotlin 实现（处理粘包/半包）。
>
> **暂不实现**：收到 OSD/EVENT 后经 MQTT 转发到服务器 —— 等 AS 上跑通再做。

---

## 4. 兼容模式

### 为什么需要它

`MopChannel` 相对 sample 的 `MopVM` 有 5 处改动。其中 **2 处是原代码的客观缺陷**，**3 处是设计选择或已撤回的判断**：

| # | 改动 | 性质 | MopVM.kt 行号 |
|---|---|---|---|
| 1 | 递归 → `while` 循环 | **缺陷**：`readData()` 调用自身且无 `tailrec`，栈只增不减，长跑必 `StackOverflowError` | :70 定义 / :94 自调 |
| 2 | 按 `DataResult.length` 截断 | **缺陷**：`String(data)` 转整个 19004 字节缓冲，第 75 行取到 `len` 却没用，包尾带残留 | :75 / :77 |
| 3 | 超时判定改写 | ~~缺陷~~ **判断已撤回**，见下 | :89 |
| 4 | `onData` 改在读线程回调 | **设计选择**：sample 是一发一收的手动测试页，post 主线程完全合理；高吞吐流式下逐块 post 会积压 Looper 队列。**本项目实际速率仅 0.5Hz，此改动收益近乎为零** | :79-80 |
| 5 | 读循环改用专用线程 | **设计选择**：sample 会让一个 `DJIExecutor` URGENT 共享池线程被永久占用 | :34 |

#### 关于第 3 项的更正

原先认定 sample 第 89 行 `result.error.errorCode().equals(DJIPipeLineError.TIMEOUT)`
是"String 与错误对象比较恒为 false"的缺陷。

2026-08-20 在 AS 上的编译错误推翻了这个推断：

```
MopChannel.kt:227:62 Unresolved reference 'errorCode'.
```

第 62 列是 `DJIPipeLineError.TIMEOUT.errorCode()`，而同一行第 22 列的
`error.errorCode()` **未报错**。即：`IDJIError.errorCode()` 存在，
`DJIPipeLineError.TIMEOUT` 上不存在 —— 说明 TIMEOUT 不是错误对象，很可能是
`String` 常量。若如此，sample 那句是 `String.equals(String)`，语义正确。

当前 `isTimeout()` 一律经 `toString()` 比较（规避 `errorCode()` 返回类型未知导致的
编译失败），并采样打印前 5 次读错误的 `code` / `raw` / `timeoutRef` 原始值。
**待首次联调拿到这些值后，应据此收紧判定并最终确认本项结论。**

由于这些改动**都没有经过实证**，提供开关可以随时切回 sample 原样做对比，不必回退代码。

### 怎么用

**界面**：主界面有「兼容模式(sample 原样)」复选框，未连接时可切，连上后自动禁用（读循环中途换行为没有意义）。

**代码**：
```kotlin
channel.compat = MopCompatMode.SAMPLE_ORIGINAL   // 切回 sample
channel.compat = MopCompatMode.FIXED             // 默认，修复版
```

四个开关相互独立，可以只翻其中一个做二分定位：

```kotlin
channel.compat = MopCompatMode(rawFullBuffer = true)   // 只还原第 2 项
```

| 开关 | 打开后的行为 | 对应改动 |
|---|---|---|
| `rawFullBuffer` | 上抛整个 19004 字节缓冲，不按 length 截断 | #2 |
| `disconnectOnAnyReadError` | 任何 `len<0` 都断开，不区分超时 | #3（该项判断已撤回，开关保留供对照） |
| `deliverOnMainThread` | `onData` 切回主线程回调 | #4 |
| `useDjiExecutorForRead` | 读循环跑在 `DJIExecutor` URGENT 池 | #5 |

当前模式会打进界面日志和 logcat（tag `MopChannel`）。

### 两处刻意不还原

**递归（#1）没有做成开关。** 它不产生任何可观察的协议行为差异 —— 唯一后果是长跑后 `StackOverflowError`，而那个崩溃特征极其明显，不会和别的问题混淆。为排查复刻一个已知会崩的实现是纯风险无收益。

**打开 `rawFullBuffer` 时仍会 copy 一份再上抛。** sample 是把复用中的共享缓冲直接拿去 `String(data)`，上层若持有引用会被下一次读覆盖。那是数据竞争而非"行为特征"，只还原"内容带尾巴"这一可观察差异。

---

## 5. 排查步骤

### 第一次跑

```
1. gradle sync
   ├─ 失败：找不到 @xml/accessory_filter
   │    → 该资源不在 sample 仓库里，来自 dji-sdk-v5-aircraft aar 的资源合并。
   │      从 sample 的 build/intermediates 合并结果里拷一份到 app/src/main/res/xml/
   ├─ 失败：MopChannel.kt 编译错误 (errorCode / DJIExecutor)
   │    → 见下方"已知未验证项"
   └─ 成功 ↓

1b. 打包阶段失败：`app-debug.apk is not writeable`
   → **不要在网络驱动器 / 映射盘上构建**（如 Windows 的 Z:）。Gradle 的文件锁
     语义在 SMB 上不可靠，权限映射也常出问题。
   → 依次尝试：删掉整个 `app/build` 目录 → 确认无进程占用 APK（AS 的 Gradle
     daemon、adb、杀毒软件实时扫描）→ 把工程移到本地盘。
   → 最干净的做法：直接在本地盘 `git clone` 本仓库。

2. 真机安装运行，看状态栏
   ├─ 一直停在"SDK 初始化中"
   │    → 看 logcat tag MsdkManager 的 onInitProcess 事件序列
   ├─ "SDK 注册失败"
   │    → App Key 与 applicationId 不匹配？确认 applicationId 仍是
   │      com.dji.sampleV5.aircraft；确认设备联网（注册需要联网）
   │    → 若报缺少网络能力，确认 app/build.gradle 里有
   │      runtimeOnly dji-sdk-v5-networkImp（sample 工程漏了这个依赖）
   └─ "SDK 已注册" ↓

3. 点「连接 MOP」
   ├─ 连接失败
   │    → 日志里核对 MopConfig 打印值，尤其 device= 是不是 PAYLOAD
   │    → 确认 PSDK 侧 channel id 是 49152
   └─ "MOP 已连接" ↓

4. 收发验证
```

### 出现异常时的对比流程

```
现象（连不上 / 掉线 / 收到的数据不对）
   │
   ├─ 勾上「兼容模式」再跑一次
   │
   ├─ 两次表现一致
   │    → 问题不在 MopChannel 的改动，往参数、注册流程、PSDK 侧查
   │
   └─ 只有 FIXED 下异常
        → 逐个翻开关二分定位：
           掉线     → 先试 disconnectOnAnyReadError
           数据不对 → 先试 rawFullBuffer
           UI 卡顿  → 先试 deliverOnMainThread
```

### 典型现象对照

| 现象 | 最可能的原因 | 动作 |
|---|---|---|
| 空闲几分钟就自动断连 | 超时判定（#3）没对上 —— 我的推断或修法有一处是错的 | 把 `result.error.errorCode()` 的实际值打出来，对齐 `MopChannel.isTimeout()` |
| 收到的数据尾部有乱码/多余字节 | 上层按整块长度而非业务长度解析 | 确认没开 `rawFullBuffer` |
| 收到的内容缺失/错位 | **不是 bug**：MOP 是流不是消息，见下 | 上层加分帧 |
| 长跑数小时后崩 `StackOverflowError` | 用回了 sample 的递归实现 | 确认跑的是本工程的 `MopChannel` |
| 主线程卡顿、ANR | 流速率高时 `deliverOnMainThread` 开着 | 关掉该开关 |
| 启动即崩 `UnsatisfiedLinkError` | native 库未按解压方式打包 | 确认 `app/build.gradle` 的 `packagingOptions.jniLibs.useLegacyPackaging = true` 与 manifest 的 `android:extractNativeLibs="true"` 一致。AGP 8 以 Gradle DSL 为准，只写 manifest 属性无效 |

### 关于"块 ≠ 消息"

这是流式最容易踩的坑，**不是缺陷**：

`readData()` 返回的是**数据块**。STABLE 模式类似 TCP，一次 `onData` 回调可能只拿到半条业务消息，也可能一次拿到两条。

- 传的是**连续字节流**（传感器采样、音视频之类）→ 顺序消费即可，当前实现够用
- 传的是**离散消息** → 需要在 `MopChannel` 之上加一层分帧（长度前缀或分隔符）

`MopChannel` 刻意不管这件事，它只负责把字节可靠地搬进搬出。

---

## 6. 已知未验证项

以下均**未经编译或运行验证**（开发机无 Android SDK），首次 sync / 运行时请重点关注：

| 项 | 位置 | 风险 |
|---|---|---|
| 超时判定的匹配规则 | `MopChannel.isTimeout()` | 已改为只用 `toString()` 匹配，规避了 `errorCode()` 的签名风险（不会编译失败），但匹配偏宽松。方法内会采样打印前 5 次读错误的原始文本，首次联调后据此收紧 |
| `DJIExecutor.getExecutorFor(Purpose.URGENT)` | `MopChannel.startReadLoop()` | 签名照抄 `MopVM.kt:34`，未实跑 |
| `@xml/accessory_filter` 能否解析 | `AndroidManifest.xml` | 该资源不在 sample 仓库中，依赖 aar 资源合并 |
| 三个 MSDK aar 能否解析 | `app/build.gradle` | 开发机 Gradle 缓存中没有下载过 |
| release 包 R8 规则是否足够 | `app/proguard-rules.pro` | sample 开了 `minifyEnabled` 却无任何规则文件，本工程补齐了，但未实测 release 包 |

**已验证的部分**（2026-08-20 在 Windows + Android Studio 上完成）：三个 MSDK aar 解析、
`@xml/accessory_filter` 解析、Kotlin 全量编译、资源打包、dex、APK 打包全部通过。
上表中「aar 解析」「accessory_filter」两项风险已排除，此处保留仅作记录。

**仍然未验证**：安装、启动、SDK 注册、MOP 连接与收发 —— 从未上机。详见 [STATUS.md](STATUS.md)。

---

## 7. 代码结构

```
app/src/main/java/com/bxt/mop/
├── MopApp.kt              Application。attachBaseContext 里 Helper.install(this)
│                          位置不能挪不能删；onCreate 调 MsdkManager.init
├── MainActivity.kt        最小收发界面 + 兼容模式开关 + 流量统计（200ms 节流）
├── UsbAttachActivity.kt   插遥控器时自动拉起
├── sdk/
│   └── MsdkManager.kt     注册时序，逐行照搬 sample，勿优化
└── mop/
    ├── MopConfig.kt       已联调通过的五个参数
    ├── MopChannel.kt      通道封装：连接、读循环、发送、断开
    └── MopCompatMode.kt   兼容模式开关
```

### 不可改动的部分

```kotlin
// MopApp.attachBaseContext —— 加壳库初始化，位置不能挪
com.cySdkyc.clx.Helper.install(this)

// MsdkManager —— 注册时序，提前调 registerApp() 必失败
SDKManager.init(ctx, callback)
  → onInitProcess(INITIALIZE_COMPLETE)
    → registerApp()
```

`app/build.gradle` 里 `packagingOptions` 那一大坨 `doNotStrip`（MSDK native 库）同样原样照搬自 sample，勿删减。

---

## 8. 后续工作

- [ ] 首次联调：确认 `deviceType` 实际值、验证超时判定
- [ ] 吞吐压测：字节总数与发送端比对（验证不丢块）、连续跑数小时（验证递归→循环的修复）
- [ ] 若 PSDK 侧发的是离散消息，加分帧层
- [ ] 跑通后改包名 + 换新 App Key
- [ ] **换掉 App 图标** —— 当前 `res/mipmap-xxxhdpi/ic_main.png` 直接取自 DJI sample，
      是 DJI 的图形资源。台架调试无妨，对外交付前应替换为自己的图标
      （AS 里 `res` 右键 → New → Image Asset 可一次生成各密度）
- [ ] 实测 release 包（`minifyEnabled true`）
