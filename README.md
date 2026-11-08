<div align="center">

# Pods

**华为 Mate 60 Pro / HarmonyOS 4.x 上的 AirPods 状态与聆听模式管理**

[![License](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-9%2B-green.svg)](#-构建)
[![HarmonyOS](https://img.shields.io/badge/HarmonyOS-4.x-red.svg)](#%EF%B8%8F-兼容性与已知限制)
[![Language](https://img.shields.io/badge/Language-Java-orange.svg)]()
[![Version](https://img.shields.io/badge/version-0.1.6-blueviolet.svg)]()

*A lightweight, offline AirPods status & listening-mode manager for HarmonyOS.*

无联网权限 · 无广告 · 无数据收集 · 不依赖 Google 服务

</div>

---

华为手机连接 AirPods 后，系统只能给出有限的状态显示，也无法管理聆听模式。Pods 用原生 Java 补上这一块：自动跟随当前使用的 AirPods，读取电量与佩戴状态，并提供降噪 / 通透 / 关闭模式的管理。

> [!IMPORTANT]
> **状态广播读取已在 HarmonyOS 4.x 真机验证；聆听模式切换尚未接通。** AirPods 的模式控制走经典蓝牙 L2CAP 私有协议，HarmonyOS 的系统蓝牙栈在通道协商阶段即拒绝连接，普通应用无法绕过。详见[兼容性与已知限制](#%EF%B8%8F-兼容性与已知限制)。

## ✨ 功能

- **自动跟随**：自动识别系统正在使用的 AirPods（含已改名的耳机），自动连接、读取与更新，无须手动选设备或刷新
- **电量显示**：左耳 / 右耳 / 充电盒使用按百分比填充的电池图标与百分比文字；电量 >50% 绿、21–50% 黄、≤20% 红，充电时叠加闪电
- **状态合并**：充电、佩戴、主麦克风显示在对应耳机下方，盒盖开合与盒充电显示在充电盒下方；未知状态直接隐藏，不用占位符冒充读数
- **状态栏与通知**：状态栏显示左右耳较低一侧电量，通知汇总各组件读数，只在内容变化时更新
- **摘戴控制**：双耳均摘下时暂停播放，戴回任意一只恢复本应用暂停的播放；通过系统媒体键实现，无须读取其他应用通知的权限，可在菜单关闭
- **后台监测**：音频连接期间通过前台服务持续扫描，熄屏使用 Apple 协议过滤避免系统暂停无过滤扫描
- **隐私优先**：无联网权限、不收集任何数据；诊断分享不含蓝牙地址、序列号或身份密钥

聆听模式（关闭 / 降噪 / 通透 / 自适应）的界面与协议栈已实现，收到耳机回报的实际模式才显示切换成功；当前版本以状态读取为核心。

## ⚠️ 兼容性与已知限制

| 功能 | 状态 |
| --- | --- |
| 电量 / 佩戴 / 充电 / 盒盖广播读取 | ✅ HarmonyOS 4.x 真机验证 |
| 后台扫描与通知更新 | ✅ 真机验证 |
| 摘戴自动播放 / 暂停 | 🔶 已实现，媒体键行为因播放器而异 |
| 聆听模式切换 | ❌ 受系统蓝牙栈限制，尚未接通 |

### 为什么模式切换不可用

AirPods 使用经典蓝牙 L2CAP 私有控制协议（PSM `0x1001`）。在 HarmonyOS 4.x 真机上，系统蓝牙栈在通道协商阶段即返回错误并拒绝连接——发生在任何应用层报文（握手、模式指令）之前：

```text
L2CAP - Peer does not support our channel types
```

这与 [LibrePods 记录的旧安卓系统兼容问题](https://github.com/librepods-org/librepods/blob/main/android/README.md#root-requirement)同源：修复需要作用于系统蓝牙进程内部。本项目不包含 root、Xposed 或系统修改，应用层无法绕过这一层。

若需要「关闭」聆听模式：可借用 iPhone / iPad 将耳机设为关闭，或将关闭加入耳机长按切换的模式，再回到华为手机使用。

### 其他边界

- 广播电量分辨率为 10%，界面如实显示收到的百分比，不添加近似符号；1% 精度需要控制回包或带密钥的加密广播，尚未接通
- 无身份密钥时，附近广播识别为启发式观察：仅当唯一一组候选持续出现才采用，多组候选时不显示、不按信号强弱挑选；同型号、同颜色、电量相同的两副耳机无法区分
- 状态栏小图标由系统统一决定颜色与展示，不修改系统原生蓝牙图标
- APK 可安装不代表所有功能可用：蓝牙连接、控制通道、广播电量分别判断，不以音频连接冒充可控制

## 🔧 工作原理

```text
AirPods ──BLE 广播──► 电量 / 佩戴 / 主麦克风 / 盒盖（明文状态字段）
AirPods ──L2CAP PSM 0x1001──► 聆听模式控制（AACP，受系统限制暂不可用）
```

- **状态广播**：独立于控制通道解析 Apple 明文广播；优先通过系统地址或 IRK 身份密钥确认归属
- **控制通道**：实现 AACP 握手、状态订阅与模式设置；只有收到耳机回报的实际模式才确认切换
- **摘戴控制**：不依赖控制通道，通过 `AudioManager` 系统媒体键控制播放
- 原生绘制耳机示意图，不使用 Apple 商标图标或专有字体

## 📦 安装

当前无预编译发布，请从源码构建（见下节），产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

```powershell
adb devices -l
adb -d install -r .\app\build\outputs\apk\debug\app-debug.apk
```

- 在系统蓝牙中配对并连接 AirPods，首次打开 Pods 授予附近设备权限即可自动读取，无须选择耳机或手动刷新
- **Android 9–11**：扫描需要定位权限与系统定位开关；**Android 12+**：使用附近设备权限
- 若系统限制后台运行，需在系统「应用启动管理」中允许 Pods 后台活动

## 🛠️ 构建

需要**完整 JDK 17**、Android SDK Platform 35 与 Build Tools 36.0.0。

PowerShell 7 一键构建（单元测试 + Lint + APK，输出到 `dist/` 并附 SHA-256）：

```powershell
.\build.ps1
```

手动构建：

```powershell
$env:JAVA_HOME = '<JDK 17 路径>'
$env:ANDROID_HOME = '<Android SDK 路径>'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

也可在未跟踪的 `local.properties` 中设置 `sdk.dir`。Gradle Wrapper 固定 8.13，下载校验已启用。

## ✅ 验证

- **单元测试**：67 项，覆盖协议编解码边界、模式确认、广播解析、自动连接、IRK / RPA 验证、无密钥候选观察、多候选拒绝与状态过期
- **真机**：HarmonyOS 4.x 上验证广播读取（左右耳与充电盒电量、佩戴、充电、盒盖）、后台扫描与通知更新；模式控制停留在 L2CAP 通道协商阶段
- **模拟器**：安装启动、权限拒绝与允许、蓝牙开启引导、未配对引导、扫描超时、诊断分享、大字体

## 🙏 开源参考

| 项目 | 参考 |
| --- | --- |
| [LibrePods](https://github.com/librepods-org/librepods) | 自动连接、AACP 报文、身份密钥与随机地址验证、系统兼容限制 |
| [CAPod](https://github.com/d4rken-org/capod) | 扫描兼容、附近广播观察、公开状态字段、媒体键思路 |
| [AAP Protocol Definition](https://github.com/tyalie/AAP-Protocol-Defintion) | 协议研究 |
| [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | 经典 L2CAP 构造接口访问（运行时依赖） |

## 📄 许可证

[GPL-3.0-or-later](LICENSE)。界面、绘图与业务实现为本项目独立编写，不使用上述项目的品牌、图标或 Apple 专有字体。依赖及许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

AirPods 是 Apple Inc. 的商标，本项目与 Apple、LibrePods、CAPod 均无隶属关系。
