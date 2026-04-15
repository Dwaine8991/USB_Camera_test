# USB Camera Stress Test

Android APK for validating how many 1080p60 Camera2 streams the A201 platform can keep alive at the same time.

## Build

```powershell
.\gradlew.bat assembleDebug
```

The first build downloads Gradle 8.10.2 into `.tools/`.

## Test flow

1. Grant camera permission on first launch.
2. Tap `刷新列表` to inspect all Camera2 devices.
3. Tap `开始测试` to open all `external` cameras that advertise `1920x1080` preview and a `60fps` AE range.
4. Watch the per-camera FPS and the summary counters.

Stable support usually means every opened stream stays near 60 FPS without disconnects.
