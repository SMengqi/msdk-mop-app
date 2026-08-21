# 项目状态：已搁置

**最后更新：2026-08-20**
**状态：暂停，非废弃。构建已通过，未与 PSDK 联调。**

---

## 1. 为什么停

两个原因，都与代码无关：

- **无联调条件** —— 无人机一直被占用，没拿到测试机会
- **优先级转移** —— 改为先验证 Pilot 那条通路

代码没有已知的阻塞问题。停在"构建成功、等待上机"这一步。

---

## 2. 进度一览

```
需求确认 ─✅─> 工程搭建 ─✅─> 编译通过 ─✅─> 打包成功 ─✅─> 上机联调 ─⬜─> 帧解码 ─⬜─> MQTT 转发 ─⬜
                                                          ↑
                                                     停在这里
```

| 阶段 | 状态 | 说明 |
|---|---|---|
| 从官方 sample 抽取 MOP 部分 | ✅ | 1000 行 Kotlin，不依赖 uxsdk |
| 连接参数对齐 PSDK 文档 | ✅ | 49200 / PORT_3 / PAYLOAD / STABLE |
| Gradle 配置、依赖解析 | ✅ | 三个 MSDK aar 均能解析 |
| Kotlin 编译 | ✅ | |
| 资源打包、dex、APK 打包 | ✅ | 2026-08-20 通过 |
| 安装到遥控器 | ⬜ | |
| SDK 注册激活 | ⬜ | |
| MOP 连接与收发 | ⬜ | |
| 帧解码与路由 | ⬜ | 未实现 |
| MQTT 转发服务器 | ⬜ | 未实现，当时明确决定暂不做 |

---

## 3. 验证边界（重要）

接手时**不要假设这个工程跑通过**。下面区分得很清楚：

### 已验证

| 项 | 怎么验的 |
|---|---|
| Gradle 配置解析、AGP/Kotlin 插件 | Linux 开发机实跑 |
| 布局 XML 结构、资源引用完整性 | 脚本校验，无缺失 `@string` / `@array` |
| ViewBinding 字段名与布局 ID 对应 | 15 个字段逐一比对 |
| 中文资源无多余 key、格式化占位符与默认语言一致 | 脚本校验（占位符不一致会在运行期抛异常） |
| 三个 MSDK aar 能解析 | Windows 侧构建通过 |
| `@xml/accessory_filter` 能解析 | 同上（该资源不在 sample 仓库里，来自 aar 资源合并，曾担心找不到） |
| Kotlin 全量编译 | 同上 |
| APK 打包 | 同上，删除 `app/build` 后通过 |

### 未验证

| 项 | 风险 |
|---|---|
| 装到遥控器能否启动 | native 库打包方式刚改过（见第 5 节），未在真机验证 |
| App Key 能否激活 | `applicationId` 沿用 `com.dji.sampleV5.aircraft` 以复用已验证的 Key，但没实际跑过注册 |
| MOP 能否连上 PSDK | **核心未验证项**。参数取自 PSDK 文档，两端从未对接过 |
| `MopChannel.isTimeout()` 判定是否正确 | 见第 4 节，这是最可能出问题的地方 |
| release 包（R8） | `minifyEnabled true`，规则文件是新写的，从未实测 |

---

## 4. 遗留的技术疑问

### 4.1 超时判定 —— 最需要先解决的一个

`MopChannel.isTimeout()` 当前用 `toString()` 做**宽松匹配**，不是精确比较。

为什么要紧：上报频率仅 **0.5Hz**，通道 99% 时间空闲，几乎每次读操作都会走到超时分支。
**判错的直接后果是连上就掉线。**

怎么解决：连上后从 logcat 捞这五行（每次新连接重新采样）：

```
MopChannel: read error sample #N: code=[...] raw=[...] timeoutRef=[...]
```

拿到真实值后把匹配改成精确比较即可。方法内已写好注释说明。

### 4.2 一条被撤回的判断

曾认定官方 sample 的 `MopVM.kt:89`

```kotlin
result.error.errorCode().equals(DJIPipeLineError.TIMEOUT)
```

是"String 与错误对象比较恒为 false"的缺陷。**该判断已撤回** —— 编译错误显示
`DJIPipeLineError.TIMEOUT.errorCode()` 无法解析而 `error.errorCode()` 可以，
说明 `TIMEOUT` 不是错误对象，很可能是 String 常量，那么 sample 那句语义是对的。

尚未最终确认，与 4.1 是同一件事，一次联调可同时解决。

### 4.3 `deviceType` 未经实测

PSDK 文档标注为 `PAYLOAD`，代码默认值也是 `PAYLOAD`，但两端从未真正对接。
首次连接时看一眼日志里 `MopConfig(... device=? ...)` 确认 —— ONBOARD 与 PAYLOAD
是两条完全不同的通道。现在参数可在界面上改，试错成本很低。

---

## 5. 关键决策记录

