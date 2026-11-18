<p align="center">
  <img src="docs/assets/app-icon.svg" width="120" height="120" alt="Tarkbot Pilot app icon">
</p>
<h1 align="center">Tarkbot Pilot</h1>
<p align="center"><strong>Use an Android phone to drive and guide a TARKBOT rover.</strong><br>USB control, live camera preview, and on-device target tracking.</p>
<p align="center">
  <img src="https://img.shields.io/badge/version-0.9.1-0f766e?style=flat-square" alt="Version 0.9.1">
  <img src="https://img.shields.io/badge/Android-8.0%2B-334155?style=flat-square" alt="Android 8.0 or later">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-334155?style=flat-square" alt="arm64-v8a">
</p>
<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>
<p align="center">
  <a href="https://github.com/ProvenceJake/tarkbot-pilot/releases/download/v0.9.1/Tarkbot-Pilot-0.9.1-arm64.apk"><strong>Download Android APK</strong></a> ·
  <a href="https://github.com/ProvenceJake/tarkbot-pilot/releases/tag/v0.9.1">Release notes</a> ·
  <a href="#get-connected">Get connected</a> ·
  <a href="#build-from-source">Build from source</a> ·
  <a href="https://github.com/ProvenceJake/tarkbot-pilot/issues">Report an issue</a>
</p>

---

Tarkbot Pilot uses an Android phone as a camera and motion controller for a **TARKBOT R20-MEC** chassis with an **OpenCTR H60** controller. It communicates over USB OTG. The app interface is in Simplified Chinese.

> [!IMPORTANT]
> **Test with all four wheels off the ground and keep the physical power switch within reach.** The app starts with motion locked and sends zero speed when possible after a stop or disconnect. A crash, sudden cable removal, or controller fault can prevent a stop command from reaching the rover. Cut power if it keeps moving.

## What it does

| Manual driving | Person following | Color and QR codes |
| --- | --- | --- |
| Use the virtual joystick to drive forward, reverse, or strafe. Hold the turn buttons to rotate. See speed and battery voltage from controller telemetry. | Start following, then walk into view during the three-second delay. The app selects a stable person near the center of the frame and uses clothing appearance and motion continuity to keep tracking when they turn away. | Follow the largest matching red, green, or blue region. Scan QR codes in the camera preview. |

Person detection runs on the phone with EfficientDet-Lite0. If the target update is older than 300 ms, the app requests a stop. After the selected person has been missing for two seconds, the app can select a new target. Person mode does not reverse automatically.

## Get connected

1. Download and install the [Android APK](https://github.com/ProvenceJake/tarkbot-pilot/releases/download/v0.9.1/Tarkbot-Pilot-0.9.1-arm64.apk), then allow camera access. The APK is a debug preview for ARM64 phones running Android 8.0 or later.
2. Power the rover and connect the OpenCTR data port to the phone with a USB OTG adapter and a data cable.
3. Tap **Connect controller (连接控制器)**, grant USB access, and wait for valid telemetry.
4. Elevate the wheels. Select **Unlock motion (解锁运动)** and confirm. Test the joystick and release it to check that the wheels stop. The status bar also acts as a stop button while motion is unlocked.
5. To test person following, select **Person (人物)** and enable automatic follow. The distance setting uses the target width as a share of the image, so a higher value brings the rover closer. The default is 34%.

To return to the original PS2 gamepad, stop the rover, disconnect in the app, and unplug the OTG cable. With the wheels elevated, hold **START** and push the **left stick forward** to switch the controller to PS2 mode. Let the stick return to center before testing.

## Compatibility

| Component | Current scope |
| --- | --- |
| Phone | Android 8.0 or later, ARM64, rear camera, and USB host/OTG for driving. Detection has been tested on an LG G7. |
| Rover | TARKBOT R20-MEC with OpenCTR H60. Motion unlock checks for model code `1`. |
| USB serial | CH9102F (`1A86:55D4`), `230400 8N1`. |
| Speed | Default linear limit of 0.25 m/s. Person following is capped at 0.28 m/s by the app. |

## Build from source

Use Android Studio with Android SDK Platform 36 and JDK 17 for Gradle. Open the repository root, sync Gradle, and run the `app` configuration on an ARM64 device with USB debugging enabled. For a command-line build, configure the Android SDK and run:

```sh
git clone https://github.com/ProvenceJake/tarkbot-pilot.git
cd tarkbot-pilot
./gradlew lintDebug testDebugUnitTest assembleDebug
```

The build downloads and verifies the person detection model. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For the device regression test, add a JPEG you can use showing a clearly visible person to `app/src/androidTest/assets/person_test.jpg`, then run `./gradlew connectedDebugAndroidTest` with a compatible Android device connected. The test skips when the local photo is missing.

## Protocol references

- [R20 Series Robot Chassis Development Manual](https://www.scribd.com/document/1008306790/Tucker-Innovation-l-R20-Series-Robot-Chassis-Development-Manual-V1-2) documents X-Protocol framing and velocity command `0x50`.
- [TARKBOT ROS driver](https://github.com/puwuer/tarkbot_robot/blob/main/src/tarkbot_robot.cpp) provides a reference for the X/Y/W velocity payload.
