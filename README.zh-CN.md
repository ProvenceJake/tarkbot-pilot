<p align="center">
  <img src="docs/assets/app-icon.svg" width="120" height="120" alt="Tarkbot Pilot 应用图标">
</p>
<h1 align="center">Tarkbot Pilot</h1>
<p align="center"><strong>用安卓手机操控 TARKBOT 小车。</strong><br>USB 遥控、实时相机预览和手机端目标跟随。</p>
<p align="center">
  <img src="https://img.shields.io/badge/version-0.9.1-0f766e?style=flat-square" alt="版本 0.9.1">
  <img src="https://img.shields.io/badge/Android-8.0%2B-334155?style=flat-square" alt="安卓 8.0 及以上">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-334155?style=flat-square" alt="arm64-v8a">
</p>
<p align="center"><a href="README.md">English</a> · <strong>简体中文</strong></p>
<p align="center">
  <a href="#开始连接">开始连接</a> ·
  <a href="#从源码构建">从源码构建</a> ·
  <a href="https://github.com/ProvenceJake/tarkbot-pilot/issues">反馈问题</a>
</p>

---

Tarkbot Pilot 把安卓手机变成 **TARKBOT R20-MEC** 小车的相机与运动主控，通过 USB OTG 连接 **OpenCTR H60** 控制板。应用界面使用简体中文。

> [!IMPORTANT]
> **首次实车测试请架空四个车轮，并确保实体电源开关随手可及。** 应用启动时会锁定运动，停止或断开时会在串口可用的情况下发送零速度。应用崩溃、线缆突然拔出或控制板异常时，停车指令可能无法送达。如果小车持续运动，请切断电源。

## 功能

| 手动遥控 | 人物跟随 | 颜色和二维码 |
| --- | --- | --- |
| 虚拟摇杆控制前进、后退和麦轮横移，按住方向键可转向。界面显示控制板反馈的速度与电压。 | 开启跟随后有三秒时间走到镜头前，小车会自动选择画面中央附近较稳定的人物。用衣着特征和运动连续性维持跟踪，目标转身时也可以继续跟随。 | 自动跟随画面中最大的红色、绿色或蓝色色块，也可在相机预览中识别二维码。 |

人物检测使用 EfficientDet-Lite0，在手机本地运行。目标超过 300 毫秒未更新时，应用会请求停车。锁定人物丢失两秒后可以重新选择目标。人物模式不会自动倒车。

## 开始连接

1. 构建并安装应用，首次打开时允许相机权限。
2. 给小车供电，用 USB OTG 转接头和数据线连接手机与 OpenCTR 的 USB 数据口。
3. 点击**连接控制器**，允许 USB 权限并等待有效遥测。
4. 架空车轮后点击**解锁运动**并确认。轻推摇杆测试，松手后检查车轮是否停止。解锁后也可点击底部状态条急停。
5. 测试人物跟随时选择**人物**，再开启自动跟随。停车距离以人体宽度占画面宽度的比例为准，默认 34%，调高会让小车更靠近。

切回原厂 PS2 手柄时，先停车，在应用里点击**断开**并拔掉 OTG 线。架空车轮后按住手柄的 **START**，同时将**左摇杆向前推**，即可切换到 PS2 模式。松开摇杆使其回中，再测试遥控。

## 兼容范围

| 组件 | 当前范围 |
| --- | --- |
| 手机 | 安卓 8.0 及以上、ARM64、后置摄像头。控制小车需要 USB 主机模式／OTG。人物检测曾在 LG G7 上验证。 |
| 小车 | TARKBOT R20-MEC，搭配 OpenCTR H60。解锁时会检查车型码 `1`。 |
| USB 串口 | CH9102F（`1A86:55D4`），`230400 8N1`。 |
| 速度 | 默认平移上限 0.25 m/s。人物跟随的应用内上限为 0.28 m/s。 |

## 从源码构建

用 Android Studio 打开仓库根目录，安装 Android SDK Platform 36，并为 Gradle 选择 JDK 17。同步完成后，将开启了 USB 调试的 ARM64 手机连到电脑，运行 `app`。也可以配置 Android SDK 后通过命令行构建：

```sh
git clone https://github.com/ProvenceJake/tarkbot-pilot.git
cd tarkbot-pilot
./gradlew lintDebug testDebugUnitTest assembleDebug
```

构建会下载并校验人物检测模型。调试 APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

运行人物检测的实机回归测试时，将一张有权使用、人物清晰可见的 JPEG 放到 `app/src/androidTest/assets/person_test.jpg`，连接兼容的安卓设备后运行 `./gradlew connectedDebugAndroidTest`。缺少本地照片时跳过这一项测试。

## 协议参考

- [R20 系列底盘开发手册](https://www.scribd.com/document/1008306790/Tucker-Innovation-l-R20-Series-Robot-Chassis-Development-Manual-V1-2)介绍 X-Protocol 帧结构和 `0x50` 速度命令。
- [TARKBOT ROS 驱动](https://github.com/puwuer/tarkbot_robot/blob/main/src/tarkbot_robot.cpp)可用于参考 X/Y/W 速度负载的编码。