避免接手时重走弯路。详细论证见 `README.md`。

| 决策 | 理由 |
|---|---|
| 不依赖 uxsdk，独立建工程 | sample 的 MOP 代码对 uxsdk 只有一处 `ToastUtils` 引用，为此扛 11.6 万行 UI 代码不划算 |
| pipelineId 用 **49200** 而非 sample 的 49152 | 49152/49153 是官方 sample 硬编码占用的，PSDK 侧特意避开 |
| `applicationId` 暂时沿用 `com.dji.sampleV5.aircraft` | 复用已激活验证的 App Key，迁移期零申请成本 |
| `MopChannel` 重写而非照搬 `MopVM` | 修了两处客观缺陷：递归调用导致栈溢出、未按 `DataResult.length` 截断 |
| 提供 `MopCompatMode` 兼容开关 | 上述改动均未经实证，可一键切回 sample 原样对比排查 |
| 不移植 `MOPCmdHelper` 文件传输协议 | 只用流式传输 |
| `jniLibs.useLegacyPackaging = true` | AGP 8 忽略 manifest 的 `extractNativeLibs`，不配会在真机报 `UnsatisfiedLinkError`。**这一条改完没在真机验证过** |

### 一个需要重新审视的决策

`MopChannel` 的两处"设计选择"（`onData` 改在读线程回调、200ms 节流统计）是按
**高吞吐流式**设计的。后来查 PSDK 文档才知道实际速率只有 **0.5Hz**（每 2 秒一帧）。

这两处改动收益近乎为零，反而增加了复杂度。**重启时可以考虑简化**：0.5Hz 下
直接在主线程回调、逐帧打印完整内容，代码更简单也更好调试。

---

## 6. 重启时怎么接手

### 第一步：换个地方构建

**不要在网络驱动器上构建**（此前 `Z:` 映射盘导致 `app-debug.apk is not writeable`）。
本地盘直接 clone：

```bash
git clone https://github.com/SMengqi/msdk-mop-app.git
```

然后在 `gradle.properties` 填 App Key，AS 打开根目录 sync。

### 第二步：上机验证（半天以内）

按 `README.md` 第 5 节的决策树走。需要：遥控器 + 飞机 + 已烧录 `bxt_msdk_link` 的 PSDK 负载。

一次联调能同时解决 4.1、4.2、4.3 三个遗留疑问。

### 第三步：补帧解码

链路通了之后才有意义。PSDK 侧协议已定死：

```
[1B TYPE][2B LEN 小端][LEN 字节 PAYLOAD]     单帧 payload 上限 65535
0x00 HELLO(飞机序列号，缓存不转发) / 0x01 OSD(JSON) / 0x02 EVENT(告警 JSON)
```

对端实现是 `bxt-psdk-3.16.0/samples/sample_c++/module_sample/bxt_msdk_link/msdk_link_frame.h`
的 `FrameDecoder`，照着写 Kotlin 版即可，需处理粘包/半包。

**这块是纯逻辑，可以在开发机上写 JVM 单测验证，不需要真机、不需要 Android SDK。**
如果重启时暂时还是拿不到飞机，这是唯一能有效推进的部分。

### 第四步：MQTT 转发

当时明确决定暂不做，无任何实现。

---

## 7. 相关资料

| 内容 | 位置 |
|---|---|
| 本工程详细说明、排查步骤、兼容模式 | `README.md` |
| PSDK 侧设计与实现计划 | `bxt-psdk-3.16.0/docs/superpowers/plans/2026-08-19-bxt-msdk-link.md` |
| PSDK 侧原始设计文档 | `bxt-psdk-3.16.0/docs/superpowers/specs/2026-08-17-bxt-msdk-link-design.md` |
| 官方 sample（对照基准） | `Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-sample` |
| 代码仓库 | https://github.com/SMengqi/msdk-mop-app |

### 提交历史

```
0c49a97  2026-08-20  修正 native 库打包方式：补 jniLibs.useLegacyPackaging
456c3a1  2026-08-20  添加 App 图标与中文名称
d506971  2026-08-20  初始提交：MSDK V5 独立 MOP 通道客户端
```

---

## 8. 收尾清单（重启后再做）

- [ ] 上机联调，确认 4.1 / 4.2 / 4.3
- [ ] 补 `FrameDecoder` + JVM 单测
- [ ] 帧路由：HELLO 缓存序列号，OSD / EVENT 转发
- [ ] MQTT 转发到服务器
- [ ] 长稳测试（验证递归改循环确实解决了栈溢出）
- [ ] 换掉 App 图标（现为 DJI sample 的 `ic_main.png`）
- [ ] 改包名 + 换新 App Key（现与官方 sample 同包名，**装同一台设备会互相覆盖**）
- [ ] 实测 release 包（`minifyEnabled true`）
- [ ] App Key 移出 `gradle.properties`，改从未入库的 `local.properties` 读取
