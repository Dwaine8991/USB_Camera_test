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

## Re-enable 60fps After Reboot

The 60fps external-camera override is applied at runtime, so it is lost after a reboot.

```powershell
.\scripts\enable-external-camera-60fps.ps1
```

If more than one Android device is connected, pass the serial number:

```powershell
.\scripts\enable-external-camera-60fps.ps1 -Serial 0123456789ABCDEF
```

The script switches `adbd` to root, bind-mounts a `fpsBound="60.0"` config over `/vendor/etc/external_camera_config.xml`, restarts camera services, and verifies that Camera2 reports the `30-60` fps range again.
